公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# 第五篇：真实 MySQL 到 Paimon 的端到端证据

实验日期：2026-10-18。CDC 固定提交 `045e8da45df4b1017849b04e2d8bfe8f0ed7936d`（release-3.7.0），Flink 1.20.3，Paimon 2.0.0，Debezium 1.9.8.Final，Java 11.0.29，Maven 3.8.8。Paimon 版本由此提交的 connector POM 决定，未覆盖为其他版本。

## 结果与证据层级

1. **43 个既有测试用例通过**：PaimonMetadataApplierTest 28、PaimonWriterTest 1、PaimonWriterHelperTest 14。报告见 `reports/`、`test-summary.json`，日志见 `paimon-tests.log`。这些不是 43 个完整端到端作业。
2. **2 个真实 MySQL → Paimon 作业通过**：真实 MySQL 8.4.11、真实 MySQL Pipeline Connector、真实 SchemaOperator/Coordinator、真实 Paimon writer/文件/Catalog/snapshot；运行在同 JVM 的 Flink MiniCluster，p=1、bucket=1，使用 EVOLVE、input changelog。精确断言和故障探针见 `tests/ArticlePaimonExperiment.java`，执行日志 `end-to-end.log`。
3. **6 组真实 Catalog 检查通过预期断言**：见 `tests/ArticlePaimonDdlMatrix.java` 和 `ddl-matrix-results.txt`。这是直接调用真实 PaimonMetadataApplier 的框架事件，非 6 个 MySQL 作业；其中预期失败的重复 RENAME 被断言为 SchemaEvolveException。
4. **持久化文件独立回读通过**：作业结束后重新打开 Catalog，直接扫描实际数据文件并读取 snapshot 3、4 的 changelog。最终 10 行、10 个不同主键，与真实 MySQL 全量逐行一致；两个快照的四条 changelog 完全重复。见 `durable-readback-results.txt` 和 `tests/ArticlePaimonReadback.java`。

本轮没有修改 CDC/Paimon 生产源码；没有替换真实写入、Catalog 或底层提交算法。测试跳过 Maven checkstyle、Spotless 和 RAT，不能称这些检查通过。没有提交、推送或公开发布。

## 实际环境

独占容器 `cdc-article5-mysql-20261018`，ID `84e10213bfa79bcce75f6f03f753edf6d273d87cc3807f1b81a16955430400b5`。镜像 mysql:8.4，SELECT VERSION() 为 8.4.11；仅映射 `127.0.0.1:32780`。ROW/FULL、GTID 开启、tmpfs 数据目录。仅操作 `cdc_article5.items`。演示密码为 `article5-local-only`，不是业务凭据。

Paimon 是真实 filesystem Catalog，位于本目录 `warehouse-schema/`、`warehouse-checkpoint/`、`warehouse-ddl-matrix/`。这些目录保留数据文件、manifest、Schema 和 snapshot，供复核；没有 S3、Hive Metastore 或共享文件系统。schema 与 checkpoint 两个作业分开运行，分别重建 MySQL 表。

真实 MySQL 读取使用生产 `MySqlDataSource`。拓扑由生产 DataSourceTranslator、SchemaOperatorTranslator、PartitioningTranslator、DataSinkTranslator 构造，未经过 CLI YAML 解析和打包分发。PaimonEventSink 使用与 SchemaOperator 一致的 UID；`*-execution-plan.json` 保存实际计划。

故障只注入在两个位置：

- DDL wrapper 每次都调用真实 PaimonMetadataApplier；首次成功添加 flag 后主动抛错。重放仍调用真实 delegate，不模拟幂等成功。
- 在生产 Paimon precommit 拓扑后追加一个观察算子；snapshotState 先阻塞，使全局 Checkpoint 无法完成，外部回读成功后主动抛错。真实 PreCommitOperator 已经发布快照。未重写 precommit 的提交实现。

## 关键时间线

### Schema 作业

初始 10 行 → C1 → binlog 更新 amount=11 → C2。添加 note、更新 amount=20 后，目标 Schema 已出现 note，但最新数据快照仍为 snapshot-2，查询得到旧值 11 和 note=null。C3 后新数据可见。随后 ADD flag，真实 Catalog 成功后注入异常，恢复后应用入口共调用两次，C4 后得到 id=101,note=blue,flag=yes,amount=30。最后 INSERT 201、DELETE 102，C5 后仍为 10 行，记录见 `schema-*.txt`。

### Checkpoint 作业

C2 / snapshot-2 基线之后执行 UPDATE 101=20、INSERT 201=40、DELETE 102。触发 C3，阻塞 precommit 后的观察算子：

- checkpoint future 未完成，完成回调只有 [1,2]；
- Paimon snapshot-3 已存在，三项业务变化均可查询；
- 释放探针并抛错，作业 attempt=1，探针从 C2 恢复到 11 条已处理数据记录；
- snapshot-3 不会因为 C3 失败而被自动回滚；
- 重放后 C4 完成，完成回调为 [1,2,4]，Paimon 新增 snapshot-4。

独立回读 snapshot-3 与 snapshot-4 均得到：

```text
DELETE:102:10
INSERT:201:40
UPDATE_AFTER:101:20
UPDATE_BEFORE:101:11
```

两个快照的 commitIdentifier 分别为 3、4，commitUser 相同。最终 PK 合并视图与源库一致；changelog 批次确实重复。`snapshot-summary.json` 中 totalRecordCount 是快照元数据统计，不能当作合并查询的最终业务行数。

### DDL 幂等边界

重复 CREATE、相同 ADD、DROP 均返回成功；重复 RENAME 抛 SchemaEvolveException。已有 note 为 VARCHAR(20) 时，再要求 ADD note BIGINT 仍返回，但实际类型仍是 VARCHAR(20)。同一 AddColumnEvent 列表为 [已存在的 note, 不存在的 extra] 时调用返回，extra 仍未增加。后二者是特意构造的 Catalog 层前置状态，不声称本轮 MySQL 作业自然产生了这种状态。

## 初始启动失败与修正

`initial-classpath-failure.log` 保留首次启动失败：合并 classpath 时 Paimon 大包中的 JdbcConnectionPools 先被加载，其方法返回 shaded HikariDataSource，而当前 CDC ConnectionPools 接口要求未 shaded 类型，产生 AbstractMethodError。源码 Enumerator 未启动成功，没有进入 CDC 数据实验。

只将固定提交的编译目录放到依赖 JAR 前面后，最终两个作业通过。`classpath-initial-conflict.txt` 和 `classpath.txt` 分别保存调整前后列表；最终列表保留版本依赖，没有修改大包或生产代码。另修正实验 finally 清理逻辑，确保失败作业的 cancel 异常不阻止 MiniCluster 退出。这些属于实验设施修正。

## 复现

本轮源码树路径可从 classpath.txt 找到。首次准备需构建固定提交的 MySQL Pipeline、MySQL Source、Paimon、Composer 及相关 reactor 依赖。不要将其他 CDC 版本或 Paimon 自带的 CDC 类优先放在这些编译产物之前。

本轮既有测试命令（在源码根目录，Java 11）：

```sh
mvn -B -ntp -pl flink-cdc-connect/flink-cdc-pipeline-connectors/flink-cdc-pipeline-connector-paimon -am \
  -Dtest=PaimonMetadataApplierTest,PaimonWriterTest,PaimonWriterHelperTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test
```

实验 Java 文件独立存放，没有新增 Maven 依赖或改动 POM。`classpath.txt` 由 Paimon 测试报告与第四篇 MySQL Source 报告中的 java.class.path 合并，加上 MySQL Pipeline target/classes，再将所有编译目录前置所得。它是当前环境的绝对路径；迁移环境时需从对应版本新生成的依赖/测试 classpath 重建，而不能原样复制路径。

当前环境可用 `run-experiments.py` 编译和重放三个探针。必须传入一个**不存在的新输出目录**和独占 MySQL 测试端口；程序会 DROP/CREATE cdc_article5.items，不得指向业务实例。原实验已完成，保留的 warehouse 不应覆盖。容器启动配置见 `mysql-container-command.txt`。

该包装脚本由实际执行过的 javac/java 命令整理，已做 Python 语法及 --help 检查；本轮三个 Java 探针按日志中的独立命令执行，未再通过包装脚本重复运行整套实验。原临时容器已停止、删除，并检查无同名残留。

## 未覆盖的保证

未验证多并行度、多表事务、跨表原子发布、S3/HDFS/Hive Catalog、物理 JM/TM kill、Savepoint 迁移、非主键或其他 merge engine、分区键变更、所有 DDL、所有失败时序。恢复由同 JVM 任务抛异常触发，静态探针用于观测和“一次故障”，不是分布式故障控制服务。

完整生产拓扑和所有场景的保证仍为 `evidence_incomplete`。本轮已选实验无 external_blocked 项。不能把 PK 最终对账成功升级为 changelog exactly-once，不能把目录中有 snapshot 等同于对应 Flink Checkpoint 已完成。
