# 跨 Chunk 修改键：真实 MySQL → Flink 作业 → Paimon（2026-10-18）

## 结果先读

这是新增的两个完整作业场景，不是把原 Reader 的 Map 重建改名为端到端。MySQL 为独立真实容器；Flink 为同 JVM MiniCluster；Source、分配器、SchemaOperator、Paimon BucketAssign、writer 与 precommit 都执行生产实现；目标为真实 filesystem Paimon 文件。没有修改连接器生产源码，也没有修复漏行。

迁移组：源 20 行，Paimon 当前表 19 行，缺少 id=102；所有存在行的三列值一致，无多余行或重复主键。稳定键对照：20 行 × 3 列完全一致。两组 Java 断言均成功，但迁移组明确断言数据不一致，不能把 assertion PASS 写成同步正确。

两组都在 C2 后、额外完成 C3 后、取消作业后、新 JVM 独立回读四个阶段全量核对。最终回读 snapshot-3，迁移缺失仍在。C1/C2/C3 是这两次运行的编号。snapshot 元数据的 totalRecordCount 是存储记录统计，不是合并查询的业务行数；当前表数量来自独立完整扫描。

## 固定版本和实际范围

CDC release-3.7.0 / 045e8da45df4b1017849b04e2d8bfe8f0ed7936d，Flink 1.20.3，Paimon 2.0.0，Java 11.0.29，MySQL 8.4.11，ROW/FULL、GTID ON、REPEATABLE-READ。Paimon filesystem Catalog、bucket=1、EVOLVE、input changelog、8mb 写缓冲。1 个 TaskManager、8 slots、parallelism=1，noRestart。沿用第五篇构建产物优先的 classpath 和 Schema UID 对齐方式；并非 CLI YAML 发行包部署。

本轮核对 1193 个 CDC 生产 Java 源文件前后哈希不变，5 个关键 Paimon/MySQL 文件与历史固定提交指纹相同。实际类加载位置在日志中。未重建生产类、未重跑历史 Reader/组件/Schema/故障测试。

## 分片和水位如何固定

表 cdc_chunk_e2e.items(id INT PRIMARY KEY,c INT NOT NULL,amount INT NOT NULL)。初始 c=95..114 共 20 行，id=101 对应 c100、id=102 对应 c110、id=103 对应 c114，其余 id=200+c。完整行保存在 mysql-initial.tsv。

与原 Reader 的三行输入不同，这里用稠密 20 行、ANALYZE TABLE、chunk.size=10 和非主键 c 分片，让生产 MySqlChunkSplitter / Assigner 真正生成并分配两个 Chunk。日志实测 approximateRowCnt=20、distributionFactor=1、dynamicChunkSize=10。钩子必须断言 A=(-∞,105)、B=[105,+∞)，出现其他范围或多余 Chunk 会失败；不是接受任意分片后事后解释。

使用生产 MySqlSource.setSnapshotHooks 的现有测试入口：A 的 post-HIGH 只观察日志位置；到 B 的 pre-LOW 时，断言 A HIGH 已观测，再同步提交下列三条 DML。p=1 的 Reader 已结束 A 才开始 B。没有 sleep 选择写入时机；等待循环仅用于等待可观察事件完成。

迁移：UPDATE items SET c=100 WHERE id=102。
对照：UPDATE items SET amount=21 WHERE id=102（c 仍为 110）。
随后两组均：UPDATE items SET amount=11 WHERE id=101；UPDATE items SET amount=31 WHERE id=103。
三条语句分别 autocommit；B LOW/HIGH 期间没有其他写入。

生产 MySqlSnapshotSplitReadTask 的实际 LOW/HIGH 日志与各 post-hook 查询逐项一致（watermark-verification.json）。作业取消后使用独立 server-id 的无快照 metadata BinlogSplitReader 审计 H_A..H_B，真实 id102 UPDATE 的 before.c=110、after.c=100/110，并用完整 BinlogOffset 断言 H_A < E_102 < L_B == H_B。这是事后日志审计，输出不会注入作业或 Sink。

两组 Source 输出都没有 UPDATE:102；迁移组快照也没有 INSERT:102，对照组有 INSERT:102:110:21。两组均输出 A 的 UPDATE:101:100:11。再写 id103 amount=32 并等 Source 实际收到，确认持续 binlog 已推进到 B HIGH 之后；此后不再写入，完成 C2 后对账。额外 C3 仍缺行，排除了所测更新只在等待下一次正常提交的解释。全局 Checkpoint 成功本身不校验源目标业务行集合。

## 全量逐字段对账

源端通过 JDBC SELECT id,c,amount ORDER BY id，目标通过全新 Paimon Catalog、newReadBuilder/newScan/newRead 全量读取。检查字段名/顺序、逐行三列值、主键重复、缺失/多余主键和公共主键字段差异。源/目标完整 TSV 与 reconciliation.txt 均保留；不是仅比 count、抽样或摘要哈希。

fresh-jvm 阶段在实验 JVM 完全退出后另起 Java 进程；仅读取 Paimon warehouse，与此前冻结且完整保存的 MySQL 结果逐字段比较，不重新连接已重置的源表。这证明文件持久化回读的结果，不是 writer 内存缓存结果。被测列均为非 NULL INT，本轮不新增 NULL、其他类型或 Schema 演进覆盖。

## 源码解释和未验证边界

已有源码推演和 Reader 实验表明：UPDATE 用 before.c=110 判定属于 B；事件不晚于 H_B，被重叠过滤；A 扫描早于迁入、B 扫描晚于迁出。新增完整作业实际观察到 id102 没有进入 Source 输出，目标在成功提交后也缺失。Paimon 主键合并和正常 Checkpoint 不会凭空补回未收到的行；本次没有把这种解释扩展成所有 Sink/所有输入的证明。

仍未测：p>1 并发分片，A→B 等其他方向，修改主键，更多 Chunk，缺失行后续更新，Checkpoint 失败及恢复、物理 JM/TM kill、DDL 并发、事务内部恢复、HA、S3/HDFS、其他 merge engine、无主键表、日志清理、版本迁移。开启 input changelog 不等于本轮独立扫描了 changelog；本轮只对账当前表。原 Reader 实验、原第五篇故障恢复实验、新增无故障完整作业分别计数。

## 复现与文件

ChunkMovementJob.java 为实际编译执行的独立 main 探针，使用 AssertJ 断言；不是新上游 JUnit 测试。需要同版本 CDC 构建目录优先于 Paimon 大包的 classpath，以及 Flink MiniCluster 测试依赖。完整 classpath 和运行命令保存在作者本地；公开 dependencies.txt 列出归一后的依赖和目录，需按本机重建 classpath。公开材料不是可直接搬迁的一键复现包。

专用容器使用 mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70，临时口令 article2-movement-local-only，仅 localhost:32783，server-id=8675321，ROW/FULL、GTID ON；主作业复制 server-id=8700-8710，事后审计=8800-8810。容器参数与环境另存 environment.json。会重建隔离库 cdc_chunk_e2e.items，禁止指向业务数据库。

配置等效于：
  source.type=mysql; tables=cdc_chunk_e2e.items
  scan.incremental.snapshot.chunk.key-column=cdc_chunk_e2e.items:c
  scan.incremental.snapshot.chunk.size=10
  parallelism=1; schema.change.behavior=evolve
  sink.type=paimon; catalog.properties.metastore=filesystem
  table.properties.bucket=1; table.properties.changelog-producer=input

实际调用方式（JDK 11；CP 为已恢复的固定版本 classpath；每组使用新目录）：
  javac -cp "$CP" -d classes ChunkMovementJob.java
  java -Xmx2g -Dlog4j.configurationFile=log4j2.properties -cp "classes:$CP" article2e2e.ChunkMovementJob /absolute/new-output 32783 move
  java -Xmx2g -Dlog4j.configurationFile=log4j2.properties -cp "classes:$CP" article2e2e.ChunkMovementJob /absolute/new-output 32783 move readback
  对照用 control 替换 move，并换另一新目录。readback 读取已有目录，不启动作业。

公开每组包含 execution.log（归一机器路径的完整最终执行日志）、源端初始/最终 TSV、全新 JVM 目标 TSV、四阶段 reconciliation.txt、source-events.txt、timeline.txt、生产水位核对、snapshot 元数据摘要、执行计划。完整 warehouse、原始日志、命令与 classpath 保留本地。result-summary.json 中 assertions 与 data_equal 分开。

## 实验装配修订

首个编译命令的 SourceRecords import 路径错误，修正为 source.split 后编译成功。首轮迁移作业已复现 20→19，但作业取消请求后的事后审计复用了 server-id，导致尚在关闭的作业 reader 收到 1236 冲突；此干扰发生在既有对账之后。随后审计使用独立 server-id、开启生产水位 INFO 日志，重新执行迁移组和对照组；最终两组均无 ERROR 日志。首轮单独保存，不作为新增场景或故障恢复证据。

尝试直接使用旧 google-java-format 1.7 时，依次遇到 Guava 和 javac-shaded 依赖不匹配，未完成格式化；停止该可选步骤，没有将退出码误写成格式检查通过。最终使用成功编译的同一探针源码运行两组场景。没有以此声称上游全套风格/构建验收通过。
