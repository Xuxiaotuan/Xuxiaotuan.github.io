公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# Flink CDC 第四篇：Schema 演进证据

本目录记录 2026-10-18 本轮实际执行的验证。文章基线为 Apache Flink CDC `release-3.7.0`，固定提交 `045e8da45df4b1017849b04e2d8bfe8f0ed7936d`；运行依赖 Flink 1.20.3、Debezium 1.9.8.Final、Temurin Java 11.0.29。不是所有版本的通用结论。

## 本轮结果

最终选定测试共 **40 个，0 failure、0 error、0 skipped**。以 `reports/` 的 8 份 XML 和 `test-summary.json` 为准。这里的 40 是执行用例数，不是 40 个独立故障模型。

| 测试类 | 用例 | 证据层级 |
| --- | ---: | --- |
| regular.SchemaCoordinatorTest | 4 | Coordinator 对象测试 |
| common.SchemaManagerTest | 8 | Schema 状态、序列化测试 |
| common.SchemaDerivatorTest | 8 | Schema 改写与数据转换测试 |
| regular.SchemaEvolveTest | 9 | 算子及 Coordinator 测试设施 |
| ArticleDdlParserTest | 4 | 真实 CustomMySqlAntlrDdlParser 对象，无数据库 |
| FlinkSchemaCoordinatorRecoveryITCase | 2 | 真实 MiniCluster、模拟 Source、内存 ValuesDatabase Sink；regular p=1 与 distributed p=2 |
| ArticleSchemaRecoveryITCase | 4 | 真实 MySQL + MySqlSource + MiniCluster + checkpointed 内存探针 Sink |
| ArticleDdlOnlyCheckpointITCase | 1 | 真实 MySQL + MiniCluster，观察真实 MySqlRecordEmitter 的 DDL 分支 |

前三个 `Article*` 文件是本轮新增测试，副本见 `tests/`；其余为固定提交自带测试。未修改生产实现。测试执行跳过了 checkstyle、Spotless 和 RAT，因此测试成功不代表通过这些风格及许可检查。

## 真实 MySQL 实验

独占临时容器 `cdc-article4-mysql-20261018`，ID `73155be108e711718d514e8df0500660f17248644da6f8310d8ef493c53b254b`；镜像 mysql:8.4，实际 SELECT VERSION() 为 **8.4.11**。仅监听 `127.0.0.1:32779`，ROW/FULL binlog、GTID 开启，tmpfs 数据目录。所有测试完成后已停止并删除该容器，不涉及其他数据库或服务。

初始表为 `cdc_article4.items(id INT PRIMARY KEY, amount INT NOT NULL)`，10 行。先完成初始快照和 Checkpoint，再以 amount=11 更新确认进入 binlog 阶段并完成基线 Checkpoint。

四组恢复实验分别组合 includeSchemaChanges=true/false 与 ADD 后是否做新 Checkpoint。ADD SQL 为 `ALTER TABLE cdc_article4.items ADD COLUMN note VARCHAR(20) AFTER id`。ADD 后更新 note='blue',amount=20；另一分支先完成 Checkpoint，再 DROP note 并更新 amount=30。Sink 首次收到指定更新时主动抛异常，实际发生 task 重启与 Checkpoint 恢复。

结果：恢复点之后的更新原始投递两次；includeSchemaChanges=true 时，相应 ADD 或 DROP 事件原始投递两次；Checkpoint 管理的探针状态恢复到基线后只保留一次后续事件。恢复点之前的 ADD 未重复输出，初始 READ 总数仍为 10。includeSchemaChanges=false 时没有 DDL 输出，但行记录能按变化后的 Struct 字段解析。

第五组关闭 Schema 输出，将 heartbeat 设为 1 小时，观察真实 emitter 调用 super.processElement 前后的状态。在仅 ADD、无后续 DML 时确认 tableSchemas 已包含 note，而 startingOffset 未在 DDL 分支推进，随后完成 Checkpoint。更新后注入失败，恢复时内部 ADD 总处理次数为 2，新结构数据恢复成功。这个结果仅覆盖该 ADD 语句、字段布局和配置，不能证明任意 DDL 重复解析都安全。

这些实验使用遍历 SourceRecord 实际 Struct 字段的自定义反序列化器；没有运行固定 RowType 的 Flink SQL，也没有将 MySQL Pipeline Connector 与真实外部 Sink 接成一条链路。静态 raw 列表用于观察跨 attempt 原始投递；受 Flink 管理的 ListState 用于验证恢复结果。两者证据不能混用。

## Pipeline 故障实验的限制

上游 `FlinkSchemaCoordinatorRecoveryITCase` 的两个实例真实运行了作业、Checkpoint 与恢复。但 Source 为 RecoverySourceFunction，目标为内存 ValuesDatabase。

FailingOnceMetadataApplier 第一次先执行 extra_v1 的 ADD，再抛出异常；第二次收到同一 ADD 时直接返回。测试因此**显式模拟了目标端幂等**。断言应用入口调用两次、Source 重启、最终 Schema 和数据正确，不等于外部 DDL 物理执行两次，也不能证明任意数据库具有这种幂等能力。

## 测试设施修正记录

DDL-only 补充实验首次在作业提交前报 NotSerializableException，匿名 emitter 捕获了测试类实例。未运行到 CDC。已将 emitter 构造移到 static 方法，保留其调用生产 super.processElement 的行为，第二次执行通过。

首次日志为 `ddl-only-checkpoint-test.log`，首次报告单独保存于 `initial-harness-failure/`，不计入最终 40 个通过用例，也不隐藏为产品成功或产品失败。最终日志为 `ddl-only-checkpoint-test-final.log`。

## 复现入口

使用固定提交的完整源码和 Java 11；将 `tests/ArticleSchemaRecoveryITCase.java`、`tests/ArticleDdlOnlyCheckpointITCase.java` 放入 MySQL source 模块对应的 `src/test/java/org/apache/flink/cdc/connectors/mysql/source/`，将 `ArticleDdlParserTest.java` 放入 MySQL pipeline 模块的 `src/test/java/org/apache/flink/cdc/connectors/mysql/source/parser/`。

先准备独占 MySQL 测试实例。测试会创建数据库并 DROP/CREATE `cdc_article4.items`，不得指向已有业务库。测试口令是固定本地演示口令 `article4-local-only`。本轮容器配置见 `mysql-container-command.txt`；启动后须为 root@% 配置 mysql_native_password，与本轮旧版 JDBC/CDC 依赖对齐。

在源码根目录依次执行以下命令，勿并发运行同一 reactor：

```sh
mvn -B -ntp -pl flink-cdc-composer -am \
  -Dtest=org.apache.flink.cdc.runtime.operators.schema.regular.SchemaCoordinatorTest,org.apache.flink.cdc.runtime.operators.schema.common.SchemaManagerTest,org.apache.flink.cdc.runtime.operators.schema.common.SchemaDerivatorTest,org.apache.flink.cdc.runtime.operators.schema.regular.SchemaEvolveTest,org.apache.flink.cdc.composer.flink.FlinkSchemaCoordinatorRecoveryITCase \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test

mvn -B -ntp -pl flink-cdc-connect/flink-cdc-pipeline-connectors/flink-cdc-pipeline-connector-mysql -am \
  -Dtest=ArticleSchemaRecoveryITCase,ArticleDdlParserTest -Darticle.mysql.port=32779 \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test

mvn -B -ntp -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc -am \
  -Dtest=ArticleDdlOnlyCheckpointITCase -Darticle.mysql.port=32779 \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test
```

本轮 Maven 为 3.8.8；依赖缓存、下载可用性影响首次复现。上面是相同测试选择的复现命令，实际结果以本目录日志为准。

## 文件与未覆盖项

- `pipeline-tests.log`：29 个对象/算子用例和 2 个模拟源 MiniCluster 作业。
- `mysql-ddl-tests.log`：4 个真实 MySQL 作业和 4 个解析器用例。
- `ddl-only-checkpoint-test-final.log`：1 个 DDL-only Checkpoint 作业。
- `upstream-source-verification.json`：6 份关键源码与 GitHub 固定提交原文的字节一致性检查。
- `debezium-source/`：从 Debezium 1.9.8.Final 官方 Maven sources JAR 提取的阅读材料。

未覆盖：快照扫描中并发 DDL、主键变更、在线改表工具、所有 MySQL DDL、所有连接器和 Schema 模式组合、物理 JM/TM 崩溃、真实外部 Sink 的 DDL/DML 原子性、外部 DDL 超时/部分成功的完整端到端矩阵。上述端到端结论状态为 **evidence_incomplete**；本轮无阻塞已选测试的 external_blocked 项。
