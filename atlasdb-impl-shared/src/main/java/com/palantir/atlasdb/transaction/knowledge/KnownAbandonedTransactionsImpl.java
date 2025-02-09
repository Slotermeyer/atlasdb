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

package com.palantir.atlasdb.transaction.knowledge;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.annotations.VisibleForTesting;
import com.palantir.atlasdb.internalschema.InternalSchemaInstallConfig;
import com.palantir.atlasdb.transaction.knowledge.AbandonedTransactionSoftCache.TransactionSoftCacheStatus;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.util.Set;
import org.checkerframework.checker.index.qual.NonNegative;

public class KnownAbandonedTransactionsImpl implements KnownAbandonedTransactions {

    public static final int MAXIMUM_CACHE_WEIGHT = 100_000;

    private final AbandonedTimestampStore abandonedTimestampStore;

    /**
     * This cache is only meant for timestamp ranges (inclusive) which are known to be concluded.
     */
    private final Cache<Bucket, Set<Long>> reliableCache;

    private final AbandonedTransactionSoftCache softCache;
    private final AbandonedTransactionsReliableCacheMetrics metrics;

    @VisibleForTesting
    KnownAbandonedTransactionsImpl(
            AbandonedTimestampStore abandonedTimestampStore,
            AbandonedTransactionSoftCache softCache,
            TaggedMetricRegistry registry,
            int maxCacheWeight) {
        this.abandonedTimestampStore = abandonedTimestampStore;
        this.softCache = softCache;
        this.metrics = AbandonedTransactionsReliableCacheMetrics.of(registry);
        this.reliableCache = Caffeine.newBuilder()
                .maximumWeight(maxCacheWeight)
                .weigher(new AbortedTransactionBucketWeigher())
                .evictionListener((k, v, cause) -> {
                    if (cause.wasEvicted()) {
                        metrics.reliableBucketEvictions().inc();
                    }
                })
                .build();
    }

    public static KnownAbandonedTransactionsImpl create(
            KnownConcludedTransactions knownConcludedTransactions,
            AbandonedTimestampStore abandonedTimestampStore,
            TaggedMetricRegistry registry,
            InternalSchemaInstallConfig config) {
        AbandonedTransactionSoftCache softCache =
                new AbandonedTransactionSoftCache(abandonedTimestampStore, knownConcludedTransactions, registry);
        return new KnownAbandonedTransactionsImpl(
                abandonedTimestampStore, softCache, registry, config.versionFourAbortedTransactionsCacheSize());
    }

    @Override
    public boolean isKnownAbandoned(long startTimestamp) {
        Bucket bucketForTimestamp = Bucket.forTimestamp(startTimestamp);
        TransactionSoftCacheStatus softCacheTransactionStatus = softCache.getSoftCacheTransactionStatus(startTimestamp);

        switch (softCacheTransactionStatus) {
            case IS_ABORTED:
                return true;
            case IS_NOT_ABORTED:
                return false;
            case PENDING_LOAD_FROM_RELIABLE:
                return getCachedAbortedTimestampsInBucket(bucketForTimestamp).contains(startTimestamp);
            default:
                throw new SafeIllegalStateException(
                        "Unrecognized transaction status returned from soft cache.",
                        SafeArg.of("status", softCacheTransactionStatus));
        }
    }

    @Override
    public void addAbandonedTimestamps(Set<Long> abandonedTimestamps) {
        abandonedTimestamps.forEach(abandonedTimestampStore::markAbandoned);
    }

    @VisibleForTesting
    void cleanup() {
        reliableCache.cleanUp();
    }

    private Set<Long> getCachedAbortedTimestampsInBucket(Bucket bucket) {
        metrics.allReads().inc();
        return reliableCache.get(bucket, this::getAbortedTransactionsRemote);
    }

    private Set<Long> getAbortedTransactionsRemote(Bucket bucket) {
        metrics.abandonedTxnCacheMiss().inc();
        return abandonedTimestampStore.getAbandonedTimestampsInRange(
                bucket.getMinTsInBucket(), bucket.getMaxTsInCurrentBucket());
    }

    private static final class AbortedTransactionBucketWeigher implements Weigher<Bucket, Set<Long>> {
        @Override
        public @NonNegative int weigh(Bucket key, Set<Long> value) {
            return value.size();
        }
    }
}
