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

package com.palantir.lock.watch;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.palantir.lock.LockDescriptor;

public interface ClientLockWatchEventLog {
    TransactionsLockWatchEvents getEventsForTransactions(
            Map<Long, IdentifiedVersion> timestampToVersion,
            Optional<IdentifiedVersion> version);
    Optional<Set<LockDescriptor>> getEventsBetweenVersions(
            IdentifiedVersion startVersion,
            IdentifiedVersion endVersion);
    Optional<IdentifiedVersion> getLatestKnownVersion();
    Optional<IdentifiedVersion> processUpdate(LockWatchStateUpdate update, Optional<IdentifiedVersion> earliestVersion);
}
