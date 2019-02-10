/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.lock.v2;

import java.util.function.Function;

import org.immutables.value.Value;

public interface LeasableLockResponse {
    <T> T accept(Visitor<T> visitor);

    @Value.Immutable
    interface Successful extends LeasableLockResponse {
        LockToken getToken();
        Lease getLease();

        default <T> T accept(Visitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    @Value.Immutable
    interface Unsuccessful extends LeasableLockResponse {
        default <T> T accept(Visitor<T> visitor) {
            return visitor.visit(this);
        }
    }

    interface Visitor<T> {
        T visit(Successful successful);
        T visit(Unsuccessful failure);

        static <T> Visitor<T> of(Function<Successful, T> successFunction, Function<Unsuccessful, T> failureFunction) {
            return new Visitor<T>() {
                @Override
                public T visit(Successful successful) {
                    return successFunction.apply(successful);
                }

                @Override
                public T visit(Unsuccessful failure) {
                    return failureFunction.apply(failure);
                }
            };
        }
    }

    static LeasableLockResponse successful(LockToken lockToken, Lease lease) {
        return ImmutableSuccessful.builder()
                .token(lockToken)
                .lease(lease)
                .build();
    }

    static LeasableLockResponse timedOut() {
        return ImmutableUnsuccessful.builder().build();
    }
}
