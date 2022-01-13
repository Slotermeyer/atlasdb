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

package com.palantir.atlasdb.backup;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.atlasdb.backup.api.AtlasBackupClient;
import com.palantir.atlasdb.backup.api.AtlasBackupClientEndpoints;
import com.palantir.atlasdb.backup.api.CompleteBackupRequest;
import com.palantir.atlasdb.backup.api.CompleteBackupResponse;
import com.palantir.atlasdb.backup.api.CompletedBackup;
import com.palantir.atlasdb.backup.api.InProgressBackupToken;
import com.palantir.atlasdb.backup.api.PrepareBackupRequest;
import com.palantir.atlasdb.backup.api.PrepareBackupResponse;
import com.palantir.atlasdb.backup.api.UndertowAtlasBackupClient;
import com.palantir.atlasdb.futures.AtlasFutures;
import com.palantir.atlasdb.http.RedirectRetryTargeter;
import com.palantir.atlasdb.timelock.AsyncTimelockService;
import com.palantir.atlasdb.timelock.ConjureResourceExceptionHandler;
import com.palantir.atlasdb.timelock.api.Namespace;
import com.palantir.conjure.java.undertow.lib.UndertowService;
import com.palantir.lock.v2.IdentifiedTimeLockRequest;
import com.palantir.lock.v2.LockImmutableTimestampResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.tokens.auth.AuthHeader;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AtlasBackupResource implements UndertowAtlasBackupClient {
    private final Function<String, AsyncTimelockService> timelockServices;
    private final ConjureResourceExceptionHandler exceptionHandler;

    @VisibleForTesting
    AtlasBackupResource(
            RedirectRetryTargeter redirectRetryTargeter, Function<String, AsyncTimelockService> timelockServices) {
        this.exceptionHandler = new ConjureResourceExceptionHandler(redirectRetryTargeter);
        this.timelockServices = timelockServices;
    }

    public static UndertowService undertow(
            RedirectRetryTargeter redirectRetryTargeter, Function<String, AsyncTimelockService> timelockServices) {
        return AtlasBackupClientEndpoints.of(new AtlasBackupResource(redirectRetryTargeter, timelockServices));
    }

    public static AtlasBackupClient jersey(
            RedirectRetryTargeter redirectRetryTargeter, Function<String, AsyncTimelockService> timelockServices) {
        return new JerseyAtlasBackupClientAdapter(new AtlasBackupResource(redirectRetryTargeter, timelockServices));
    }

    @Override
    public ListenableFuture<PrepareBackupResponse> prepareBackup(AuthHeader authHeader, PrepareBackupRequest request) {
        return handleExceptions(() -> Futures.immediateFuture(prepareBackupInternal(request)));
    }

    private PrepareBackupResponse prepareBackupInternal(PrepareBackupRequest request) {
        Set<InProgressBackupToken> preparedBackups =
                request.getNamespaces().stream().map(this::prepareBackup).collect(Collectors.toSet());
        return PrepareBackupResponse.of(preparedBackups);
    }

    private InProgressBackupToken prepareBackup(Namespace namespace) {
        AsyncTimelockService timelock = timelock(namespace);
        LockImmutableTimestampResponse response = timelock.lockImmutableTimestamp(IdentifiedTimeLockRequest.create());
        long timestamp = timelock.getFreshTimestamp();

        return InProgressBackupToken.builder()
                .namespace(namespace)
                .lockToken(response.getLock())
                .immutableTimestamp(response.getImmutableTimestamp())
                .backupStartTimestamp(timestamp)
                .build();
    }

    @Override
    public ListenableFuture<CompleteBackupResponse> completeBackup(
            AuthHeader authHeader, CompleteBackupRequest request) {
        return handleExceptions(() -> completeBackupInternal(request));
    }

    @SuppressWarnings("ConstantConditions")
    private ListenableFuture<CompleteBackupResponse> completeBackupInternal(CompleteBackupRequest request) {
        Map<InProgressBackupToken, ListenableFuture<Optional<CompletedBackup>>> futureMap =
                request.getBackupTokens().stream().collect(Collectors.toMap(token -> token, this::completeBackupAsync));
        ListenableFuture<Map<InProgressBackupToken, CompletedBackup>> singleFuture =
                AtlasFutures.allAsMap(futureMap, MoreExecutors.newDirectExecutorService());

        return Futures.transform(
                singleFuture,
                map -> CompleteBackupResponse.of(ImmutableSet.copyOf(map.values())),
                MoreExecutors.directExecutor());
    }

    @Override
    public ListenableFuture<Void> disableTimelock(AuthHeader authHeader, Set<Namespace> namespaces) {
        return handleExceptions(() -> disableInternal(namespaces));
    }

    private ListenableFuture<Void> disableInternal(Set<Namespace> namespaces) {
        // todo(gmaretic): disable all remote nodes
        // todo(gmaretic): disable locally
        return Futures.immediateVoidFuture();
    }

    @Override
    public ListenableFuture<Void> reenableTimelock(AuthHeader authHeader, Set<Namespace> namespaces) {
        return handleExceptions(() -> reenableInternal(namespaces));
    }

    public ListenableFuture<Void> reenableInternal(Set<Namespace> namespaces) {
        // todo(gmaretic): reenable all remote nodes
        // todo(gmaretic): reenable locally
        return Futures.immediateVoidFuture();
    }

    @SuppressWarnings("ConstantConditions") // optional token is never null
    private ListenableFuture<Optional<CompletedBackup>> completeBackupAsync(InProgressBackupToken backupToken) {
        return Futures.transform(
                maybeUnlock(backupToken),
                maybeToken -> maybeToken.map(_successfulUnlock -> fetchFastForwardTimestamp(backupToken)),
                MoreExecutors.directExecutor());
    }

    @SuppressWarnings("ConstantConditions") // Set of locks is never null
    private ListenableFuture<Optional<LockToken>> maybeUnlock(InProgressBackupToken backupToken) {
        return Futures.transform(
                timelock(backupToken.getNamespace()).unlock(ImmutableSet.of(backupToken.getLockToken())),
                singletonOrEmptySet -> singletonOrEmptySet.stream().findFirst(),
                MoreExecutors.directExecutor());
    }

    private CompletedBackup fetchFastForwardTimestamp(InProgressBackupToken backupToken) {
        Namespace namespace = backupToken.getNamespace();
        long fastForwardTimestamp = timelock(namespace).getFreshTimestamp();
        return CompletedBackup.builder()
                .namespace(namespace)
                .immutableTimestamp(backupToken.getImmutableTimestamp())
                .backupStartTimestamp(backupToken.getBackupStartTimestamp())
                .backupEndTimestamp(fastForwardTimestamp)
                .build();
    }

    private AsyncTimelockService timelock(Namespace namespace) {
        return timelockServices.apply(namespace.get());
    }

    private <T> ListenableFuture<T> handleExceptions(Supplier<ListenableFuture<T>> supplier) {
        return exceptionHandler.handleExceptions(supplier);
    }

    public static final class JerseyAtlasBackupClientAdapter implements AtlasBackupClient {
        private final AtlasBackupResource resource;

        public JerseyAtlasBackupClientAdapter(AtlasBackupResource resource) {
            this.resource = resource;
        }

        @Override
        public PrepareBackupResponse prepareBackup(AuthHeader authHeader, PrepareBackupRequest request) {
            return unwrap(resource.prepareBackup(authHeader, request));
        }

        @Override
        public CompleteBackupResponse completeBackup(AuthHeader authHeader, CompleteBackupRequest request) {
            return unwrap(resource.completeBackup(authHeader, request));
        }

        @Override
        public void disableTimelock(AuthHeader authHeader, Set<Namespace> namespaces) {
            unwrap(resource.disableTimelock(authHeader, namespaces));
        }

        @Override
        public void reenableTimelock(AuthHeader authHeader, Set<Namespace> namespaces) {
            unwrap(resource.reenableTimelock(authHeader, namespaces));
        }

        private static <T> T unwrap(ListenableFuture<T> future) {
            return AtlasFutures.getUnchecked(future);
        }
    }
}
