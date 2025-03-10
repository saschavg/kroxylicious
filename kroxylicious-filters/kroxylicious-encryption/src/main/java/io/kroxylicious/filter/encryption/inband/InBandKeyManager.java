/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.inband;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MemoryRecordsBuilder;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.apache.kafka.common.utils.ByteUtils;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.kroxylicious.filter.encryption.AadSpec;
import io.kroxylicious.filter.encryption.CipherCode;
import io.kroxylicious.filter.encryption.EncryptionException;
import io.kroxylicious.filter.encryption.EncryptionScheme;
import io.kroxylicious.filter.encryption.EncryptionVersion;
import io.kroxylicious.filter.encryption.EnvelopeEncryptionFilter;
import io.kroxylicious.filter.encryption.KeyManager;
import io.kroxylicious.filter.encryption.Receiver;
import io.kroxylicious.filter.encryption.RecordField;
import io.kroxylicious.filter.encryption.WrapperVersion;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.Serde;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * An implementation of {@link KeyManager} that uses envelope encryption, AES-GCM and stores the KEK id and encrypted DEK
 * alongside the record ("in-band").
 * @param <K> The type of KEK id.
 * @param <E> The type of the encrypted DEK.
 */
public class InBandKeyManager<K, E> implements KeyManager<K> {

    private static final int MAX_ATTEMPTS = 3;

    /**
     * The encryption header. The value is the encryption version that was used to serialize the parcel and the wrapper.
     */
    static final String ENCRYPTION_HEADER_NAME = "kroxylicious.io/encryption";

    /**
     * The encryption version used on the produce path.
     * Note that the encryption version used on the fetch path is read from the
     * {@link #ENCRYPTION_HEADER_NAME} header.
     */
    private final EncryptionVersion encryptionVersion;

    private final Kms<K, E> kms;
    private final BufferPool bufferPool;
    private final Serde<E> edekSerde;
    // TODO cache expiry, with key descruction
    private final AsyncLoadingCache<K, KeyContext> keyContextCache;
    private final AsyncLoadingCache<E, AesGcmEncryptor> decryptorCache;
    private final long dekTtlNanos;
    private final int maxEncryptionsPerDek;
    private final Header[] encryptionHeader;

    public InBandKeyManager(Kms<K, E> kms,
                            BufferPool bufferPool,
                            int maxEncryptionsPerDek) {
        this.kms = kms;
        this.bufferPool = bufferPool;
        this.edekSerde = kms.edekSerde();
        this.dekTtlNanos = 5_000_000_000L;
        this.maxEncryptionsPerDek = maxEncryptionsPerDek;
        // TODO This ^^ must be > the maximum size of a batch to avoid an infinite loop
        this.keyContextCache = Caffeine.newBuilder()
                .buildAsync((key, executor) -> makeKeyContext(key));
        this.decryptorCache = Caffeine.newBuilder()
                .buildAsync((edek, executor) -> makeDecryptor(edek));
        this.encryptionVersion = EncryptionVersion.V1; // TODO read from config
        this.encryptionHeader = new Header[]{ new RecordHeader(ENCRYPTION_HEADER_NAME, new byte[]{ encryptionVersion.code() }) };
    }

    private CompletionStage<KeyContext> currentDekContext(@NonNull K kekId) {
        // todo should we add some scheduled timeout as well? or should we rely on the KMS to timeout appropriately.
        return keyContextCache.get(kekId);
    }

    private CompletableFuture<KeyContext> makeKeyContext(@NonNull K kekId) {
        return kms.generateDekPair(kekId)
                .thenApply(dekPair -> {
                    E edek = dekPair.edek();
                    short edekSize = (short) edekSerde.sizeOf(edek);
                    ByteBuffer serializedEdek = ByteBuffer.allocate(edekSize);
                    edekSerde.serialize(edek, serializedEdek);
                    serializedEdek.flip();

                    return new KeyContext(serializedEdek,
                            System.nanoTime() + dekTtlNanos,
                            maxEncryptionsPerDek,
                            // Either we have a different Aes encryptor for each thread
                            // or we need mutex
                            // or we externalize the state
                            AesGcmEncryptor.forEncrypt(new AesGcmIvGenerator(new SecureRandom()), dekPair.dek()));
                }).toCompletableFuture();
    }

    @Override
    @NonNull
    @SuppressWarnings("java:S2445")
    public CompletionStage<MemoryRecords> encrypt(@NonNull String topicName,
                                                  int partition,
                                                  @NonNull EncryptionScheme<K> encryptionScheme,
                                                  @NonNull MemoryRecords records,
                                                  @NonNull IntFunction<ByteBufferOutputStream> bufferAllocator) {
        if (records.sizeInBytes() == 0) {
            // no records to transform, return input without modification
            return CompletableFuture.completedFuture(records);
        }
        List<Record> encryptionRequests = recordStream(records).toList();
        // it is possible to encounter MemoryRecords that have had all their records compacted away, but
        // the recordbatch metadata still exists. https://kafka.apache.org/documentation/#recordbatch
        if (encryptionRequests.isEmpty()) {
            return CompletableFuture.completedFuture(records);
        }
        MemoryRecordsBuilder builder = recordsBuilder(allocateBufferForEncode(records, bufferAllocator), records);
        return attemptEncrypt(topicName, partition, encryptionScheme, encryptionRequests, (kafkaRecord, plaintextBuffer, headers) -> {
            builder.appendWithOffset(kafkaRecord.offset(), kafkaRecord.timestamp(), kafkaRecord.key(), plaintextBuffer, headers);
        }, 0).thenApply(unused -> builder.build());
    }

    @NonNull
    private static Stream<Record> recordStream(MemoryRecords memoryRecords) {
        return StreamSupport.stream(memoryRecords.records().spliterator(), false);
    }

    private static MemoryRecordsBuilder recordsBuilder(@NonNull ByteBufferOutputStream buffer, @NonNull MemoryRecords records) {
        RecordBatch firstBatch = records.firstBatch();
        return new MemoryRecordsBuilder(buffer,
                firstBatch.magic(),
                firstBatch.compressionType(), // TODO we might not want to use the client's compression
                firstBatch.timestampType(),
                firstBatch.baseOffset(),
                0L,
                firstBatch.producerId(),
                firstBatch.producerEpoch(),
                firstBatch.baseSequence(),
                firstBatch.isTransactional(),
                firstBatch.isControlBatch(),
                firstBatch.partitionLeaderEpoch(),
                0);
    }

    private ByteBufferOutputStream allocateBufferForEncode(MemoryRecords records, IntFunction<ByteBufferOutputStream> bufferAllocator) {
        int sizeEstimate = 2 * records.sizeInBytes();
        // Accurate estimation is tricky without knowing the record sizes
        return bufferAllocator.apply(sizeEstimate);
    }

    @SuppressWarnings("java:S2445")
    private CompletionStage<Void> attemptEncrypt(String topicName, int partition, @NonNull EncryptionScheme<K> encryptionScheme, @NonNull List<? extends Record> records,
                                                 @NonNull Receiver receiver, int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return CompletableFuture.failedFuture(
                    new RequestNotSatisfiable("failed to reserve an EDEK to encrypt " + records.size() + " records for topic " + topicName + " partition "
                            + partition + " after " + attempt + " attempts"));
        }
        return currentDekContext(encryptionScheme.kekId()).thenCompose(keyContext -> {
            synchronized (keyContext) {
                // if it's not alive we know a previous encrypt call has removed this stage from the cache and fall through to retry encrypt
                if (!keyContext.isDestroyed()) {
                    if (!keyContext.hasAtLeastRemainingEncryptions(records.size())) {
                        // remove the key context from the cache, then call encrypt again to drive caffeine to recreate it
                        rotateKeyContext(encryptionScheme, keyContext);
                    }
                    else {
                        // todo ensure that a failure during encryption terminates the entire operation with a failed future
                        return encrypt(encryptionScheme, records, receiver, keyContext);
                    }
                }
            }
            return attemptEncrypt(topicName, partition, encryptionScheme, records, receiver, attempt + 1);
        });
    }

    @NonNull
    private CompletableFuture<Void> encrypt(@NonNull EncryptionScheme<K> encryptionScheme, @NonNull List<? extends Record> records,
                                            @NonNull Receiver receiver, KeyContext keyContext) {
        var maxParcelSize = records.stream()
                .mapToInt(kafkaRecord -> Parcel.sizeOfParcel(
                        encryptionVersion.parcelVersion(),
                        encryptionScheme.recordFields(),
                        kafkaRecord))
                .filter(value -> value > 0)
                .max()
                .orElseThrow();
        var maxWrapperSize = records.stream()
                .mapToInt(kafkaRecord -> sizeOfWrapper(keyContext, maxParcelSize))
                .filter(value -> value > 0)
                .max()
                .orElseThrow();
        ByteBuffer parcelBuffer = bufferPool.acquire(maxParcelSize);
        ByteBuffer wrapperBuffer = bufferPool.acquire(maxWrapperSize);
        try {
            encryptRecords(encryptionScheme, keyContext, records, parcelBuffer, wrapperBuffer, receiver);
        }
        finally {
            if (wrapperBuffer != null) {
                bufferPool.release(wrapperBuffer);
            }
            if (parcelBuffer != null) {
                bufferPool.release(parcelBuffer);
            }
        }
        keyContext.recordEncryptions(records.size());
        return CompletableFuture.completedFuture(null);
    }

    // this must only be called while holding the lock on this keycontext
    private void rotateKeyContext(@NonNull EncryptionScheme<K> encryptionScheme, KeyContext keyContext) {
        keyContext.destroy();
        K kekId = encryptionScheme.kekId();
        keyContextCache.synchronous().invalidate(kekId);
    }

    private void encryptRecords(@NonNull EncryptionScheme<K> encryptionScheme,
                                @NonNull KeyContext keyContext,
                                @NonNull List<? extends Record> records,
                                @NonNull ByteBuffer parcelBuffer,
                                @NonNull ByteBuffer wrapperBuffer,
                                @NonNull Receiver receiver) {
        records.forEach(kafkaRecord -> {
            if (encryptionScheme.recordFields().contains(RecordField.RECORD_HEADER_VALUES)
                    && kafkaRecord.headers().length > 0
                    && !kafkaRecord.hasValue()) {
                // todo implement header encryption preserving null record-values
                throw new IllegalStateException("encrypting headers prohibited when original record value null, we must preserve the null for tombstoning");
            }
            if (kafkaRecord.hasValue()) {
                Parcel.writeParcel(encryptionVersion.parcelVersion(), encryptionScheme.recordFields(), kafkaRecord, parcelBuffer);
                parcelBuffer.flip();
                var transformedValue = writeWrapper(keyContext, parcelBuffer, wrapperBuffer);
                Header[] headers = transformHeaders(encryptionScheme, kafkaRecord);
                receiver.accept(kafkaRecord, transformedValue, headers);
                wrapperBuffer.rewind();
                parcelBuffer.rewind();
            }
            else {
                receiver.accept(kafkaRecord, null, kafkaRecord.headers());
            }
        });
    }

    private Header[] transformHeaders(@NonNull EncryptionScheme<K> encryptionScheme, Record kafkaRecord) {
        Header[] oldHeaders = kafkaRecord.headers();
        Header[] headers;
        if (encryptionScheme.recordFields().contains(RecordField.RECORD_HEADER_VALUES) || oldHeaders.length == 0) {
            headers = encryptionHeader;
        }
        else {
            headers = new Header[1 + oldHeaders.length];
            headers[0] = encryptionHeader[0];
            System.arraycopy(oldHeaders, 0, headers, 1, oldHeaders.length);
        }
        return headers;
    }

    private int sizeOfWrapper(KeyContext keyContext, int parcelSize) {
        var edek = keyContext.serializedEdek();
        return ByteUtils.sizeOfUnsignedVarint(edek.length)
                + edek.length
                + 1 // aad code
                + 1 // cipher code
                + keyContext.encodedSize(parcelSize);

    }

    @Nullable
    private ByteBuffer writeWrapper(KeyContext keyContext,
                                    ByteBuffer parcel,
                                    ByteBuffer wrapper) {
        switch (encryptionVersion.wrapperVersion()) {
            case V1 -> {
                var edek = keyContext.serializedEdek();
                ByteUtils.writeUnsignedVarint(edek.length, wrapper);
                wrapper.put(edek);
                wrapper.put(AadSpec.NONE.code()); // aadCode
                wrapper.put(CipherCode.AES_GCM_96_128.code());
                keyContext.encodedSize(parcel.limit());
                ByteBuffer aad = ByteUtils.EMPTY_BUF; // TODO pass the AAD to encode
                keyContext.encode(parcel, wrapper); // iv and ciphertext
            }
        }
        wrapper.flip();
        return wrapper;
    }

    /**
     * Reads the {@link #ENCRYPTION_HEADER_NAME} header from the record.
     * @param topicName The topic name.
     * @param partition The partition.
     * @param kafkaRecord The record.
     * @return The encryption header, or null if it's missing (indicating that the record wasn't encrypted).
     */
    static EncryptionVersion decryptionVersion(String topicName, int partition, Record kafkaRecord) {
        for (Header header : kafkaRecord.headers()) {
            if (ENCRYPTION_HEADER_NAME.equals(header.key())) {
                byte[] value = header.value();
                if (value.length != 1) {
                    throw new EncryptionException("Invalid value for header with key '" + ENCRYPTION_HEADER_NAME + "' "
                            + "in record at offset " + kafkaRecord.offset()
                            + " in partition " + partition
                            + " of topic " + topicName);
                }
                return EncryptionVersion.fromCode(value[0]);
            }
        }
        return null;
    }

    private CompletableFuture<AesGcmEncryptor> makeDecryptor(E edek) {
        return kms.decryptEdek(edek)
                .thenApply(AesGcmEncryptor::forDecrypt).toCompletableFuture();
    }

    private record DecryptState(@NonNull Record kafkaRecord, @NonNull ByteBuffer valueWrapper, @Nullable EncryptionVersion decryptionVersion,
                                @Nullable AesGcmEncryptor encryptor) {}

    @NonNull
    @Override
    public CompletionStage<MemoryRecords> decrypt(@NonNull String topicName, int partition, @NonNull MemoryRecords records,
                                                  @NonNull IntFunction<ByteBufferOutputStream> bufferAllocator) {
        if (records.sizeInBytes() == 0) {
            // no records to transform, return input without modification
            return CompletableFuture.completedFuture(records);
        }
        List<Record> encryptionRequests = recordStream(records).toList();
        // it is possible to encounter MemoryRecords that have had all their records compacted away, but
        // the recordbatch metadata still exists. https://kafka.apache.org/documentation/#recordbatch
        if (encryptionRequests.isEmpty()) {
            return CompletableFuture.completedFuture(records);
        }
        ByteBufferOutputStream buffer = allocateBufferForDecode(records, bufferAllocator);
        MemoryRecordsBuilder outputBuilder = recordsBuilder(buffer, records);
        return decrypt(topicName, partition, recordStream(records).toList(), (kafkaRecord, plaintextBuffer, headers) -> {
            outputBuilder.appendWithOffset(kafkaRecord.offset(), kafkaRecord.timestamp(), kafkaRecord.key(), plaintextBuffer, headers);
        }).thenApply(unused -> outputBuilder.build());
    }

    @NonNull
    private CompletionStage<Void> decrypt(String topicName,
                                          int partition,
                                          @NonNull List<? extends Record> records,
                                          @NonNull Receiver receiver) {
        var decryptStateStages = new ArrayList<CompletionStage<DecryptState>>(records.size());

        for (Record kafkaRecord : records) {
            var decryptionVersion = decryptionVersion(topicName, partition, kafkaRecord);
            if (decryptionVersion == null) {
                decryptStateStages.add(CompletableFuture.completedStage(new DecryptState(kafkaRecord, kafkaRecord.value(), null, null)));
            }
            else {
                // right now (because we only support topic name based kek selection) once we've resolved the first value we
                // can keep the lock and process all the records
                ByteBuffer wrapper = kafkaRecord.value();
                decryptStateStages.add(
                        resolveEncryptor(decryptionVersion.wrapperVersion(), wrapper).thenApply(enc -> new DecryptState(kafkaRecord, wrapper, decryptionVersion, enc)));
            }
        }

        return EnvelopeEncryptionFilter.join(decryptStateStages)
                .thenApply(decryptStates -> {
                    decryptStates.forEach(decryptState -> {
                        if (decryptState.encryptor() == null) {
                            receiver.accept(decryptState.kafkaRecord(), decryptState.valueWrapper(), decryptState.kafkaRecord().headers());
                        }
                        else {
                            decryptRecord(decryptState.decryptionVersion(), decryptState.encryptor(), decryptState.valueWrapper(), decryptState.kafkaRecord(), receiver);
                        }
                    });
                    return null;
                });
    }

    private ByteBufferOutputStream allocateBufferForDecode(MemoryRecords memoryRecords, IntFunction<ByteBufferOutputStream> allocator) {
        int sizeEstimate = memoryRecords.sizeInBytes();
        return allocator.apply(sizeEstimate);
    }

    @SuppressWarnings("java:S2445")
    private void decryptRecord(EncryptionVersion decryptionVersion,
                               AesGcmEncryptor encryptor,
                               ByteBuffer wrapper,
                               Record kafkaRecord,
                               @NonNull Receiver receiver) {
        var aadSpec = AadSpec.fromCode(wrapper.get());
        ByteBuffer aad = switch (aadSpec) {
            case NONE -> ByteUtils.EMPTY_BUF;
        };

        var cipherCode = CipherCode.fromCode(wrapper.get());

        ByteBuffer plaintextParcel;
        synchronized (encryptor) {
            plaintextParcel = decryptParcel(wrapper.slice(), encryptor);
        }
        Parcel.readParcel(decryptionVersion.parcelVersion(), plaintextParcel, kafkaRecord, receiver);
    }

    private CompletionStage<AesGcmEncryptor> resolveEncryptor(WrapperVersion wrapperVersion, ByteBuffer wrapper) {
        switch (wrapperVersion) {
            case V1:
                var edekLength = ByteUtils.readUnsignedVarint(wrapper);
                ByteBuffer slice = wrapper.slice(wrapper.position(), edekLength);
                var edek = edekSerde.deserialize(slice);
                wrapper.position(wrapper.position() + edekLength);
                return decryptorCache.get(edek);
        }
        throw new EncryptionException("Unknown wrapper version " + wrapperVersion);
    }

    private ByteBuffer decryptParcel(ByteBuffer ciphertextParcel, AesGcmEncryptor encryptor) {
        ByteBuffer plaintext = ciphertextParcel.duplicate();
        encryptor.decrypt(ciphertextParcel, plaintext);
        plaintext.flip();
        return plaintext;
    }

}
