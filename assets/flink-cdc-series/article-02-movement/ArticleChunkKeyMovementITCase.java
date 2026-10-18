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
import org.apache.flink.table.catalog.ObjectPath;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class ArticleChunkKeyMovementITCase {
    private static final String DB = "cdc_article2_move";
    private final TableId table = new TableId(DB, null, "items");
    private Connection writer;
    private MySqlSourceConfig config;
    private Map<TableId, TableChange> schemas;
    private final RowType keyType =
            (RowType) DataTypes.ROW(DataTypes.FIELD("c", DataTypes.INT())).getLogicalType();

    @BeforeEach
    void setup() throws Exception {
        int port = Integer.parseInt(System.getProperty("article.mysql.port"));
        writer =
                DriverManager.getConnection(
                        "jdbc:mysql://127.0.0.1:"
                                + port
                                + "/?useSSL=false&allowPublicKeyRetrieval=true",
                        "root",
                        "article2-movement-local-only");
        sql("CREATE DATABASE IF NOT EXISTS " + DB);
        sql("DROP TABLE IF EXISTS " + DB + ".items");
        sql(
                "CREATE TABLE "
                        + DB
                        + ".items(id INT PRIMARY KEY, c INT NOT NULL, amount INT NOT NULL) ENGINE=InnoDB");
        sql("INSERT INTO " + DB + ".items VALUES (101,100,10),(102,110,20),(103,120,30)");
        config = config(false);
        try (MySqlConnection jdbc = DebeziumUtils.createMySqlConnection(config);
                MySqlSchema schema = new MySqlSchema(config, true)) {
            schemas =
                    Collections.singletonMap(
                            table,
                            schema.getTableSchema(
                                    new MySqlPartition(
                                            config.getMySqlConnectorConfig().getLogicalName()),
                                    jdbc,
                                    table));
            System.out.println(
                    "ARTICLE connection autocommit="
                            + jdbc.connection().getAutoCommit()
                            + " isolation="
                            + jdbc.connection().getTransactionIsolation());
        }
    }

    private MySqlSourceConfig config(boolean skip) {
        return new MySqlSourceConfigFactory()
                .hostname("127.0.0.1")
                .port(Integer.parseInt(System.getProperty("article.mysql.port")))
                .username("root")
                .password("article2-movement-local-only")
                .databaseList(DB)
                .tableList(DB + ".items")
                .serverId("8191-8194")
                .chunkKeyColumn(new ObjectPath(DB, "items"), "c")
                .splitSize(4)
                .fetchSize(2)
                .skipSnapshotBackfill(skip)
                .createConfig(0);
    }

    @AfterEach
    void close() throws Exception {
        if (writer != null) {
            writer.close();
        }
    }

    private void sql(String text) throws java.sql.SQLException {
        try (java.sql.Statement s = writer.createStatement()) {
            s.execute(text);
        }
    }

    private void update(int id, int value) throws java.sql.SQLException {
        sql("UPDATE " + DB + ".items SET amount=" + value + " WHERE id=" + id);
    }

    private MySqlSnapshotSplit split(int n, Integer start, Integer end) {
        return new MySqlSnapshotSplit(
                table,
                n,
                keyType,
                start == null ? null : new Object[] {start},
                end == null ? null : new Object[] {end},
                null,
                schemas);
    }

    private List<SourceRecord> snapshot(
            MySqlSnapshotSplit split, SnapshotPhaseHooks hooks, MySqlSourceConfig cfg)
            throws Exception {
        SnapshotSplitReader reader = new SnapshotSplitReader(cfg, 0, hooks);
        try {
            reader.submitSplit(split);
            List<SourceRecord> all = new ArrayList<>();
            Iterator<SourceRecords> batch;
            while ((batch = reader.pollSplitRecords()) != null) {
                while (batch.hasNext()) {
                    all.addAll(batch.next().getSourceRecordList());
                }
            }
            trace("SNAPSHOT " + split.splitId(), all);
            return all;
        } finally {
            reader.close();
        }
    }

    private BinlogOffset watermark(List<SourceRecord> rows, boolean high) {
        return rows.stream()
                .filter(
                        r ->
                                high
                                        ? RecordUtils.isHighWatermarkEvent(r)
                                        : RecordUtils.isLowWatermarkEvent(r))
                .map(RecordUtils::getWatermark)
                .findFirst()
                .orElseThrow();
    }

    private List<SourceRecord> binlog(
            BinlogOffset start, BinlogOffset end, List<FinishedSnapshotSplitInfo> infos)
            throws Exception {
        List<SourceRecord> all = new ArrayList<>();
        BinlogSplitReader reader = new BinlogSplitReader(config, 0);
        try {
            reader.submitSplit(
                    new MySqlBinlogSplit("binlog-split", start, end, infos, schemas, infos.size()));
            Iterator<SourceRecords> batch;
            while ((batch = reader.pollSplitRecords()) != null) {
                while (batch.hasNext()) {
                    all.addAll(batch.next().getSourceRecordList());
                }
            }
            trace(infos.isEmpty() ? "RAW_BINLOG" : "FILTERED_BINLOG", all);
            return all;
        } finally {
            reader.close();
        }
    }

    private List<String> values(List<SourceRecord> rows) {
        List<String> out = new ArrayList<>();
        for (SourceRecord r : rows) {
            if (!RecordUtils.isDataChangeRecord(r)) {
                continue;
            }
            Struct v = (Struct) r.value();
            Struct row = v.getStruct("after");
            if (row == null) {
                row = v.getStruct("before");
            }
            out.add(
                    v.getString("op")
                            + ":"
                            + row.getInt32("id")
                            + ":"
                            + row.getInt32("c")
                            + ":"
                            + row.getInt32("amount"));
        }
        return out;
    }

    private void trace(String label, List<SourceRecord> rows) {
        System.out.println("ARTICLE " + label + " values=" + values(rows));
        for (SourceRecord r : rows) {
            if (RecordUtils.isLowWatermarkEvent(r) || RecordUtils.isHighWatermarkEvent(r)) {
                System.out.println("ARTICLE WATERMARK " + r.value() + " " + r.sourceOffset());
            } else if (RecordUtils.isDataChangeRecord(r)) {
                System.out.println(
                        "ARTICLE ROW "
                                + values(Collections.singletonList(r))
                                + " before="
                                + ((Struct) r.value()).getStruct("before")
                                + " after="
                                + ((Struct) r.value()).getStruct("after")
                                + " offset="
                                + r.sourceOffset());
            }
        }
    }

    @Test
    void movingFromUnscannedBToFinishedAIsMissingFromReconstruction() throws Exception {
        runScenario(true);
    }

    @Test
    void stableChunkKeyControlReconstructsAllRows() throws Exception {
        runScenario(false);
    }

    private void runScenario(boolean move) throws Exception {
        String label = move ? "MOVEMENT" : "STABLE_CONTROL";
        System.out.println("SCENARIO=" + label);
        for (Class<?> type :
                Arrays.asList(
                        SnapshotSplitReader.class, BinlogSplitReader.class, RecordUtils.class)) {
            System.out.println(
                    "LOADED "
                            + type.getName()
                            + " "
                            + type.getProtectionDomain().getCodeSource().getLocation());
        }
        MySqlSnapshotSplit a = split(0, null, 105);
        MySqlSnapshotSplit b = split(1, 105, null);
        List<SourceRecord> ra = snapshot(a, SnapshotPhaseHooks.empty(), config);
        BinlogOffset ha = watermark(ra, true);
        assertThat(values(ra)).containsExactly("r:101:100:10");

        if (move) {
            sql("UPDATE " + DB + ".items SET c=100 WHERE id=102");
        } else {
            update(102, 21);
        }
        update(101, 11);
        update(103, 31);
        List<SourceRecord> rb = snapshot(b, SnapshotPhaseHooks.empty(), config);
        BinlogOffset lb = watermark(rb, false);
        BinlogOffset hb = watermark(rb, true);
        assertThat(lb.isAfter(ha)).isTrue();
        assertThat(hb).isEqualTo(lb);
        if (move) {
            assertThat(values(rb)).containsExactly("r:103:120:31");
        } else {
            assertThat(values(rb)).containsExactlyInAnyOrder("r:102:110:21", "r:103:120:31");
        }

        List<SourceRecord> raw = binlog(ha, hb, Collections.emptyList());
        assertThat(values(raw))
                .containsExactly(
                        move ? "u:102:100:20" : "u:102:110:21", "u:101:100:11", "u:103:120:31");
        SourceRecord update =
                raw.stream().filter(RecordUtils::isDataChangeRecord).findFirst().orElseThrow();
        BinlogOffset e = RecordUtils.getBinlogPosition(update);
        Struct envelope = (Struct) update.value();
        assertThat(((Struct) update.key()).getInt32("id")).isEqualTo(102);
        assertThat(envelope.getStruct("before").getInt32("c")).isEqualTo(110);
        assertThat(envelope.getStruct("after").getInt32("c")).isEqualTo(move ? 100 : 110);
        assertThat(e.isAfter(ha)).isTrue();
        assertThat(e.isBefore(lb)).isTrue();
        System.out.println("BOUNDARY_ASSERTIONS H_A < E_102 < L_B == H_B : PASS");
        System.out.println("BOUNDARIES H_A=" + ha + " E_102=" + e + " L_B=" + lb + " H_B=" + hb);

        List<FinishedSnapshotSplitInfo> infos =
                Arrays.asList(
                        new FinishedSnapshotSplitInfo(
                                table, a.splitId(), a.getSplitStart(), a.getSplitEnd(), ha),
                        new FinishedSnapshotSplitInfo(
                                table, b.splitId(), b.getSplitStart(), b.getSplitEnd(), hb));
        List<SourceRecord> filtered = binlog(ha, hb, infos);
        assertThat(values(filtered)).containsExactly("u:101:100:11");

        Map<Integer, String> reconstructed = new TreeMap<>();
        for (List<SourceRecord> records : Arrays.asList(ra, rb, filtered)) {
            for (SourceRecord record : records) {
                if (!RecordUtils.isDataChangeRecord(record)) {
                    continue;
                }
                Struct row = ((Struct) record.value()).getStruct("after");
                assertThat(row).isNotNull();
                reconstructed.put(
                        row.getInt32("id"), row.getInt32("c") + "," + row.getInt32("amount"));
            }
        }
        Map<Integer, String> source = new TreeMap<>();
        try (java.sql.Statement st = writer.createStatement();
                java.sql.ResultSet rs =
                        st.executeQuery("SELECT id,c,amount FROM " + DB + ".items ORDER BY id")) {
            while (rs.next()) {
                source.put(rs.getInt(1), rs.getInt(2) + "," + rs.getInt(3));
            }
        }
        assertThat(source)
                .hasSize(3)
                .containsEntry(101, "100,11")
                .containsEntry(103, "120,31")
                .containsEntry(102, move ? "100,20" : "110,21");
        Set<Integer> missing = new TreeSet<>(source.keySet());
        missing.removeAll(reconstructed.keySet());
        if (move) {
            assertThat(reconstructed)
                    .hasSize(2)
                    .containsEntry(101, "100,11")
                    .containsEntry(103, "120,31");
            assertThat(missing).containsExactly(102);
        } else {
            assertThat(reconstructed).isEqualTo(source);
            assertThat(missing).isEmpty();
        }
        System.out.println("MYSQL_FULL_ROWS=" + source);
        System.out.println("RECONSTRUCTED_READER_ROWS=" + reconstructed);
        System.out.println("MISSING_KEYS=" + missing);
        System.out.println("DATA_EQUAL=" + source.equals(reconstructed));
        System.out.println("CHARACTERIZATION_ASSERTIONS=PASS " + label);
    }
}
