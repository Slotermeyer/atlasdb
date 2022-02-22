/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.keyvalue.cassandra.partitioning;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class Utils {
    private static final Random RANDOM = new Random(0);

    static Set<CassandraHost> getSetOfBlockListedHosts(int numHosts, int numberBlocklisted) {
        Set<CassandraHost> allHosts = IntStream.range(0, numHosts)
                .boxed()
                .map(ImmutableCassandraHost::of)
                .collect(Collectors.toSet());

        while (allHosts.size() > numberBlocklisted) {
            allHosts.remove(ImmutableCassandraHost.of(RANDOM.nextInt(numHosts)));
        }
        return allHosts;
    }

    static boolean canAddPermutation(
            Map<SweepShard, Set<CassandraHost>> blocklist, List<CassandraHost> permutation, SweepShard proposedShard) {
        return permutation.stream()
                .noneMatch(num -> blocklist.containsKey(proposedShard)
                        && blocklist.get(proposedShard).contains(num));
    }
}
