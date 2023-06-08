/*
 * (c) Copyright 2023 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.lock;

import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@SuppressWarnings("ClassInitializationDeadlock")
public interface ThreadAwareLockClient {

    ThreadAwareLockClient UNKNOWN = ThreadAwareLockClient.of(LockClient.ANONYMOUS, "");

    @Nullable
    LockClient client();

    String requestingThread();

    static ImmutableThreadAwareLockClient.Builder builder() {
        return ImmutableThreadAwareLockClient.builder();
    }

    static ThreadAwareLockClient of(LockClient client, String requestingThread) {
        return builder().client(client).requestingThread(requestingThread).build();
    }
}
