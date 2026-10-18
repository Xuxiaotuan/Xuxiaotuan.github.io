---
layout: post
title: Flink CDC 深入解析（一）：一致性的含义与边界
description: 结合固定版本源码和独立 MySQL 实验，区分表状态、事件历史和事务可见性。
keywords: Flink CDC, MySQL, Checkpoint, DBLog, Paimon
categories:
  - Flink
tags:
  - Flink CDC
  - MySQL
  - 源码分析
permalink: /2026/10/18/flink-cdc-1-consistency/
mermaid: true
---

[系列导读与验证材料](/flink-cdc-series/) · [一：一致性边界](/2026/10/18/flink-cdc-1-consistency/) · [二：增量快照](/2026/10/18/flink-cdc-2-incremental-snapshot/) · [三·上：Source 状态机](/2026/10/18/flink-cdc-3a-source-state/) · [三·下：故障恢复](/2026/10/18/flink-cdc-3b-checkpoint-recovery/) · [四：Schema 演进](/2026/10/18/flink-cdc-4-schema-evolution/) · [五：Paimon 提交](/2026/10/18/flink-cdc-5-paimon-commit/)

---

一条订单在源库中经历了 `amount=100 → 200 → 300`，下游最终也是 `300`。这足以说明同步正确吗？

如果下游是一张当前订单表，这个结果可能符合要求；如果下游要统计修改次数，丢掉中间的 `200` 就改变了计算结果；如果一次源端事务同时修改订单和库存，即使两张目标表最后都正确，也仍然需要检查读者是否曾看见半个事务。

Flink CDC 的一致性必须结合阶段、数据模型和观察边界来解释。增量快照负责建立可衔接的初始状态，Source 与 Flink 的恢复机制负责保存和恢复读取进度，而外部写入的重复效果与可见性还取决于 Sink。把这些性质压缩成一句“Exactly-once”，容易在设计和验收时得到过强的结论。

本文是系列第一篇，主线为 MySQL Connector。源码固定在 Flink CDC `release-3.7.0`，提交 `045e8da45df4b1017849b04e2d8bfe8f0ed7936d`。根 POM 声明默认 Flink 基线为 `1.20.3`、Debezium 为 `1.9.8.Final`。[版本声明](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/pom.xml#L65)

文中的源码分析与实测分别标明：本轮执行了 18 项 Java 测试，以及独立 MySQL 8.4.11 实例上的三组验证。没有运行完整的 Flink CDC 到外部 Sink 的故障恢复实验。

## 表状态与变更历史回答不同的问题

先把讨论限制在一个 MySQL 日志历史、稳定主键、固定 Schema、无业务转换的场景。设 `S(p)` 表示提交边界 `p` 对应的表状态，`E(p,q]` 表示从 `p` 到 `q` 之间按该日志顺序展开的行级变更。将这些变更依次应用到已知状态，得到：

```text
Apply(S(p), E(p,q]) = S(q)
```

这是状态复制需要满足的目标关系。它要求初始状态与日志边界相容、区间变更完整、应用规则正确。它不表示随便一次 SELECT 都已经得到了 `S(p)`，也不表示并行扫描产生的所有 Chunk 天然属于同一个边界。

状态是历史的结果，却不能唯一确定历史。对于同一个初始值 `100`，下面两段变更都会得到 `300`：

```text
历史 A：UPDATE amount=200；UPDATE amount=300
历史 B：UPDATE amount=300
```

因此，“最终值相等”无法证明历史 A 被完整保留。同样，把 `amount=300` 重复赋值两次，最终值也没有区别。最终状态对账能发现一部分丢失、错误映射和旧值覆盖，但无法单独证明没有重复写入，也无法证明每个中间版本都被观察到。

这一区别决定了 CDC 的验收口径。当前状态表关注给定追平边界上的逐键结果；变更审计关注明确起点之后的事件、顺序和事务信息；计数、通知等消费者还要说明初始化记录是否算作业务事件。三者可以使用同一类技术，但不能共享一个含糊的“数据一致”标准。

## 全量查询与日志消费之间存在并发窗口

只消费现存 binlog 通常无法重建一张表的全部历史状态，因为日志有保留期限。DBLog 论文把全量状态获取与持续日志处理放到同一个问题中，特别强调避免旧版本在新版本之后覆盖下游。它提出的背景是持续同步与状态补齐，不能将论文中的历史产品比较表直接当作今天的能力列表。[DBLog 第 1 至 3 节](https://arxiv.org/html/2010.12597v1)

考虑一种朴素流程：先查询整张表，查询结束后再从当前日志末尾开始消费。

| 顺序 | 源端动作 | 同步程序看到的内容 |
| --- | --- | --- |
| 1 | 订单金额为 100 | SELECT 读到 100 |
| 2 | 业务提交金额更新为 200 | 对应变更进入 binlog |
| 3 | 全量读取结束 | 下游初始化为 100 |
| 4 | 从此时日志末尾开始消费 | 第 2 步的更新可能被跳过 |

把日志起点提前可以覆盖这次更新，却又引入快照与日志重叠的问题：扫描可能已经看到新值，后续回放却先送来旧值。完整方案必须说明扫描结果与日志区间怎样合并，而不只是选择一个“足够早”的起点。

数据库的 MVCC 进一步说明了为什么“查询时刻”和“最新日志位置”不能混用。在 InnoDB 的 REPEATABLE READ 事务中，普通一致性读通常复用既有读取视图；READ COMMITTED 下则按语句建立读取视图。需要同时说明隔离级别、事务边界与读取方式，才能解释 SELECT 看到了哪些提交。[MySQL 一致性读](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html)

**数据库实测。**在独立 MySQL 8.4.11 实例中，会话 A 建立 REPEATABLE READ 一致性快照并读取 `(amount=100, revision=1)`。会话 B 更新并提交为 `(200,2)` 后，A 在原事务内仍读到 `(100,1)`；A 提交后再次读取，才得到 `(200,2)`。

另一组操作中，A 把金额从 200 更新为 300，但暂不提交。B 此时仍读到 200，`SHOW BINARY LOG STATUS` 的位置保持在同一文件的 1301。A 提交后，B 读到 300，位置变为 1616，GTID 集合也前进一个事务。这验证了该隔离实例中事务提交与可见日志的关系；没有涉及 Flink 作业，也不能据此概括多主拓扑中的日志顺序。

由此可以确定后续算法推导的一个前提：判断一条变更是否应参与衔接，要考察其提交及日志归属，不能只看事务开始时间或业务字段中的时间戳。

## 增量快照输出的是初始化状态

Flink CDC 的 MySQL 增量快照为每个 Chunk 记录扫描前后的 LOW/HIGH 日志边界，读取该窗口内属于 Chunk 的变更，并修正扫描缓冲区。完整的覆盖与过滤推导留到第二篇；第一篇先检查它对输出语义的影响。

**源码结论。**在 `SnapshotSplitReader.pollWithBuffer()` 中，有主键记录以 key 放入 `snapshotRecords`。进入回填阶段后，每条日志通过 `RecordUtils.upsertBinlog()` 修改这个缓冲区。输出时取的是缓冲区最终内容，而不是将扫描记录和所有回填日志原样串起来。[快照缓冲与输出](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/debezium/reader/SnapshotSplitReader.java#L312)

对有主键分支，合并实现具有两个直接相关的行为：更新用 after 值构造 READ 记录并覆盖相同 key 的缓冲项；删除移除该 key。这里的 READ 是 Debezium 的快照读取事件类型，不能把它解释为业务再次执行了一次 INSERT。[合并与 READ 构造](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/utils/RecordUtils.java#L174)

下面是便于阅读的语义概括，省略了边界判断、Schema 和无主键分支：

```text
snapshot[key] = 扫描得到的行
遇到属于该 Chunk 的更新：snapshot[key] = READ(after)
遇到属于该 Chunk 的删除：移除 snapshot[key]
窗口处理结束：输出剩余 snapshot 值
```

**原实现方法级实测。**新增测试直接调用该提交中未修改的 `RecordUtils.upsertBinlog()`，使用构造的 Debezium SourceRecord。结果如下：

| 输入 | 实际缓冲结果 |
| --- | --- |
| 初始 READ(100)，随后 UPDATE(100→200)、UPDATE(200→300) | 一个 key、一条 READ，after.amount=300 |
| 初始 READ(100)，随后 UPDATE(100→200)、DELETE(200) | 缓冲区为空 |

两项测试均通过。它们验证了合并方法的语义，没有执行真实的 SELECT、binlog 读取或完整 Source 调度。结合 `pollWithBuffer()` 的调用与输出路径，可以解释为什么快照窗口内的中间版本会折叠；尚不能把这组测试称作并发扫描的集成验证。

Pipeline 的事件转换还会继续改变表达方式：`DebeziumEventDeserializationSchema.deserializeDataChangeRecord()` 将 CREATE 和 READ 都映射成 `DataChangeEvent.insertEvent(...)`。因此，Pipeline 中一个 INSERT 事件可以表示“初始化时发现这条当前记录”，不能单凭操作类型推断业务刚创建了订单。[事件转换](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-debezium/src/main/java/org/apache/flink/cdc/debezium/event/DebeziumEventDeserializationSchema.java#L112)

这对下游业务有实际影响。用 CDC 初始化一张订单表，可以按主键建立当前状态；若要计算“今天新增订单数”或“每次改价次数”，还必须定义统计起点、初始化处理方式和日志保留条件。

DBLog 也使用水位协调扫描和日志，但其论文算法在输出日志的同时移除冲突快照行，不能把它与这里的回填覆盖逐行等同。本文的“中间版本折叠”结论来自 Flink CDC 的代码和方法级测试。[DBLog 算法 1](https://arxiv.org/html/2010.12597v1)

## Exactly-once 需要指定观察边界

在讨论故障之前，先把常被混用的性质拆开。

| 性质 | 需要观察什么 | 不能据此推出什么 |
| --- | --- | --- |
| 追平后的状态一致 | 在同一逻辑边界比较键、完整行值和删除结果 | 中间事件没有丢失 |
| 单键版本顺序 | 指定阶段与观察接口上的逐键输出顺序 | 多键之间具有统一事务可见性 |
| 变更历史完整 | 明确起点之后应保留的事件集合及顺序 | 初始化前的历史能够恢复 |
| 恢复后的逻辑效果恰好一次 | 受管理状态或指定提交结果中的效果 | 记录在物理执行中从未重读 |
| 源事务原子可见 | 外部读者是否可能看到同一事务的部分修改 | 每个支持 Checkpoint 的 Sink 都具备该性质 |

Flink 的状态管理论文明确区分系统内部状态保证与外部输出副作用，并讨论幂等和事务型 Sink。相关论述说明：一致恢复输入位置与受管理状态，并不自动撤销已经发生的外部动作。[State Management in Apache Flink 第 3.2 节与第 4.4 节](https://www.vldb.org/pvldb/vol10/p1718-carbone.pdf)

例如，一条记录在上次成功 Checkpoint 之后被读取并更新了算子状态，随后作业失败。恢复到旧 Checkpoint 时，这部分状态也回退，输入可以重新处理。判断逻辑效果是否重复，需要同时看恢复后的状态，而不能只数读到记录的次数。如果处理期间已经发送了不可撤销的通知，还必须检查通知侧的协议。

**数据库实测中的反例。**为了验证“最终值相等不足以证明只写一次”，实验创建目标模型表和 AFTER UPDATE 审计触发器，连续两次执行：

```sql
UPDATE article_probe.sink_model
SET amount = 200
WHERE id = 42;
```

目标行最后仍是 200，审计表记录为 2 条。这是明确安排的重复写入模型，未通过 Flink Sink 执行，也没有模拟真实 Checkpoint 故障。它支持的结论是：只看目标行最终值，会漏掉重复副作用。

重复赋值可以对目标字段表现为幂等，但幂等的范围必须说清。相同 SQL 触发的审计、通知和其他副作用，未必同时幂等。进一步地，幂等也不解决旧值覆盖新值：`set(300)` 后到达的 `set(200)` 仍会让状态回退。

## Source 保存的是恢复进度

Flink CDC MySQL 的新 Source 入口是 `org.apache.flink.cdc.connectors.mysql.source.MySqlSource`。它实现 `Source<T, MySqlSplit, PendingSplitsState>`，创建 Reader 和 Enumerator，并为 Split 与 Enumerator 状态提供序列化器。这与 FLIP-27 的职责划分一致：Enumerator 负责发现和分配工作，Reader 负责读取分片。[MySqlSource](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/MySqlSource.java#L95) · [FLIP-27](https://cwiki.apache.org/confluence/spaces/FLINK/pages/95653746/FLIP-27%2BRefactor%2BSource%2BInterface)

沿第一篇需要的范围，可以把路径缩成：

```text
MySqlSource
  ├─ Enumerator / Assigner：分片生成、分配和阶段推进
  └─ SourceReader / SplitReader：快照与 binlog 读取
       └─ MySqlRecordEmitter：处理内部事件、更新 Split 状态
            └─ DeserializationSchema：构造对外数据
                 └─ SourceOutput：交给 Flink 数据流
```

这里使用了 Debezium 的读取与事件能力，但不是把一个 Kafka Connect 作业原样放进 Flink。Split 的调度、恢复状态和输出接入遵循 Flink Source 协议；配置中也使用了 `EmbeddedFlinkDatabaseHistory` 等适配。[配置构造](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/config/MySqlSourceConfigFactory.java#L383)

**源码结论。**对于数据变更，`MySqlRecordEmitter.processElement()` 先更新当前 Binlog Split 的 startingOffset，再调用反序列化输出。心跳和事务元数据也有更新进度的分支，即使它们未被选择为对外输出。这里更新的是运行中的状态，不等于该位置已经进入一次成功 Checkpoint，更不等于外部 Sink 已提交。[Emitter](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/reader/MySqlRecordEmitter.java#L84)

恢复链路还能继续追踪两步：`MySqlBinlogSplitState.toMySqlSplit()` 将当前位置放回 Split；`MySqlSourceReader.snapshotState()` 汇集需要保存的分片，包括尚未收到确认的完成分片。完整的失败窗口和 Runtime 调用时序在第三篇展开。[Split 状态](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/split/MySqlBinlogSplitState.java#L67) · [Reader 状态快照](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/reader/MySqlSourceReader.java#L135)

不能仅凭“位点先更新、记录后输出”这一局部代码顺序，判断整个 Checkpoint 协议是否会丢数据。这样的判断还需要考察状态捕获、输出执行、失败传播和恢复的共同边界。

位点本身也比一个数更复杂。`BinlogOffset` 同时定义了文件名、文件位置、GTID 集合、已处理事件数和事件内行数等信息。一个事务可以包含多个变更事件，一个事件又可以包含多行，因此恢复进度可能细到事务或事件内部。[BinlogOffset](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/offset/BinlogOffset.java#L34)

| 字段 | 本篇需要理解的作用 |
| --- | --- |
| file、pos | 定位某段 binlog 历史中的位置 |
| gtids | 表达相关 GTID 集合信息 |
| event、row | 表达恢复时需要跳过的事件与行进度 |
| server_id、ts_sec | 参与部分比较分支，不能当作通用因果时钟 |

**上游测试实跑。**`BinlogOffsetTest` 的 7 项测试全部通过，覆盖文件位置、GTID、事件和行级进度等比较分支。其中相同 GTID 集合仍会继续比较 event 与 row；这直接否定了“GTID 相同就一定是完全相同恢复位置”的简化。

还需要保留一个重要的适用边界：对于测试中两组互不包含的 GTID 集合，当前实现正向、反向比较都返回 1，上游测试明确断言了这一行为。因此，这个 `compareTo` 不能被当作任意两段独立数据库历史之间的通用全序比较器。这个发现说明比较逻辑依赖使用场景；本文未据此推断生产中发生了数据丢失，也未验证主库切换流程。[比较实现](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/offset/BinlogOffset.java#L172) · [对应上游测试](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/test/java/org/apache/flink/cdc/connectors/mysql/source/offset/BinlogOffsetTest.java#L31)

## 事务元数据与下游原子可见是不同的能力

假设源端一个事务同时修改两行：

```text
订单状态：unpaid → paid
库存数量：10 → 9
COMMIT
```

源端事务保证这两次修改按数据库事务语义提交。变更进入 CDC 后，即使两条记录都有相同事务标识，也仍然需要一个下游协议决定何时发布、怎样协调参与者，以及读者从哪个版本读取。拥有事务标识只是构建这种协议所需的信息之一。

Debezium 1.9 支持事务 BEGIN/END 事件以及附着在数据事件上的事务信息，并且相关配置默认关闭。这个能力首先属于 Debezium，不能越过 Flink CDC 的配置、Emitter 和反序列化路径直接推断下游表现。[Debezium 事务元数据](https://debezium.io/documentation/reference/1.9/connectors/mysql.html#mysql-transaction-metadata)

**源码结论与单测结果。**通用 `MySqlRecordEmitter` 通过 `includeTransactionMetadataEvents` 控制事务边界事件是否送入反序列化器。其上游测试中，开关关闭时事务事件不输出但位点推进；打开时事件被输出。本轮 `MySqlRecordEmitterTest` 的 9 项测试全部通过。它们使用构造事件，不能证明从真实数据库到 Sink 的事务行为。

MySQL Pipeline 还有更具体的边界：`MySqlDataSource` 使用专用的 `MySqlPipelineRecordEmitter`。这个类调用父类构造器时，将心跳和事务元数据事件输出开关显式设为 `false`。[Pipeline Emitter](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-pipeline-connectors/flink-cdc-pipeline-connector-mysql/src/main/java/org/apache/flink/cdc/connectors/mysql/source/reader/MySqlPipelineRecordEmitter.java#L91)

另一方面，Pipeline 反序列化逐条产生 `DataChangeEvent`。该事件模型包含 tableId、before、after、操作类型和可选 metadata；MySQL 实现按所选元数据转换，不会仅因为上游具备事务信息就自动形成一个源事务提交批次。[事件模型](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-common/src/main/java/org/apache/flink/cdc/common/event/DataChangeEvent.java#L39) · [MySQL 元数据转换](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-pipeline-connectors/flink-cdc-pipeline-connector-mysql/src/main/java/org/apache/flink/cdc/connectors/mysql/source/MySqlEventDeserializer.java#L157)

由此能得出的结论是：**当前 MySQL Pipeline 的这条输出路径不提供“将源端 BEGIN 到 END 作为一个原子发布单元”的接口保证。**具体 Sink 仍可能提供自己的提交原子性，但必须单独核对提交单位和读取语义。Checkpoint 边界、源端事务边界和目标端提交边界不能默认相同。

本文没有实测 Paimon 或其他目标系统在同一源事务下的跨行、跨表可见过程，因此也不宣称某个目标系统已经出现了“半个事务”。

## 一致性应写成可检验的契约

在第一篇建立的范围内，评估一条 CDC 链路至少需要写清下面几项。

**复制对象。**同步的是当前表状态，还是从明确位置开始的变更历史；快照记录是否参与业务计数或触发动作。

**源端前提。**数据库与存储引擎、日志格式与保留条件、主键及分片键是否稳定、是否允许 DDL、是否涉及主库切换。本文的单实例实验不能替代这些场景的验证。

**阶段与接口。**结论属于快照初始化、稳定增量还是失败恢复；观察的是原始 SourceRecord、Pipeline 事件、受管理状态还是目标表。

**目标端效果。**如何处理重复与乱序，删除如何落地，写入是否存在额外副作用，目标端何时对读者可见。

**验收边界。**先确定停止写入、已知日志位置或其他可判定的追平条件，再比较完整键值与删除结果；需要历史完整性或事务可见性时，增加独立证据。

一个范围清楚的结论可以写成：在指定版本、固定 Schema、稳定主键和可回放日志条件下，作业追平到指定边界后，目标状态与源表一致；故障后的重复效果与事务可见性由另行验证的 Sink 协议约束。只有这些附加性质也被检查，才能继续扩大声明。

第二篇将沿 LOW/HIGH、回填缓冲与日志过滤继续推导：每个 Chunk 如何建立可衔接的状态，不同 HIGH 的 Chunk 又怎样进入持续日志阶段。

## 本文的验证记录

验证日期为 2026 年 10 月 18 日。Java 测试使用 Temurin 11.0.29；源码基准为前述固定提交，业务实现未修改，仅在临时源码副本中增加两项文章验证测试。数据库验证使用单独启动的 MySQL 8.4.11 容器，ROW binlog、GTID ON、无网络接入；没有使用已有业务数据库。

| 验证项 | 实际结果 | 证据层级 |
| --- | --- | --- |
| 上游 BinlogOffsetTest | 7 项通过，0 失败，0 跳过 | 原代码单元测试 |
| 上游 MySqlRecordEmitterTest | 9 项通过，0 失败，0 跳过 | 原代码单元测试，使用构造事件 |
| 新增 ArticleSnapshotMergeTest | 2 项通过，连续更新折叠、删除移除均符合断言 | 直接调用原实现的方法级测试 |
| 两会话读取视图 | 原 RR 事务读到旧值，结束后读到新值 | 真实数据库实验 |
| 未提交与提交后的日志边界 | 同文件位置 1301 → 1301 → 1616，提交后值与 GTID 前进 | 真实数据库实验 |
| 重复赋值与审计计数 | 最终金额 200，审计计数 2 | 真实数据库上的刻意重复写入模型 |

三组数据库验证与 Java 测试相互独立，不能合并声称“Flink CDC 3.7 已在 MySQL 8.4.11 上通过端到端验证”。

本轮未运行完整 Source 的扫描窗口集成实验、Checkpoint 故障恢复、主库切换以及外部 Sink 的提交与事务可见性实验。这些层面的证据状态为 `evidence_incomplete`。本文关于快照合并、事件转换与 Pipeline 输出开关的判断，分别以指向固定提交的代码和上述相应测试为依据。

复核材料保留了两次 Maven 日志、三份 Surefire XML、Java 验证源码、MySQL 验证脚本及完整 SQL 返回、binlog 事件列表、镜像标识和源码哈希。公开的测试源码、结果及复现条件见[系列验证材料](/flink-cdc-series/#evidence-1)，完整原始日志保留于作者本地。

---

[系列导读与验证材料](/flink-cdc-series/) · [一：一致性边界](/2026/10/18/flink-cdc-1-consistency/) · [二：增量快照](/2026/10/18/flink-cdc-2-incremental-snapshot/) · [三·上：Source 状态机](/2026/10/18/flink-cdc-3a-source-state/) · [三·下：故障恢复](/2026/10/18/flink-cdc-3b-checkpoint-recovery/) · [四：Schema 演进](/2026/10/18/flink-cdc-4-schema-evolution/) · [五：Paimon 提交](/2026/10/18/flink-cdc-5-paimon-commit/)
