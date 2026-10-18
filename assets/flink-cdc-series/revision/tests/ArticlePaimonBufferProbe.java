package article5;

import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.StatefulSinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.DataChangeEvent;
import org.apache.flink.cdc.common.event.CreateTableEvent;
import org.apache.flink.cdc.common.event.Event;
import org.apache.flink.cdc.common.event.SchemaChangeEvent;
import org.apache.flink.cdc.common.event.SchemaChangeEventType;
import org.apache.flink.cdc.common.sink.DataSink;
import org.apache.flink.cdc.common.sink.EventSinkProvider;
import org.apache.flink.cdc.common.sink.FlinkSinkProvider;
import org.apache.flink.cdc.common.sink.MetadataApplier;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonEventSink;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonRecordEventSerializer;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonWriter;
import org.apache.flink.cdc.connectors.paimon.sink.v2.PaimonWriterState;
import org.apache.flink.cdc.connectors.paimon.sink.v2.bucket.BucketWrapperChangeEvent;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.mergetree.WriteBuffer;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Observes the real write buffer; never mutates it or substitutes write/replace/commit. */
public class ArticlePaimonBufferProbe extends ArticlePaimonExperiment {
    static boolean enabled;
    static boolean failAfterDdl;
    static volatile BufferWriter currentWriter;
    static final List<String> WRITER_EVENTS = new CopyOnWriteArrayList<>();
    static final List<String> OBSERVATIONS = new CopyOnWriteArrayList<>();
    static final AtomicInteger DDL_CALLS = new AtomicInteger();
    static final CountDownLatch DDL_APPLIED = new CountDownLatch(1);
    static final CountDownLatch DDL_RELEASE = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        evidence = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        String mode = args[2];
        enabled = !mode.equals("schema");
        failAfterDdl = mode.equals("buffer-failure");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "article5-local-only")) {
            mysql = connection;
            try (Statement s = mysql.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT VERSION(), @@binlog_format, @@binlog_row_image, @@gtid_mode")) {
                r.next(); note("environment=" + r.getString(1) + "," + r.getString(2)
                        + "," + r.getString(3) + "," + r.getString(4));
            }
            for (Class<?> c : new Class<?>[] {PaimonWriter.class,
                    org.apache.flink.cdc.connectors.paimon.sink.v2.PreCommitOperator.class,
                    io.debezium.connector.mysql.MySqlStreamingChangeEventSource.class,
                    org.apache.paimon.operation.FileStoreCommitImpl.class}) {
                note("loaded=" + c.getName() + " from=" + c.getProtectionDomain().getCodeSource().getLocation());
            }
            cluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(1).setNumberSlotsPerTaskManager(8)
                    .setConfiguration(new org.apache.flink.configuration.Configuration()).build());
            cluster.before();
            try {
                if (mode.equals("schema")) {
                    runSchemaScenario(port);
                    reconcile("schema-full-reconciliation");
                } else {
                    runBuffered(port, mode);
                }
                note("SCENARIO_PASSED=" + mode);
            } finally {
                DDL_RELEASE.countDown();
                if (release != null) release.countDown();
                try { if (job != null) job.cancel().get(30, TimeUnit.SECONDS); }
                catch (Exception cleanup) { note("cleanup=" + cleanup); }
                finally {
                    cluster.after();
                    Files.write(evidence.resolve("buffer-observations.txt"), OBSERVATIONS);
                }
            }
        }
    }

    static void runBuffered(int port, String mode) throws Exception {
        initialize(mode, port, false);
        long baseline = snapshotId();
        List<Long> baselineCompleted = new ArrayList<>(COMPLETED);
        sql("UPDATE cdc_article5.items SET amount=13 WHERE id=103");
        sql("INSERT INTO cdc_article5.items VALUES (202,22)");
        sql("DELETE FROM cdc_article5.items WHERE id=102");
        await(() -> WRITER_EVENTS.contains("delete:102"), "old-layout writes reached real writer");
        BufferState before = inspect(currentWriter);
        assertThat(before.count).isEqualTo(4);
        assertThat(before.columns).containsExactly("id", "amount");
        assertThat(snapshotId()).isEqualTo(baseline);
        assertThat(rows()).containsEntry(103, "103,10").doesNotContainKey(202).containsKey(102);
        note("BEFORE_DDL " + before + " visibleSnapshot=" + baseline + " completed=" + COMPLETED);

        sql("ALTER TABLE cdc_article5.items ADD COLUMN flag VARCHAR(20) AFTER id");
        sql("UPDATE cdc_article5.items SET flag='blue',amount=20 WHERE id=101");
        assertThat(DDL_APPLIED.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(columns()).containsExactly("id", "flag", "amount");
        assertThat(snapshotId()).isEqualTo(baseline);
        assertThat(COMPLETED).containsExactlyElementsOf(baselineCompleted);
        BufferState during = inspect(currentWriter);
        assertThat(during.count).isEqualTo(before.count);
        assertThat(during.columns).containsExactly("id", "amount");
        assertThat(WRITER_EVENTS).doesNotContain("schema:flag");
        note("DDL_SUCCEEDED_WRITER_NOT_SWITCHED " + during + " targetColumns=" + columns());
        record(mode + "-ddl-applied-old-buffer-held");
        DDL_RELEASE.countDown();
        await(() -> WRITER_EVENTS.contains("data:101:20")
                && (!failAfterDdl || attempt >= 1), "post-DDL data reached real writer");
        assertThat(WRITER_EVENTS).contains("schema:buffer4-to-0");
        checkpoint();
        assertThat(DDL_CALLS.get()).isEqualTo(failAfterDdl ? 2 : 1);
        assertThat(rows()).containsEntry(103, "103,null,13")
                .containsEntry(202, "202,null,22").doesNotContainKey(102)
                .containsEntry(101, "101,blue,20");
        reconcile(mode + "-full-reconciliation");
        record(mode + "-final");
        stop();
        // Re-open after cancellation, rather than reading a writer-local cache.
        reconcile(mode + "-durable-reconciliation");
    }

    static void reconcile(String label) throws Exception {
        List<String> sourceColumns = new ArrayList<>();
        Map<Integer, List<Object>> sourceRows = new TreeMap<>();
        try (Statement s = mysql.createStatement();
                ResultSet r = s.executeQuery("SELECT * FROM cdc_article5.items ORDER BY id")) {
            ResultSetMetaData md = r.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) sourceColumns.add(md.getColumnName(i));
            while (r.next()) {
                List<Object> values = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) values.add(r.getObject(i));
                assertThat(sourceRows.put(r.getInt(1), values)).isNull();
            }
        }
        List<String> targetColumns;
        Map<Integer, List<Object>> targetRows = new TreeMap<>();
        int physicalVisibleRows = 0;
        try (Catalog catalog = FlinkCatalogFactory.createPaimonCatalog(options)) {
            FileStoreTable table = (FileStoreTable) catalog.getTable(new Identifier("cdc_article5", "items"));
            targetColumns = table.rowType().getFieldNames();
            ReadBuilder builder = table.newReadBuilder();
            try (RecordReader<InternalRow> r = builder.newRead().createReader(builder.newScan().plan())) {
                RecordReader.RecordIterator<InternalRow> batch;
                while ((batch = r.readBatch()) != null) {
                    InternalRow row;
                    while ((row = batch.next()) != null) {
                        List<Object> values = new ArrayList<>();
                        for (int i = 0; i < targetColumns.size(); i++) {
                            String name = targetColumns.get(i);
                            values.add(row.isNullAt(i) ? null
                                    : name.equals("id") || name.equals("amount")
                                            ? Integer.valueOf(row.getInt(i)) : row.getString(i).toString());
                        }
                        physicalVisibleRows++;
                        assertThat(targetRows.put(row.getInt(0), values))
                                .as("duplicate visible primary key %s", row.getInt(0)).isNull();
                    }
                    batch.releaseBatch();
                }
            }
        }
        assertThat(targetColumns).containsExactlyElementsOf(sourceColumns);
        assertThat(physicalVisibleRows).isEqualTo(sourceRows.size());
        assertThat(targetRows).isEqualTo(sourceRows);
        String result = "columns=" + sourceColumns + "\nsource=" + sourceRows + "\ntarget=" + targetRows
                + "\nvisibleRows=" + physicalVisibleRows + "\nuniqueKeys=" + targetRows.size()
                + "\nFULL_FIELD_RECONCILIATION=PASS\n";
        Files.writeString(evidence.resolve(label + ".txt"), result);
        note(label + " " + result.replace('\n', ' '));
    }

    static void note(String s) { OBSERVATIONS.add(s); System.out.println("BUFFER_PROBE " + s); }

    static Object field(Object object, String name) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException absent) { /* Search the declaring superclass. */ }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new IllegalStateException("Missing field " + name + " on " + object.getClass());
    }

    static class BufferState {
        int count;
        List<String> columns = new ArrayList<>();
        List<String> implementations = new ArrayList<>();
        public String toString() { return "bufferRecords=" + count + " writerColumns=" + columns
                + " buffers=" + implementations; }
    }

    static BufferState inspect(BufferWriter writer) {
        BufferState result = new BufferState();
        Map<?, ?> tables = (Map<?, ?>) field(writer, "tables");
        for (Object table : tables.values()) result.columns = ((FileStoreTable) table).rowType().getFieldNames();
        Map<?, ?> writes = (Map<?, ?>) field(writer, "writes");
        for (Object sinkWrite : writes.values()) {
            Object tableWrite = field(sinkWrite, "write");
            Object storeWrite = field(tableWrite, "write");
            Map<?, ?> partitions = (Map<?, ?>) field(storeWrite, "writers");
            for (Object buckets : partitions.values()) for (Object container : ((Map<?, ?>) buckets).values()) {
                Object bucketWriter = field(container, "writer");
                WriteBuffer buffer = (WriteBuffer) field(bucketWriter, "writeBuffer");
                result.count += buffer.size();
                result.implementations.add(buffer.getClass().getSimpleName() + ":" + buffer.size());
            }
        }
        return result;
    }

    public static class BufferWriter extends PaimonWriter<Event> {
        BufferWriter(Options options, MetricGroup metrics, String commitUser, long checkpoint) {
            super(options, metrics, commitUser,
                    new PaimonRecordEventSerializer(ZoneId.of("UTC"), options), checkpoint);
            currentWriter = this;
        }
        @Override public void write(Event event, Context context) throws IOException {
            Event inner = event instanceof BucketWrapperChangeEvent
                    ? ((BucketWrapperChangeEvent) event).getInnerEvent() : event;
            boolean schema = inner instanceof SchemaChangeEvent;
            boolean flag = inner instanceof CreateTableEvent
                    && ((CreateTableEvent) inner).getSchema().getColumn("flag").isPresent();
            BufferState beforeSchema = schema ? inspect(this) : null;
            if (schema) note("WRITER_BEFORE_SCHEMA event=" + inner + " " + beforeSchema);
            super.write(event, context);
            if (schema) {
                BufferState afterSchema = inspect(this);
                note("WRITER_AFTER_SCHEMA event=" + inner.getClass().getSimpleName() + " " + afterSchema);
                if (flag) {
                    WRITER_EVENTS.add("schema:flag");
                    if (beforeSchema.count == 4) {
                        assertThat(afterSchema.count).isZero();
                        WRITER_EVENTS.add("schema:buffer4-to-0");
                    }
                }
            }
            if (inner instanceof DataChangeEvent) {
                DataChangeEvent d = (DataChangeEvent) inner;
                String key = d.after() == null ? "delete:" + d.before().getInt(0)
                        : "data:" + d.after().getInt(0) + ":" + d.after().getInt(d.after().getArity() - 1);
                note("WRITER_AFTER " + key + " attempt=" + attempt + " " + inspect(this));
                WRITER_EVENTS.add(key);
            }
        }
        @Override public void flush(boolean endOfInput) {
            BufferState before = inspect(this);
            super.flush(endOfInput);
            BufferState after = inspect(this);
            note("FLUSH_RETURN endOfInput=" + endOfInput + " before=" + before + " after=" + after);
        }
    }

    static boolean isFlag(Event event) {
        return event instanceof AddColumnEvent && ((AddColumnEvent) event).getAddedColumns().stream()
                .anyMatch(c -> c.getAddColumn().getName().equals("flag"));
    }

    public static class ObservedSink extends PaimonEventSink {
        ObservedSink(Options options) {
            super(options, "article5-buffer", new PaimonRecordEventSerializer(ZoneId.of("UTC"), options),
                    UID, ZoneId.of("UTC"));
        }
        @Override public PaimonWriter<Event> createWriter(WriterInitContext context) {
            return new BufferWriter(catalogOptions, context.metricGroup(), commitUser,
                    context.getRestoredCheckpointId().orElse(0));
        }
        @Override public PaimonWriter<Event> createWriter(Sink.InitContext context) {
            return new BufferWriter(catalogOptions, context.metricGroup(), commitUser,
                    context.getRestoredCheckpointId().orElse(0));
        }
        @Override public StatefulSinkWriter<Event, PaimonWriterState> restoreWriter(
                WriterInitContext context, Collection<PaimonWriterState> states) {
            String user = states.isEmpty() ? commitUser : states.iterator().next().getCommitUser();
            return new BufferWriter(catalogOptions, context.metricGroup(), user,
                    context.getRestoredCheckpointId().orElse(0));
        }
    }

    static class BufferSink extends UidAlignedSink {
        BufferSink(DataSink delegate, Options options) { super(delegate, options); }
        @Override public EventSinkProvider getEventSinkProvider() {
            return FlinkSinkProvider.of(new ObservedSink(catalogOptions));
        }
    }

    public static class BufferApplier implements MetadataApplier {
        private final MetadataApplier delegate;
        BufferApplier(MetadataApplier delegate) { this.delegate = delegate; }
        @Override public void applySchemaChange(SchemaChangeEvent event) {
            boolean flag = isFlag(event);
            int call = flag ? DDL_CALLS.incrementAndGet() : 0;
            delegate.applySchemaChange(event);
            if (flag) note("REAL_DDL_RETURN call=" + call + " " + inspect(currentWriter));
            if (flag && call == 1) {
                DDL_APPLIED.countDown();
                try { if (!DDL_RELEASE.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("DDL release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                if (failAfterDdl) throw new RuntimeException("BUFFER_PROBE failure AFTER real ADD, BEFORE writer schema switch");
            }
        }
        @Override public MetadataApplier setAcceptedSchemaEvolutionTypes(Set<SchemaChangeEventType> types) {
            delegate.setAcceptedSchemaEvolutionTypes(types); return this;
        }
        @Override public boolean acceptsSchemaEvolutionType(SchemaChangeEventType type) {
            return delegate.acceptsSchemaEvolutionType(type);
        }
        @Override public Set<SchemaChangeEventType> getSupportedSchemaEvolutionTypes() {
            return delegate.getSupportedSchemaEvolutionTypes();
        }
    }
}
