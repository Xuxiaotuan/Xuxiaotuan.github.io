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

import org.apache.flink.cdc.common.configuration.Configuration;
import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.DataChangeEvent;
import org.apache.flink.cdc.common.event.Event;
import org.apache.flink.cdc.common.event.SchemaChangeEvent;
import org.apache.flink.cdc.common.event.SchemaChangeEventType;
import org.apache.flink.cdc.common.pipeline.SchemaChangeBehavior;
import org.apache.flink.cdc.common.sink.DataSink;
import org.apache.flink.cdc.common.sink.EventSinkProvider;
import org.apache.flink.cdc.common.sink.FlinkSinkProvider;
import org.apache.flink.cdc.common.sink.MetadataApplier;
import org.apache.flink.cdc.common.source.DataSource;
import org.apache.flink.cdc.composer.definition.SinkDef;
import org.apache.flink.cdc.composer.definition.SourceDef;
import org.apache.flink.cdc.composer.flink.coordination.OperatorIDGenerator;
import org.apache.flink.cdc.composer.flink.translator.DataSinkTranslator;
import org.apache.flink.cdc.composer.flink.translator.DataSourceTranslator;
import org.apache.flink.cdc.composer.flink.translator.OperatorUidGenerator;
import org.apache.flink.cdc.composer.flink.translator.PartitioningTranslator;
import org.apache.flink.cdc.composer.flink.translator.SchemaOperatorTranslator;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonEventSink;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonRecordEventSerializer;
import org.apache.flink.cdc.runtime.typeutils.EventTypeInfo;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.connector.sink2.CommittableMessage;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.flink.sink.MultiTableCommittable;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL and filesystem Paimon, with observation-only and fault-injection probes. */
public class ArticlePaimonExperiment {
    static final String UID = "article5-schema";
    static final List<String> RAW = new CopyOnWriteArrayList<>();
    static final List<Long> COMPLETED = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAIL_DDL = new AtomicBoolean();
    static final AtomicBoolean HOLD_CHECKPOINT = new AtomicBoolean();
    static final AtomicInteger FLAG_APPLICATIONS = new AtomicInteger();
    static volatile CountDownLatch reached;
    static volatile CountDownLatch release;
    static volatile int attempt;
    static volatile long heldCheckpoint;
    static volatile int restoredRecords;
    static String warehouse;
    static Options options;
    static Path evidence;
    static Connection mysql;
    static JobClient job;
    static MiniClusterWithClientResource cluster;

    public static void main(String[] args) throws Exception {
        evidence = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "article5-local-only")) {
            mysql = connection;
            cluster = new MiniClusterWithClientResource(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(1).setNumberSlotsPerTaskManager(8)
                            .setConfiguration(new org.apache.flink.configuration.Configuration()).build());
            cluster.before();
            try {
                runSchemaScenario(port);
                runCheckpointScenario(port);
                System.out.println("ARTICLE5 ALL_END_TO_END_SCENARIOS_PASSED");
            } finally {
                if (release != null) { release.countDown(); }
                try {
                    if (job != null) { job.cancel().get(30, TimeUnit.SECONDS); }
                } catch (Exception cleanupFailure) {
                    System.err.println("ARTICLE5 cleanup: " + cleanupFailure);
                } finally {
                    cluster.after();
                }
            }
        }
    }

    static void initialize(String name, int port, boolean probeCommit) throws Exception {
        RAW.clear();
        COMPLETED.clear();
        FLAG_APPLICATIONS.set(0);
        FAIL_DDL.set(false);
        HOLD_CHECKPOINT.set(false);
        reached = new CountDownLatch(1);
        release = new CountDownLatch(1);
        attempt = 0;
        heldCheckpoint = -1;
        restoredRecords = -1;
        warehouse = evidence.resolve("warehouse-" + name).toString();
        Files.createDirectories(Path.of(warehouse));
        options = new Options();
        options.setString("warehouse", warehouse);
        options.setString("metastore", "filesystem");
        options.setString("cache-enabled", "false");
        sql("CREATE DATABASE IF NOT EXISTS cdc_article5");
        sql("DROP TABLE IF EXISTS cdc_article5.items");
        sql("CREATE TABLE cdc_article5.items(id INT PRIMARY KEY, amount INT NOT NULL)");
        for (int id = 101; id <= 110; id++) {
            sql("INSERT INTO cdc_article5.items VALUES (" + id + ",10)");
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(600_000);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(2, Time.milliseconds(200)));
        Configuration pipeline = Configuration.fromMap(Map.of(
                "parallelism", "1", "schema.change.behavior", "evolve", "local-time-zone", "UTC"));
        Configuration sourceConfig = Configuration.fromMap(Map.of(
                "hostname", "127.0.0.1", "port", String.valueOf(port),
                "username", "root", "password", "article5-local-only",
                "tables", "cdc_article5.items", "server-id", "8500-8510",
                "scan.incremental.snapshot.chunk.size", "4", "server-time-zone", "UTC"));
        SourceDef sourceDef = new SourceDef("mysql", "Real MySQL", sourceConfig);
        Configuration sinkConfig = Configuration.fromMap(Map.of(
                "catalog.properties.warehouse", warehouse,
                "catalog.properties.metastore", "filesystem",
                "catalog.properties.cache-enabled", "false",
                "table.properties.bucket", "1",
                "table.properties.changelog-producer", "input",
                "table.properties.write-buffer-size", "8mb"));
        SinkDef sinkDef = new SinkDef("paimon", "Real filesystem Paimon", sinkConfig);
        DataSourceTranslator sourceTranslator = new DataSourceTranslator();
        DataSinkTranslator sinkTranslator = new DataSinkTranslator();
        DataSource source = sourceTranslator.createDataSource(sourceDef, pipeline, env);
        DataSink baseSink = sinkTranslator.createDataSink(sinkDef, pipeline, env);
        MetadataApplier applier = new DdlFaultApplier(baseSink.getMetadataApplier());
        OperatorUidGenerator uids = new OperatorUidGenerator("article5");
        DataStream<Event> stream = sourceTranslator.translate(sourceDef, source, env, 1, uids)
                .map(new SourceProbe()).returns(new EventTypeInfo()).name("Article source observation");
        stream = new SchemaOperatorTranslator(SchemaChangeBehavior.EVOLVE, UID,
                Duration.ofSeconds(60), "UTC")
                .translateRegular(stream, 1, applier, Collections.emptyList());
        OperatorIDGenerator ids = new OperatorIDGenerator(UID);
        stream = new PartitioningTranslator().translateRegular(stream, 1, 1,
                ids.generate(), baseSink.getDataChangeEventHashFunctionProvider(1), uids);
        DataSink selectedSink = probeCommit
                ? new CommitProbeSink(baseSink, options) : new UidAlignedSink(baseSink, options);
        sinkTranslator.translate(sinkDef, stream, selectedSink, ids.generate(), uids);
        Files.writeString(evidence.resolve(name + "-execution-plan.json"), env.getExecutionPlan());
        job = env.executeAsync("article5-" + name);
        await(() -> RAW.stream().filter(x -> x.startsWith("data:")).count() >= 10, "initial read");
        checkpoint();
        await(() -> rows().size() == 10, "committed initial rows");
        sql("UPDATE cdc_article5.items SET amount=11 WHERE id=101");
        await(() -> RAW.contains("data:101:11"), "binlog pilot");
        checkpoint();
        assertThat(rows().get(101)).isEqualTo("101,11");
        record(name + "-baseline");
    }

    static void runSchemaScenario(int port) throws Exception {
        initialize("schema", port, false);
        long snapshotBefore = snapshotId();
        sql("ALTER TABLE cdc_article5.items ADD COLUMN note VARCHAR(20) AFTER id");
        sql("UPDATE cdc_article5.items SET note='blue',amount=20 WHERE id=101");
        await(() -> RAW.contains("data:101:20") && columns().contains("note"), "schema propagated");
        assertThat(snapshotId()).isEqualTo(snapshotBefore);
        assertThat(rows().get(101)).isEqualTo("101,null,11");
        record("schema-ddl-visible-data-uncommitted");
        checkpoint();
        assertThat(rows().get(101)).isEqualTo("101,blue,20");
        FAIL_DDL.set(true);
        sql("ALTER TABLE cdc_article5.items ADD COLUMN flag VARCHAR(20) AFTER note");
        sql("UPDATE cdc_article5.items SET flag='yes',amount=30 WHERE id=101");
        await(() -> attempt >= 1 && FLAG_APPLICATIONS.get() == 2
                && Collections.frequency(RAW, "data:101:30") >= 1, "DDL application replay");
        checkpoint();
        assertThat(rows().get(101)).isEqualTo("101,blue,yes,30");
        assertThat(FLAG_APPLICATIONS.get()).isEqualTo(2);
        record("schema-recovered-after-real-ddl");
        sql("INSERT INTO cdc_article5.items VALUES (201,'green','ok',40)");
        sql("DELETE FROM cdc_article5.items WHERE id=102");
        await(() -> RAW.contains("data:201:40") && RAW.contains("delete:102"), "insert and delete");
        checkpoint();
        assertThat(rows().size()).isEqualTo(10);
        assertThat(rows()).doesNotContainKey(102).containsEntry(201, "201,green,ok,40");
        record("schema-final");
        stop();
    }

    static void runCheckpointScenario(int port) throws Exception {
        initialize("checkpoint", port, true);
        sql("UPDATE cdc_article5.items SET amount=20 WHERE id=101");
        sql("INSERT INTO cdc_article5.items VALUES (201,40)");
        sql("DELETE FROM cdc_article5.items WHERE id=102");
        await(() -> RAW.contains("data:101:20") && RAW.contains("data:201:40")
                && RAW.contains("delete:102"), "DML ready for held checkpoint");
        long previousCompleted = Collections.max(COMPLETED);
        HOLD_CHECKPOINT.set(true);
        CompletableFuture<?> checkpointFuture = cluster.getMiniCluster().triggerCheckpoint(job.getJobID());
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(checkpointFuture.isDone()).isFalse();
        assertThat(COMPLETED).doesNotContain(heldCheckpoint);
        assertThat(rows()).containsEntry(101, "101,20").containsEntry(201, "201,40")
                .doesNotContainKey(102);
        record("checkpoint-target-visible-before-global-completion");
        release.countDown();
        await(() -> attempt >= 1 && Collections.frequency(RAW, "data:101:20") == 2,
                "restore after failed checkpoint");
        assertThat(COMPLETED).doesNotContain(heldCheckpoint);
        assertThat(restoredRecords).isEqualTo(11);
        assertThat(rows()).containsEntry(101, "101,20");
        record("checkpoint-after-restart-before-recommit");
        checkpoint();
        assertThat(rows().size()).isEqualTo(10);
        assertThat(rows()).containsEntry(101, "101,20").containsEntry(201, "201,40")
                .doesNotContainKey(102);
        record("checkpoint-final");
        System.out.println("ARTICLE5 checkpoint window previousCompleted=" + previousCompleted
                + " failed=" + heldCheckpoint + " completed=" + COMPLETED
                + " rawUpdates=" + Collections.frequency(RAW, "data:101:20"));
        stop();
    }

    static void stop() throws Exception {
        job.cancel().get(30, TimeUnit.SECONDS);
        job = null;
    }

    static void sql(String sql) throws Exception {
        try (Statement statement = mysql.createStatement()) { statement.execute(sql); }
    }

    static void checkpoint() throws Exception {
        int previous = COMPLETED.size();
        cluster.getMiniCluster().triggerCheckpoint(job.getJobID()).get(60, TimeUnit.SECONDS);
        await(() -> COMPLETED.size() > previous, "completed checkpoint callback");
    }

    static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(30);
        }
        assertThat(condition.getAsBoolean()).as(label + "; attempt=" + attempt
                + "; job=" + job.getJobStatus().get() + "; raw=" + RAW).isTrue();
    }

    static Map<Integer, String> rows() {
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            FileStoreTable table = (FileStoreTable) catalog.getTable(new Identifier("cdc_article5", "items"));
            ReadBuilder builder = table.newReadBuilder();
            Map<Integer, String> values = new TreeMap<>();
            try (RecordReader<InternalRow> reader = builder.newRead().createReader(builder.newScan().plan())) {
                RecordReader.RecordIterator<InternalRow> batch;
                while ((batch = reader.readBatch()) != null) {
                    InternalRow row;
                    while ((row = batch.next()) != null) {
                        List<String> fields = new ArrayList<>();
                        int n = table.rowType().getFieldCount();
                        for (int i = 0; i < n; i++) {
                            fields.add(row.isNullAt(i) ? "null" : (i == 0 || i == n - 1)
                                    ? String.valueOf(row.getInt(i)) : row.getString(i).toString());
                        }
                        values.put(row.getInt(0), String.join(",", fields));
                    }
                    batch.releaseBatch();
                }
            }
            return values;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static List<String> columns() {
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            return catalog.getTable(new Identifier("cdc_article5", "items")).rowType().getFieldNames();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static long snapshotId() {
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            Long id = ((FileStoreTable) catalog.getTable(new Identifier("cdc_article5", "items")))
                    .snapshotManager().latestSnapshotId();
            return id == null ? -1 : id;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void record(String name) throws Exception {
        String message = "name=" + name + "\nwarehouse=" + warehouse + "\ncolumns=" + columns()
                + "\nsnapshotId=" + snapshotId() + "\ncompleted=" + COMPLETED
                + "\nheldCheckpoint=" + heldCheckpoint + "\nattempt=" + attempt
                + "\nrestoredRecords=" + restoredRecords + "\nflagApplications=" + FLAG_APPLICATIONS
                + "\nrows=" + rows() + "\nraw=" + RAW + "\n";
        Files.writeString(evidence.resolve(name + ".txt"), message);
        System.out.println("ARTICLE5 " + message.replace('\n', ' '));
    }

    public static class SourceProbe extends RichMapFunction<Event, Event>
            implements CheckpointedFunction, CheckpointListener {
        private transient ListState<Integer> state;
        private int count;
        @Override public void open(org.apache.flink.configuration.Configuration config) {
            attempt = getRuntimeContext().getAttemptNumber();
        }
        @Override public Event map(Event event) {
            if (event instanceof DataChangeEvent) {
                DataChangeEvent data = (DataChangeEvent) event;
                if (data.after() == null) {
                    RAW.add("delete:" + data.before().getInt(0));
                } else {
                    RAW.add("data:" + data.after().getInt(0) + ":"
                            + data.after().getInt(data.after().getArity() - 1));
                }
                count++;
            } else { RAW.add(event.getClass().getSimpleName()); }
            return event;
        }
        @Override public void snapshotState(FunctionSnapshotContext context) throws Exception {
            state.update(Collections.singletonList(count));
        }
        @Override public void initializeState(FunctionInitializationContext context) throws Exception {
            state = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("count", Integer.class));
            if (context.isRestored()) {
                for (int value : state.get()) { count = value; }
                restoredRecords = count;
            }
        }
        @Override public void notifyCheckpointComplete(long id) { COMPLETED.add(id); }
    }

    public static class DdlFaultApplier implements MetadataApplier {
        private final MetadataApplier delegate;
        DdlFaultApplier(MetadataApplier delegate) { this.delegate = delegate; }
        @Override public void applySchemaChange(SchemaChangeEvent event) {
            boolean flag = event instanceof AddColumnEvent
                    && ((AddColumnEvent) event).getAddedColumns().stream()
                    .anyMatch(c -> c.getAddColumn().getName().equals("flag"));
            if (flag) { FLAG_APPLICATIONS.incrementAndGet(); }
            delegate.applySchemaChange(event);
            if (flag && FAIL_DDL.compareAndSet(true, false)) {
                throw new RuntimeException("ARTICLE5 injected failure AFTER real Paimon ADD flag");
            }
        }
        @Override public MetadataApplier setAcceptedSchemaEvolutionTypes(Set<SchemaChangeEventType> types) {
            delegate.setAcceptedSchemaEvolutionTypes(types);
            return this;
        }
        @Override public boolean acceptsSchemaEvolutionType(SchemaChangeEventType type) {
            return delegate.acceptsSchemaEvolutionType(type);
        }
        @Override public Set<SchemaChangeEventType> getSupportedSchemaEvolutionTypes() {
            return delegate.getSupportedSchemaEvolutionTypes();
        }
    }

    static class UidAlignedSink implements DataSink {
        final DataSink delegate;
        final Options catalogOptions;
        UidAlignedSink(DataSink delegate, Options options) {
            this.delegate = delegate;
            this.catalogOptions = options;
        }
        @Override public EventSinkProvider getEventSinkProvider() {
            return FlinkSinkProvider.of(new PaimonEventSink(catalogOptions, "article5",
                    new PaimonRecordEventSerializer(ZoneId.of("UTC"), catalogOptions), UID, ZoneId.of("UTC")));
        }
        @Override public MetadataApplier getMetadataApplier() { return delegate.getMetadataApplier(); }
    }

    static class CommitProbeSink extends UidAlignedSink {
        CommitProbeSink(DataSink delegate, Options options) { super(delegate, options); }
        @Override public EventSinkProvider getEventSinkProvider() {
            return FlinkSinkProvider.of(new ObservedPaimonSink(catalogOptions));
        }
    }

    public static class ObservedPaimonSink extends PaimonEventSink {
        ObservedPaimonSink(Options options) {
            super(options, "article5", new PaimonRecordEventSerializer(ZoneId.of("UTC"), options), UID, ZoneId.of("UTC"));
        }
        @Override public DataStream<CommittableMessage<MultiTableCommittable>> addPreCommitTopology(
                DataStream<CommittableMessage<MultiTableCommittable>> input) {
            DataStream<CommittableMessage<MultiTableCommittable>> stream = super.addPreCommitTopology(input);
            return stream.transform("Article after real precommit", stream.getType(), new AfterCommitProbe())
                    .setParallelism(1);
        }
    }

    public static class AfterCommitProbe
            extends AbstractStreamOperator<CommittableMessage<MultiTableCommittable>>
            implements OneInputStreamOperator<CommittableMessage<MultiTableCommittable>,
                    CommittableMessage<MultiTableCommittable>> {
        @Override public void processElement(StreamRecord<CommittableMessage<MultiTableCommittable>> record) {
            output.collect(record);
        }
        @Override public void snapshotState(StateSnapshotContext context) throws Exception {
            super.snapshotState(context);
            if (HOLD_CHECKPOINT.compareAndSet(true, false)) {
                heldCheckpoint = context.getCheckpointId();
                reached.countDown();
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("ARTICLE5 checkpoint probe release timed out");
                }
                throw new RuntimeException("ARTICLE5 injected failure AFTER real Paimon snapshot publication");
            }
        }
    }
}
