/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.cdc.connectors.mysql.source.utils;

import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;

import io.debezium.data.Envelope;
import io.debezium.util.SchemaNameAdjuster;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleSnapshotMergeTest {
    private final Schema rowSchema = SchemaBuilder.struct().name("article.orders.Value")
            .field("id", Schema.INT32_SCHEMA).field("amount", Schema.INT32_SCHEMA).optional().build();
    private final Schema keySchema = SchemaBuilder.struct().name("article.orders.Key")
            .field("id", Schema.INT32_SCHEMA).build();
    private final Schema sourceSchema = SchemaBuilder.struct().name("article.Source")
            .field("ts_ms", Schema.INT64_SCHEMA).build();
    private final Struct source = new Struct(sourceSchema).put("ts_ms", 1000L);
    private final Struct key = new Struct(keySchema).put("id", 42);
    private final Envelope envelope = Envelope.defineSchema().withName("article.orders.Envelope")
            .withRecord(rowSchema).withSource(sourceSchema).build();
    private final RowType splitType = RowType.of(new LogicalType[] {new IntType()}, new String[] {"id"});

    private Struct row(int amount) {
        return new Struct(rowSchema).put("id", 42).put("amount", amount);
    }

    private SourceRecord record(Struct value) {
        return new SourceRecord(Collections.emptyMap(), Collections.emptyMap(), "article.orders",
                keySchema, key, envelope.schema(), value);
    }

    private Map<Struct, List<SourceRecord>> initialBuffer() {
        Map<Struct, List<SourceRecord>> buffer = new HashMap<>();
        buffer.put(key, Collections.singletonList(record(envelope.read(row(100), source, Instant.EPOCH))));
        return buffer;
    }

    private void merge(Map<Struct, List<SourceRecord>> buffer, Struct value) {
        RecordUtils.upsertBinlog(buffer, record(value), splitType, SchemaNameAdjuster.create(), null, null);
    }

    @Test
    void successiveUpdatesCollapseToOneReadRecord() {
        Map<Struct, List<SourceRecord>> buffer = initialBuffer();
        merge(buffer, envelope.update(row(100), row(200), source, Instant.EPOCH));
        merge(buffer, envelope.update(row(200), row(300), source, Instant.EPOCH));
        assertThat(buffer).hasSize(1);
        assertThat(buffer.get(key)).hasSize(1);
        SourceRecord result = buffer.get(key).get(0);
        assertThat(Envelope.operationFor(result)).isEqualTo(Envelope.Operation.READ);
        assertThat(((Struct) result.value()).getStruct("after").getInt32("amount")).isEqualTo(300);
    }

    @Test
    void deleteRemovesTheBufferedRow() {
        Map<Struct, List<SourceRecord>> buffer = initialBuffer();
        merge(buffer, envelope.update(row(100), row(200), source, Instant.EPOCH));
        merge(buffer, envelope.delete(row(200), source, Instant.EPOCH));
        assertThat(buffer).isEmpty();
    }
}
