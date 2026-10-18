/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package article5;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.table.source.TableScan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Read durable Paimon changelogs and reconcile all final rows with the real MySQL source. */
public class ArticlePaimonReadback {
    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(args[0]);
        Options options = new Options();
        options.setString("warehouse", evidence.resolve("warehouse-checkpoint").toString());
        options.setString("metastore", "filesystem");
        options.setString("cache-enabled", "false");
        ArticlePaimonExperiment.options = options;
        StringBuilder report = new StringBuilder();
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            FileStoreTable table = (FileStoreTable) catalog.getTable(new Identifier("cdc_article5", "items"));
            ReadBuilder builder = table.newReadBuilder();
            List<Integer> visibleKeys = new ArrayList<>();
            try (RecordReader<InternalRow> reader = builder.newRead().createReader(builder.newScan().plan())) {
                RecordReader.RecordIterator<InternalRow> batch;
                while ((batch = reader.readBatch()) != null) {
                    InternalRow row;
                    while ((row = batch.next()) != null) { visibleKeys.add(row.getInt(0)); }
                    batch.releaseBatch();
                }
            }
            assertThat(visibleKeys).hasSize(10).doesNotHaveDuplicates();
            report.append("direct full scan: 10 rows, no duplicate visible primary keys\n");
            StreamTableScan scan = builder.newStreamScan();
            scan.restore(3L);
            List<List<String>> snapshots = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                TableScan.Plan plan = scan.plan();
                List<String> rows = new ArrayList<>();
                try (RecordReader<InternalRow> reader = builder.newRead().createReader(plan)) {
                    RecordReader.RecordIterator<InternalRow> batch;
                    while ((batch = reader.readBatch()) != null) {
                        InternalRow row;
                        while ((row = batch.next()) != null) {
                            rows.add(row.getRowKind() + ":" + row.getInt(0) + ":" + row.getInt(1));
                        }
                        batch.releaseBatch();
                    }
                }
                Collections.sort(rows);
                snapshots.add(rows);
                report.append("snapshot ").append(i + 3).append(" changelog=").append(rows).append("\n");
            }
            assertThat(snapshots.get(0)).hasSize(4);
            assertThat(snapshots.get(1)).containsExactlyElementsOf(snapshots.get(0));
            report.append("duplicate complete changelog batch across snapshots 3 and 4: CONFIRMED\n");
        }
        Map<Integer, String> mysqlRows = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:" + args[1] + "/?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "article5-local-only");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id, amount FROM cdc_article5.items ORDER BY id")) {
            while (result.next()) {
                int id = result.getInt(1);
                mysqlRows.put(id, id + "," + result.getInt(2));
            }
        }
        Map<Integer, String> targetRows = ArticlePaimonExperiment.rows();
        assertThat(targetRows).isEqualTo(mysqlRows);
        report.append("MySQL=").append(mysqlRows).append("\nPaimon=").append(targetRows)
                .append("\nfull primary-key row reconciliation: PASS\n");
        Files.writeString(evidence.resolve("durable-readback-results.txt"), report);
        System.out.print("ARTICLE5 DURABLE_READBACK_PASSED\n" + report);
    }
}
