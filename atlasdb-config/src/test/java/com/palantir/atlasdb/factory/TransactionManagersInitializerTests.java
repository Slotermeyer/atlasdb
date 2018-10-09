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

package com.palantir.atlasdb.factory;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.Namespace;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.schema.metadata.SchemaMetadataService;
import com.palantir.atlasdb.table.description.Schema;

@RunWith(MockitoJUnitRunner.class)
public class TransactionManagersInitializerTests {

    @Mock private KeyValueService keyValueService;
    @Mock private SchemaMetadataService schemaMetadataService;

    @Before
    public void setUp() {
        when(keyValueService.getMetadataForTables()).thenReturn(ImmutableMap.of());
    }

    @Test
    public void canDropDeprecatedTables() {
        Schema schema1 = schemaWithNamespaceAndDeprecatedTables("namespace", "aTable");
        Schema schema2 = schemaWithNamespaceAndDeprecatedTables("anotherNamespace", "anotherTable");

        initializer(schema1, schema2).tryInitialize();

        Set<TableReference> expectedDeprecatedTables = ImmutableSet.of(
                TableReference.create(schema1.getNamespace(), "aTable"),
                TableReference.create(schema2.getNamespace(), "anotherTable"));

        verify(keyValueService).dropTables(expectedDeprecatedTables);
    }

    private static Schema schemaWithNamespaceAndDeprecatedTables(String namespace, String... deprecatedTables) {
        Schema schema = new Schema(Namespace.create(namespace));
        schema.addDeprecatedTables(deprecatedTables);
        return schema;
    }

    private TransactionManagersInitializer initializer(Schema... schemas) {
        return new TransactionManagersInitializer(keyValueService, ImmutableSet.copyOf(schemas), schemaMetadataService);
    }
}
