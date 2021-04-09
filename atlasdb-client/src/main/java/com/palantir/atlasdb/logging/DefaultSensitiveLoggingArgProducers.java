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

package com.palantir.atlasdb.logging;

import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.logsafe.Arg;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import java.util.List;

public final class DefaultSensitiveLoggingArgProducers {
    private static final SensitiveLoggingArgProducer ALWAYS_UNSAFE = new DefaultSensitiveLoggingArgProducer(false);
    private static final SensitiveLoggingArgProducer ALWAYS_SAFE = new DefaultSensitiveLoggingArgProducer(true);

    private DefaultSensitiveLoggingArgProducers() {
        // nope
    }

    private static class DefaultSensitiveLoggingArgProducer implements SensitiveLoggingArgProducer {
        private final boolean safe;

        private DefaultSensitiveLoggingArgProducer(boolean safe) {
            this.safe = safe;
        }

        @Override
        public List<Arg<?>> getArgsForRow(TableReference tableReference, byte[] row) {
            return singleton(getArg("row", row));
        }

        @Override
        public List<Arg<?>> getArgsForDynamicColumnsColumnKey(TableReference tableReference, byte[] row) {
            return singleton(getArg("columnKey", row));
        }

        @Override
        public List<Arg<?>> getArgsForValue(TableReference tableReference, Cell cellReference, byte[] value) {
            return singleton(getArg("value", value));
        }

        private static <T> List<T> singleton(T element) {
            return ImmutableList.of(element);
        }

        private Arg<?> getArg(String name, Object intendedValue) {
            return safe ? SafeArg.of(name, intendedValue) : UnsafeArg.of(name, intendedValue);
        }
    }
}
