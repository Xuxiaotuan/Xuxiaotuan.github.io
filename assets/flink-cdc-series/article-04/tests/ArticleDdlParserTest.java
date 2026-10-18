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
package org.apache.flink.cdc.connectors.mysql.source.parser;

import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.AlterColumnTypeEvent;
import org.apache.flink.cdc.common.event.DropColumnEvent;
import org.apache.flink.cdc.common.event.RenameColumnEvent;
import org.apache.flink.cdc.common.event.SchemaChangeEvent;
import org.apache.flink.cdc.common.types.DataTypes;
import io.debezium.relational.Tables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Direct parser probes; no MySQL server and no downstream DDL execution. */
class ArticleDdlParserTest {
    private CustomMySqlAntlrDdlParser parser;
    private Tables tables;
    @BeforeEach
    void setup() {
        parser = new CustomMySqlAntlrDdlParser(false, true, false);
        parser.setCurrentDatabase("article4");
        tables = new Tables();
        parse("CREATE TABLE items(id INT PRIMARY KEY, amount INT)");
    }
    @Test
    void addAfterRetainsPosition() {
        List<SchemaChangeEvent> events = parse("ALTER TABLE items ADD COLUMN note VARCHAR(20) AFTER id");
        assertThat(events).hasSize(1);
        AddColumnEvent.ColumnWithPosition c = ((AddColumnEvent) events.get(0)).getAddedColumns().get(0);
        assertThat(c.getAddColumn().getName()).isEqualTo("note");
        assertThat(c.getPosition()).isEqualTo(AddColumnEvent.ColumnPosition.AFTER);
        assertThat(c.getExistedColumnName()).isEqualTo("id");
        System.out.println("ARTICLE4 parser ADD: " + events);
    }
    @Test
    void changeColumnSplitsTypeAndRenameInOrder() {
        List<SchemaChangeEvent> events = parse("ALTER TABLE items CHANGE COLUMN amount amount_new BIGINT");
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AlterColumnTypeEvent.class);
        assertThat(((AlterColumnTypeEvent) events.get(0)).getTypeMapping()).containsEntry("amount", DataTypes.BIGINT());
        assertThat(((RenameColumnEvent) events.get(1)).getNameMapping()).containsEntry("amount", "amount_new");
        System.out.println("ARTICLE4 parser CHANGE: " + events);
    }
    @Test
    void dropColumnProducesStructuredEvent() {
        List<SchemaChangeEvent> events = parse("ALTER TABLE items DROP COLUMN amount");
        assertThat(events).hasSize(1);
        assertThat(((DropColumnEvent) events.get(0)).getDroppedColumnNames()).containsExactly("amount");
        System.out.println("ARTICLE4 parser DROP: " + events);
    }
    @Test
    void indexDdlDoesNotBecomeColumnEvolution() {
        List<SchemaChangeEvent> events = parse("ALTER TABLE items ADD INDEX idx_amount(amount)");
        assertThat(events).isEmpty();
        System.out.println("ARTICLE4 parser ADD INDEX: no SchemaChangeEvent");
    }
    private List<SchemaChangeEvent> parse(String sql) {
        parser.parse(sql, tables);
        return parser.getAndClearParsedEvents();
    }
}
