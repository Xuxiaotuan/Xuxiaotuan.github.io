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
package org.apache.flink.cdc.connectors.mysql.source.reader;

import org.apache.flink.cdc.connectors.mysql.MySqlTestUtils;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSource;
import org.apache.flink.cdc.connectors.mysql.source.events.FinishedSnapshotSplitsAckEvent;
import org.apache.flink.cdc.connectors.mysql.source.events.FinishedSnapshotSplitsReportEvent;
import org.apache.flink.cdc.connectors.mysql.source.events.FinishedSnapshotSplitsRequestEvent;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffset;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSplitSerializer;
import org.apache.flink.connector.testutils.source.reader.TestingReaderContext;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.RowType;

import io.debezium.relational.TableId;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Reader state round trip and event calls, not a distributed message-loss experiment. */
class ArticleReaderAckTest {
    @Test
    void lostAckKeepsFinishedSplitInCheckpointAndRestoreReportsWithoutRescanning() throws Exception {
        MySqlSource<SourceRecord> source = MySqlSource.<SourceRecord>builder()
                .hostname("127.0.0.1").port(9).databaseList("article3").tableList("article3.items")
                .username("unused").password("unused").serverTimeZone("UTC")
                .deserializer(new MySqlTestUtils.ForwardDeserializeSchema()).build();
        TestingReaderContext firstContext = new TestingReaderContext();
        MySqlSourceReader<SourceRecord> first = (MySqlSourceReader<SourceRecord>) source.createReader(firstContext);
        TestingReaderContext restoredContext = new TestingReaderContext();
        MySqlSourceReader<SourceRecord> second = (MySqlSourceReader<SourceRecord>) source.createReader(restoredContext);
        try {
            MySqlSnapshotSplit finished = new MySqlSnapshotSplit(new TableId("article3", null, "items"), "items:0",
                    (RowType) DataTypes.ROW(DataTypes.FIELD("id", DataTypes.INT())).getLogicalType(),
                    null, null, BinlogOffset.ofBinlogFilePosition("binlog.000001", 100), Collections.emptyMap());
            first.addSplits(Collections.singletonList(finished));
            assertThat(firstContext.getSentEvents()).anyMatch(e -> e instanceof FinishedSnapshotSplitsReportEvent);
            MySqlSplit state = first.snapshotState(1).get(0);
            MySqlSplitSerializer serializer = MySqlSplitSerializer.INSTANCE;
            MySqlSplit roundTrip = serializer.deserialize(serializer.getVersion(), serializer.serialize(state));
            second.addSplits(Collections.singletonList(roundTrip));
            assertThat(second.getFinishedUnackedSplits()).containsKey("items:0");
            assertThat(second.getNumberOfCurrentlyAssignedSplits()).isZero();
            int events = restoredContext.getSentEvents().size();
            second.handleSourceEvents(new FinishedSnapshotSplitsRequestEvent());
            assertThat(restoredContext.getSentEvents()).hasSize(events + 1);
            assertThat(second.snapshotState(2)).hasSize(1);
            second.handleSourceEvents(new FinishedSnapshotSplitsAckEvent(Collections.singletonList("items:0")));
            assertThat(second.snapshotState(3)).isEmpty();
            System.out.println("ARTICLE3 ACK protocol: finished split serialized, restored without active scan, re-reported, removed only after ACK");
        } finally { first.close(); second.close(); }
    }
}
