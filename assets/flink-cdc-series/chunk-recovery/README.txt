跨 Chunk 迁移的 Checkpoint 恢复：真实 MySQL → Flink → Paimon

结果（最终运行 final/ 下四组）：
- move-pre：A 已输出、B 未 LOW、迁移前完成 C1；C2 外部发布后失败；实际恢复 C1，只重扫 B。恢复后 C3/C4 完成；MySQL 20 行，Paimon 19 行，缺 (102,100,20)。
- move-post：相同 C1 和迁移；binlog 已追赶 amount=32 后完成 C2；C3 外部发布后失败；实际恢复 C2 binlog Split，不重扫 A/B；C4/C5 完成，仍缺上述行。
- control-pre/control-post：仅将 id102 amount20→21，c 保持110；对应两个恢复路径，均为20行×3列一致。
- 四个行为刻画断言通过、进程退出0；仅两组对照 data_equal=true，迁移两组为false。不是连接器修复。

版本与链路：见 environment.json、dependencies.txt 和 execution.log 的 LOADED。
CDC release-3.7.0 固定提交045e8da45df4b1017849b04e2d8bfe8f0ed7936d，Flink1.20.3，Paimon2.0.0，Java11.0.29，MySQL8.4.11 ROW/FULL GTID ON。
真实 Source Factory/translator、MySqlSource、Splitter/Assigner、Readers、SchemaOperator、分区、BucketAssign、PaimonWriter、真实precommit。MiniCluster一个TM、8slots、p=1；filesystem、bucket=1、EVOLVE、input changelog配置、8mb writer buffer，默认回填。仅检查当前表，没有扫描这四组input changelog。

固定时序及证据：
1. 初始20行c95..114；ANALYZE TABLE；chunk.size=10、chunk key显式设c；生产Splitter实际生成A<105、B>=105，断言边界与原始两次扫描。
2. 现有SnapshotPhaseHooks阻塞B pre-LOW，等A的10行输出并完成C1。Reader状态实际仅B，HIGH=null，源id102仍为(102,110,20)。
3. 释放B，迁移c110→100（对照只改amount21），再更新id101 amount11、id103 amount31；均为独立提交。生产LOW/HIGH日志与hook逐项匹配。独立server-id8800-8810原始binlog审计确认H_A<E_102<L_B==H_B；主作业server-id8700-8710。原始B和恢复B水位分别保存，未混用。
4. 两组都等Source输出id101追赶和id103 amount32；pre组不再完成Checkpoint，post组完成C2。p=1真实分配器允许不等待额外完成Checkpoint即切入binlog。
5. 下一Checkpoint在真实precommit下游的snapshotState阻塞。闸门到达不保证上游已发布：另开Catalog轮询真实snapshot.commitIdentifier等于该Checkpoint，再确认全局Future仍未完成且无完成回调，并逐字段对账。随后释放闸门抛RuntimeException，由Flink自动重启task一次。
6. Flink初始化上下文的restoredCheckpointId精确为C1/C2；观察算子恢复的受管count为10或21/22（事件数，非行数）；实际Reader.addSplits首项的toString范围/位点与所选Reader.snapshotState相同。未声称比较完整Checkpoint文件或所有内部字段。恢复C1只新增B一次扫描；C2无新增快照扫描。
7. pre组等恢复B的HIGH固定后，再写id103 amount33；post组恢复后也写33；两组等待对应UPDATE输出。随后冻结源表，恢复后两个完成Checkpoint、取消作业后、新JVM分别全量读取真实Paimon。

全量核对：
每组五时点：published-before-global-failure、after-recovery-checkpoint、after-extra-checkpoint、after-cancel、fresh-jvm。
第一个时点对照mysql-before-failure.tsv（id103 amount32），后四个对照mysql-final.tsv（amount33）。
检查列名/顺序、全部主键、所有id/c/amount、重复键、缺失/多余行及公共键字段差异；不是count或关键行抽样。Java使用独立新Catalog全表scan，新JVM在实验JVM退出后另起，只读取warehouse与冻结源TSV。collect-evidence.py再独立解析20组TSV对比。
迁移组五时点均只缺id102，其余19×3一致；对照20×3全部一致。

如何复现：
准备上述固定源码及现有依赖并编译生产模块；目标classes应置于Paimon fat JAR之前，避免其携带的CDC/Hikari类遮蔽。
dependencies.txt是本次实际classpath的规范化路径清单；将相应target/classes与本地Maven依赖按原顺序组为classpath.txt。不是可直接使用的机器路径。
启动独立MySQL容器，参考docker-command.json；仅重建cdc_chunk_e2e.items，不要指向真实业务库。启用mysql_native_password并将实验root@%设为该认证方式；演示密码仅用于临时本地容器。
创建classes目录；Java11运行：
  javac -cp "$(cat classpath.txt)" -d classes ChunkRecoveryJob.java
  java -Xmx2g -Dlog4j.configurationFile=log4j2.properties -cp "classes:$(cat classpath.txt)" article2recovery.ChunkRecoveryJob OUTPUT MYSQL_PORT MODE
MODE为move-pre、move-post、control-pre、control-post；OUTPUT先创建空目录，各组单独warehouse，顺序执行避免共享实验表冲突。
进程退出后同一命令最后加readback，在新JVM读取该OUTPUT持久化数据。公开run.py自动建目录并保存日志，需调整Java路径与探针源码路径。实际命令本地保留，公开路径归一化为/work和/home/author。

观察与装配边界：
ObservedSource逐项透传真实SourceReader操作，仅记录snapshotState和addSplits；Observe保存计数并原样返回Event。没有模拟Source事件或替换过滤器。
Sink沿用既有Schema UID对齐，再在真实precommit下游增加故障算子；没有绕过真实flush、commit或Catalog。
生产1193个Java文件与历史固定源码哈希比较无变化；既有target/classes未重编译，关键实际加载路径可查。实验为main+AssertJ，非上游JUnit或完整Maven测试套件。
轮询只等待明确可观察条件，迁移和故障顺序由latch/Checkpoint Future固定。不是靠sleep估算迁移时间。

过程记录：
首次试跑在fault闸门到达后立即全量读取，读到旧snapshot的id101 amount10；不是漏行场景的新故障。改为等待实际snapshot.commitIdentifier后四组通过；再明确恢复B HIGH闸门、恢复Split对照、故障前源数据文件，最终重跑四组及新JVM。正文只用final目录的结果。重复运行不增加场景数。
原始试跑/编译/classpath/完整warehouse保留本地。公开文件仅规范化机器路径和尾随空白，未改结果；manifest保留原始与公开SHA256。

结论范围：
A已完成进入C1时，从这个迁移前恢复点只重扫B，缺行仍在；C2恢复binlog也未补回。不是所有迁移前Checkpoint的通用结论。
未验证A尚未完成的更早Checkpoint、全量重建、后续再更新id102、反向迁移、PK变更、p>1、DDL、物理JM/TM/HA、其他存储和本组changelog。
未重跑全部历史Reader/组件测试。第二、三、五篇共用这4个新场景，不与原Reader或无故障作业重复计数。
