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

package com.palantir.atlasdb.transaction.impl.expectations;

import com.palantir.atlasdb.transaction.api.expectations.ExpectationsAwareTransaction;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ExpectationsManagerImpl implements ExpectationsManager {
    Set<ExpectationsAwareTransaction> tracking = ConcurrentHashMap.newKeySet();

    @Override
    public void scheduleMetricsUpdate(long delayMillis) {}

    @Override
    public void registerTransaction(ExpectationsAwareTransaction transaction) {
        tracking.add(transaction);
    }

    @Override
    public void unregisterTransaction(ExpectationsAwareTransaction transaction) {
        tracking.remove(transaction);
    }

    @Override
    public void markConcludedTransaction(ExpectationsAwareTransaction transaction) {
        transaction.runExpectationsCallbacks();
        unregisterTransaction(transaction);
    }

    @Override
    public void close() throws Exception {}
}
