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

package com.palantir.atlasdb.keyvalue.api.watch;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;
import com.palantir.lock.watch.CacheStatus;
import com.palantir.lock.watch.IdentifiedVersion;
import com.palantir.lock.watch.LockWatchCreatedEvent;
import com.palantir.lock.watch.LockWatchEvent;
import com.palantir.lock.watch.LockWatchStateUpdate;
import com.palantir.logsafe.Preconditions;

final class LockWatchEventLogImpl implements LockWatchEventLog {
    private final ClientLockWatchSnapshot snapshot;
    private final VersionedEventStore eventStore = new VersionedEventStore();

    private Optional<IdentifiedVersion> latestVersion = Optional.empty();

    static LockWatchEventLog create() {
        return create(ClientLockWatchSnapshotImpl.create());
    }

    @VisibleForTesting
    static LockWatchEventLog create(ClientLockWatchSnapshot snapshot) {
        return new LockWatchEventLogImpl(snapshot);
    }

    private LockWatchEventLogImpl(ClientLockWatchSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public CacheStatus processUpdate(LockWatchStateUpdate update) {
        if (!latestVersion.isPresent() || !update.logId().equals(latestVersion.get().id())) {
            return update.accept(new NewLeaderVisitor());
        } else {
            return update.accept(new ProcessingVisitor());
        }
    }

    @Override
    public ClientLogEvents getEventsBetweenVersions(
            Optional<IdentifiedVersion> lastKnownVersion,
            IdentifiedVersion endVersion) {
        Optional<IdentifiedVersion> startVersion = lastKnownVersion.map(this::createStartVersion);
        IdentifiedVersion currentVersion = getLatestVersionAndVerify(endVersion);
        ClientLogEvents.Builder eventBuilder = new ClientLogEvents.Builder();
        final long fromSequence;

        if (!startVersion.isPresent() || differentLeaderOrTooFarBehind(currentVersion, startVersion.get())) {
            eventBuilder.addEvents(LockWatchCreatedEvent.fromSnapshot(snapshot.getSnapshot()));
            eventBuilder.clearCache(true);
            if (eventStore.isEmpty()) {
                return eventBuilder.build();
            }
            fromSequence = eventStore.getFirstKey();
        } else {
            eventBuilder.clearCache(false);
            fromSequence = startVersion.get().version();
        }

        eventBuilder.addAllEvents(eventStore.getEventsBetweenVersionsInclusive(fromSequence, endVersion.version()));
        return eventBuilder.build();
    }

    @Override
    public void removeEventsBefore(long earliestSequence) {
        Set<Map.Entry<Long, LockWatchEvent>> eventsToBeRemoved = eventStore.getElementsUpToExclusive(earliestSequence);
        Optional<Long> latestDeletedVersion = Streams.findLast(eventsToBeRemoved.stream()).map(Map.Entry::getKey);
        Optional<IdentifiedVersion> currentVersion = getLatestKnownVersion();

        if (eventsToBeRemoved.isEmpty() || !latestDeletedVersion.isPresent() || !currentVersion.isPresent()) {
            return;
        }

        snapshot.processEvents(
                eventsToBeRemoved.stream().map(Map.Entry::getValue).collect(Collectors.toList()),
                IdentifiedVersion.of(currentVersion.get().id(), latestDeletedVersion.get()));
        eventStore.clearElementsUpToExclusive(earliestSequence);
    }

    @Override
    public Optional<IdentifiedVersion> getLatestKnownVersion() {
        return latestVersion;
    }

    private boolean differentLeaderOrTooFarBehind(IdentifiedVersion currentVersion,
            IdentifiedVersion startVersion) {
        return !startVersion.id().equals(currentVersion.id()) || !eventStore.hasFloorKey(startVersion.version());
    }

    private IdentifiedVersion createStartVersion(IdentifiedVersion startVersion) {
        return IdentifiedVersion.of(startVersion.id(), startVersion.version() + 1);
    }

    private IdentifiedVersion getLatestVersionAndVerify(IdentifiedVersion endVersion) {
        Preconditions.checkState(latestVersion.isPresent(), "Cannot get events when log does not know its version");
        IdentifiedVersion currentVersion = latestVersion.get();
        Preconditions.checkArgument(endVersion.version() <= currentVersion.version(),
                "Transactions' view of the world is more up-to-date than the log");
        return currentVersion;
    }

    private void processSuccess(LockWatchStateUpdate.Success success) {
        Preconditions.checkState(latestVersion.isPresent(), "Must have a known version to process successful updates");

        if (success.lastKnownVersion() > latestVersion.get().version()) {
            success.events().forEach(event -> eventStore.put(event.sequence(), event));
            latestVersion = Optional.of(IdentifiedVersion.of(success.logId(), eventStore.getLastKey()));
        }
    }

    private void processSnapshot(LockWatchStateUpdate.Snapshot snapshotUpdate) {
        eventStore.clear();
        this.snapshot.resetWithSnapshot(snapshotUpdate);
        latestVersion = Optional.of(IdentifiedVersion.of(snapshotUpdate.logId(), snapshotUpdate.lastKnownVersion()));
    }

    private void processFailed() {
        eventStore.clear();
        snapshot.reset();
        latestVersion = Optional.empty();
    }

    private class ProcessingVisitor implements LockWatchStateUpdate.Visitor<CacheStatus> {
        @Override
        public CacheStatus visit(LockWatchStateUpdate.Failed failed) {
            processFailed();
            return CacheStatus.CLEAR_CACHE;
        }

        @Override
        public CacheStatus visit(LockWatchStateUpdate.Success success) {
            processSuccess(success);
            return CacheStatus.KEEP_CACHE;
        }

        @Override
        public CacheStatus visit(LockWatchStateUpdate.Snapshot snapshotUpdate) {
            processSnapshot(snapshotUpdate);
            return CacheStatus.CLEAR_CACHE;
        }
    }

    private class NewLeaderVisitor implements LockWatchStateUpdate.Visitor<CacheStatus> {
        @Override
        public CacheStatus visit(LockWatchStateUpdate.Failed failed) {
            processFailed();
            return CacheStatus.CLEAR_CACHE;
        }

        @Override
        public CacheStatus visit(LockWatchStateUpdate.Success success) {
            processFailed();
            return CacheStatus.CLEAR_CACHE;
        }

        @Override
        public CacheStatus visit(LockWatchStateUpdate.Snapshot snapshotUpdate) {
            processSnapshot(snapshotUpdate);
            return CacheStatus.CLEAR_CACHE;
        }
    }
}
