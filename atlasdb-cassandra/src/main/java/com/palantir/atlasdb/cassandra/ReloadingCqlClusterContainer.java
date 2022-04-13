/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.cassandra;

import com.palantir.atlasdb.cassandra.CassandraServersConfigs.CassandraServersConfig;
import com.palantir.atlasdb.cassandra.backup.CqlCluster;
import com.palantir.atlasdb.keyvalue.cassandra.async.client.creation.ClusterFactory.CassandraClusterConfig;
import com.palantir.atlasdb.timelock.api.Namespace;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.refreshable.Refreshable;
import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * This container creates new CQL Clusters when the underlying server refreshable changes.
 * After a refresh, previous CQL Clusters are closed to avoid resource leaks.
 *
 * There is no guarantee that a cluster provided via {@link #get} will <i>not</i> be closed whilst in use.
 * Consumers should be resilient to any uses after the cluster has been closed, and where necessary, retry.
 *
 * After {@link #close()} has returned, no further CQL Clusters will be created, and all managed CQL Clusters will be
 * closed.
 */
public class ReloadingCqlClusterContainer implements Closeable, Supplier<CqlCluster> {
    private static final SafeLogger log = SafeLoggerFactory.get(ReloadingCqlClusterContainer.class);

    private final AtomicReference<Optional<CqlCluster>> lastCqlCluster;
    private final Refreshable<CqlCluster> refreshableCqlCluster;
    private boolean isClosed;

    private ReloadingCqlClusterContainer(
            CassandraClusterConfig cassandraClusterConfig,
            Refreshable<CassandraServersConfig> refreshableCassandraServersConfig,
            Namespace namespace,
            CqlClusterFactory cqlClusterFactory) {
        this.isClosed = false;
        this.lastCqlCluster = new AtomicReference<>(Optional.empty());
        this.refreshableCqlCluster = refreshableCassandraServersConfig.map(cassandraServersConfig ->
                createNewCluster(cassandraClusterConfig, cassandraServersConfig, namespace, cqlClusterFactory));

        refreshableCqlCluster.subscribe(cqlCluster -> {
            Optional<CqlCluster> maybeCqlClusterToClose = lastCqlCluster.getAndSet(Optional.of(cqlCluster));
            if (maybeCqlClusterToClose.isPresent()) {
                try {
                    maybeCqlClusterToClose.get().close();
                } catch (IOException e) {
                    log.warn("Failed to close CQL Cluster after reloading server list", e);
                }
            }
        });
    }

    public static ReloadingCqlClusterContainer of(
            CassandraClusterConfig cassandraClusterConfig,
            Refreshable<CassandraServersConfig> refreshableCassandraServersConfig,
            Namespace namespace) {
        return of(cassandraClusterConfig, refreshableCassandraServersConfig, namespace, CqlCluster::create);
    }

    public static ReloadingCqlClusterContainer of(
            CassandraClusterConfig cassandraClusterConfig,
            Refreshable<CassandraServersConfig> refreshableCassandraServersConfig,
            Namespace namespace,
            CqlClusterFactory cqlClusterFactory) {
        return new ReloadingCqlClusterContainer(
                cassandraClusterConfig, refreshableCassandraServersConfig, namespace, cqlClusterFactory);
    }

    /**
     * Synchronized: See {@link #close()}.
     */
    private synchronized CqlCluster createNewCluster(
            CassandraClusterConfig cassandraClusterConfig,
            CassandraServersConfig cassandraServersConfig,
            Namespace namespace,
            CqlClusterFactory cqlClusterFactory) {
        if (isClosed) {
            throw new IllegalStateException();
        }
        return cqlClusterFactory.create(cassandraClusterConfig, cassandraServersConfig, namespace);
    }

    /**
     * Synchronized: A lock is taken out to ensure no new CQL Clusters are created after retrieving the current stored
     * cql cluster to close. By doing so, we avoid closing a cluster and subsequently creating a new one that is
     * never closed.
     */
    @Override
    public synchronized void close() throws IOException {
        isClosed = true;
        Optional<CqlCluster> maybeCqlClusterToClose = lastCqlCluster.get();
        if (maybeCqlClusterToClose.isPresent()) {
            maybeCqlClusterToClose.get().close();
        }
    }

    /**
     * Gets the latest CqlCluster that reflects any changes in the server list.
     * The CQL Cluster returned will be closed after {@link #close} is called, or the server list is refreshed, even
     * if the CQL Cluster is in active use.
     */
    @Override
    public CqlCluster get() {
        return refreshableCqlCluster.get();
    }

    @FunctionalInterface
    interface CqlClusterFactory {
        CqlCluster create(
                CassandraClusterConfig cassandraClusterConfig,
                CassandraServersConfig cassandraServersConfig,
                Namespace namespace);
    }
}
