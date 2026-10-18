公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# Flink CDC 第一篇验证材料

日期：2026-10-18。本文材料用于支持第一篇的一致性语义分析，不是完整 CDC 链路验收报告。

## 源码与结果

- Flink CDC tag：`release-3.7.0`。
- commit：`045e8da45df4b1017849b04e2d8bfe8f0ed7936d`。
- 源码归档：https://codeload.github.com/apache/flink-cdc/tar.gz/045e8da45df4b1017849b04e2d8bfe8f0ed7936d
- Java：Temurin 11.0.29；默认 Flink 1.x profile；POM 声明 Flink 1.20.3、Debezium 1.9.8.Final。
- `BinlogOffsetTest`：7 项通过。
- `MySqlRecordEmitterTest`：9 项通过。
- `ArticleSnapshotMergeTest`：2 项通过，直接调用原 `RecordUtils.upsertBinlog()`，输入为构造的 SourceRecord。
- 原有业务 Java 文件未修改；只在临时副本添加上述文章测试。
- MySQL：8.4.11，ROW binlog，GTID ON，REPEATABLE READ。
- MySQL 两会话视图、提交前后位点、重复赋值与审计计数三组验证通过。

## 文件说明

- `maven-targeted-tests.log`：两类上游测试的实际 Maven 日志。
- `maven-merge-probe.log`：新增合并测试的实际 Maven 日志。
- `TEST-*.xml`：三份实际 Surefire 报告。
- `unit-test-summary.json`：从报告提取的测试数量与结果。
- `ArticleSnapshotMergeTest.java`：新增方法级测试源码。
- `mysql_visibility_probe.py`：MySQL 验证脚本，使用两个独立会话；不运行 Flink。
- `mysql-visibility-result.json`：完整 SQL 顺序、返回和断言结果。
- `mysql-binlog-events.tsv`：隔离实例的 SHOW BINLOG EVENTS 返回；不是解码后的行值。
- `mysql-image.txt`：本轮镜像 ID 和架构。
- `source-identity.json`、`source-content-sha256.json`：来源及关键 Java 文件哈希。

## Java 测试复现

将固定提交的源码解压到一个新的临时目录，在源码根目录执行。先设置 JAVA_HOME 指向本机 JDK 11。

```sh
mvn -B -ntp \
  -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc -am \
  -Dtest=BinlogOffsetTest,MySqlRecordEmitterTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test
```

把本目录的 `ArticleSnapshotMergeTest.java` 复制到临时源码的以下目录：

```text
flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/test/java/org/apache/flink/cdc/connectors/mysql/source/utils/
```

保持其他参数不变，将测试选择器改成 `-Dtest=ArticleSnapshotMergeTest` 执行第二组。这里跳过的是风格和许可证检查，不是测试；这不是发布验证。

## MySQL 验证复现

使用独立、可丢弃的容器，不连接任何业务库。以下名称必须空闲；不应删除同名未知容器。`mysql:8.4` 标签会移动，如需严格复现应使用记录中的镜像 ID（且该镜像仍在本机）。

```sh
docker run -d --name cdc-article1-mysql-20261018 \
  --label codex.task=flink-cdc-article1 \
  --network none --memory=1g --cpus=1 \
  --tmpfs /var/lib/mysql:rw,size=256m \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.4 \
  --server-id=8675309 --log-bin=binlog --binlog-format=ROW \
  --binlog-row-image=FULL --gtid-mode=ON --enforce-gtid-consistency=ON \
  --innodb-buffer-pool-size=64M --performance-schema=OFF
```

确认该新实例已经就绪后运行 `python3 mysql_visibility_probe.py`。脚本只适用于空白实验实例，创建 `article_probe` 数据库，重复运行前应新建实验容器。

本轮脚本全部通过后保留输出，并停止、移除了自行创建的容器。其他容器未操作。

## 结论边界

这三种证据不可相互升级：上游单测、原实现方法级测试、独立 MySQL 实验均未运行完整 Flink CDC 作业。未验证真实并发扫描的完整输出、Checkpoint 故障恢复、主库切换、Paimon 或其他 Sink 的提交和事务可见性。上述层级仍为 `evidence_incomplete`。
