/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.records;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.MutableRecordBatch;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.apache.kafka.common.utils.CloseableIterator;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility methods for dealing with {@link RecordBatch}es.
 * @see MemoryRecordsUtils
 */
public class RecordBatchUtils {

    private RecordBatchUtils() {
    }

    /**
     * Returns a sequential stream over the records in a batch.
     * @param batch The record batch
     * @return A stream over the records in the given {@code batch}.
     */
    public static @NonNull Stream<Record> recordStream(@NonNull RecordBatch batch) {
        Objects.requireNonNull(batch);
        Spliterator<Record> spliterator;
        Integer size = batch.countOrNull();
        int characteristics = Spliterator.ORDERED | Spliterator.NONNULL;
        if (!(batch instanceof MutableRecordBatch)) {
            characteristics |= Spliterator.IMMUTABLE;
        }
        CloseableIterator<Record> iterator = batch.streamingIterator(BufferSupplier.create());
        try {
            if (size != null) {
                spliterator = Spliterators.spliterator(iterator, size,
                        characteristics | Spliterator.SIZED | Spliterator.SUBSIZED);
            }
            else {
                spliterator = Spliterators.spliteratorUnknownSize(iterator,
                        characteristics);
            }

            Stream<Record> stream;
            if ((spliterator.characteristics() & Spliterator.IMMUTABLE) != 0) {
                // Per javadoc on StreamSupport#stream(), then the spliterator is immutable use the non-Supplier factory
                stream = StreamSupport.stream(spliterator, false);
            }
            else {
                stream = StreamSupport.stream(() -> spliterator, characteristics, false);
            }
            return stream.onClose(iterator::close);
        }
        catch (RuntimeException e) {
            iterator.close();
            throw e;
        }
    }

    /**
     * Convert the given {@code recordBatch} into a {@link MemoryRecords},
     * applying a per-record mapping operation to each record.
     * @param recordBatch The batch of records to convert
     * @param mapper The mapping function to apply to the records in the batch
     * @param resultBuffer A final buffer. This is required to encourage buffer reuse when
     * callers make multiple invocations of this method.
     * @return A MemoryRecords containing the mapped records
     */
    public static @NonNull MemoryRecords toMemoryRecords(
                                                         @NonNull RecordBatch recordBatch,
                                                         @NonNull RecordTransform mapper,
                                                         @NonNull ByteBufferOutputStream resultBuffer) {
        Objects.requireNonNull(recordBatch);
        Objects.requireNonNull(mapper);
        Objects.requireNonNull(resultBuffer);
        List<Runnable> closers = new ArrayList<>();
        try (Stream<Record> recordStream = recordStream(recordBatch)) {
            return recordStream
                    .collect(toMemoryRecordsCollector(closers::add, recordBatch, mapper, resultBuffer));
        }
        finally {
            closers.forEach(Runnable::run);
        }
    }

    /**
     * Factory method for a Collector that applies a mapping function as it builds a {@link MemoryRecords}.
     */
    private static Collector<Record, BatchAwareMemoryRecordsBuilder, MemoryRecords> toMemoryRecordsCollector(Consumer<Runnable> onClose,
                                                                                                             RecordBatch recordBatch,
                                                                                                             RecordTransform mapper,
                                                                                                             ByteBufferOutputStream resultBuffer) {
        return new Collector<>() {

            @Override
            public Supplier<BatchAwareMemoryRecordsBuilder> supplier() {
                return () -> {
                    BatchAwareMemoryRecordsBuilder batchAwareMemoryRecordsBuilder = new BatchAwareMemoryRecordsBuilder(resultBuffer);
                    onClose.accept(batchAwareMemoryRecordsBuilder::close);
                    return batchAwareMemoryRecordsBuilder.addBatchLike(recordBatch);
                };
            }

            @Override
            public BiConsumer<BatchAwareMemoryRecordsBuilder, Record> accumulator() {
                return (builder, record) -> {
                    mapper.init(record);
                    builder.appendWithOffset(
                            mapper.transformOffset(record),
                            mapper.transformTimestamp(record),
                            mapper.transformKey(record),
                            mapper.transformValue(record),
                            mapper.transformHeaders(record));
                    mapper.resetAfterTransform(record);
                };
            }

            @Override
            public BinaryOperator<BatchAwareMemoryRecordsBuilder> combiner() {
                return MemoryRecordsUtils::combineBuilders;
            }

            @Override
            public Function<BatchAwareMemoryRecordsBuilder, MemoryRecords> finisher() {
                return BatchAwareMemoryRecordsBuilder::build;
            }

            @Override
            public Set<Characteristics> characteristics() {
                return Set.of();
            }
        };
    }
}
