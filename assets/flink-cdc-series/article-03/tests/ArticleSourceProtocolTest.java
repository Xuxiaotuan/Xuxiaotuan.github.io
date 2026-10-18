/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.cdc.connectors.mysql.source.assigners;

import org.apache.flink.api.connector.source.SplitsAssignment;
import org.apache.flink.cdc.connectors.mysql.source.assigners.state.ChunkSplitterState;
import org.apache.flink.cdc.connectors.mysql.source.assigners.state.HybridPendingSplitsState;
import org.apache.flink.cdc.connectors.mysql.source.assigners.state.PendingSplitsStateSerializer;
import org.apache.flink.cdc.connectors.mysql.source.assigners.state.SnapshotPendingSplitsState;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffset;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSchemalessSnapshotSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSplitSerializer;
import org.apache.flink.cdc.connectors.mysql.table.StartupOptions;
import org.apache.flink.runtime.source.coordinator.SplitAssignmentTracker;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.RowType;

import io.debezium.relational.TableId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.apache.flink.cdc.connectors.mysql.testutils.MetricsUtils.getMySqlSplitEnumeratorContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Controlled protocol calls to real assigner and runtime tracker; no database or running job. */
class ArticleSourceProtocolTest {
    private static final TableId TABLE = new TableId("article3", null, "items");
    private static final RowType KEY = (RowType) DataTypes.ROW(DataTypes.FIELD("id", DataTypes.INT())).getLogicalType();

    @Test
    void parallelHandoffRequiresCheckpointTakenAfterAllReports() {
        MySqlHybridSplitAssigner a = fresh(2);
        a.snapshotState(10);
        a.onFinishedSplits(highs());
        a.notifyCheckpointComplete(10);
        assertThat(a.getNext()).isEmpty();
        a.snapshotState(11);
        a.notifyCheckpointComplete(10);
        assertThat(a.getNext()).isEmpty();
        a.notifyCheckpointComplete(11);
        assertThat(a.getNext().get().asBinlogSplit().getStartingOffset()).isEqualTo(offset(100));
        System.out.println("ARTICLE3 parallel: C10 before reports rejected; C11 after reports permits binlog at Hmin=100");
        a.close();
    }

    @Test
    void singleReaderSkipsHandoffCheckpointGate() {
        MySqlHybridSplitAssigner a = fresh(1);
        a.onFinishedSplits(highs());
        assertThat(a.getNext()).isPresent();
        System.out.println("ARTICLE3 parallelism=1: binlog assignable immediately after all reports");
        a.close();
    }

    @Test
    void serializedBoundaryCheckpointRearmsFinishOnNextCheckpoint() throws Exception {
        MySqlHybridSplitAssigner a = fresh(2);
        a.onFinishedSplits(highs());
        PendingSplitsStateSerializer serializer = new PendingSplitsStateSerializer(MySqlSplitSerializer.INSTANCE, false);
        byte[] bytes = serializer.serialize(a.snapshotState(11));
        a.notifyCheckpointComplete(11);
        assertThat(a.getNext()).isPresent();
        HybridPendingSplitsState restored = (HybridPendingSplitsState) serializer.deserialize(serializer.getVersion(), bytes);
        assertThat(restored.getSnapshotPendingSplits().getSnapshotAssignerStatus()).isEqualTo(AssignerStatus.INITIAL_ASSIGNING);
        MySqlHybridSplitAssigner b = restored(2, restored);
        b.notifyCheckpointComplete(11);
        assertThat(b.getNext()).isEmpty();
        b.snapshotState(12);
        b.notifyCheckpointComplete(12);
        assertThat(b.getNext()).isPresent();
        System.out.println("ARTICLE3 serialized C11 retained INITIAL_ASSIGNING; restored assigner waits for new C12 completion");
        a.close();
        b.close();
    }

    @Test
    void runtimeTrackerReturnsOnlyAssignmentsNotCoveredByRestoredCheckpoint() throws Exception {
        SplitAssignmentTracker<MySqlSplit> tracker = new SplitAssignmentTracker<>();
        MySqlSplit a = split("a");
        MySqlSplit b = split("b");
        MySqlSplit c = split("c");
        tracker.recordSplitAssignment(new SplitsAssignment<>(Collections.singletonMap(0, Collections.singletonList(a))));
        tracker.onCheckpoint(10);
        tracker.onCheckpointComplete(10);
        tracker.recordSplitAssignment(new SplitsAssignment<>(Collections.singletonMap(0, Collections.singletonList(b))));
        tracker.onCheckpoint(11);
        tracker.recordSplitAssignment(new SplitsAssignment<>(Collections.singletonMap(0, Collections.singletonList(c))));
        assertThat(tracker.getAndRemoveUncheckpointedAssignment(0, 10)).containsExactly(b, c);
        System.out.println("ARTICLE3 tracker restore C10: return B,C; A remains reader-checkpoint responsibility");
    }

    @Test
    void returnedSnapshotSplitInvalidatesItsOldFinishedReport() {
        MySqlHybridSplitAssigner a = fresh(2);
        a.onFinishedSplits(highs());
        a.addSplits(Collections.singletonList(split("a")));
        HybridPendingSplitsState state = (HybridPendingSplitsState) a.snapshotState(12);
        assertThat(state.getSnapshotPendingSplits().getSplitFinishedOffsets()).doesNotContainKey("a");
        assertThat(a.getNext().get().splitId()).isEqualTo("a");
        a.notifyCheckpointComplete(12);
        assertThat(a.getNext()).isEmpty();
        a.close();
    }

    private MySqlHybridSplitAssigner fresh(int parallelism) {
        LinkedHashMap<String, MySqlSchemalessSnapshotSplit> assigned = new LinkedHashMap<>();
        assigned.put("a", split("a"));
        assigned.put("b", split("b"));
        SnapshotPendingSplitsState state = new SnapshotPendingSplitsState(
                new ArrayList<>(Collections.singletonList(TABLE)), new ArrayList<>(), assigned,
                new HashMap<>(), new HashMap<>(), AssignerStatus.INITIAL_ASSIGNING,
                new ArrayList<>(), false, true, ChunkSplitterState.NO_SPLITTING_TABLE_STATE);
        return restored(parallelism, new HybridPendingSplitsState(state, false));
    }

    private MySqlHybridSplitAssigner restored(int parallelism, HybridPendingSplitsState state) {
        return new MySqlHybridSplitAssigner(new MySqlSourceConfigFactory()
                .startupOptions(StartupOptions.initial()).hostname("localhost").port(3306)
                .databaseList("article3").tableList("article3.items").username("root")
                .password("").serverTimeZone("UTC").createConfig(0), parallelism, state,
                getMySqlSplitEnumeratorContext());
    }

    private static MySqlSchemalessSnapshotSplit split(String id) {
        return new MySqlSchemalessSnapshotSplit(TABLE, id, KEY,
                id.equals("a") ? null : new Object[] {105},
                id.equals("a") ? new Object[] {105} : null, null);
    }

    private static BinlogOffset offset(long position) {
        return BinlogOffset.ofBinlogFilePosition("binlog.000001", position);
    }

    private static Map<String, BinlogOffset> highs() {
        Map<String, BinlogOffset> result = new HashMap<>();
        result.put("a", offset(100));
        result.put("b", offset(200));
        return result;
    }
}
