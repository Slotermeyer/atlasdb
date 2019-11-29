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

package com.palantir.lock.client;

import java.util.Set;

import com.palantir.lock.v2.LockImmutableTimestampResponse;
import com.palantir.lock.v2.LockRequest;
import com.palantir.lock.v2.LockResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.NamespacedTimelockRpcClient;
import com.palantir.lock.v2.StartTransactionWithWatchesResponse;
import com.palantir.lock.v2.TimelockService;
import com.palantir.lock.v2.WaitForLocksRequest;
import com.palantir.lock.v2.WaitForLocksResponse;
import com.palantir.lock.watch.LockWatchEventLog;
import com.palantir.timestamp.TimestampRange;

public final class RemoteTimelockServiceAdapter implements TimelockService, AutoCloseable {
    private final NamespacedTimelockRpcClient rpcClient;
    private final LockLeaseService lockLeaseService;
    private final TransactionStarter transactionStarter;

    private RemoteTimelockServiceAdapter(NamespacedTimelockRpcClient rpcClient, LockWatchEventLog lockWatchLog) {
        this.rpcClient = rpcClient;
        this.lockLeaseService = LockLeaseService.create(rpcClient);
        this.transactionStarter = TransactionStarter.create(lockLeaseService, lockWatchLog);
    }

    public static RemoteTimelockServiceAdapter create(NamespacedTimelockRpcClient rpcClient, LockWatchEventLog lockWatchLog) {
        return new RemoteTimelockServiceAdapter(rpcClient, lockWatchLog);
    }

    @Override
    public long getFreshTimestamp() {
        return rpcClient.getFreshTimestamp();
    }

    @Override
    public TimestampRange getFreshTimestamps(int numTimestampsRequested) {
        return rpcClient.getFreshTimestamps(numTimestampsRequested);
    }

    @Override
    public long getImmutableTimestamp() {
        return rpcClient.getImmutableTimestamp();
    }

    @Override
    public WaitForLocksResponse waitForLocks(WaitForLocksRequest request) {
        return rpcClient.waitForLocks(request);
    }

    @Override
    public LockImmutableTimestampResponse lockImmutableTimestamp() {
        return lockLeaseService.lockImmutableTimestamp();
    }

    @Override
    public LockResponse lock(LockRequest request) {
        return lockLeaseService.lock(request);
    }

    @Override
    public StartTransactionWithWatchesResponse startIdentifiedAtlasDbTransaction() {
        return transactionStarter.startIdentifiedAtlasDbTransaction();
    }

    @Override
    public Set<LockToken> refreshLockLeases(Set<LockToken> tokens) {
        return transactionStarter.refreshLockLeases(tokens);
    }

    @Override
    public Set<LockToken> unlock(Set<LockToken> tokens) {
        return transactionStarter.unlock(tokens);
    }

    @Override
    public long currentTimeMillis() {
        return rpcClient.currentTimeMillis();
    }

    @Override
    public void close() {
        transactionStarter.close();
    }
}
