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

import com.palantir.common.streams.KeyedStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class ProposedSolution {
    private final Map<SweepShard, List<TokenRingSlice>> proposedSolution;

    public ProposedSolution(int numShards) {
        proposedSolution = new HashMap<>();
        IntStream.range(0, numShards)
                .forEach(shard -> proposedSolution.put(ImmutableSweepShard.of(shard), new ArrayList<>()));
    }

    public Map<SweepShard, List<TokenRingSlice>> getProposedSolution() {
        return proposedSolution;
    }

    public void addTrivialDistribution(List<TokenRingSlice> allSlices) {
        int INDEX = 0;
        for (Map.Entry<SweepShard, List<TokenRingSlice>> e: proposedSolution.entrySet()) {
            try {
                e.getValue().add(allSlices.get(INDEX));
            } catch (Exception ex) {
                //
            }
            INDEX++;
        }
    }

    public Map<SweepShard, Set<CassandraHost>> getHostsAssignedToShards() {
        return KeyedStream.stream(proposedSolution).map(this::convertToHosts).collectToMap();
    }

    private Set<CassandraHost> convertToHosts(List<TokenRingSlice> slices) {
        return slices.stream().flatMap(val -> val.hosts().stream()).collect(Collectors.toSet());
    }
}
