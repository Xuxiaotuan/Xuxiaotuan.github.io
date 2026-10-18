公开阅读副本（2026-10-18）

本说明记录原实验；下文提到的完整日志、warehouse、报告和 classpath 部分只保留于作者本地，公开文件以 SHA256SUMS 为准。本副本把机器路径归一为 /work 或 /home/author，并整理行尾空白，未重新执行命令。所有 article*-local-only 口令均为已销毁的一次性实验库口令。迁移环境需按固定版本重建依赖，不是开箱即用复现包。

# Flink CDC 第二篇实验与源码证据

日期：2026-10-18。对象：release-3.7.0 / 045e8da45df4b1017849b04e2d8bfe8f0ed7936d。

## 实际完成

- 深读 DBLog 第 3 节、Algorithm 1，以及 MySQL 增量快照、回填、Split 范围和多 HIGH 过滤代码。
- 新增 ArticleIncrementalSnapshotITCase，使用未修改的生产 Reader 连接独立 MySQL。
- 最终运行 5 个真实数据库 Reader 集成用例，0 失败、0 错误、0 跳过。
- 同轮运行上游 SplitKeyUtilsTest 的 8 个单测，0 失败、0 错误、0 跳过。
- 整理探针格式后再次运行，同样 13/13 通过。不是 26 个不同用例。
- 没有修改生产源码，没有提交、推送或发布。
- 本目录日志中的示例口令只用于一次性本地实验容器。

## 文件与证据层级

- ArticleIncrementalSnapshotITCase.java：可复制到指定提交 test 源码目录运行的探针。
- maven-reader-tests.log、TEST-*.xml、test-summary.json：最终成功运行。
- 五个以测试方法名命名的 txt：对应 XML 中的实际 system-out，含完整水位和 SourceRecord 恢复位置。
- maven-reader-first-pass.log：首次成功运行，文章 GTID 1-38 / event=2 / row=1 的例子来自此文件。
- maven-reader-initial-compile-failure.log：初次探针编译错误，把不实现 AutoCloseable 的 Reader 放进 try-with-resources；后来改为 finally 关闭。这次没有成功执行测试。
- format-probe.log：离线 Spotless 因 Maven 仓库标识/依赖解析失败；没有声称通过格式/发布检查。之后仅手工整理新增探针，再次编译并运行测试。
- mysql-environment.tsv、mysql-image.txt：MySQL 8.4.11，ROW/FULL、GTID ON、REPEATABLE-READ；镜像 ID 和 digest。
- source-identity.json、source-content-sha256.json：上游提交及阅读文件校验。
- DBLog-2010.12597v1.pdf：论文原文。
- MySqlOffsetContext-debezium-1.9.8.Final.java：从 Debezium v1.9.8.Final 原始源码取得，解释恢复位置的 GTID/event/row 语义。

## 探针覆盖

1. windowMergesCommittedChangesButExcludesPostHigh：LOW 后插入，SELECT 后多次更新/删除/插入删除，HIGH 后更新；快照 11 条 READ，持续日志只有 u:101=40。
2. multipleChunksUseIndividualHighWatermarks：手工固定 (-∞,105)、[105,+∞) 两个 Split，分别 HIGH；有界追赶只输出 u:103=30；新写入后重读输出 4 条精确 UPDATE。这里没有运行 Assigner 自动切分和分配。
3. skippingBackfillReplaysChangeAlreadySeenBySelect：快照 r:101=20，HIGH=LOW，后续 u:101=20、u:101=30。
4. unchangedWindowHasEqualWatermarksAndCompletes：无变化，LOW=HIGH，10 条 READ。
5. transactionStartedBeforeLowCommitsBeforeHigh：独立事务 LOW 前修改但未提交，SELECT 后/HIGH 前提交，归一为 r:101=77。

这些是 Reader 集成和工具单测，不是完整 Flink Job、Checkpoint 故障恢复、并发 Chunk 或 Sink E2E。两次构造 Binlog Reader 从同一位置重读是独立断言，不是故障恢复实测。完全相等的 HIGH 谓词由源码确认，未额外合成记录调用私有方法。

## 复现

源码归档仍在：
/work/source/flink-cdc-045e8da45df4b1017849b04e2d8bfe8f0ed7936d

测试在模块相对路径：
src/test/java/org/apache/flink/cdc/connectors/mysql/debezium/reader/ArticleIncrementalSnapshotITCase.java

创建一个新的、名称未被占用的一次性实验容器。不要将探针指向已有数据库：探针会重建 cdc_article2.items。原实验容器名为 cdc-article2-mysql-20261018；完成后已清理。

```sh
docker run -d --name cdc-article2-mysql-20261018 \
  --label codex.task=flink-cdc-article2 \
  -p 127.0.0.1::3306 --memory=1g --cpus=1 \
  --tmpfs /var/lib/mysql:rw,size=256m \
  -e MYSQL_ROOT_PASSWORD=article2-local-only -e MYSQL_ROOT_HOST=% \
  mysql:8.4 --server-id=8675310 --log-bin=binlog \
  --binlog-format=ROW --binlog-row-image=FULL \
  --gtid-mode=ON --enforce-gtid-consistency=ON \
  --innodb-buffer-pool-size=64M --performance-schema=OFF \
  --mysql-native-password=ON
```

等待 MySQL 就绪，执行一次性本地账号设置，读取宿主端口：

```sh
docker exec cdc-article2-mysql-20261018 mysql -uroot -particle2-local-only \
  -e "ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'article2-local-only';"
docker port cdc-article2-mysql-20261018 3306
```

原运行端口为 32777；复现时替换以下 article.mysql.port。使用 JDK 11；以下是本机实际使用的 Maven 命令。跳过的是格式/风格/许可证检查，不是测试。

```sh
JAVA_HOME=/home/author/Library/Java/JavaVirtualMachines/temurin-11.0.29/Contents/Home \
/home/author/software/apache-maven-3.8.8/bin/mvn -B -ntp \
  -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc -am \
  -Dtest=ArticleIncrementalSnapshotITCase,SplitKeyUtilsTest \
  -Darticle.mysql.port=32777 -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcheckstyle.skip -Dspotless.check.skip=true -Drat.skip=true test
```

从源码根目录执行。探针自行按用例重建表、使用 Hook 安排时序，每个测试设置 90 秒超时。用例产生的 GTID、字节位置和 HashMap 输出顺序会变化；精确业务值和应保留的日志顺序由 AssertJ 断言。

完成后仅停止并移除自己的实验容器：

```sh
docker stop cdc-article2-mysql-20261018
docker rm cdc-article2-mysql-20261018
```

不覆盖的条件：无主键与跨 Chunk 修改键、字符串 collation、DDL、日志清理、主从切换、分叉 GTID、并发调度、Checkpoint 失败窗口、端到端 Sink 提交、吞吐/内存/延迟基准。
