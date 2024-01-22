/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.security.KeyStore;
import java.util.Locale;
import java.util.Optional;

/**
 * Provides TLS configuration for this peer.  This class is designed to be used for both TLS server and client roles.
 *
 * @param key   specifies a key provider that provides the certificate/key used to identify this peer.
 * @param trust specifies a trust provider used by this peer to determine whether to trust the peer. If omitted platform trust is used instead.
 *
 * TODO ability to restrict by TLS protocol and cipher suite.
 */
public record Tls(KeyProvider key,
                  TrustProvider trust) {

    public static final String PEM = "PEM";

    public boolean requiresClientAuth(){
        return Optional.ofNullable(trust()).isPresent();
    }

    public static String getStoreTypeOrPlatformDefault(String storeType) {
        return storeType == null ? KeyStore.getDefaultType().toUpperCase(Locale.ROOT) : storeType.toUpperCase(Locale.ROOT);
    }

    public boolean definesKey() {
        return key != null;
    }
}
