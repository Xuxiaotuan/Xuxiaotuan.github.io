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
package org.apache.flink.cdc.connectors.mysql.source;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.cdc.connectors.utils.ExternalResourceProxy;
import org.apache.flink.cdc.debezium.DebeziumDeserializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.util.Collector;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.apache.flink.cdc.connectors.mysql.source.utils.RecordUtils;
import io.debezium.relational.history.HistoryRecord;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL ADD/DROP COLUMN and task recovery; dynamic records, not a fixed Flink SQL row type. */
@Timeout(180)
class ArticleSchemaRecoveryITCase {
    @RegisterExtension
    final ExternalResourceProxy<MiniClusterWithClientResource> cluster = new ExternalResourceProxy<>(
            new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(1).setNumberSlotsPerTaskManager(4)
                    .setConfiguration(new Configuration()).build()));

    static final AtomicBoolean failUpdateOnce = new AtomicBoolean();
    static volatile String failureRecord;
    static final List<String> raw = new CopyOnWriteArrayList<>();
    static volatile List<String> current = Collections.emptyList();
    static volatile List<String> restored = Collections.emptyList();
    static volatile int attempt;
    static volatile boolean didRestore;
    static final Map<Long, List<String>> completed = new ConcurrentHashMap<>();
    private Connection writer;
    private JobClient job;

    @BeforeEach
    void setup() throws Exception {
        raw.clear(); completed.clear(); current = Collections.emptyList(); restored = Collections.emptyList();
        attempt = 0; didRestore = false; failUpdateOnce.set(false);
        writer = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port() + "/?useSSL=false&allowPublicKeyRetrieval=true", "root", "article4-local-only");
        sql("CREATE DATABASE IF NOT EXISTS cdc_article4");
        sql("DROP TABLE IF EXISTS cdc_article4.items");
        sql("CREATE TABLE cdc_article4.items(id INT PRIMARY KEY, amount INT NOT NULL)");
        for (int id = 101; id <= 110; id++) { sql("INSERT INTO cdc_article4.items VALUES (" + id + ",10)"); }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (job != null) { job.cancel().get(30, TimeUnit.SECONDS); }
        if (writer != null) { writer.close(); }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void restoresSchemaAtCheckpointAndReplaysLaterDdl(boolean includeSchema, boolean checkpointAfterAdd) throws Exception {
        start(source(includeSchema), 1);
        await(() -> current.size() == 10, "initial snapshot");
        checkpoint();
        sql("UPDATE cdc_article4.items SET amount=11 WHERE id=101");
        await(() -> current.contains("u:id=101,amount=11"), "binlog pilot");
        checkpoint();
        List<String> baseline = new ArrayList<>(current);
        String add = "ALTER TABLE cdc_article4.items ADD COLUMN note VARCHAR(20) AFTER id";
        String drop = "ALTER TABLE cdc_article4.items DROP COLUMN note";
        String withNote = "u:id=101,note=blue,amount=20";
        String withoutNote = "u:id=101,amount=30";
        if (!checkpointAfterAdd) {
            failureRecord = withNote;
            failUpdateOnce.set(true);
        }
        sql(add);
        sql("UPDATE cdc_article4.items SET note='blue', amount=20 WHERE id=101");
        if (checkpointAfterAdd) {
            await(() -> current.contains(withNote), "ADD COLUMN data with new position");
            checkpoint();
            baseline = new ArrayList<>(current);
            failureRecord = withoutNote;
            failUpdateOnce.set(true);
            sql(drop);
            sql("UPDATE cdc_article4.items SET amount=30 WHERE id=101");
        }
        String expectedData = checkpointAfterAdd ? withoutNote : withNote;
        await(() -> didRestore && attempt >= 1 && current.contains(expectedData), "DDL replay after task failure");
        List<String> expected = new ArrayList<>(baseline);
        if (includeSchema) { expected.add("ddl:" + (checkpointAfterAdd ? drop : add)); }
        expected.add(expectedData);
        assertThat(restored).containsExactlyElementsOf(baseline);
        assertThat(current).containsExactlyElementsOf(expected);
        assertThat(Collections.frequency(raw, expectedData)).isEqualTo(2);
        assertThat(raw.stream().filter(x -> x.startsWith("r:")).count()).isEqualTo(10);
        if (includeSchema) {
            assertThat(Collections.frequency(raw, "ddl:" + (checkpointAfterAdd ? drop : add))).isEqualTo(2);
            if (checkpointAfterAdd) { assertThat(Collections.frequency(raw, "ddl:" + add)).isEqualTo(1); }
        } else { assertThat(raw).noneMatch(x -> x.startsWith("ddl:")); }
        checkpoint();
        System.out.println("ARTICLE4 source DDL recovery: includeSchema=" + includeSchema + ", checkpointAfterAdd=" + checkpointAfterAdd
                + ", attempt=" + attempt + ", restored=" + restored + ", current=" + current + ", raw=" + raw);
    }

    private void start(MySqlSource<String> source, int parallelism) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(600_000);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(2, Time.milliseconds(100)));
        env.fromSource(source, WatermarkStrategy.noWatermarks(), "article4-source").setParallelism(parallelism)
                .addSink(new StateSink()).name("article4-state-probe").setParallelism(1);
        job = env.executeAsync("article4 recovery evidence");
    }

    private void checkpoint() throws Exception {
        int before = completed.size();
        cluster.get().getMiniCluster().triggerCheckpoint(job.getJobID()).get(60, TimeUnit.SECONDS);
        await(() -> completed.size() > before, "checkpoint completion callback");
        System.out.println("ARTICLE4 completed checkpoints=" + completed.keySet() + ", currentSize=" + current.size());
    }

    private MySqlSource<String> source(boolean includeSchema) {
        return MySqlSource.<String>builder().hostname("127.0.0.1").port(port())
                .databaseList("cdc_article4").tableList("cdc_article4.items")
                .username("root").password("article4-local-only").serverTimeZone("UTC")
                .serverId("8400-8410").splitSize(4).includeSchemaChanges(includeSchema).deserializer(new TextDeserializer()).build();
    }

    private static int port() { return Integer.parseInt(System.getProperty("article.mysql.port", "32779")); }
    private void sql(String sql) throws SQLException { try (Statement s = writer.createStatement()) { s.execute(sql); } }
    private void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(25); }
        assertThat(condition.getAsBoolean()).as(description + "; status=" + job.getJobStatus().get() + "; attempt=" + attempt + "; current=" + current).isTrue();
    }

    public static class TextDeserializer implements DebeziumDeserializationSchema<String> {
        @Override
        public void deserialize(SourceRecord record, Collector<String> out) throws Exception {
            if (RecordUtils.isSchemaChangeEvent(record)) {
                String ddl = RecordUtils.getHistoryRecord(record).document().getString(HistoryRecord.Fields.DDL_STATEMENTS);
                if (ddl != null && ddl.contains("ALTER TABLE")) { out.collect("ddl:" + ddl); }
                return;
            }
            Struct value = (Struct) record.value();
            Struct row = value.getStruct("after");
            if (row != null) {
                List<String> fields = new ArrayList<>();
                row.schema().fields().forEach(field -> fields.add(field.name() + "=" + row.get(field)));
                out.collect(value.getString("op") + ":" + String.join(",", fields));
            }
        }
        @Override
        public TypeInformation<String> getProducedType() { return TypeInformation.of(String.class); }
    }

    public static class StateSink extends RichSinkFunction<String> implements CheckpointedFunction, CheckpointListener {
        private transient ListState<String> checkpointState;
        private final List<String> values = new ArrayList<>();
        private final Map<Long, List<String>> images = new ConcurrentHashMap<>();
        @Override
        public void open(Configuration parameters) { attempt = getRuntimeContext().getAttemptNumber(); }
        @Override
        public void invoke(String value, Context context) {
            raw.add(value); values.add(value); current = new ArrayList<>(values);
            if (value.equals(failureRecord) && failUpdateOnce.compareAndSet(true, false)) {
                throw new RuntimeException("ARTICLE4 intentional failure after uncheckpointed u:101=30");
            }
        }
        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            checkpointState.update(values); images.put(context.getCheckpointId(), new ArrayList<>(values));
        }
        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            checkpointState = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("article4-records", String.class));
            values.clear();
            if (context.isRestored()) {
                for (String value : checkpointState.get()) { values.add(value); }
                restored = new ArrayList<>(values); didRestore = true;
            }
            current = new ArrayList<>(values);
        }
        @Override
        public void notifyCheckpointComplete(long checkpointId) { completed.put(checkpointId, images.get(checkpointId)); }
    }
}
