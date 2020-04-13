/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.http.v2;

import java.time.Duration;

import com.google.common.annotations.VisibleForTesting;
import com.palantir.atlasdb.config.ServerListConfig;
import com.palantir.conjure.java.api.config.service.ServiceConfiguration;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.conjure.java.client.config.ClientConfigurations;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;

public final class StaticClientConfigurations {
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    @VisibleForTesting
    // The read timeout controls how long the client waits to receive the first byte from the server before giving up,
    // so in general read timeouts should not be set to less than what is considered an acceptable time for the server
    // to give a suitable response.
    // In the context of TimeLock, this timeout must be longer than how long an AwaitingLeadershipProxy takes to
    // decide whether a node is the leader and still has a quorum.
    public static final Duration NON_BLOCKING_READ_TIMEOUT = Duration.ofMillis(12566); // Odd number for debugging

    // Should not be reduced below 65 seconds to support workflows involving locking.
    static final Duration BLOCKING_READ_TIMEOUT = Duration.ofSeconds(65);

    // Under standard settings, throws after expected outages of 1/2 * 0.01 * (2^13 - 1) = 40.96 s
    private static final Duration STANDARD_BACKOFF_SLOT_SIZE = Duration.ofMillis(10);
    private static final int STANDARD_MAX_RETRIES = 13;
    private static final int NO_RETRIES = 0;

    private static final Duration STANDARD_FAILED_URL_COOLDOWN = Duration.ofMillis(100);
    private static final Duration NON_RETRY_FAILED_URL_COOLDOWN = Duration.ofMillis(1);


    static ClientConfiguration apply(StaticClientConfiguration staticClientConfig, ServerListConfig serverConfig) {
        ClientConfiguration.Builder mixed = ClientConfiguration.builder()
                .from(ClientConfigurations.of(toServiceConfiguration(serverConfig)))
                .enableGcmCipherSuites(true)
                .enableHttp2(true);

        mixed.readTimeout(staticClientConfig.fastReadTimeOut() ? NON_BLOCKING_READ_TIMEOUT : BLOCKING_READ_TIMEOUT);
        // TODO(forozco): add more config if necessary
//        staticClientConfig.nodeSelectionStrategy().ifPresent(mixed::nodeSelectionStrategy);
//        staticClientConfig.failedUrlCooldown().ifPresent(mixed::failedUrlCooldown);
        staticClientConfig.clientQoS().ifPresent(mixed::clientQoS);
//        staticClientConfig.serverQoS().ifPresent(mixed::serverQoS);
//        staticClientConfig.retryOnTimeout().ifPresent(mixed::retryOnTimeout);
        staticClientConfig.maxNumRetries().ifPresent(mixed::maxNumRetries);
        return mixed.build();
    }

    private static ServiceConfiguration toServiceConfiguration(ServerListConfig serverConfig) {
        return ServiceConfiguration.builder()
                .security(serverConfig.sslConfiguration().orElseThrow(
                        () -> new SafeIllegalStateException("CJR must be configured with SSL",
                                SafeArg.of("serverConfig", serverConfig))))
                .uris(serverConfig.servers())
                .proxy(serverConfig.proxyConfiguration())
                .build();
    }

    private StaticClientConfigurations() {
    }
}
