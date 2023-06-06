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

package com.palantir.atlasdb.workload.invariant;

import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.workload.invariant.RowColumnRangeQueryManager.RowColumnRangeQueryState;
import com.palantir.atlasdb.workload.store.CellReferenceAndValue;
import com.palantir.atlasdb.workload.store.ImmutableWorkloadCell;
import com.palantir.atlasdb.workload.store.Table;
import com.palantir.atlasdb.workload.store.TableAndWorkloadCell;
import com.palantir.atlasdb.workload.store.WorkloadCell;
import com.palantir.atlasdb.workload.transaction.InMemoryTransactionReplayer;
import com.palantir.atlasdb.workload.transaction.witnessed.InvalidWitnessedSingleCellTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.InvalidWitnessedTransaction;
import com.palantir.atlasdb.workload.transaction.witnessed.InvalidWitnessedTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.WitnessedDeleteTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.WitnessedReadTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.WitnessedTransactionActionVisitor;
import com.palantir.atlasdb.workload.transaction.witnessed.WitnessedWriteTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.range.InvalidWitnessedRowsColumnRangeReadTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.range.WitnessedRowsColumnRangeIteratorCreationTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.range.WitnessedRowsColumnRangeReadTransactionAction;
import com.palantir.atlasdb.workload.workflow.WorkflowHistory;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import one.util.streamex.StreamEx;

public enum SerializableInvariant implements TransactionInvariant {
    INSTANCE;

    @Override
    public void accept(
            WorkflowHistory workflowHistory, Consumer<List<InvalidWitnessedTransaction>> invalidWitnessedTransactions) {
        SerializableInvariantVisitor visitor = new SerializableInvariantVisitor();
        List<InvalidWitnessedTransaction> transactions = StreamEx.of(workflowHistory.history())
                .mapPartial(witnessedTransaction -> {
                    List<InvalidWitnessedTransactionAction> invalidTransactions = StreamEx.of(
                                    witnessedTransaction.actions())
                            .flatCollection(action -> action.accept(visitor))
                            .collect(Collectors.toList());

                    if (invalidTransactions.isEmpty()) {
                        return Optional.empty();
                    }

                    return Optional.of(InvalidWitnessedTransaction.of(witnessedTransaction, invalidTransactions));
                })
                .toList();
        invalidWitnessedTransactions.accept(transactions);
    }

    private static final class SerializableInvariantVisitor
            implements WitnessedTransactionActionVisitor<List<InvalidWitnessedTransactionAction>> {

        private final InMemoryTransactionReplayer inMemoryTransactionReplayer = new InMemoryTransactionReplayer();
        private final RowColumnRangeQueryManager rowColumnRangeQueryManager = RowColumnRangeQueryManager.create();

        @Override
        public List<InvalidWitnessedTransactionAction> visit(WitnessedReadTransactionAction readTransactionAction) {
            Optional<Integer> expectedValue = inMemoryTransactionReplayer
                    .getValues()
                    .get(readTransactionAction.table())
                    .map(table -> table.get(
                            readTransactionAction.cell().key(),
                            readTransactionAction.cell().column()))
                    .toJavaOptional()
                    .orElseGet(Optional::empty);

            if (!expectedValue.equals(readTransactionAction.value())) {
                return ImmutableList.of(InvalidWitnessedSingleCellTransactionAction.of(
                        readTransactionAction, MismatchedValue.of(readTransactionAction.value(), expectedValue)));
            }

            return ImmutableList.of();
        }

        @Override
        public List<InvalidWitnessedTransactionAction> visit(WitnessedWriteTransactionAction writeTransactionAction) {
            inMemoryTransactionReplayer.visit(writeTransactionAction);
            rowColumnRangeQueryManager.invalidateOverlappingQueries(writeTransactionAction);
            return ImmutableList.of();
        }

        @Override
        public List<InvalidWitnessedTransactionAction> visit(WitnessedDeleteTransactionAction deleteTransactionAction) {
            inMemoryTransactionReplayer.visit(deleteTransactionAction);
            rowColumnRangeQueryManager.invalidateOverlappingQueries(deleteTransactionAction);
            return ImmutableList.of();
        }

        @Override
        public List<InvalidWitnessedTransactionAction> visit(
                WitnessedRowsColumnRangeIteratorCreationTransactionAction
                        rowsColumnRangeIteratorCreationTransactionAction) {
            rowColumnRangeQueryManager.trackQueryCreation(rowsColumnRangeIteratorCreationTransactionAction);
            return ImmutableList.of();
        }

        @Override
        public List<InvalidWitnessedTransactionAction> visit(
                WitnessedRowsColumnRangeReadTransactionAction rowsColumnRangeReadTransactionAction) {
            Optional<RowColumnRangeQueryState> maybeLiveQueryState =
                    rowColumnRangeQueryManager.getLiveQueryState(rowsColumnRangeReadTransactionAction);
            if (maybeLiveQueryState.isEmpty()) {
                // Query either does not exist, or corresponds to a range for which we have subsequently seen a write
                // or delete - which is not defined behaviour.
                return ImmutableList.of();
            }
            RowColumnRangeQueryState liveQueryState = maybeLiveQueryState.get();
            Table sourceTable = inMemoryTransactionReplayer
                    .getValues()
                    .get(rowsColumnRangeReadTransactionAction.table())
                    .getOrElseThrow(() -> new SafeRuntimeException("Verifying read against nonexistent table"));
            Optional<CellReferenceAndValue> expectedRead = sourceTable
                    .getColumnsInRange(
                            rowsColumnRangeReadTransactionAction.specificRow(),
                            liveQueryState
                                    .lastReadCell()
                                    .map(WorkloadCell::column)
                                    .map(column -> column + 1) // Find the starting point of the next query
                                    .or(() -> liveQueryState
                                            .workloadColumnRangeSelection()
                                            .startColumnInclusive()),
                            liveQueryState.workloadColumnRangeSelection().endColumnExclusive())
                    .headOption()
                    .toJavaOptional()
                    .map(tuple -> CellReferenceAndValue.builder()
                            .tableAndWorkloadCell(TableAndWorkloadCell.of(
                                    rowsColumnRangeReadTransactionAction.table(),
                                    ImmutableWorkloadCell.of(
                                            rowsColumnRangeReadTransactionAction.specificRow(), tuple._1())))
                            .value(tuple._2())
                            .build());

            rowColumnRangeQueryManager.trackQueryRead(rowsColumnRangeReadTransactionAction);

            if (!expectedRead.equals(rowsColumnRangeReadTransactionAction.cellReferenceAndValue())) {
                return ImmutableList.of(InvalidWitnessedRowsColumnRangeReadTransactionAction.builder()
                        .rowColumnRangeRead(rowsColumnRangeReadTransactionAction)
                        .expectedRead(expectedRead)
                        .build());
            }
            return ImmutableList.of();
        }
    }
}
