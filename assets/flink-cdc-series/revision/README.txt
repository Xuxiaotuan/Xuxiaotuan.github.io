公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# Flink CDC 系列审校补充证据（2026-10-18）

本目录只记录本轮修订和新增实验。原第一至第五篇证据目录保持不变；旧测试数不代表本轮重新执行。

## 修订范围

1. 第四篇：FlushSuccessEvent 只确认具体 flush 调用返回，不能普遍证明旧缓冲排空或数据提交。
2. 第四篇：MySqlStreamingChangeEventSource 运行时来自 CDC MySQL 模块的同包改写类，不能只引用 Debezium 原版解释该路径。
3. 第五篇：Checkpoint 图改为先广播 Barrier，再进行状态快照；上游提交和下游探针阻塞是并行分支，以独立查询确认可见性。
4. 新增旧 Schema writer 缓冲与 ADD COLUMN 的正常/故障实验；重新执行原 Schema 作业，增加全量逐字段对账。
5. 第四、五篇补入实际结果，第五篇增加全系列验证范围表。

## 版本与环境

- CDC release-3.7.0：045e8da45df4b1017849b04e2d8bfe8f0ed7936d。
- Flink 1.20.3，Debezium 1.9.8.Final（含 CDC 改写类），Java 11.0.29。
- Paimon 2.0.0：604e6d5e131c74a8d127333a2a3ad6d0319732bf。
- MySQL 实际查询：8.4.11、ROW、FULL、GTID ON。
- 独立临时容器 cdc-article5-buffer-20261018，仅绑定 localhost:32781；容器、镜像标识见 mysql-container.json、mysql-image.json。
- 固定镜像 digest：mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70。
- MiniCluster，parallelism=1、bucket=1、filesystem Catalog、EVOLVE、changelog-producer=input、write-buffer-size=8mb。
- 故障是同 JVM 内 Task 异常/自动重启，不是物理 JM/TM kill。数据通过实际 MySqlSource、Schema 处理、Paimon writer、提交路径进入本地 Paimon 文件。

source-verification.json 核对五个关键 CDC 生产源文件与固定 upstream 提交一致；未改动生产源码。运行日志记录实际类加载位置。

## 探针怎样观测旧缓冲

tests/ArticlePaimonBufferProbe.java 继承原第五篇 harness。tests/ArticlePaimonExperiment.java 仅增加两个条件接入点，差异见 harness-change.diff。保留原 UID 对齐、真实连接器和手动 Checkpoint 驱动条件。

BufferWriter 覆写 write/flush 后调用原 super 实现，只额外观测；通过只读反射定位真实 MergeTreeWriter.writeBuffer，并调用 WriteBuffer.size()。本轮实际实现为 SortBufferWriteBuffer。它不是用上游记录数猜测 writer 缓冲，也没有用替代算法实现提交或 replace。

MetadataApplier 包装器每次先执行真实 delegate。第一次 ADD 返回后用 latch 固定窗口，让独立 Catalog 查询与 writer 缓冲观测完成；随后正常返回，或在返回 Coordinator 之前抛错。第一次 ADD 时，旧数据已经写入 writer，而该 DDL 后没有新完成的 Checkpoint。

四条内部记录来自一次 UPDATE 的 before/after、一条 INSERT、一条 DELETE，不能称作四行业务数据。

初版观察钩子只识别 AddColumnEvent，未记录 writer 实际收到的物理 CreateTableEvent；初版两次运行的缓冲窗口与最终对账已经通过。随后按固定源码的 BucketAssignOperator 路径修正观察钩子，加入 Schema 刷新前后 4→0 的断言并重跑两场景。保留 probe-before-schema-event-observation.java、probe-observation-refinement.diff 和首轮 runs/buffer-normal、runs/buffer-failure；它们不计为额外场景。下表引用最终重跑。

## 实际执行结果

| 场景 | 证据目录 | 实际结果 | 结论范围 |
| --- | --- | --- | --- |
| 原 Schema 作业重新执行并全量对账 | runs/schema | exit 0；C3 后 ADD flag 成功再抛错，attempt=1 重放，真实 flag 应用入口两次；C5 后取消作业，用新 Catalog 读回；10 行、10 个唯一主键、4 列全部一致，含 NULL | 当前表状态；所测两次 ADD 和一处故障 |
| 旧布局缓冲下正常 ADD | runs/buffer-normal-final | exit 0；DDL 前及 flush 后均 4 条；真实 ADD 后仍 4 条且 writer 为两列；收到物理 Schema 事件后 4→0、writer 三列；C3 后及取消后分别对账，10 行 × 3 列一致 | 所测正常状态移交；缓冲清空自身不证明发布 |
| 旧布局缓冲下 ADD 成功后故障 | runs/buffer-failure-final | exit 0；同样固定旧布局 4 条窗口后抛错；从 C2 恢复至 attempt=1；真实 DDL 入口两次；旧 DML 重放按三列物理结构转换；Schema 刷新 4→0；C3 后及取消后分别对账，10 行 × 3 列一致 | 所测重放和幂等 ADD；不代表恢复了故障前的内存缓冲 |

结果索引见 result-summary.json。每次运行保存 command.json、process-result.json、execution.log、buffer-observations.txt、执行计划、各阶段查询/Checkpoint 信息、Paimon warehouse。最终缓冲两次运行的基线为 C2/snapshot-2，最终为 C3/snapshot-3；Checkpoint 编号是该次运行的标识，不能当作协议常量。

Schema 作业最终字段顺序为 [id,note,flag,amount]：
- id=101 → [101,blue,yes,30]；
- id=201 → [201,green,ok,40]；
- id=103..110 → [id,NULL,NULL,10]；
- id=102 不存在。

缓冲两个场景最终字段顺序为 [id,flag,amount]：
- id=101 → [101,blue,20]；
- id=103 → [103,NULL,13]；
- id=202 → [202,NULL,22]；
- id=104..110 → [id,NULL,10]；
- id=102 不存在。

对账检查字段名/顺序、全部行、全部所测类型值（INT、VARCHAR）和 NULL，并在构造按主键索引时显式检查重复键。不是只比较 count、关键行或哈希；也不代表验证了全部 MySQL/Paimon 类型映射。原 Schema 实例已经删除，本次是同场景重跑，不能追溯宣称旧运行已完成此前缺失的核对。

## 源码解释与实测的界线

- 源码：PaimonWriter.flush 为空；replace 提取待提交状态、关闭旧 writer、以新表结构恢复。实测：flush 前后真实缓冲同为 4，物理 Schema 更新后为 0。最终完成 Checkpoint 后独立读回才证明本次目标数据可见且对账一致。
- 源码：BucketAssignOperator.convertSchemaChangeEvent 使用目标 physicalSchema，向 writer 发出 CreateTableEvent；convertDataChangeEvent 在恢复时可引导物理 Schema 并转换字段。实测：正常路径两列切三列；故障恢复先建立三列 writer，再重放旧 DML，新增 flag 为 NULL。
- 故障旧 writer 的内存缓冲不是被 Checkpoint 恢复；本窗口从旧完成 Checkpoint 重读 binlog，重新产生记录。
- 本轮没有重新扫描新增场景的 changelog；不能从最终主键表正确推断变更历史只出现一次。原第五篇 snapshot-3/4 重复 changelog 是另一故障场景的既有证据。

## 复现入口

这份 harness 使用本机已构建固定版本 classpath，classpath.txt 保留绝对路径；不是可搬迁的独立发行包。需恢复这些构建产物/依赖，或按固定版本重建并更新 classpath。compile-command.json 是实际 javac 参数；compile-final.log 记录观察钩子修订后的成功编译。

run-probes.py 已实际执行，不是只跑过 --help。它会重置隔离数据库 cdc_article5，禁止指向业务数据库。只对上面专用临时 MySQL 使用；root 采用实验口令 article5-local-only。

在 Playground 工作区执行（重跑时必须使用新的 output 目录）：

```sh
python3 outputs/flink-cdc-series-revision-evidence-20261018/run-probes.py --mysql-port 32781 --mode schema --output outputs/flink-cdc-series-revision-evidence-20261018/runs/schema
python3 outputs/flink-cdc-series-revision-evidence-20261018/run-probes.py --mysql-port 32781 --mode buffer-normal --output outputs/flink-cdc-series-revision-evidence-20261018/runs/buffer-normal-final
python3 outputs/flink-cdc-series-revision-evidence-20261018/run-probes.py --mysql-port 32781 --mode buffer-failure --output outputs/flink-cdc-series-revision-evidence-20261018/runs/buffer-failure-final
```

以上是历史实际命令，已有目录不能覆盖。复现需重新创建临时 MySQL；启动参数保存在 mysql-container.json，初始化演示用户为 mysql_native_password。临时库不作为长期证据，原始源端完整查询结果已经保存；Paimon warehouse 保留供独立读回。

## 未验证边界（evidence_incomplete）

并行多 Chunk / 异构 HIGH 与真实 Sink 故障组合、事务内部 event/row Checkpoint、快照阶段并发 DDL、RENAME 或多个目标操作部分成功后的全链路恢复、多表原子性、物理进程/HA 故障、S3/HDFS/Hive、非主键/其他 merge engine、扩缩容与版本迁移、CLI 发行包部署。这些不因本轮 PASS 而变为已验证。

本轮未重跑前五篇全部既有测试；第五篇原 43 个组件测试、2 个真实作业、6 组 Catalog 检查仍作为历史证据。新增覆盖是两个缓冲场景，以及一个既有 Schema 场景的更完整重跑，不能把重复执行累计成新增独立覆盖，也不能把各层证据相加成普遍端到端保证。
