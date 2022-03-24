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
package com.palantir.atlasdb.transaction.impl;

import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.table.description.TableMetadata;
import com.palantir.atlasdb.transaction.api.ConflictHandler;
import com.palantir.common.concurrent.PTExecutors;
import java.util.Optional;

public final class ConflictDetectionManagers {
    private ConflictDetectionManagers() {}

    public static ConflictDetectionManager createWithNoConflictDetection() {
        return _tableReference -> Optional.of(ConflictHandler.IGNORE_ALL);
    }

    public static ConflictDetectionManager createWithoutWarmingCache(KeyValueService kvs) {
        TableMetadataManagers tableMetadataManagers = TableMetadataManagers.createWithoutWarmingCache(kvs);
        return tableReference -> tableMetadataManagers.get(tableReference).map(TableMetadata::getConflictHandler);
    }

    public static ConflictDetectionManager create(KeyValueService kvs) {
        // FIX THIS - CANNOT USE newSingleThreadedExecutor as will block startup
        TableMetadataManagers tableMetadataManagers =
                TableMetadataManagers.create(kvs, true, PTExecutors.newSingleThreadExecutor());
        return tableReference -> tableMetadataManagers.get(tableReference).map(TableMetadata::getConflictHandler);
    }
}
