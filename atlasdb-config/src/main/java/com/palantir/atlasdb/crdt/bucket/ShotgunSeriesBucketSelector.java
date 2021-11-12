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

package com.palantir.atlasdb.crdt.bucket;

import com.palantir.atlasdb.crdt.Series;
import java.util.concurrent.ThreadLocalRandom;

public class ShotgunSeriesBucketSelector implements SeriesBucketSelector {
    private final long maximumBuckets;

    public ShotgunSeriesBucketSelector(long maximumBuckets) {
        this.maximumBuckets = maximumBuckets;
    }

    @Override
    public long getBucket(Series _series) {
        return ThreadLocalRandom.current().nextLong(maximumBuckets);
    }

    @Override
    public void releaseBucket(Series _series, long _bucket) {
        // No op
    }
}
