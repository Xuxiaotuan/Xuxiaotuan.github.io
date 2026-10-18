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
import org.apache.flink.cdc.connectors.mysql.source.utils.hooks.SnapshotPhaseHooks;
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
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual MiniCluster task restart with external MySQL; the test sink is checkpointed operator state. */
@Timeout(180)
class ArticleSourceRecoveryITCase {
    @RegisterExtension
    final ExternalResourceProxy<MiniClusterWithClientResource> cluster = new ExternalResourceProxy<>(
            new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(1).setNumberSlotsPerTaskManager(4)
                    .setConfiguration(new Configuration()).build()));

    static volatile CountDownLatch snapshotEntered;
    static volatile CountDownLatch snapshotRelease;
    static final AtomicBoolean failSnapshotOnce = new AtomicBoolean();
    static final AtomicBoolean failUpdateOnce = new AtomicBoolean();
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
        attempt = 0; didRestore = false; failUpdateOnce.set(false); failSnapshotOnce.set(false);
        snapshotEntered = new CountDownLatch(1); snapshotRelease = new CountDownLatch(1);
        writer = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port() + "/?useSSL=false&allowPublicKeyRetrieval=true", "root", "article3-local-only");
        sql("CREATE DATABASE IF NOT EXISTS cdc_article3");
        sql("DROP TABLE IF EXISTS cdc_article3.items");
        sql("CREATE TABLE cdc_article3.items(id INT PRIMARY KEY, amount INT NOT NULL)");
        for (int id = 101; id <= 110; id++) { sql("INSERT INTO cdc_article3.items VALUES (" + id + ",10)"); }
    }

    @AfterEach
    void cleanup() throws Exception {
        snapshotRelease.countDown();
        if (job != null) { job.cancel().get(30, TimeUnit.SECONDS); }
        if (writer != null) { writer.close(); }
    }

    @Test
    void checkpointDuringUnfinishedSnapshotThenTaskFailureRereadsRange() throws Exception {
        failSnapshotOnce.set(true);
        MySqlSource<String> source = source();
        SnapshotPhaseHooks hooks = new SnapshotPhaseHooks();
        hooks.setPreHighWatermarkAction((connection, split) -> {
            if (failSnapshotOnce.compareAndSet(true, false)) {
                snapshotEntered.countDown();
                try {
                    if (!snapshotRelease.await(60, TimeUnit.SECONDS)) { throw new SQLException("snapshot test gate timeout"); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                throw new SQLException("ARTICLE3 intentional failure before HIGH");
            }
        });
        source.setSnapshotHooks(hooks);
        start(source, 1);
        assertThat(snapshotEntered.await(60, TimeUnit.SECONDS)).isTrue();
        checkpoint();
        assertThat(current).isEmpty();
        snapshotRelease.countDown();
        await(() -> didRestore && attempt >= 1 && current.size() == 10, "snapshot restart and ten rows");
        assertThat(restored).isEmpty();
        assertThat(current).hasSize(10).doesNotHaveDuplicates();
        assertThat(current).allMatch(x -> x.startsWith("r:"));
        checkpoint();
        System.out.println("ARTICLE3 actual snapshot recovery: attempt=" + attempt + ", restored=" + restored + ", current=" + current + ", raw=" + raw);
    }

    @Test
    void binlogTaskFailureReplaysUncheckpointedTransactionAndRollsBackSinkState() throws Exception {
        start(source(), 2);
        await(() -> current.size() == 10, "parallel snapshot rows");
        checkpoint();
        sql("UPDATE cdc_article3.items SET amount=11 WHERE id=101");
        // A completion report may race the manually triggered checkpoint. A later checkpoint
        // deterministically covers all reports and permits the parallel handoff.
        checkpoint();
        await(() -> current.contains("u:101=11"), "binlog handoff pilot");
        checkpoint();
        List<String> baseline = new ArrayList<>(current);
        assertThat(baseline).hasSize(11);
        failUpdateOnce.set(true);
        writer.setAutoCommit(false);
        sql("UPDATE cdc_article3.items SET amount=20 WHERE id=101");
        sql("UPDATE cdc_article3.items SET amount=30 WHERE id=101");
        sql("UPDATE cdc_article3.items SET amount=40 WHERE id=102");
        writer.commit(); writer.setAutoCommit(true);
        await(() -> didRestore && attempt >= 1 && current.size() == 14, "binlog restart and replay");
        List<String> expected = new ArrayList<>(baseline);
        expected.add("u:101=20"); expected.add("u:101=30"); expected.add("u:102=40");
        assertThat(restored).containsExactlyElementsOf(baseline);
        assertThat(current).containsExactlyElementsOf(expected);
        assertThat(Collections.frequency(raw, "u:101=20")).isEqualTo(2);
        assertThat(Collections.frequency(raw, "u:101=30")).isEqualTo(2);
        assertThat(raw.stream().filter(x -> x.startsWith("r:")).count()).isEqualTo(10);
        checkpoint();
        System.out.println("ARTICLE3 actual binlog recovery: attempt=" + attempt + ", restored=" + restored + ", current=" + current + ", raw=" + raw);
    }

    private void start(MySqlSource<String> source, int parallelism) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(600_000);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(2, Time.milliseconds(100)));
        env.fromSource(source, WatermarkStrategy.noWatermarks(), "article3-source").setParallelism(parallelism)
                .addSink(new StateSink()).name("article3-state-probe").setParallelism(1);
        job = env.executeAsync("article3 recovery evidence");
    }

    private void checkpoint() throws Exception {
        int before = completed.size();
        cluster.get().getMiniCluster().triggerCheckpoint(job.getJobID()).get(60, TimeUnit.SECONDS);
        await(() -> completed.size() > before, "checkpoint completion callback");
        System.out.println("ARTICLE3 completed checkpoints=" + completed.keySet() + ", currentSize=" + current.size());
    }

    private MySqlSource<String> source() {
        return MySqlSource.<String>builder().hostname("127.0.0.1").port(port())
                .databaseList("cdc_article3").tableList("cdc_article3.items")
                .username("root").password("article3-local-only").serverTimeZone("UTC")
                .serverId("8300-8310").splitSize(4).deserializer(new TextDeserializer()).build();
    }

    private static int port() { return Integer.parseInt(System.getProperty("article.mysql.port", "32778")); }
    private void sql(String sql) throws SQLException { try (Statement s = writer.createStatement()) { s.execute(sql); } }
    private void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(25); }
        assertThat(condition.getAsBoolean()).as(description + "; status=" + job.getJobStatus().get() + "; attempt=" + attempt + "; current=" + current).isTrue();
    }

    public static class TextDeserializer implements DebeziumDeserializationSchema<String> {
        @Override
        public void deserialize(SourceRecord record, Collector<String> out) {
            Struct value = (Struct) record.value();
            Struct row = value.getStruct("after");
            if (row != null) { out.collect(value.getString("op") + ":" + row.getInt32("id") + "=" + row.getInt32("amount")); }
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
            if (value.equals("u:101=30") && failUpdateOnce.compareAndSet(true, false)) {
                throw new RuntimeException("ARTICLE3 intentional failure after uncheckpointed u:101=30");
            }
        }
        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            checkpointState.update(values); images.put(context.getCheckpointId(), new ArrayList<>(values));
        }
        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            checkpointState = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("article3-records", String.class));
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
