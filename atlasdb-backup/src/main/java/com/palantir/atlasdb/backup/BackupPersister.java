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

import com.palantir.atlasdb.backup.api.CompletedBackup;
import com.palantir.atlasdb.backup.api.InProgressBackupToken;
import com.palantir.atlasdb.internalschema.InternalSchemaMetadataState;
import com.palantir.atlasdb.timelock.api.Namespace;
import java.util.Optional;
import java.util.UUID;

interface BackupPersister {
    void storeSchemaMetadata(Namespace namespace, InternalSchemaMetadataState internalSchemaMetadataState);

    Optional<InternalSchemaMetadataState> getSchemaMetadata(Namespace namespace);

    void storeCompletedBackup(CompletedBackup completedBackup);

    Optional<CompletedBackup> getCompletedBackup(Namespace namespace);

    void storeImmutableTimestamp(InProgressBackupToken inProgressBackupToken);

    Optional<Long> getImmutableTimestamp(Namespace namespace);

    void storeRestoreLockId(Namespace namespace, UUID lockId);

    Optional<UUID> getRestoreLockId(Namespace namespace);
}
