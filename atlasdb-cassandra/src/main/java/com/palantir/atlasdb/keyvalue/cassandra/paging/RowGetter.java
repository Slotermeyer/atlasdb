/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.cassandra.paging;

import java.net.InetSocketAddress;
import java.util.List;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.UnavailableException;

import com.palantir.atlasdb.keyvalue.api.InsufficientConsistencyException;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.cassandra.CassandraClientPool;
import com.palantir.atlasdb.keyvalue.cassandra.CassandraKeyValueServiceImpl;
import com.palantir.atlasdb.keyvalue.cassandra.TracingQueryRunner;
import com.palantir.common.base.FunctionCheckedException;
import com.palantir.common.base.Throwables;

public class RowGetter {
    private CassandraClientPool clientPool;
    private TracingQueryRunner queryRunner;
    private ConsistencyLevel consistency;
    private TableReference tableRef;

    public RowGetter(
            CassandraClientPool clientPool,
            TracingQueryRunner queryRunner,
            ConsistencyLevel consistency,
            TableReference tableRef) {
        this.clientPool = clientPool;
        this.queryRunner = queryRunner;
        this.consistency = consistency;
        this.tableRef = tableRef;
    }

    public List<KeySlice> getRows(KeyRange keyRange, SlicePredicate slicePredicate) throws Exception {
        ColumnParent colFam = new ColumnParent(CassandraKeyValueServiceImpl.internalTableName(tableRef));
        InetSocketAddress host = clientPool.getRandomHostForKey(keyRange.getStart_key());
        return clientPool.runWithRetryOnHost(
                host,
                new FunctionCheckedException<Cassandra.Client, List<KeySlice>, Exception>() {
                    @Override
                    public List<KeySlice> apply(Cassandra.Client client) throws Exception {
                        try {
                            return queryRunner.run(client, tableRef,
                                    () -> client.get_range_slices(colFam, slicePredicate, keyRange, consistency));
                        } catch (UnavailableException e) {
                            throw new InsufficientConsistencyException("get_range_slices requires " + consistency
                                    + " Cassandra nodes to be up and available.", e);
                        } catch (Exception e) {
                            throw Throwables.unwrapAndThrowAtlasDbDependencyException(e);
                        }
                    }

                    @Override
                    public String toString() {
                        return "get_range_slices(" + colFam + ")";
                    }
                });
    }
}
