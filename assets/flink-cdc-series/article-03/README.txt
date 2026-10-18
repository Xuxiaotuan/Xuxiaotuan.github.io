公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# Flink CDC 第三篇证据

日期：2026-10-18。研究基线：Apache Flink CDC release-3.7.0，提交 `045e8da45df4b1017849b04e2d8bfe8f0ed7936d`；运行依赖 Flink 1.20.3、Debezium 1.9.8.Final；Java 11.0.29；MySQL 8.4.11，ROW/FULL、GTID 开启。

## 本轮实际执行

| 证据 | 数量 | 结果 | 范围 |
| --- | ---: | --- | --- |
| ArticleSourceProtocolTest | 5 | PASS | 真实 Assigner、序列化器与 Flink SplitAssignmentTracker 对象的受控调用 |
| ArticleReaderAckTest | 1 | PASS | 真实 Reader 的 finished-unacked 状态序列化、恢复、重报及 ACK 清除；不连接数据库 |
| MySqlSnapshotMetadataReleaseTest | 18 | PASS | 上游已有元数据释放协议测试 |
| PendingSplitsStateSerializerTest | 24 | PASS | 上游已有 Enumerator 状态序列化测试 |
| MySqlSplitSerializerTest | 3 | PASS | 上游已有 Reader Split 序列化测试 |
| ArticleSourceRecoveryITCase | 2 | PASS | 真实 MySQL 与 MiniCluster 作业的异常注入、Checkpoint 恢复及重放 |

合计 53 项，0 失败、0 错误、0 跳过。不能将 51 项内存协议/序列化测试称为 51 次集群故障实验。

新建测试源码在 `tests/`；Surefire XML 和文本在 `reports/`。Maven 控制台输出为 `protocol-tests.log`、`ack-test.log`、`recovery-tests.log`。`checksums.json` 对研究产物提供 SHA-256。

## 关键结果

1. 并行度 2：C10 取状态后才收到完成报告，完成 C10 不允许 binlog 分配；收齐报告后取 C11，再完成 C11，允许从最小 HIGH 分配 binlog。
2. 并行度 1：收齐完成报告即可分配 binlog，无需上述切换 Checkpoint 门槛。
3. 真正序列化 C11 再反序列化，状态仍为 INITIAL_ASSIGNING；恢复后的 Assigner 没有运行时 checkpointIdToFinish，需再取 C12 并完成它。这是对象级恢复测试，不是 JM 故障实验。
4. SplitAssignmentTracker：A 被成功 C10 覆盖，B 在待完成 C11 中，C 在 C11 之后；回到 C10 时返回 B、C，A 由 Reader 的 Checkpoint 状态恢复。
5. 返回未完成 snapshot split 时，旧完成水位被移除，不能沿用其旧完成报告越过切换条件。
6. 未收到 ACK 的已完成 snapshot split 经真实 Split 序列化后，恢复到真实 Reader 的 finishedUnackedSplits；活动 Split 数为 0，重发请求使其再次报告，收到 ACK 后下次状态为空。这不是网络丢包实验。
7. 快照作业：Source 并行度 1，在第一 Chunk 的 pre-HIGH hook 暂停，完成 C1，此时下游受管列表为空；释放 hook 并抛出 SQLException，框架将执行 attempt 从 0 重启为 1，恢复为空列表，随后收到 10 条不同 READ，C2 完成。
8. binlog 作业：Source 并行度 2，10 条 READ 加预热更新 u:101=11 被 C3 覆盖。单个 MySQL 事务依次写 101=20、101=30、102=40；测试 Sink 收到 30 后抛出异常一次。attempt=1 恢复 11 条基线；最终受管列表为基线加 20、30、40 共 14 条。非受管 raw 观察序列是 20、30、20、30、40。最终 C4 完成。只证明此作业受管状态的回滚/重放，不证明外部事务 Sink 的 Exactly-once。

## 可复现方式

测试放入该固定提交对应模块的同名 package 目录。原生产源码不需要修改。使用单独的 MySQL 实例，并显式提供端口。实验容器已停止并清理；它只包含本轮测试数据库。以下凭据只用于一次性本地测试。

```sh
docker run -d --name cdc-article3-mysql-repro -p 127.0.0.1:33306:3306 \
  --memory=1g --cpus=1 --tmpfs /var/lib/mysql:rw,size=256m \
  -e MYSQL_ROOT_PASSWORD=article3-local-only -e MYSQL_ROOT_HOST=% mysql:8.4 \
  --server-id=8675311 --log-bin=binlog --binlog-format=ROW --binlog-row-image=FULL \
  --gtid-mode=ON --enforce-gtid-consistency=ON --innodb-buffer-pool-size=64M \
  --performance-schema=OFF --mysql-native-password=ON
```

就绪后在该实验容器内将 root@% 改为 mysql_native_password，密码保持上面的实验值。测试自动建 `cdc_article3` 库并重建其中 `items` 表。不要指向已有业务数据库。原实验镜像 ID 为 `sha256:cc52b8b5900d106bc482241091cfa7496d830280868e19aca8a24e8e7086568e`，标签未来可能移动。

```sh
JAVA_HOME=/home/author/Library/Java/JavaVirtualMachines/temurin-11.0.29/Contents/Home \
/home/author/software/apache-maven-3.8.8/bin/mvn -B -ntp \
  -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc -am \
  -Dtest=ArticleSourceProtocolTest,ArticleReaderAckTest,MySqlSnapshotMetadataReleaseTest,PendingSplitsStateSerializerTest,MySqlSplitSerializerTest,ArticleSourceRecoveryITCase \
  -Darticle.mysql.port=33306 -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test
```

本轮实际分三次运行，分别对应上述三个日志文件；没有把合并后的命令再次执行，也未把格式检查记为 PASS。测试运行不构成完整构建、兼容性或压力测试证明。

## 源码追踪补充

`flink-runtime-source/` 是 Apache Flink release-1.20.3 的官方源码原件，包括 SourceReaderBase、SourceOperator、SourceCoordinator、SourceCoordinatorContext、SplitAssignmentTracker、OperatorCoordinatorHolder 和 SubtaskGatewayImpl。特别核实 Coordinator Checkpoint 后关闭事件 gateway、Source 子任务确认 Checkpoint 后开放 gateway 的协议；没有在网络层另做 ACK/Barrier 乱序注入。

论文依据：Carbone 等，State Management in Apache Flink，VLDB 2017，§3.2、§4.4；接口设计依据 FLIP-27。理论依据与实现测试分别列述。

## 第二篇勘误

原第二篇把 Hybrid 内部 snapshot-only 条件分支理解为 snapshot-only 作业默认路径。本轮沿 MySqlSource.createEnumerator 入口核实后已修正：该启动模式实际选择 MySqlSnapshotSplitAssigner，不使用初始模式的 Hybrid。此前手工构造有界 Reader 的实验结果有效，但不能证明 snapshot-only 作业会补读 minHIGH 到 maxHIGH。

## 未验证范围

`evidence_incomplete`：物理 TaskManager/JobManager 进程退出、JM leadership 切换、跨机 HA 存储、网络乱序和延迟注入、并行度变化恢复、savepoint 跨版本升级、新增表过程、DDL、binlog 清理或主从切换、长时间高负载，以及真实外部 Sink 的可见性与 Exactly-once。未验证项不影响已记录测试结果，也不能由这些结果推定通过。
