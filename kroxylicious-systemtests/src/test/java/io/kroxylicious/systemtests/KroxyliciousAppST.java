/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousApp;
import io.kroxylicious.systemtests.templates.strimzi.KafkaTemplates;
import io.kroxylicious.systemtests.utils.DeploymentUtils;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Kroxylicious app system tests.
 * If using minikube, 'minikube tunnel' shall be executed before these tests
 *
 * Disabled to focus on kubernetes system tests
 */
@Disabled
public class KroxyliciousAppST extends AbstractST {
    private static final Logger LOGGER = LoggerFactory.getLogger(KroxyliciousAppST.class);
    private static KroxyliciousApp kroxyliciousApp;
    private final String clusterName = "my-external-cluster";

    /**
     * Kroxylicious app is running.
     */
    @Test
    void kroxyAppIsRunning() {
        LOGGER.info("Given local Kroxylicious");
        String clusterIp = kubeClient().getService(Constants.KROXY_DEFAULT_NAMESPACE, clusterName + "-kafka-external-bootstrap").getSpec().getClusterIP();
        kroxyliciousApp = new KroxyliciousApp(clusterIp);
        kroxyliciousApp.waitForKroxyliciousProcess();
        assertThat("Kroxylicious app is not running!", kroxyliciousApp.isRunning());
    }

    /**
     * Sets before all.
     */
    @BeforeAll
    void setupBefore() {
        assumeTrue(DeploymentUtils.checkLoadBalancerIsWorking(Constants.KROXY_DEFAULT_NAMESPACE), "Load balancer is not working fine, if you are using"
                + "minikube please run 'minikube tunnel' before running the tests");
        LOGGER.info("Deploying Kafka in {} namespace", Constants.KROXY_DEFAULT_NAMESPACE);
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaPersistentWithExternalIp(Constants.KROXY_DEFAULT_NAMESPACE, clusterName, 3, 3).build());
    }

    /**
     * Tear down.
     */
    @AfterEach
    void tearDown() {
        if (kroxyliciousApp != null) {
            LOGGER.info("Removing kroxylicious app");
            kroxyliciousApp.stop();
        }
    }
}
