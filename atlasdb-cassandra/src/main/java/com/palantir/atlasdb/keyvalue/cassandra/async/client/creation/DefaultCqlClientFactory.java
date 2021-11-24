/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.keyvalue.cassandra.async.client.creation;

import com.datastax.driver.core.Cluster;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfig;
import com.palantir.atlasdb.cassandra.CassandraServersConfigs;
import com.palantir.atlasdb.keyvalue.cassandra.async.CqlClient;
import com.palantir.atlasdb.keyvalue.cassandra.async.CqlClientImpl;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class DefaultCqlClientFactory implements CqlClientFactory {
    public static final CqlClientFactory DEFAULT = new DefaultCqlClientFactory();

    private static final SafeLogger log = SafeLoggerFactory.get(DefaultCqlClientFactory.class);

    private final Supplier<Cluster.Builder> cqlClusterBuilderFactory;

    public DefaultCqlClientFactory(Supplier<Cluster.Builder> cqlClusterBuilderFactory) {
        this.cqlClusterBuilderFactory = cqlClusterBuilderFactory;
    }

    public static DefaultCqlClientFactory create(Supplier<Cluster.Builder> cqlClusterBuilderFactory) {
        return new DefaultCqlClientFactory(cqlClusterBuilderFactory);
    }

    public DefaultCqlClientFactory() {
        this(Cluster::builder);
    }

    @Override
    public Optional<CqlClient> constructClient(
            TaggedMetricRegistry taggedMetricRegistry, CassandraKeyValueServiceConfig config, boolean initializeAsync) {
        return config.servers().accept(new CassandraServersConfigs.Visitor<Optional<CqlClient>>() {
            @Override
            public Optional<CqlClient> visit(CassandraServersConfigs.DefaultConfig defaultConfig) {
                return Optional.empty();
            }

            @Override
            public Optional<CqlClient> visit(CassandraServersConfigs.CqlCapableConfig cqlCapableConfig) {
                if (!cqlCapableConfig.validateHosts()) {
                    log.warn("Your CQL capable config is wrong, the hosts for CQL and Thrift are not the same, using "
                            + "async API will be falling back to synchronous implementations.");
                    return Optional.empty();
                }

                Set<InetSocketAddress> servers = cqlCapableConfig.cqlHosts();
                Cluster cluster = new ClusterFactory(cqlClusterBuilderFactory).constructCluster(servers, config);

                return Optional.of(CqlClientImpl.create(
                        taggedMetricRegistry, cluster, cqlCapableConfig.tuning(), initializeAsync));
            }
        });
    }
}
