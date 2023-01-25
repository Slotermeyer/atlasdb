/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.keyvalue.api.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CompileTimeConstant;
import com.palantir.atlasdb.keyvalue.api.AtlasLockDescriptorUtils;
import com.palantir.atlasdb.keyvalue.api.AtlasLockDescriptorUtils.TableRefAndRemainder;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CellReference;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.watch.LockEvent;
import com.palantir.lock.watch.LockWatchCreatedEvent;
import com.palantir.lock.watch.LockWatchEvent;
import com.palantir.lock.watch.LockWatchReferenceTableExtractor;
import com.palantir.lock.watch.UnlockEvent;
import com.palantir.logsafe.Safe;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.concurrent.ThreadSafe;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

@ThreadSafe
final class ValueStoreImpl implements ValueStore {
    /**
     * We introduce some overhead to storing each value. This makes caching numerous empty values with small cell
     * names more costly.
     */
    static final int CACHE_OVERHEAD = 128;

    private final StructureHolder<io.vavr.collection.Map<CellReference, CacheEntry>> values;
    private final StructureHolder<io.vavr.collection.Set<TableReference>> watchedTables;
    private final ImmutableSet<TableReference> allowedTables;
    private final Cache<CellReference, Integer> loadedValues;
    private final LockWatchVisitor visitor = new LockWatchVisitor();
    private final CacheMetrics metrics;

    ValueStoreImpl(Set<TableReference> allowedTables, long maxCacheSize, CacheMetrics metrics) {
        this.allowedTables = ImmutableSet.copyOf(allowedTables);
        this.values = StructureHolder.create(HashMap::empty);
        this.watchedTables = StructureHolder.create(HashSet::empty);
        this.loadedValues = Caffeine.newBuilder()
                .maximumWeight(maxCacheSize)
                .weigher(EntryWeigher.INSTANCE)
                .executor(MoreExecutors.directExecutor())
                .removalListener((cellReference, value, cause) -> {
                    if (cause.wasEvicted()) {
                        values.with(map -> map.remove(cellReference));
                    }
                    metrics.decreaseCacheSize(EntryWeigher.INSTANCE.weigh(cellReference, value));
                })
                .build();
        this.metrics = metrics;
        metrics.setMaximumCacheSize(maxCacheSize);
    }

    @Override
    public void reset() {
        values.resetToInitialValue();
        watchedTables.resetToInitialValue();
        loadedValues.invalidateAll();

        // Forcing the cache to run cleanup here guarantees that the metrics are not affected after they have been reset
        loadedValues.cleanUp();
        metrics.resetCacheSize();
    }

    @Override
    public void applyEvent(LockWatchEvent event) {
        event.accept(visitor);
    }

    @Override
    public void putValue(CellReference cellReference, CacheValue value) {
        values.with(map -> map.put(cellReference, CacheEntry.unlocked(value), (oldValue, newValue) -> {
            if (oldValue.status().isLocked()) {
                throw invalidPut("Trying to cache a value that is currently locked", cellReference, oldValue, newValue);
            } else if (!oldValue.equals(newValue)) {
                throw invalidPut(
                        "Trying to cache a value that is not equal to currently cached value",
                        cellReference,
                        oldValue,
                        newValue);
            }
            metrics.decreaseCacheSize(
                    EntryWeigher.INSTANCE.weigh(cellReference, oldValue.value().size()));
            return newValue;
        }));
        loadedValues.put(cellReference, value.size());
        metrics.increaseCacheSize(EntryWeigher.INSTANCE.weigh(cellReference, value.size()));
    }

    private static SafeIllegalStateException invalidPut(
            @CompileTimeConstant @Safe String message,
            CellReference cellReference,
            CacheEntry oldValue,
            CacheEntry newValue) {
        throw new SafeIllegalStateException(
                message,
                UnsafeArg.of("table", cellReference.tableRef()),
                UnsafeArg.of("cell", cellReference.cell()),
                UnsafeArg.of("oldValue", oldValue),
                UnsafeArg.of("newValue", newValue));
    }

    @Override
    public ValueCacheSnapshot getSnapshot() {
        return ValueCacheSnapshotImpl.of(values.getSnapshot(), watchedTables.getSnapshot(), allowedTables);
    }

    private void putLockedCell(CellReference cellReference) {
        if (values.apply(map -> map.get(cellReference).toJavaOptional())
                .filter(CacheEntry::isUnlocked)
                .isPresent()) {
            loadedValues.invalidate(cellReference);
        }
        values.with(map -> map.put(cellReference, CacheEntry.locked()));
    }

    private void clearLockedCell(CellReference cellReference) {
        values.with(map -> map.get(cellReference)
                .toJavaOptional()
                .filter(entry -> entry.status().isLocked())
                .map(_unused -> map.remove(cellReference))
                .orElse(map));
    }

    private void applyLockedDescriptors(Set<LockDescriptor> lockDescriptors) {
        getCandidateCells(lockDescriptors).forEach(this::putLockedCell);
    }

    private Stream<CellReference> getCandidateCells(Set<LockDescriptor> lockDescriptors) {
        return lockDescriptors.stream()
                .map(AtlasLockDescriptorUtils::tryParseTableRef)
                .flatMap(Optional::stream)
                // Explicitly exclude descriptors corresponding to watched rows from non-watched tables
                .filter(this::isTableWatched)
                .flatMap(this::extractCandidateCells);
    }

    private boolean isTableWatched(TableRefAndRemainder parsedLockDescriptor) {
        return watchedTables.getSnapshot().contains(parsedLockDescriptor.tableRef());
    }

    private Stream<CellReference> extractCandidateCells(TableRefAndRemainder descriptor) {
        return AtlasLockDescriptorUtils.candidateCells(descriptor).stream();
    }

    private final class LockWatchVisitor implements LockWatchEvent.Visitor<Void> {
        @Override
        public Void visit(LockEvent lockEvent) {
            applyLockedDescriptors(lockEvent.lockDescriptors());
            return null;
        }

        @Override
        public Void visit(UnlockEvent unlockEvent) {
            getCandidateCells(unlockEvent.lockDescriptors()).forEach(ValueStoreImpl.this::clearLockedCell);
            return null;
        }

        @Override
        public Void visit(LockWatchCreatedEvent lockWatchCreatedEvent) {
            lockWatchCreatedEvent.references().stream()
                    .map(reference -> reference.accept(LockWatchReferenceTableExtractor.INSTANCE))
                    .flatMap(Optional::stream)
                    .forEach(tableReference -> watchedTables.with(tables -> tables.add(tableReference)));

            applyLockedDescriptors(lockWatchCreatedEvent.lockDescriptors());
            return null;
        }
    }

    enum EntryWeigher implements Weigher<CellReference, Integer> {
        INSTANCE;

        @Override
        public @NonNegative int weigh(@NonNull CellReference key, @NonNull Integer value) {
            return CACHE_OVERHEAD + value + weighTable(key.tableRef()) + weighCell(key.cell());
        }

        private int weighTable(@NonNull TableReference table) {
            return table.toString().length();
        }

        private int weighCell(@NonNull Cell cell) {
            return cell.getRowName().length + cell.getColumnName().length;
        }
    }
}
