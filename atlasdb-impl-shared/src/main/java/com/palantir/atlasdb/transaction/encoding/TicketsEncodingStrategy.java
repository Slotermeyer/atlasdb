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

package com.palantir.atlasdb.transaction.encoding;

import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.transaction.impl.TransactionConstants;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The ticketing algorithm distributes timestamps among rows and dynamic columns to avoid hot-spotting.
 *
 * We divide the first PARTITIONING_QUANTUM timestamps among the first ROW_PER_QUANTUM rows.
 * We aim to distribute start timestamps as evenly as possible among these rows as numbers increase, by taking the
 * least significant bits of the timestamp and using that as the row number. For example, we would store timestamps
 * 1, ROW_PER_QUANTUM + 1, 2 * ROW_PER_QUANTUM + 1 etc. in the same row.
 *
 * We store the row name as a bit-wise reversed version of the row number to ensure even distribution in key-value
 * services that rely on consistent hashing or similar mechanisms for partitioning.
 *
 * We also use a delta encoding for the commit timestamp as these differences are expected to be small.
 *
 * A long is 9 bytes at most, so one row here consists of at most 4 longs -> 36 bytes, and an individual row
 * is probabilistically below 1M dynamic column keys. A row is expected to be less than 14M (56M worst case).
 *
 * Note the usage of {@link PtBytes#EMPTY_BYTE_ARRAY} for transactions that were rolled back; this is a space
 * optimisation, as we would otherwise store a negative value which uses 9 bytes in a VAR_LONG.
 */
public enum TicketsEncodingStrategy implements TimestampEncodingStrategy<Long> {
    INSTANCE;

    public static final byte[] ABORTED_TRANSACTION_VALUE = PtBytes.EMPTY_BYTE_ARRAY;

    // DO NOT change the following without a transactions table migration!
    public static final long PARTITIONING_QUANTUM = 25_000_000;
    public static final int ROWS_PER_QUANTUM = TransactionConstants.V2_TRANSACTION_NUM_PARTITIONS;

    private static final TicketsCellEncodingStrategy CELL_ENCODING_STRATEGY =
            new TicketsCellEncodingStrategy(PARTITIONING_QUANTUM, ROWS_PER_QUANTUM);

    @Override
    public Cell encodeStartTimestampAsCell(long startTimestamp) {
        return CELL_ENCODING_STRATEGY.encodeStartTimestampAsCell(startTimestamp);
    }

    @Override
    public long decodeCellAsStartTimestamp(Cell cell) {
        return CELL_ENCODING_STRATEGY.decodeCellAsStartTimestamp(cell);
    }

    @Override
    public byte[] encodeCommitTimestampAsValue(long startTimestamp, Long commitTimestamp) {
        if (commitTimestamp == TransactionConstants.FAILED_COMMIT_TS) {
            return ABORTED_TRANSACTION_VALUE;
        }
        return TransactionConstants.getValueForTimestamp(commitTimestamp - startTimestamp);
    }

    @Override
    public Long decodeValueAsCommitTimestamp(long startTimestamp, byte[] value) {
        if (Arrays.equals(value, ABORTED_TRANSACTION_VALUE)) {
            return TransactionConstants.FAILED_COMMIT_TS;
        }
        return startTimestamp + TransactionConstants.getTimestampForValue(value);
    }

    public Stream<byte[]> getRowSetCoveringTimestampRange(long fromInclusive, long toInclusive) {
        return CELL_ENCODING_STRATEGY.getRowSetCoveringTimestampRange(fromInclusive, toInclusive);
    }
}
