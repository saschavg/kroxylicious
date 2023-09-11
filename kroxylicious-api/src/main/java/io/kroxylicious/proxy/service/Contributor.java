/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.service;

import io.kroxylicious.proxy.config.BaseConfig;

/**
 * Support loading an Instance of a service, optionally providing it with configuration obtained
 * from the Kroxylicious configuration file.
 *
 * @param <T> the service type
 */
public interface Contributor<T, S extends Context> {

    /**
     * Identifies if this Contributor instance can create instances for the provided short name.
     * @param shortName The type name to test for.
     * @return <code>true</code> if the <code>shortName</code> is supported by this contributor.
     */
    boolean contributes(String shortName);

    /**
     * @deprecated subsumed into {@link ConfigurationDefinition} and thus {@link Contributor#getConfigDefinition(String)}
     *
     * Gets the concrete type of the configuration required by this service instance.
     * @param shortName service short name
     *
     * @return class of a concrete type, or null if this contributor does not offer this short name.
     */
    @Deprecated(forRemoval = true, since = "0.3.0")
    Class<? extends BaseConfig> getConfigType(String shortName);

    /**
     * Defines the configuration requirements of this contributor for the given short name.
     * @param shortName the name of the type.
     * @return the ConfigurationDefinition for the short name
     */
    ConfigurationDefinition getConfigDefinition(String shortName);

    /**
     * Creates an instance of the service.
     *
     * @param shortName service short name
     * @param context   context containing service configuration which may be null if the service instance does not accept configuration.
     * @return the service instance, or null if this contributor does not offer this short name.
     */
    T getInstance(String shortName, S context);

    record ConfigurationDefinition(Class<? extends BaseConfig> configurationType) {}
}