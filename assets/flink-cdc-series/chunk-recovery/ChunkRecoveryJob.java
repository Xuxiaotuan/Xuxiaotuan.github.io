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
package article2recovery;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.cdc.common.configuration.Configuration;
import org.apache.flink.cdc.common.event.*;
import org.apache.flink.cdc.common.pipeline.SchemaChangeBehavior;
import org.apache.flink.cdc.common.sink.*;
import org.apache.flink.cdc.common.source.*;
import org.apache.flink.cdc.composer.definition.*;
import org.apache.flink.cdc.composer.flink.coordination.OperatorIDGenerator;
import org.apache.flink.cdc.composer.flink.translator.*;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSource;
import org.apache.flink.cdc.connectors.mysql.source.MySqlDataSource;
import org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import org.apache.flink.cdc.connectors.mysql.source.offset.BinlogOffset;
import org.apache.flink.cdc.connectors.mysql.source.split.*;
import org.apache.flink.cdc.connectors.mysql.source.utils.*;
import org.apache.flink.cdc.connectors.mysql.source.utils.hooks.SnapshotPhaseHooks;
import org.apache.flink.cdc.connectors.mysql.debezium.DebeziumUtils;
import org.apache.flink.cdc.connectors.mysql.debezium.reader.BinlogSplitReader;
import org.apache.flink.cdc.connectors.mysql.debezium.reader.SnapshotSplitReader;
import org.apache.flink.cdc.connectors.mysql.source.split.SourceRecords;
import org.apache.flink.cdc.connectors.mysql.schema.MySqlSchema;
import org.apache.flink.cdc.connectors.paimon.sink.v2.*;
import org.apache.flink.cdc.runtime.typeutils.EventTypeInfo;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.paimon.catalog.*;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.ReadBuilder;
import io.debezium.connector.mysql.MySqlPartition;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges.TableChange;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.flink.api.connector.source.*;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.connector.sink2.CommittableMessage;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.paimon.flink.sink.MultiTableCommittable;
import org.apache.flink.cdc.connectors.mysql.source.assigners.state.PendingSplitsState;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;

/** Full Flink job with production split assignment, Readers, Schema operators and Paimon sink. */
public class ChunkRecoveryJob {
    static final String DB = "cdc_chunk_e2e";
    static final String UID = "chunk-e2e-schema";
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final List<String> TIMELINE = new CopyOnWriteArrayList<>();
    static final List<Long> COMPLETED = new CopyOnWriteArrayList<>();
    static final List<MySqlSnapshotSplit> SPLITS = new CopyOnWriteArrayList<>();
    static volatile BinlogOffset ha, lb, hb;
    static volatile boolean move;
    static boolean post;
    static final CountDownLatch bReached = new CountDownLatch(1), bRelease = new CountDownLatch(1);
    static final CountDownLatch faultReached = new CountDownLatch(1), faultRelease = new CountDownLatch(1);
    static final AtomicBoolean firstB = new AtomicBoolean(true), failOnce = new AtomicBoolean();
    static volatile int attempt, restoredCount = -1;
    static volatile boolean recoveryBHigh;
    static volatile long restoredCheckpoint = -1, failedCheckpoint = -1;
    static long expectedRestore;
    static final Map<Long,List<MySqlSplit>> savedReaderStates = new ConcurrentHashMap<>();
    static final List<MySqlSplit> restoredReaderSplits = new CopyOnWriteArrayList<>();
    static Path out;
    static Connection mysql;
    static Options options;
    static JobClient job;
    static MiniClusterWithClientResource cluster;
    static MySqlSourceConfig config;

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        move = args[2].startsWith("move"); post = args[2].endsWith("post");
        options = new Options();
        options.setString("warehouse", out.resolve("warehouse").toString());
        options.setString("metastore", "filesystem");
        options.setString("cache-enabled", "false");
        if (args.length > 3 && args[3].equals("readback")) {
            Map<Integer,List<Integer>> source = new TreeMap<>();
            for (String line : Files.readAllLines(out.resolve("mysql-final.tsv")).subList(1,21)) {
                String[] f = line.split("\t");
                source.put(Integer.valueOf(f[0]), Arrays.asList(Integer.valueOf(f[0]),Integer.valueOf(f[1]),Integer.valueOf(f[2])));
            }
            reconcile(source, "fresh-jvm");
            return;
        }
        int port = Integer.parseInt(args[1]);
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+port+"/?useSSL=false&allowPublicKeyRetrieval=true", "root", "article2-movement-local-only")) {
            mysql = c;
            initializeData();
            cluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(1).setNumberSlotsPerTaskManager(8)
                    .setConfiguration(new org.apache.flink.configuration.Configuration()).build());
            cluster.before();
            try { runJob(port); }
            finally {
                bRelease.countDown(); faultRelease.countDown();
                if (job != null) { job.cancel().get(30,TimeUnit.SECONDS); }
                cluster.after();
                Files.write(out.resolve("timeline.txt"),TIMELINE);
                Files.write(out.resolve("source-events.txt"),EVENTS);
            }
        }
    }
    static void initializeData() throws Exception {
        sql("CREATE DATABASE IF NOT EXISTS "+DB);
        sql("DROP TABLE IF EXISTS "+DB+".items");
        sql("CREATE TABLE "+DB+".items(id INT PRIMARY KEY,c INT NOT NULL,amount INT NOT NULL) ENGINE=InnoDB");
        for (int c=95;c<=114;c++) {
            int id = c==100?101:c==110?102:c==114?103:200+c;
            int amount = id==101?10:id==102?20:id==103?30:c;
            sql("INSERT INTO "+DB+".items VALUES("+id+","+c+","+amount+")");
        }
        sql("ANALYZE TABLE "+DB+".items");
        writeRows("mysql-initial.tsv", sourceRows());
        try (Statement s=mysql.createStatement();ResultSet r=s.executeQuery("SELECT VERSION(),@@binlog_format,@@binlog_row_image,@@gtid_mode,@@transaction_isolation")) {
            r.next(); Files.writeString(out.resolve("mysql-environment.txt"),r.getString(1)+" "+r.getString(2)+" "+r.getString(3)+" "+r.getString(4)+" "+r.getString(5)+"\n");
        }
    }
    static void runJob(int port) throws Exception {
        StreamExecutionEnvironment env=StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); env.enableCheckpointing(600000);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, org.apache.flink.api.common.time.Time.milliseconds(200)));
        Configuration pipeline=Configuration.fromMap(Map.of("parallelism","1","schema.change.behavior","evolve","local-time-zone","UTC"));
        Map<String,String> cfg = new HashMap<>();
        cfg.put("hostname","127.0.0.1");cfg.put("port",String.valueOf(port));cfg.put("username","root");cfg.put("password","article2-movement-local-only");
        cfg.put("tables",DB+".items");cfg.put("server-id","8700-8710");cfg.put("server-time-zone","UTC");
        cfg.put("scan.incremental.snapshot.chunk.size","10");
        cfg.put("scan.incremental.snapshot.chunk.key-column",DB+".items:c");
        SourceDef sd=new SourceDef("mysql","MySQL fixed chunks",Configuration.fromMap(cfg));
        DataSourceTranslator st=new DataSourceTranslator();
        MySqlDataSource ds=(MySqlDataSource)st.createDataSource(sd,pipeline,env);
        config=ds.getSourceConfig();
        MySqlSource<Event> source=(MySqlSource<Event>)((FlinkSourceProvider)ds.getEventSourceProvider()).getSource();
        SnapshotPhaseHooks hooks=new SnapshotPhaseHooks();
        hooks.setPreLowWatermarkAction((jdbc,split)-> {
            MySqlSnapshotSplit s=(MySqlSnapshotSplit)split;
            SPLITS.add(s); trace("PRE_LOW attempt="+attempt+" "+s);
            if (s.getSplitStart()==null) {
                assertThat(s.getSplitEnd()).containsExactly(105);
            } else {
                assertThat(s.getSplitStart()).containsExactly(105);assertThat(s.getSplitEnd()).isNull();
                if (firstB.compareAndSet(true,false)) {
                    assertThat(ha).isNotNull(); bReached.countDown();
                    gate(bRelease,"before B LOW / migration");
                    sql(move?"UPDATE "+DB+".items SET c=100 WHERE id=102":"UPDATE "+DB+".items SET amount=21 WHERE id=102");
                    sql("UPDATE "+DB+".items SET amount=11 WHERE id=101");
                    sql("UPDATE "+DB+".items SET amount=31 WHERE id=103");
                    trace("MUTATIONS_COMMITTED before B LOW");
                } else trace("RECOVERY_RESCAN_B no repeated mutation");
            }
        });
        hooks.setPostLowWatermarkAction((jdbc,split)-> {
            BinlogOffset offset=DebeziumUtils.currentBinlogOffset(jdbc);
            trace("POST_LOW "+split.splitId()+" offset="+offset);
            if (((MySqlSnapshotSplit)split).getSplitStart()!=null && lb==null) { lb=offset; }
        });
        hooks.setPostHighWatermarkAction((jdbc,split)-> {
            BinlogOffset offset=DebeziumUtils.currentBinlogOffset(jdbc);
            trace("POST_HIGH "+split.splitId()+" offset="+offset);
            if (((MySqlSnapshotSplit)split).getSplitStart()==null) { ha=offset; } else if(hb==null) { hb=offset; } else { recoveryBHigh=true; }
        });
        source.setSnapshotHooks(hooks);
        DataSinkTranslator sinkTranslator=new DataSinkTranslator();
        Configuration sinkConfig=Configuration.fromMap(Map.of("catalog.properties.warehouse",out.resolve("warehouse").toString(),
                "catalog.properties.metastore","filesystem","catalog.properties.cache-enabled","false","table.properties.bucket","1",
                "table.properties.changelog-producer","input","table.properties.write-buffer-size","8mb"));
        SinkDef sinkDef=new SinkDef("paimon","Real Paimon",sinkConfig);
        DataSink base=sinkTranslator.createDataSink(sinkDef,pipeline,env);
        OperatorUidGenerator uids=new OperatorUidGenerator("chunk-e2e");
        DataSource hooked=new DataSource() {
            public EventSourceProvider getEventSourceProvider(){return FlinkSourceProvider.of(new ObservedSource(source));}
            public MetadataAccessor getMetadataAccessor(){return ds.getMetadataAccessor();}
        };
        DataStream<Event> stream=st.translate(sd,hooked,env,1,uids).map(new Observe()).returns(new EventTypeInfo()).name("Source observation only");
        stream=new SchemaOperatorTranslator(SchemaChangeBehavior.EVOLVE,UID,Duration.ofSeconds(60),"UTC")
                .translateRegular(stream,1,base.getMetadataApplier(),Collections.emptyList());
        OperatorIDGenerator ids=new OperatorIDGenerator(UID);
        stream=new PartitioningTranslator().translateRegular(stream,1,1,ids.generate(),base.getDataChangeEventHashFunctionProvider(1),uids);
        sinkTranslator.translate(sinkDef,stream,new AlignedSink(base,options),ids.generate(),uids);
        Files.writeString(out.resolve("execution-plan.json"),env.getExecutionPlan());
        for (Class<?> k:Arrays.asList(MySqlSource.class,SnapshotSplitReader.class,BinlogSplitReader.class,RecordUtils.class,PaimonWriter.class,PreCommitOperator.class)) {
            trace("LOADED "+k.getName()+" "+k.getProtectionDomain().getCodeSource().getLocation());
        }
        job=env.executeAsync("chunk-key-"+(move?"move":"control")); trace("JOB_ID "+job.getJobID());
        assertThat(bReached.await(60,TimeUnit.SECONDS)).as("B blocked before LOW").isTrue();
        await(()->EVENTS.stream().filter(x->x.startsWith("INSERT:")).count()==10,"A output");
        checkpoint(); expectedRestore=Collections.max(COMPLETED);
        assertThat(sourceRows().get(102)).containsExactly(102,110,20);
        assertThat(savedReaderStates.get(expectedRestore)).anySatisfy(s -> {
            assertThat(s.isSnapshotSplit()).isTrue();
            assertThat(s.asSnapshotSplit().getSplitStart()).containsExactly(105);
            assertThat(s.asSnapshotSplit().getHighWatermark()).isNull();
        });
        trace("PRE_MIGRATION_COMPLETED checkpoint="+expectedRestore);
        bRelease.countDown();
        await(()->hb!=null && EVENTS.stream().filter(x->x.startsWith("INSERT:")).count()>=(move?19:20),"snapshots emitted");
        assertThat(SPLITS).hasSize(2);assertThat(hb).isEqualTo(lb);
        await(()->EVENTS.contains("UPDATE:101:100:11"),"post-A pre-B binlog marker");
        sql("UPDATE "+DB+".items SET amount=32 WHERE id=103");
        await(()->EVENTS.contains("UPDATE:103:114:32"),"live binlog caught up after B HIGH");
        if(post) {
            checkpoint(); expectedRestore=Collections.max(COMPLETED);
            assertThat(savedReaderStates.get(expectedRestore)).hasSize(1);
            assertThat(savedReaderStates.get(expectedRestore).get(0).isBinlogSplit()).isTrue();
            trace("POST_MIGRATION_COMPLETED checkpoint="+expectedRestore);
        }
        Map<Integer,List<Integer>> frozen=sourceRows(); writeRows("mysql-before-failure.tsv",frozen);
        failOnce.set(true);
        CompletableFuture<?> failedFuture=cluster.getMiniCluster().triggerCheckpoint(job.getJobID());
        assertThat(faultReached.await(60,TimeUnit.SECONDS)).as("fault checkpoint held").isTrue();
        assertThat(failedFuture.isDone()).isFalse(); assertThat(COMPLETED).doesNotContain(failedCheckpoint);
        // Independent catalog scan proves publication, not merely the probe's position in the DAG.
        await(()->{
            try(Catalog cat=FlinkCatalogFactory.createPaimonCatalog(options)){
                FileStoreTable t=(FileStoreTable)cat.getTable(new Identifier(DB,"items"));
                Long latest=t.snapshotManager().latestSnapshotId();
                return latest!=null && t.snapshotManager().snapshot(latest).commitIdentifier()==failedCheckpoint;
            }catch(Exception e){throw new RuntimeException(e);}
        },"real external snapshot publication");
        assertThat(failedFuture.isDone()).isFalse();
        reconcile(frozen,"published-before-global-failure");
        try(Catalog cat=FlinkCatalogFactory.createPaimonCatalog(options)) {
            FileStoreTable table=(FileStoreTable)cat.getTable(new Identifier(DB,"items"));
            long id=table.snapshotManager().latestSnapshotId();
            assertThat(table.snapshotManager().snapshot(id).commitIdentifier()).isEqualTo(failedCheckpoint);
            trace("EXTERNAL_SNAPSHOT_PUBLISHED snapshot="+id+" commitIdentifier="+failedCheckpoint+" globalFuturePending=true");
        }
        faultRelease.countDown();
        try { failedFuture.get(60,TimeUnit.SECONDS); throw new AssertionError("fault checkpoint unexpectedly completed"); }
        catch(ExecutionException expected) { trace("FAILED_CHECKPOINT_EXCEPTION "+expected.getCause()); }
        await(()->attempt==1 && restoredCheckpoint==expectedRestore,"restore exact completed checkpoint");
        assertThat(restoredCount).isEqualTo(post?(move?21:22):10);
        if(!post) await(()->SPLITS.size()==3 && recoveryBHigh,"only B snapshot reread and its HIGH fixed");
        await(()->!restoredReaderSplits.isEmpty(),"restored source split delivered");
        assertThat(restoredReaderSplits.get(0).toString()).isEqualTo(savedReaderStates.get(expectedRestore).get(0).toString());
        trace("RESTORED_READER_STATE_MATCH checkpoint="+expectedRestore);
        // A new marker demonstrates that the restored source has finished handoff and is live.
        sql("UPDATE "+DB+".items SET amount=33 WHERE id=103");
        await(()->EVENTS.contains("UPDATE:103:114:33"),"restored binlog caught up");
        Map<Integer,List<Integer>> finalRows=sourceRows();writeRows("mysql-final.tsv",finalRows);
        assertThat(COMPLETED).doesNotContain(failedCheckpoint);
        if(post) {assertThat(SPLITS).hasSize(2); assertThat(restoredReaderSplits).anyMatch(MySqlSplit::isBinlogSplit);}
        else {assertThat(SPLITS).hasSize(3);assertThat(SPLITS.get(2).getSplitStart()).containsExactly(105);}
        checkpoint();reconcile(finalRows,"after-recovery-checkpoint");
        checkpoint();reconcile(finalRows,"after-extra-checkpoint");
        job.cancel().get(30,TimeUnit.SECONDS);job=null;
        reconcile(finalRows,"after-cancel"); auditRawBinlog();
        Files.writeString(out.resolve("result.txt"),"mode="+(move?"move":"control")+"-"+(post?"post":"pre")+
                "\nassertions=PASS\ndata_equal="+!move+"\nsource_rows=20\nsink_rows="+(move?19:20)+
                "\nmissing_keys="+(move?"[102]":"[]")+"\ncompleted="+COMPLETED+
                "\nrestored_checkpoint="+restoredCheckpoint+"\nrestored_observer_count="+restoredCount+
                "\nfailed_checkpoint="+failedCheckpoint+"\nattempt="+attempt+"\nsnapshot_executions="+SPLITS.size()+"\n");
        trace("CHARACTERIZATION_ASSERTIONS_PASS data_equal="+!move);
    }
    static void gate(CountDownLatch latch,String label) throws SQLException {
        try { if(!latch.await(90,TimeUnit.SECONDS))throw new SQLException("gate timeout "+label); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}
    }
    static void auditRawBinlog() throws Exception {
        MySqlSourceConfig auditConfig=new org.apache.flink.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory()
                .hostname(config.getHostname()).port(config.getPort()).username(config.getUsername()).password(config.getPassword())
                .databaseList(DB).tableList(DB+".items").serverId("8800-8810").createConfig(0);
        TableId table=new TableId(DB,null,"items");
        Map<TableId,TableChange> schemas;
        try(MySqlConnection jdbc=DebeziumUtils.createMySqlConnection(auditConfig);MySqlSchema schema=new MySqlSchema(auditConfig,true)) {
            schemas=Collections.singletonMap(table,schema.getTableSchema(new MySqlPartition(auditConfig.getMySqlConnectorConfig().getLogicalName()),jdbc,table));
        }
        List<SourceRecord> records=new ArrayList<>();
        BinlogSplitReader reader=new BinlogSplitReader(auditConfig,0);
        try {
            reader.submitSplit(new MySqlBinlogSplit("binlog-split",ha,hb,Collections.emptyList(),schemas,0));
            Iterator<SourceRecords> batch;
            while((batch=reader.pollSplitRecords())!=null) { while(batch.hasNext()) {records.addAll(batch.next().getSourceRecordList());} }
        } finally {reader.close();}
        SourceRecord update=records.stream().filter(RecordUtils::isDataChangeRecord).filter(r->((Struct)r.key()).getInt32("id")==102).findFirst().orElseThrow();
        Struct v=(Struct)update.value();BinlogOffset e=RecordUtils.getBinlogPosition(update);
        assertThat(v.getStruct("before").getInt32("c")).isEqualTo(110);
        assertThat(v.getStruct("after").getInt32("c")).isEqualTo(move?100:110);
        assertThat(e.isAfter(ha)).isTrue();assertThat(e.isBefore(lb)).isTrue();
        trace("BOUNDARY_ASSERTIONS_PASS H_A="+ha+" E_102="+e+" L_B="+lb+" H_B="+hb);
        for(SourceRecord r:records) {if(RecordUtils.isDataChangeRecord(r))trace("AUDIT_RAW "+r);}
        assertThat(EVENTS.stream().filter(x->x.startsWith("UPDATE:102:")).count()).isZero();
    }
    static void checkpoint() throws Exception {
        int before=COMPLETED.size();
        cluster.getMiniCluster().triggerCheckpoint(job.getJobID()).get(60,TimeUnit.SECONDS);
        await(()->COMPLETED.size()>before,"checkpoint callback");
        trace("GLOBAL_CHECKPOINT_FUTURE_COMPLETED callbacks="+COMPLETED);
    }
    static void await(BooleanSupplier p,String label) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(!p.getAsBoolean() && System.nanoTime()<deadline) {Thread.sleep(30);}
        assertThat(p.getAsBoolean()).as(label+" events="+EVENTS+" job="+job.getJobStatus().get()).isTrue();
    }
    static void sql(String text) throws SQLException {try(Statement s=mysql.createStatement()){s.execute(text);}}
    static void trace(String s) {TIMELINE.add(s);System.out.println("CHUNK_E2E "+s);}
    static Map<Integer,List<Integer>> sourceRows() throws Exception {
        Map<Integer,List<Integer>> rows=new TreeMap<>();
        try(Statement s=mysql.createStatement();ResultSet r=s.executeQuery("SELECT id,c,amount FROM "+DB+".items ORDER BY id")) {
            assertThat(Arrays.asList(r.getMetaData().getColumnLabel(1),r.getMetaData().getColumnLabel(2),r.getMetaData().getColumnLabel(3))).containsExactly("id","c","amount");
            while(r.next()){List<Integer> v=Arrays.asList(r.getInt(1),r.getInt(2),r.getInt(3));assertThat(rows.put(v.get(0),v)).isNull();}
        } return rows;
    }
    static void writeRows(String name,Map<Integer,List<Integer>> rows) throws Exception {
        List<String> lines=new ArrayList<>();lines.add("id\tc\tamount");
        for(List<Integer> v:rows.values()){lines.add(v.get(0)+"\t"+v.get(1)+"\t"+v.get(2));} Files.write(out.resolve(name),lines);
    }
    static void reconcile(Map<Integer,List<Integer>> source,String label) throws Exception {
        Map<Integer,List<Integer>> target=new TreeMap<>();long snapshot;
        try(Catalog cat=FlinkCatalogFactory.createPaimonCatalog(options)) {
            FileStoreTable table=(FileStoreTable)cat.getTable(new Identifier(DB,"items"));
            assertThat(table.rowType().getFieldNames()).containsExactly("id","c","amount");
            snapshot=table.snapshotManager().latestSnapshotId();
            ReadBuilder b=table.newReadBuilder();
            try(RecordReader<InternalRow> rr=b.newRead().createReader(b.newScan().plan())) {
                RecordReader.RecordIterator<InternalRow> batch;
                while((batch=rr.readBatch())!=null) { InternalRow row;
                    while((row=batch.next())!=null) {
                        List<Integer> v=new ArrayList<>();for(int i=0;i<3;i++){v.add(row.isNullAt(i)?null:row.getInt(i));}
                        assertThat(target.put(v.get(0),v)).as("duplicate sink primary key").isNull();
                    } batch.releaseBatch();
                }
            }
        }
        Set<Integer> missing=new TreeSet<>(source.keySet());missing.removeAll(target.keySet());
        Set<Integer> extra=new TreeSet<>(target.keySet());extra.removeAll(source.keySet());
        List<String> differences=new ArrayList<>();
        for(Integer id:target.keySet()) {if(source.containsKey(id)&&!source.get(id).equals(target.get(id)))differences.add(id+":"+source.get(id)+" != "+target.get(id));}
        writeRows(label+"-paimon.tsv",target);
        String report="phase="+label+"\nsnapshot="+snapshot+"\ncolumns=[id,c,amount]\nsource_rows="+source.size()+"\ntarget_rows="+target.size()+"\nmissing="+missing+"\nextra="+extra+"\nfield_differences="+differences+"\ndata_equal="+source.equals(target)+"\n";
        Files.writeString(out.resolve(label+"-reconciliation.txt"),report);System.out.println(report);
        assertThat(extra).isEmpty();assertThat(differences).isEmpty();assertThat(source).hasSize(20);
        if(move){assertThat(missing).containsExactly(102);assertThat(target).hasSize(19);}else{assertThat(target).isEqualTo(source);}
    }
    public static class Observe extends RichMapFunction<Event,Event> implements CheckpointListener, CheckpointedFunction {
        private transient ListState<Integer> state;
        private int count;
        @Override public void open(org.apache.flink.configuration.Configuration c){attempt=getRuntimeContext().getAttemptNumber();trace("TASK_OPEN attempt="+attempt);}
        @Override public void snapshotState(FunctionSnapshotContext c)throws Exception{state.update(Collections.singletonList(count));trace("OBSERVER_STATE checkpoint="+c.getCheckpointId()+" count="+count);}
        @Override public void initializeState(FunctionInitializationContext c)throws Exception{
            state=c.getOperatorStateStore().getListState(new ListStateDescriptor<>("count",Integer.class));
            if(c.isRestored()){for(int n:state.get())count=n;restoredCount=count;restoredCheckpoint=c.getRestoredCheckpointId().getAsLong();trace("RESTORED checkpoint="+restoredCheckpoint+" count="+count);}
        }
        @Override public Event map(Event e) {
            if(e instanceof DataChangeEvent){count++;DataChangeEvent d=(DataChangeEvent)e;
                org.apache.flink.cdc.common.data.RecordData r=d.after()==null?d.before():d.after();
                EVENTS.add(d.op()+":"+r.getInt(0)+":"+r.getInt(1)+":"+r.getInt(2));
            }else EVENTS.add(e.toString());return e;
        }
        @Override public void notifyCheckpointComplete(long id){COMPLETED.add(id);trace("CHECKPOINT_CALLBACK "+id);}
    }
    static class AlignedSink implements DataSink {
        final DataSink delegate;final Options catalog;
        AlignedSink(DataSink d,Options o){delegate=d;catalog=o;}
        public EventSinkProvider getEventSinkProvider(){return FlinkSinkProvider.of(new ObservedPaimonSink(catalog));}
        public MetadataApplier getMetadataApplier(){return delegate.getMetadataApplier();}
    }

    public static class ObservedPaimonSink extends PaimonEventSink {
        ObservedPaimonSink(Options o){super(o,"chunk-e2e",new PaimonRecordEventSerializer(ZoneId.of("UTC"),o),UID,ZoneId.of("UTC"));}
        @Override public DataStream<CommittableMessage<MultiTableCommittable>> addPreCommitTopology(DataStream<CommittableMessage<MultiTableCommittable>> in){
            DataStream<CommittableMessage<MultiTableCommittable>> s=super.addPreCommitTopology(in);
            return s.transform("fault after real precommit",s.getType(),new FaultProbe()).setParallelism(1);
        }
    }
    public static class FaultProbe extends AbstractStreamOperator<CommittableMessage<MultiTableCommittable>> implements OneInputStreamOperator<CommittableMessage<MultiTableCommittable>,CommittableMessage<MultiTableCommittable>> {
        @Override public void processElement(StreamRecord<CommittableMessage<MultiTableCommittable>> r){output.collect(r);}
        @Override public void snapshotState(StateSnapshotContext c)throws Exception{
            super.snapshotState(c);
            if(failOnce.compareAndSet(true,false)){
                failedCheckpoint=c.getCheckpointId();trace("FAULT_HELD checkpoint="+failedCheckpoint);faultReached.countDown();
                gate(faultRelease,"external publication checked");
                throw new RuntimeException("CHUNK_RECOVERY intentional task failure after Paimon publication before global checkpoint completion");
            }
        }
    }
    /** Delegates all Source operations; records the real Reader state without replacing it. */
    public static class ObservedSource implements Source<Event,MySqlSplit,PendingSplitsState> {
        final MySqlSource<Event> delegate;
        ObservedSource(MySqlSource<Event> d){delegate=d;}
        public Boundedness getBoundedness(){return delegate.getBoundedness();}
        public SimpleVersionedSerializer<MySqlSplit> getSplitSerializer(){return delegate.getSplitSerializer();}
        public SimpleVersionedSerializer<PendingSplitsState> getEnumeratorCheckpointSerializer(){return delegate.getEnumeratorCheckpointSerializer();}
        public SplitEnumerator<MySqlSplit,PendingSplitsState> createEnumerator(SplitEnumeratorContext<MySqlSplit> c){return delegate.createEnumerator(c);}
        public SplitEnumerator<MySqlSplit,PendingSplitsState> restoreEnumerator(SplitEnumeratorContext<MySqlSplit> c,PendingSplitsState s){trace("RESTORE_ENUMERATOR "+s);return delegate.restoreEnumerator(c,s);}
        public SourceReader<Event,MySqlSplit> createReader(SourceReaderContext c)throws Exception{
            SourceReader<Event,MySqlSplit> d=delegate.createReader(c);
            return new SourceReader<Event,MySqlSplit>() {
                public void start(){d.start();}
                public InputStatus pollNext(ReaderOutput<Event> o)throws Exception{return d.pollNext(o);}
                public List<MySqlSplit> snapshotState(long id){List<MySqlSplit> s=d.snapshotState(id);savedReaderStates.put(id,new ArrayList<>(s));trace("READER_STATE checkpoint="+id+" splits="+s);return s;}
                public CompletableFuture<Void> isAvailable(){return d.isAvailable();}
                public void addSplits(List<MySqlSplit> s){trace("READER_ADD attempt="+attempt+" splits="+s);if(restoredCheckpoint>=0)restoredReaderSplits.addAll(s);d.addSplits(s);}
                public void notifyNoMoreSplits(){d.notifyNoMoreSplits();}
                public void handleSourceEvents(SourceEvent e){d.handleSourceEvents(e);}
                public void notifyCheckpointComplete(long id)throws Exception{d.notifyCheckpointComplete(id);}
                public void notifyCheckpointAborted(long id)throws Exception{d.notifyCheckpointAborted(id);}
                public void close()throws Exception{d.close();}
            };
        }
    }
}
