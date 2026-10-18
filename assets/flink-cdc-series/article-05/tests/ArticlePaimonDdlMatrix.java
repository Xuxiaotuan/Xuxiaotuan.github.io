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

import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.CreateTableEvent;
import org.apache.flink.cdc.common.event.DropColumnEvent;
import org.apache.flink.cdc.common.event.RenameColumnEvent;
import org.apache.flink.cdc.common.event.TableId;
import org.apache.flink.cdc.common.exceptions.SchemaEvolveException;
import org.apache.flink.cdc.common.schema.Column;
import org.apache.flink.cdc.common.schema.Schema;
import org.apache.flink.cdc.common.types.DataTypes;
import org.apache.flink.cdc.connectors.paimon.sink.PaimonMetadataApplier;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.FileStoreTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Direct real filesystem catalog checks; not a streaming pipeline. */
public class ArticlePaimonDdlMatrix {
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.setString("warehouse", Path.of(args[0], "warehouse-ddl-matrix").toString());
        options.setString("metastore", "filesystem");
        options.setString("cache-enabled", "false");
        TableId id = TableId.tableId("matrix", "items");
        Identifier target = new Identifier("matrix", "items");
        PaimonMetadataApplier applier = new PaimonMetadataApplier(
                options, Map.of("bucket", "1"), Collections.emptyMap());
        CreateTableEvent create = new CreateTableEvent(id,
                Schema.newBuilder().physicalColumn("id", DataTypes.INT().notNull())
                        .physicalColumn("amount", DataTypes.INT()).primaryKey("id").build());
        applier.applySchemaChange(create);
        applier.applySchemaChange(create);
        StringBuilder report = new StringBuilder("duplicate CREATE: returned successfully\n");
        AddColumnEvent add = new AddColumnEvent(id, Collections.singletonList(
                new AddColumnEvent.ColumnWithPosition(Column.physicalColumn("note", DataTypes.VARCHAR(20)))));
        applier.applySchemaChange(add);
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            long firstSchema = ((FileStoreTable) catalog.getTable(target)).schema().id();
            applier.applySchemaChange(add);
            assertThat(((FileStoreTable) catalog.getTable(target)).schema().id()).isEqualTo(firstSchema);
            report.append("duplicate identical ADD: returned, schema id unchanged\n");

            applier.applySchemaChange(new AddColumnEvent(id, Collections.singletonList(
                    new AddColumnEvent.ColumnWithPosition(Column.physicalColumn("note", DataTypes.BIGINT())))));
            assertThat(catalog.getTable(target).rowType().getTypeAt(2).toString()).contains("VARCHAR(20)");
            report.append("same-name ADD with incompatible requested BIGINT: returned, existing VARCHAR(20) retained\n");

            applier.applySchemaChange(new AddColumnEvent(id, Arrays.asList(
                    new AddColumnEvent.ColumnWithPosition(Column.physicalColumn("note", DataTypes.VARCHAR(20))),
                    new AddColumnEvent.ColumnWithPosition(Column.physicalColumn("extra", DataTypes.INT())))));
            assertThat(catalog.getTable(target).rowType().getFieldNames()).doesNotContain("extra");
            report.append("ADD list [existing note, missing extra]: returned, extra still absent\n");

            RenameColumnEvent rename = new RenameColumnEvent(id, Map.of("note", "memo"));
            applier.applySchemaChange(rename);
            assertThatThrownBy(() -> applier.applySchemaChange(rename)).isInstanceOf(SchemaEvolveException.class);
            assertThat(catalog.getTable(target).rowType().getFieldNames()).contains("memo").doesNotContain("note");
            report.append("duplicate RENAME note->memo: SchemaEvolveException\n");

            DropColumnEvent drop = new DropColumnEvent(id, Collections.singletonList("memo"));
            applier.applySchemaChange(drop);
            applier.applySchemaChange(drop);
            assertThat(catalog.getTable(target).rowType().getFieldNames()).containsExactly("id", "amount");
            report.append("duplicate DROP memo: returned, memo absent\n");
        } finally { applier.close(); }
        Files.writeString(Path.of(args[0], "ddl-matrix-results.txt"), report);
        System.out.print("ARTICLE5 DDL_MATRIX_PASSED\n" + report);
    }
}
