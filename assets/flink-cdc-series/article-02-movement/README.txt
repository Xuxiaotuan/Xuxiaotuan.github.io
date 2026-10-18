# 第二篇补充：跨 Chunk 修改键的真实 MySQL Reader 实验

日期：2026-10-18。Flink CDC release-3.7.0 / 045e8da45df4b1017849b04e2d8bfe8f0ed7936d；Flink 1.20.3，Temurin 11.0.29；MySQL 8.4.11、InnoDB、ROW/FULL、GTID ON。

## 结果与证据层级

2 个行为刻画用例，断言 0 失败、0 错误、0 跳过。首次执行与 Spotless 整理后的最终执行结果相同，只计 2 个独立场景，不计为 4 个。

- movingFromUnscannedBToFinishedAIsMissingFromReconstruction：MySQL 3 行，Reader 输出重建 2 行，MISSING_KEYS=[102]，DATA_EQUAL=false。测试通过表示遗漏路径被复现，不表示数据正确。
- stableChunkKeyControlReconstructsAllRows：MySQL 与 Reader 重建均为 3 行，所有字段一致。
- 两组均实际断言 H_A < E_102 < L_B == H_B。E_102 来自真实 UPDATE 的完整 BinlogOffset，不是手工构造位置或仅比较 GTID 字符串。
- 无快照 metadata 的 Binlog Reader 实际读到 id=102 的 UPDATE；同一范围带 A/B FinishedSnapshotSplitInfo 的 Reader 只输出 id=101 的 UPDATE。
- 无连接器修复。114 个 MySQL 模块生产源文件前后哈希不变；17 个既有固定版本关键源文件指纹匹配。实际加载的 SnapshotSplitReader、BinlogSplitReader、RecordUtils 位置在逐用例输出中。

新增探针直接实例化生产 Reader；没有反射替换过滤谓词，没有伪造 binlog 事件。主键 id 不变，普通整数列 c 被显式配置为 Chunk key。表初始为 (101,100,10)、(102,110,20)、(103,120,30)，列为 id,c,amount。

同步读取 A=(-infinity,105) 至完成；迁移组把 id=102 的 c 从 110 改为 100，对照组只把 amount 改为 21；两组再将 id=101 amount 改为 11、id=103 amount 改为 31；随后读取 B=[105,+infinity)。三条 UPDATE 分别自动提交，不依赖 sleep 时长。此顺序使迁移位于 A HIGH 之后、B LOW 之前。

每组分别启动两个有界 Binlog Reader 从 H_A 读取至 H_B：空 finished metadata 的对照读取，以及带两个实际 HIGH 的生产过滤读取。最后按 id 应用快照和过滤后事件，并与 SELECT id,c,amount 的全部结果比较。这里的重建表是测试程序中的 Map，不是实际 Sink。

## 文件

- ArticleChunkKeyMovementITCase.java：最终实际编译执行的探针。
- 两个方法名对应的 txt：从最终 Surefire XML 的 system-out 原样提取，公开副本仅归一机器路径和行尾空白；包括 before/after、位点、完整输出及对账。
- test-summary.json：最终结果，分别保存 assertions 与 data_equal，不能混为一谈。
- environment.json：版本、镜像 ID/digest、配置。
- production-verification.json：源文件与探针指纹核对。
- final-mysql.tsv：最终迁移组源表及服务器配置的独立 SQL 查询。

完整 Maven 日志、Surefire XML（含本机环境/classpath）、首次运行与格式命令记录保留于作者本地。旧第二篇证据未覆盖，原 5 个 Reader 与 8 个工具单测未在本轮重跑。首次格式筛选未命中文件；第二次错误地把 glob 作为正则传入，命令失败；修正为下述正则后只格式化 1 个探针并通过检查，再执行最终实验。格式命令问题不计为数据库实验结果。

## 复现条件与命令

需要 Docker、JDK 11、Maven 3.8.8 和该固定提交的源码及依赖。将本探针复制到源码根目录下：
flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/test/java/org/apache/flink/cdc/connectors/mysql/debezium/reader/ArticleChunkKeyMovementITCase.java

只能使用可丢弃的独立测试库；探针会重建 cdc_article2_move.items。以下是本轮使用的镜像与参数。先确认容器名未被占用：

```sh
docker run -d --name cdc-article2-movement-20261018 \
  --label codex.task=flink-cdc-article2-movement \
  -p 127.0.0.1::3306 --memory=1g --cpus=1 \
  --tmpfs /var/lib/mysql:rw,size=256m \
  -e MYSQL_ROOT_PASSWORD=article2-movement-local-only -e MYSQL_ROOT_HOST=% \
  mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70 \
  --server-id=8675320 --log-bin=binlog --binlog-format=ROW \
  --binlog-row-image=FULL --gtid-mode=ON --enforce-gtid-consistency=ON \
  --innodb-buffer-pool-size=64M --performance-schema=OFF --mysql-native-password=ON
```

等待日志出现 ready for connections，再设置该演示实例的账号并查看宿主端口：

```sh
docker exec cdc-article2-movement-20261018 mysql -uroot -particle2-movement-local-only \
  -e "ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'article2-movement-local-only';"
docker port cdc-article2-movement-20261018 3306
```

从源码根目录执行。JAVA_HOME 指向 JDK 11，mvn 使用 Maven 3.8.8。以下 32782 是本轮端口，复现时必须替换成实际端口。

```sh
mvn -B -ntp -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc \
  '-DspotlessFiles=.*ArticleChunkKeyMovementITCase.java' spotless:apply spotless:check
mvn -B -ntp -pl flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc -am \
  -Dtest=ArticleChunkKeyMovementITCase -Darticle.mysql.port=32782 \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip \
  -Dspotless.check.skip=true -Drat.skip=true test
```

第二条命令跳过格式、风格与许可证检查，不跳过测试；探针已用上一条命令单独完成 Spotless 检查。没有执行 Flink 2.x 或完整仓库测试，也不是上游贡献验收。

本轮证据保存后已停止并移除专用容器，示例口令只属于该已销毁演示库。复现后仅清理自己创建的容器：

```sh
docker stop cdc-article2-movement-20261018
docker rm cdc-article2-movement-20261018
```

## 尚未验证

自动切分/分配、并发 Chunk、主键更新、其他 Chunk key 类型和迁移方向、无主键表、事务内部恢复、Checkpoint 故障、真实 Sink、生产集群、主从切换，以及缺失后再发生更新的行为，均未由本实验验证。Reader 层的特定遗漏不能推广为所有配置必然漏行，也不能由稳定键对照通过推出端到端 exactly-once。
