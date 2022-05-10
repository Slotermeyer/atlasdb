/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.atlasdb.keyvalue.cassandra;

import com.google.common.base.Stopwatch;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.Futures;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.containers.CassandraResource;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyAlreadyExistsException;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.pue.ConsensusForgettingStore;
import com.palantir.atlasdb.pue.InstrumentedConsensusForgettingStore;
import com.palantir.atlasdb.pue.KvsConsensusForgettingStore;
import com.palantir.atlasdb.pue.PutUnlessExistsValue;
import com.palantir.atlasdb.transaction.api.Transaction;
import com.palantir.atlasdb.transaction.encoding.TwoPhaseEncodingStrategy;
import com.palantir.atlasdb.transaction.impl.AbstractTransactionTest;
import com.palantir.atlasdb.transaction.impl.GetAsyncDelegate;
import com.palantir.atlasdb.transaction.impl.TransactionConstants;
import com.palantir.atlasdb.transaction.impl.TransactionSchemaVersionEnforcement;
import com.palantir.atlasdb.transaction.impl.TransactionTables;
import com.palantir.atlasdb.transaction.service.SimpleTransactionService;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.WriteBatchingTransactionService;
import com.palantir.common.streams.KeyedStream;
import com.palantir.flake.FlakeRetryingRule;
import com.palantir.flake.ShouldRetry;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@ShouldRetry // The first test can fail with a TException: No host tried was able to create the keyspace requested.
@RunWith(Parameterized.class)
public class CassandraKeyValueServiceTransactionIntegrationTest extends AbstractTransactionTest {
    private static final String SYNC_TRANSACTIONS_1 = "sync, transactions1";
    private static final String ASYNC_TRANSACTIONS_2 = "async, transactions2";
    private static final String ASYNC_TRANSACTIONS_3 = "async, transactions3";
    private static final Supplier<KeyValueService> KVS_SUPPLIER =
            Suppliers.memoize(CassandraKeyValueServiceTransactionIntegrationTest::createAndRegisterKeyValueService);

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        Object[][] data = new Object[][] {
            // {
            //     SYNC_TRANSACTIONS_1,
            //     UnaryOperator.identity(),
            //     TransactionConstants.DIRECT_ENCODING_TRANSACTIONS_SCHEMA_VERSION
            // },
            // {
            //     ASYNC_TRANSACTIONS_2,
            //     (UnaryOperator<Transaction>) GetAsyncDelegate::new,
            //     TransactionConstants.TICKETS_ENCODING_TRANSACTIONS_SCHEMA_VERSION
            // },
            {
                ASYNC_TRANSACTIONS_3,
                (UnaryOperator<Transaction>) GetAsyncDelegate::new,
                TransactionConstants.TWO_STAGE_ENCODING_TRANSACTIONS_SCHEMA_VERSION
            }
        };
        return Arrays.asList(data);
    }

    @ClassRule
    public static final CassandraResource CASSANDRA = new CassandraResource(KVS_SUPPLIER);

    // This constant exists so that fresh timestamps are always greater than the write timestamps of values used in the
    // test.
    private static final long ONE_BILLION = 1_000_000_000;

    @Rule
    public final TestRule flakeRetryingRule = new FlakeRetryingRule();

    private final String name;
    private final UnaryOperator<Transaction> transactionWrapper;
    private final int transactionsSchemaVersion;

    public CassandraKeyValueServiceTransactionIntegrationTest(
            String name, UnaryOperator<Transaction> transactionWrapper, int transactionsSchemaVersion) {
        super(CASSANDRA, CASSANDRA);
        this.name = name;
        this.transactionWrapper = transactionWrapper;
        this.transactionsSchemaVersion = transactionsSchemaVersion;
    }

    @Before
    public void before() {
        advanceTimestamp();
        installTransactionsVersion();
    }

    private void advanceTimestamp() {
        timestampManagementService.fastForwardTimestamp(ONE_BILLION);
    }

    private void installTransactionsVersion() {
        keyValueService.truncateTable(AtlasDbConstants.COORDINATION_TABLE);
        TransactionSchemaVersionEnforcement.ensureTransactionsGoingForwardHaveSchemaVersion(
                transactionSchemaManager, timestampService, timestampManagementService, transactionsSchemaVersion);
    }

    @Test
    public void sadness() {
        TransactionTables.createTables(keyValueService);
        TransactionTables.truncateTables(keyValueService);

        TaggedMetricRegistry metricRegistry = new DefaultTaggedMetricRegistry();

        ConsensusForgettingStore store = InstrumentedConsensusForgettingStore.create(
                new KvsConsensusForgettingStore(keyValueService, TransactionConstants.TRANSACTIONS2_TABLE),
                metricRegistry);

        Map<Cell, Long> cellToStartTs = LongStream.range(1, 100)
                .boxed()
                .collect(Collectors.toMap(TwoPhaseEncodingStrategy.INSTANCE::encodeStartTimestampAsCell, x -> x));
        Map<Cell, byte[]> stagingValues = KeyedStream.stream(cellToStartTs)
                .map(startTs -> TwoPhaseEncodingStrategy.INSTANCE.encodeCommitTimestampAsValue(
                        startTs, PutUnlessExistsValue.committed(startTs)))
                .collectToMap();
        store.putUnlessExists(stagingValues);
        SimpleTransactionService v3TransactionService =
                SimpleTransactionService.createV3(keyValueService, metricRegistry, () -> false);
        TransactionService transactionService = WriteBatchingTransactionService.create(v3TransactionService);
        int numTransactionGetters = 300;
        ExecutorService executorService = Executors.newFixedThreadPool(numTransactionGetters);
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i < 100; i++) {
            long ts = i;
            futures.addAll(LongStream.range(1, numTransactionGetters)
                    .mapToObj(ignore -> executorService.submit(() -> transactionService.get(ts)))
                    .collect(Collectors.toList()));
        }
        futures.stream().forEach(Futures::getUnchecked);
        System.out.println(stopwatch.elapsed());
    }

    @Test
    public void happiness() {
        TransactionTables.createTables(keyValueService);
        TransactionTables.truncateTables(keyValueService);

        TaggedMetricRegistry metricRegistry = new DefaultTaggedMetricRegistry();

        ConsensusForgettingStore store = InstrumentedConsensusForgettingStore.create(
                new KvsConsensusForgettingStore(keyValueService, TransactionConstants.TRANSACTIONS2_TABLE),
                metricRegistry);

        Map<Cell, Long> cellToStartTs = LongStream.range(1, 100)
                .boxed()
                .collect(Collectors.toMap(TwoPhaseEncodingStrategy.INSTANCE::encodeStartTimestampAsCell, x -> x));
        Map<Cell, byte[]> stagingValues = KeyedStream.stream(cellToStartTs)
                .map(startTs -> TwoPhaseEncodingStrategy.INSTANCE.encodeCommitTimestampAsValue(
                        startTs, PutUnlessExistsValue.committed(startTs)))
                .collectToMap();
        store.putUnlessExists(stagingValues);
        SimpleTransactionService v3TransactionService =
                SimpleTransactionService.createV3(keyValueService, metricRegistry, () -> true);
        TransactionService transactionService = WriteBatchingTransactionService.create(v3TransactionService);
        int numTransactionGetters = 300;
        ExecutorService executorService = Executors.newFixedThreadPool(numTransactionGetters);
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i < 100; i++) {
            long ts = i;
            futures.addAll(LongStream.range(1, numTransactionGetters)
                    .mapToObj(ignore -> executorService.submit(() -> transactionService.get(ts)))
                    .collect(Collectors.toList()));
        }
        futures.stream().forEach(Futures::getUnchecked);
        System.out.println(stopwatch.elapsed());
    }

    @Test
    public void v2() {
        TransactionTables.createTables(keyValueService);
        TransactionTables.truncateTables(keyValueService);

        SimpleTransactionService v3TransactionService = SimpleTransactionService.createV2(keyValueService);
        TransactionService transactionService = WriteBatchingTransactionService.create(v3TransactionService);
        transactionService.putUnlessExistsMultiple(
                LongStream.range(1, 100).boxed().collect(Collectors.toMap(x -> x, x -> x)));
        int numTransactionGetters = 300;
        ExecutorService executorService = Executors.newFixedThreadPool(numTransactionGetters);
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i < 100; i++) {
            long ts = i;
            futures.addAll(LongStream.range(1, numTransactionGetters)
                    .mapToObj(ignore -> executorService.submit(() -> transactionService.get(ts)))
                    .collect(Collectors.toList()));
        }
        futures.stream().forEach(Futures::getUnchecked);
        System.out.println(stopwatch.elapsed());
    }

    private void tryPutTimestampPermittingExceptions(TransactionService transactionService, long timestamp) {
        try {
            transactionService.putUnlessExists(timestamp, timestamp + 1);
        } catch (KeyAlreadyExistsException ex) {
            // OK
        }
    }

    private static KeyValueService createAndRegisterKeyValueService() {
        CassandraKeyValueService kvs = CassandraKeyValueServiceImpl.createForTesting(CASSANDRA.getConfig());
        CASSANDRA.registerKvs(kvs);
        return kvs;
    }

    @Override
    protected boolean supportsReverse() {
        return false;
    }

    @Override
    protected Transaction startTransaction() {
        return transactionWrapper.apply(super.startTransaction());
    }
}
