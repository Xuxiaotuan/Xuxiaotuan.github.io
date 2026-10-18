/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.connectors.mysql.debezium.reader;

import org.apache.flink.cdc.connectors.mysql.debezium.DebeziumUtils;
import org.apache.flink.cdc.connectors.mysql.schema.MySqlSchema;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffset;
import org.apache.flink.cdc.connectors.mysql.source.split.FinishedSnapshotSplitInfo;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import org.apache.flink.cdc.connectors.mysql.source.split.SourceRecords;
import org.apache.flink.cdc.connectors.mysql.source.utils.RecordUtils;
import org.apache.flink.cdc.connectors.mysql.source.utils.hooks.SnapshotPhaseHooks;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.RowType;

import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.connector.mysql.MySqlPartition;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges.TableChange;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class ArticleIncrementalSnapshotITCase {
    private static final String DB = "cdc_article2";
    private final TableId table = new TableId(DB, null, "items");
    private Connection writer;
    private MySqlSourceConfig config;
    private Map<TableId, TableChange> schemas;
    private final RowType keyType = (RowType) DataTypes.ROW(DataTypes.FIELD("id", DataTypes.INT())).getLogicalType();

    @BeforeEach
    void setup() throws Exception {
        int port = Integer.parseInt(System.getProperty("article.mysql.port"));
        writer = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false&allowPublicKeyRetrieval=true", "root", "article2-local-only");
        sql("CREATE DATABASE IF NOT EXISTS " + DB);
        sql("DROP TABLE IF EXISTS " + DB + ".items");
        sql("CREATE TABLE " + DB + ".items(id INT PRIMARY KEY, amount INT NOT NULL) ENGINE=InnoDB");
        sql("INSERT INTO " + DB + ".items VALUES (101,10),(102,10),(103,10),(104,10),(105,10),(106,10),(107,10),(108,10),(109,10),(110,10)");
        config = config(false);
        try (MySqlConnection jdbc = DebeziumUtils.createMySqlConnection(config);
             MySqlSchema schema = new MySqlSchema(config, true)) {
            schemas = Collections.singletonMap(table, schema.getTableSchema(new MySqlPartition(config.getMySqlConnectorConfig().getLogicalName()), jdbc, table));
            System.out.println("ARTICLE connection autocommit=" + jdbc.connection().getAutoCommit() + " isolation=" + jdbc.connection().getTransactionIsolation());
        }
    }

    private MySqlSourceConfig config(boolean skip) {
        return new MySqlSourceConfigFactory().hostname("127.0.0.1")
                .port(Integer.parseInt(System.getProperty("article.mysql.port")))
                .username("root").password("article2-local-only")
                .databaseList(DB).tableList(DB + ".items").serverId("8191-8194")
                .splitSize(4).fetchSize(2).skipSnapshotBackfill(skip).createConfig(0);
    }
    @AfterEach
    void close() throws Exception { if (writer != null) { writer.close(); } }
    private void sql(String text) throws java.sql.SQLException {
        try (java.sql.Statement s = writer.createStatement()) { s.execute(text); }
    }
    private void update(int id, int value) throws java.sql.SQLException {
        sql("UPDATE " + DB + ".items SET amount=" + value + " WHERE id=" + id);
    }
    private MySqlSnapshotSplit split(int n, Integer start, Integer end) {
        return new MySqlSnapshotSplit(table, n, keyType, start == null ? null : new Object[]{start},
                end == null ? null : new Object[]{end}, null, schemas);
    }
    private List<SourceRecord> snapshot(MySqlSnapshotSplit split, SnapshotPhaseHooks hooks, MySqlSourceConfig cfg) throws Exception {
        SnapshotSplitReader reader = new SnapshotSplitReader(cfg, 0, hooks);
        try {
            reader.submitSplit(split);
            List<SourceRecord> all = new ArrayList<>();
            Iterator<SourceRecords> batch;
            while ((batch = reader.pollSplitRecords()) != null) {
                while (batch.hasNext()) { all.addAll(batch.next().getSourceRecordList()); }
            }
            trace("SNAPSHOT " + split.splitId(), all);
            return all;
        } finally { reader.close(); }
    }
    private BinlogOffset watermark(List<SourceRecord> rows, boolean high) {
        return rows.stream().filter(r -> high ? RecordUtils.isHighWatermarkEvent(r) : RecordUtils.isLowWatermarkEvent(r))
                .map(RecordUtils::getWatermark).findFirst().orElseThrow();
    }
    private List<SourceRecord> continuous(List<MySqlSnapshotSplit> splits, List<List<SourceRecord>> snapshots, MySqlSourceConfig cfg) throws Exception {
        List<FinishedSnapshotSplitInfo> infos = new ArrayList<>();
        BinlogOffset start = null;
        for (int i = 0; i < splits.size(); i++) {
            MySqlSnapshotSplit s = splits.get(i);
            BinlogOffset h = watermark(snapshots.get(i), true);
            if (start == null || h.isBefore(start)) { start = h; }
            infos.add(new FinishedSnapshotSplitInfo(table, s.splitId(), s.getSplitStart(), s.getSplitEnd(), h));
        }
        BinlogOffset end;
        try (MySqlConnection jdbc = DebeziumUtils.createMySqlConnection(cfg)) { end = DebeziumUtils.currentBinlogOffset(jdbc); }
        List<SourceRecord> all = new ArrayList<>();
        BinlogSplitReader reader = new BinlogSplitReader(cfg, 0);
        try {
            reader.submitSplit(new MySqlBinlogSplit("binlog-split", start, end, infos, schemas, infos.size()));
            Iterator<SourceRecords> batch;
            while ((batch = reader.pollSplitRecords()) != null) {
                while (batch.hasNext()) { all.addAll(batch.next().getSourceRecordList()); }
            }
        } finally { reader.close(); }
        trace("CONTINUOUS start=" + start + " end=" + end, all);
        return all;
    }
    private List<String> values(List<SourceRecord> rows) {
        List<String> out = new ArrayList<>();
        for (SourceRecord r : rows) {
            if (!RecordUtils.isDataChangeRecord(r)) { continue; }
            Struct v = (Struct) r.value();
            Struct row = v.getStruct("after");
            if (row == null) { row = v.getStruct("before"); }
            out.add(v.getString("op") + ":" + row.getInt32("id") + "=" + row.getInt32("amount"));
        }
        return out;
    }
    private void trace(String label, List<SourceRecord> rows) {
        System.out.println("ARTICLE " + label + " values=" + values(rows));
        for (SourceRecord r : rows) {
            if (RecordUtils.isLowWatermarkEvent(r) || RecordUtils.isHighWatermarkEvent(r)) {
                System.out.println("ARTICLE WATERMARK " + r.value() + " " + r.sourceOffset());
            } else if (RecordUtils.isDataChangeRecord(r)) {
                System.out.println("ARTICLE ROW " + values(Collections.singletonList(r)) + " " + r.sourceOffset());
            }
        }
    }

    @Test
    void windowMergesCommittedChangesButExcludesPostHigh() throws Exception {
        MySqlSnapshotSplit s = split(0, null, null);
        SnapshotPhaseHooks h = new SnapshotPhaseHooks();
        h.setPostLowWatermarkAction((c, x) -> sql("INSERT INTO " + DB + ".items VALUES (111,11)"));
        h.setPreHighWatermarkAction((c, x) -> {
            update(101, 20); update(101, 30);
            sql("DELETE FROM " + DB + ".items WHERE id=102");
            sql("INSERT INTO " + DB + ".items VALUES (112,12),(113,13)");
            sql("DELETE FROM " + DB + ".items WHERE id=113");
        });
        h.setPostHighWatermarkAction((c, x) -> update(101, 40));
        List<SourceRecord> rows = snapshot(s, h, config);
        assertThat(values(rows)).hasSize(11).contains("r:101=30", "r:111=11", "r:112=12");
        assertThat(values(rows)).noneMatch(v -> v.startsWith("r:102=") || v.startsWith("r:113="));
        assertThat(values(continuous(Collections.singletonList(s), Collections.singletonList(rows), config)))
                .containsExactly("u:101=40");
    }

    @Test
    void multipleChunksUseIndividualHighWatermarks() throws Exception {
        MySqlSnapshotSplit a = split(0, null, 105), b = split(1, 105, null);
        SnapshotPhaseHooks ha = new SnapshotPhaseHooks(), hb = new SnapshotPhaseHooks();
        ha.setPreHighWatermarkAction((c, x) -> update(103, 20));
        ha.setPostHighWatermarkAction((c, x) -> { update(103, 30); update(106, 40); });
        hb.setPreHighWatermarkAction((c, x) -> { update(106, 50); update(105, 55); });
        List<SourceRecord> ra = snapshot(a, ha, config), rb = snapshot(b, hb, config);
        assertThat(values(ra)).hasSize(4).contains("r:103=20").noneMatch(v -> v.startsWith("r:105="));
        assertThat(values(rb)).hasSize(6).contains("r:105=55", "r:106=50");
        assertThat(watermark(rb, true).isAfter(watermark(ra, true))).isTrue();
        assertThat(values(continuous(Arrays.asList(a,b), Arrays.asList(ra,rb), config)))
                .containsExactly("u:103=30");
        update(103, 60); update(106, 70); update(110, 99);
        assertThat(values(continuous(Arrays.asList(a,b), Arrays.asList(ra,rb), config)))
                .containsExactly("u:103=30", "u:103=60", "u:106=70", "u:110=99");
    }

    @Test
    void skippingBackfillReplaysChangeAlreadySeenBySelect() throws Exception {
        MySqlSourceConfig skip = config(true);
        MySqlSnapshotSplit s = split(0, null, null);
        SnapshotPhaseHooks h = new SnapshotPhaseHooks();
        h.setPostLowWatermarkAction((c, x) -> update(101, 20));
        h.setPreHighWatermarkAction((c, x) -> update(101, 30));
        List<SourceRecord> rows = snapshot(s, h, skip);
        assertThat(watermark(rows, true)).isEqualTo(watermark(rows, false));
        assertThat(values(rows)).contains("r:101=20");
        assertThat(values(continuous(Collections.singletonList(s), Collections.singletonList(rows), skip)))
                .containsExactly("u:101=20", "u:101=30");
    }

    @Test
    void unchangedWindowHasEqualWatermarksAndCompletes() throws Exception {
        List<SourceRecord> rows = snapshot(split(0, null, null), SnapshotPhaseHooks.empty(), config);
        assertThat(watermark(rows, true)).isEqualTo(watermark(rows, false));
        assertThat(values(rows)).hasSize(10).allMatch(v -> v.startsWith("r:") && v.endsWith("=10"));
    }

    @Test
    void transactionStartedBeforeLowCommitsBeforeHigh() throws Exception {
        writer.setAutoCommit(false);
        update(101, 77);
        SnapshotPhaseHooks h = new SnapshotPhaseHooks();
        h.setPreHighWatermarkAction((c, x) -> writer.commit());
        List<SourceRecord> rows = snapshot(split(0, null, null), h, config);
        assertThat(values(rows)).contains("r:101=77");
        assertThat(watermark(rows, true).isAfter(watermark(rows, false))).isTrue();
    }
}
