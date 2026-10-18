---
layout: post
title: Flink CDC 深入解析（三·下）：Checkpoint 恢复与故障窗口
description: 用真实 MySQL 与 MiniCluster 检查恢复状态、故障窗口、物理重放和受管状态的一致恢复。
keywords: Flink CDC, MySQL, Checkpoint, DBLog, Paimon
categories:
  - Flink
tags:
  - Flink CDC
  - MySQL
  - 源码分析
permalink: /2026/10/18/flink-cdc-3b-checkpoint-recovery/
mermaid: true
---

[系列导读与验证材料](/flink-cdc-series/) · [一：一致性边界](/2026/10/18/flink-cdc-1-consistency/) · [二：增量快照](/2026/10/18/flink-cdc-2-incremental-snapshot/) · [三·上：Source 状态机](/2026/10/18/flink-cdc-3a-source-state/) · [三·下：故障恢复](/2026/10/18/flink-cdc-3b-checkpoint-recovery/) · [四：Schema 演进](/2026/10/18/flink-cdc-4-schema-evolution/) · [五：Paimon 提交](/2026/10/18/flink-cdc-5-paimon-commit/)

---

Flink CDC 在失败前已经读过一条 binlog，恢复后仍可能再读、再发送这条记录。判断恢复是否正确，必须同时检查 Source 恢复的位置和下游恢复的状态。只数网络或日志里出现了几次同一条消息，会把正常的重放误判成一致性故障。

第三篇分为上下两篇。[上篇](/2026/10/18/flink-cdc-3a-source-state/)回答快照何时能够切换到 binlog；这一篇沿着同一张主键表，分析未完成快照、完成报告、Split 分配和 binlog 输出几个故障窗口，再用实际作业重启检验其中两条恢复路径。核心问题是：失败以后，哪些事实仍然有效，哪些工作必须重做。

本文固定使用 Flink CDC 3.7.0 提交 `045e8da45df4b1017849b04e2d8bfe8f0ed7936d`，运行依赖为 Flink 1.20.3、Debezium 1.9.8.Final。实验于 2026 年 10 月 18 日完成，使用 Java 11.0.29、MySQL 8.4.11，开启 GTID，binlog 为 ROW/FULL。主线是默认回填开启的 MySQL `initial` 模式；讨论的作业使用默认对齐 Checkpoint。

## 恢复的是同一个 Checkpoint 对应的状态

Carbone 等人的 Flink 状态管理论文把恢复建立在一致状态快照和可重放输入之上：失败后回到一个已完成的逻辑切面，再重新消费切面之后的数据。论文也把内部状态保证与外部 Sink 副作用分开讨论。这里借用这两个原则分析恢复；2017 年的论文并不包含后来引入的 FLIP-27 Source 代码，也不能代替当前版本的实现核对。[论文 §3.2 与 §4.4](https://www.vldb.org/pvldb/vol10/p1718-carbone.pdf)

设成功 Checkpoint C 保存了 Source 的位置 O，以及下游状态 S。C 之后处理的两条更新可能已经改变内存状态，也可能已经到达某个外部观察者。任务失败后，Flink 恢复 O 和 S，重新处理后续记录。受管状态经过回滚和重放，可以得到一致结果；外部观察者已经看到的消息不会因此自动消失。

因此，这篇文章分别使用三种证据：源码结论说明实现会做什么；对象级协议测试通过真实类和序列化器检验受控时序；作业实验让真实 MySQL、Flink Source、Checkpoint 和任务重启共同运行。后两者的覆盖范围不同。

## Source 恢复同时涉及两份状态和分配账本

Reader 的状态由 `SourceOperator` 写入 Operator State。取 Checkpoint 时，它调用 `sourceReader.snapshotState(checkpointId)`；恢复启动时，再把保存的 Split 交给 `sourceReader.addSplits(...)`。对 Reader 而言，收到恢复的 Split 和收到新分配的 Split 使用同一个入口，但 Split 中携带的状态决定接下来是继续读、重新读，还是重报完成。[SourceOperator 状态与恢复入口](https://github.com/apache/flink/blob/release-1.20.3/flink-streaming-java/src/main/java/org/apache/flink/streaming/api/operators/SourceOperator.java#L350)

Enumerator 的状态则经 `SourceCoordinator.checkpointCoordinator` 单线程事件循环序列化。完整恢复时，`MySqlSource.restoreEnumerator` 根据保存的 `PendingSplitsState` 类型重建 Assigner。它恢复的不是一份“所有 Reader 当前执行到哪里”的即时数据库，而是指定 Checkpoint 下的分配进度、完成水位和阶段状态。[SourceCoordinator Checkpoint](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/source/coordinator/SourceCoordinator.java#L393)，[CDC 恢复工厂](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/MySqlSource.java#L238)

两者之间还存在一个容易漏看的对象：Flink Runtime 的 `SplitAssignmentTracker`。它记录哪些 Split 在哪个 Checkpoint 周期内分配给哪个子任务，使存活的 Coordinator 在子任务回退时能够找回不被恢复点覆盖的分配。

| 恢复所需信息 | 负责组件 | 实际用途 |
| --- | --- | --- |
| 已分配 Split 的执行状态 | Reader 的 Operator State | 恢复未完成 Chunk、已完成但未 ACK 的 Chunk、binlog 位置等 |
| 待分配工作及阶段状态 | Enumerator Checkpoint | 继续枚举、分配，重建完成水位和切换条件 |
| 恢复点之后发生的分配 | Runtime 的 SplitAssignmentTracker | 子任务局部恢复时，把 Reader 状态里不存在的后续分配退回 Enumerator |

这不是说流式 Checkpoint 文件里统一存着三张相同的表。在 Flink 1.20.3 的流式路径中，`SourceCoordinator` 先调用账本的 `onCheckpoint`，再序列化 Enumerator；整条作业重建和 Coordinator 存活时的子任务恢复，需要分开读。把账本的职责直接归给 CDC 的 `assignedSplits`，会漏掉 Runtime 的恢复协议。[流式与批式 Checkpoint 分支](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/source/coordinator/SourceCoordinator.java#L393)

## Split 为什么不能在失败后全部重新分配

假设一个 Reader 先后收到 A、B、C 三个 Split。A 的分配已被成功 Checkpoint 10 覆盖；B 在随后发起但尚未成功的 Checkpoint 11 之前分配；C 在 Checkpoint 11 之后分配。现在任务必须回到 Checkpoint 10。

```text
分配 A → 成功 C10 → 分配 B → 发起 C11 → 分配 C → 失败
                    恢复点仍是 C10

Reader 从 C10 恢复：A 及其 C10 时刻的执行状态
Runtime 退回 Enumerator：B、C
```

`SourceCoordinator.subtaskReset` 取得 `getAndRemoveUncheckpointedAssignment(subtaskId, restoredCheckpointId)` 的结果，再调用 Enumerator 的 `addSplitsBack`。账本会回收恢复点之后各 Checkpoint 周期以及当前尚未归档的分配。A 已由 Reader 状态负责，不能再按同样理由重新分配一遍。[subtaskReset](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/source/coordinator/SourceCoordinator.java#L346)，[SplitAssignmentTracker](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/source/coordinator/SplitAssignmentTracker.java#L102)

对象级测试直接调用 Flink 1.20.3 的 `SplitAssignmentTracker`，得到的返回列表恰好是 B、C。这验证了恢复点分类规则，没有模拟网络通信或调度器故障。

CDC 收到退回的 snapshot Split 后，会重新加入待分配集合，删除原 `assignedSplits` 项和该 Split 的完成水位。收到退回的 binlog Split 时，Hybrid Assigner 则撤销已分配标记，保留重新构造所需的快照元数据。这里的退回通常代表“不被 Reader 的恢复点覆盖的分配”；已覆盖的 binlog Split 应从 Reader 状态恢复其位置，不能把这两个路径混成“失败后一律从最小 HIGH 重读”。[snapshot 退回处理](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlSnapshotSplitAssigner.java#L450)，[binlog 退回处理](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlHybridSplitAssigner.java#L201)

## 逐个检查快照阶段的故障窗口

| 故障窗口 | 源码中的恢复依据 | 本轮证据 |
| --- | --- | --- |
| Chunk 扫描或回填尚未完成 | Split 仍没有已发射 HIGH，恢复后重新读取该范围 | 真实作业实验 |
| Chunk 已输出完成但尚未收到完成 ACK | finishedUnackedSplits 随 Reader 状态保存，恢复后重报 | Reader 对象与真实序列化测试 |
| Enumerator 已 ACK，但更新尚未进入成功 Checkpoint | 回到旧恢复点；ACK 本身不建立持久恢复点 | CDC 与 Runtime 调用链核对 |
| 全部报告已收齐，但切换 Checkpoint 尚未成功 | 保持快照阶段，不能分配初始 binlog Split | Assigner 对象测试 |
| 切换 Checkpoint 成功后、下一次状态保存前失败 | 恢复出来的 Assigner 可能仍处于 INITIAL_ASSIGNING | 实际字节序列化后的对象恢复测试 |
| binlog 已输出新数据，但新的 Checkpoint 尚未成功 | 恢复旧位置并重放，受管下游同时回滚 | 真实作业实验 |

### 未完成 Chunk 没有逐行持久进度

正常回填路径把 Chunk 扫描结果和窗口内日志合并在内存中，再输出规范化结果。Reader 的 snapshot Split 状态保存范围及完成 HIGH，没有把 JDBC 结果集游标或回填 Map 的每一步作为恢复状态。因此，Checkpoint 恰好发生在数据库读取过程中，并不意味着恢复时能从该 Chunk 的第 N 行继续。[SnapshotSplitReader 缓冲路径](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/debezium/reader/SnapshotSplitReader.java#L312)，[MySqlSnapshotSplitState](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/split/MySqlSnapshotSplitState.java)

重读的基本单位是仍未完成的范围。新的读取可能面对已经变化的数据库状态，因此还必须重新执行 LOW/HIGH 与回填流程。它不是把旧 JDBC 连接复活，也不是恢复一次尚未提交的数据库快照事务。对已完成且被成功 Checkpoint 覆盖的其他 Chunk，则没有理由一起重扫。

### 完成 ACK 为什么不会制造状态空洞

先构造一个危险时序：Enumerator 先做 Checkpoint，之后收到完成报告并 ACK；Reader 再做同一轮 Checkpoint，却已经因为 ACK 删除了本地完成记录。如果这组动作能任意穿插，就可能出现双方 Checkpoint 都没有保存完成事实的空洞。仅说“有 ACK 重试”不能排除它。

Flink 的 `OperatorCoordinatorHolder` 与 `SubtaskGatewayImpl` 正是这条因果链的一部分。Coordinator 的 Checkpoint future 完成后，gateway 关闭并缓冲属于后续周期的下发事件；相关 Source 子任务确认该轮 Checkpoint 后，gateway 才重新放行。这个协议也依赖调度器到任务的事件传输顺序。因此，完成 ACK 不能脱离 Runtime 的 Checkpoint 事件排序单独分析。[OperatorCoordinatorHolder 协议与实现](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/operators/coordination/OperatorCoordinatorHolder.java#L57)，[SubtaskGatewayImpl](https://github.com/apache/flink/blob/release-1.20.3/flink-runtime/src/main/java/org/apache/flink/runtime/operators/coordination/SubtaskGatewayImpl.java#L140)

本轮对这个窗口完成了源码链路核对，但没有在 RPC 层制造 ACK 与 Barrier 的乱序。Reader 测试只证明：没有 ACK 时，已完成 Split 可以被序列化并恢复到待确认集合；恢复不启动该 Split 的扫描，收到重报请求会再发完成报告，收到 ACK 后才从下一次 Reader 状态中消失。两种证据不能合并成一次网络故障实测。

### 完成通知不会追写已经生成的 Checkpoint

并行度大于 1 时，`snapshotState(11)` 若发现所有快照 Split 完成，会记住 `checkpointIdToFinish=11`。但这次返回并序列化的状态仍可能是 `INITIAL_ASSIGNING`。随后 `notifyCheckpointComplete(11)` 才把运行中的状态改为 `INITIAL_ASSIGNING_FINISHED`；完成通知不会回头修改已经保存的字节。

本轮把这个边界真正序列化再恢复。结果如下：

```text
原 Assigner：报告全部完成 → 保存 C11 → 完成 C11 → 可以分配 binlog
保存字节中的状态：INITIAL_ASSIGNING

新 Assigner：从 C11 字节恢复 → 通知 C11 完成 → 仍不可分配
新 Assigner：保存 C12 → 完成 C12 → 可以分配 binlog
```

原因是 `checkpointIdToFinish` 属于运行时门槛，没有随 `SnapshotPendingSplitsState` 保存；恢复后需要重新建立这个门槛。这说明恢复点靠近阶段边界时，可能多等一次成功 Checkpoint。它不等于快照数据丢失，也不能把“恢复成功但仍等 Checkpoint”直接判成状态机卡死。[状态快照与完成回调](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlSnapshotSplitAssigner.java#L462)

## 实验一 在快照 HIGH 前失败

实验表为 `cdc_article3.items(id INT PRIMARY KEY, amount INT NOT NULL)`，初始主键 101 至 110，amount 均为 10。Source 并行度设为 1，Chunk 大小设为 4，使用真实 MySQL 和单进程 MiniCluster。测试 Sink 把收到的记录保存在 Flink Operator ListState，另用不受 Checkpoint 管理的列表记录每一次实际送达。

测试在第一个 Chunk 的 `preHighWatermarkAction` 中暂停数据库读取线程，主任务线程仍能执行 Checkpoint。C1 成功后，Sink 状态为空。测试随后释放读取线程并主动抛出一次 SQLException，使正常故障处理路径重启任务；没有手工创建新的 Reader，也没有由测试代码直接调用恢复方法。

实际结果是执行 attempt 从 0 变成 1，Sink 从 C1 恢复为空列表，随后得到主键 101 至 110 的 10 条不同 READ，C2 再次成功完成。异常栈明确指出第一个范围为 `splitStart=null, splitEnd=[105], highWatermark=null`。

这个实验验证了“Checkpoint 保存未完成 Chunk，任务异常后重读并继续快照”的实际路径。它没有验证一个已经输出部分数据、又开启跳过回填选项的 Chunk；默认回填路径在 HIGH 和回填完成之前并未把该 Chunk 的规范化结果交给下游。也没有在实验过程中修改这张表，因而不能把结果扩大为所有并发写时序的恢复证明。

## 实验二 在 binlog 更新输出中途失败

第二个作业使用同样的 10 行初始数据，把 Source 并行度改为 2。先通过成功 Checkpoint 放行快照到 binlog 的切换，再执行一次 `101=11` 的预热更新，确认作业已经进入连续日志阶段。C3 保存的 Sink 基线是 10 条 READ 加这一条 UPDATE，共 11 条。

随后，在一个 MySQL 事务内依次执行以下三条语句并提交：

```sql
UPDATE cdc_article3.items SET amount=20 WHERE id=101;
UPDATE cdc_article3.items SET amount=30 WHERE id=101;
UPDATE cdc_article3.items SET amount=40 WHERE id=102;
```

测试 Sink 在第一次收到 `101=30` 时，先写入自己的内存观察列表，再抛出一次异常。自动 Checkpoint 间隔设置为 600 秒，测试期间由程序手动触发 Checkpoint，因此这个事务的输出不会在故障前悄悄进入另一个成功恢复点。

| 观察位置 | 实际结果 |
| --- | --- |
| 故障后的执行 attempt | 1 |
| Sink 从 C3 恢复的列表 | 11 条基线记录 |
| 非受管 raw 列表中的事务更新 | 101=20、101=30、101=20、101=30、102=40 |
| 恢复后受管列表中的事务更新 | 101=20、101=30、102=40 |
| 恢复后的受管列表总数 | 14 |
| 整次 raw 观察中的 READ 数 | 10，没有重新出现一轮全表快照 |
| 恢复后完成的 Checkpoint | C4 |

日志中的两次 `20` 和两次 `30` 不是推算值，而是测试捕获并逐项断言的实际送达结果。与此同时，受管列表先恢复到 11 条，再追加三条更新，最终为 14 条。相同作业里同时出现了“物理送达重复”和“恢复后受管状态没有重复应用”。

这个 Sink 是用于观察的 CheckpointedFunction，不是 MySQL 两阶段提交 Sink，也不是 Paimon。实验只能证明该作业受管状态的回滚与重放，不能证明任意外部系统都不会收到重复请求。

### 这个事务实验还不能证明什么

`MySqlRecordEmitter` 在处理 binlog 记录时推进 Split 的 `startingOffset`，该位置随 Reader 状态保存。实际位置不仅是文件名和字节位置，还涉及 Debezium 的恢复坐标。恢复后是否精确跳过事务内已处理事件，要继续检查 offset 的生成、序列化以及 Debezium 启动语义。[Emitter 推进位置](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/reader/MySqlRecordEmitter.java#L84)，[BinlogOffset](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/offset/BinlogOffset.java)

本轮故障发生在一个已提交事务的输出中间，但恢复用的 C3 在整个事务之前。因此实验确实验证了完整事务数据从旧恢复点重放，却没有验证“Checkpoint 正好保存事务中间的 event/row 位置，再从那里恢复”的全部边界。把这两种情况写成同一个实验结论，会夸大证据。

<h2 id="chunk-recovery">跨 Chunk 迁移：更早的恢复点不等于整表重扫</h2>

第二篇的漏行反例提出了一个不同的问题：如果 Source 的选择规则已遗漏一行，Checkpoint 恢复能否补回？原来的两项作业使用受管 ListState，不能回答真实目标表的结果。因此另行执行 **4 个 MySQL → Flink MiniCluster → Paimon 故障场景**，与第二、五篇共用证据，不计入原有 53 项。

固定 CDC `045e8da…`、Flink 1.20.3、Paimon 2.0.0、MySQL 8.4.11；表有 20 行，主键 id 不变，非主键 c 用作 Chunk key。真实 Splitter/Assigner 生成 `A=(-∞,105)`、`B=[105,+∞)`；p=1、bucket=1。A HIGH 之后、B LOW 之前，将 id102 的 c110 改为 100；稳定键对照只将 amount20 改为 21。水位和原始 binlog 审计确认 `H_A < E_102 < L_B == H_B`。

两个恢复点并非按编号猜测，而是这样建立：

1. A 已输出 10 行，B 暂停在 pre-LOW，迁移尚未发生。完成 C1；Reader 实际保存 B 的 snapshot Split，HIGH 为 null。
2. 释放 B、提交迁移，快照完成后等待持续日志输出 id103 amount=32。迁移后组再完成 C2；Reader 保存带实际 file/pos/GTID/event/row 的 binlog Split。迁移前组不完成这个额外 Checkpoint。
3. 两组各触发下一次 Checkpoint，在真实 Paimon precommit 下游阻塞。独立 Catalog 确认新 snapshot 的 commitIdentifier 等于该 Checkpoint，而 Future 仍未完成、没有完成回调；随后抛异常，走 Flink 的 task 重启。

| 观察项 | 迁移前恢复组 | 迁移后恢复组 |
| --- | --- | --- |
| Flink 初始化上下文给出的恢复点 | C1 | C2 |
| 失败 Checkpoint | C2；未加入完成列表 | C3；未加入完成列表 |
| 恢复的观察算子数据记录计数 | 10 | 迁移组 21、对照组 22；是事件数，不是目标行数 |
| Reader 恢复入口收到的首个 Split | B snapshot，HIGH=null，与 C1 相同 | binlog Split，范围/位点文本与 C2 相同 |
| 整次运行执行的 snapshot 扫描 | A、B、恢复后的 B，共 3 次 | A、B，共 2 次 |
| 后续成功 Checkpoint | C3、C4 | C4、C5 |
| 迁移组全量结果 | MySQL 20 行、Paimon 19 行，缺 id102 | 同左 |
| 稳定键对照全量结果 | 20 行 × 3 列一致 | 20 行 × 3 列一致 |

**实际结果**：四组均进入 attempt=1，并恢复预定的已完成 Checkpoint；没有让失败 Checkpoint 成为恢复点。恢复后在首个成功 Checkpoint、额外成功 Checkpoint、取消作业后及新 JVM 回读四个时点，对全部主键和 `id,c,amount` 逐字段比较。迁移组仅缺 `(102,100,20)`，其余 19 行一致；对照组全部一致。两个迁移场景的行为断言通过，数据正确性均为 false。[探针、状态轨迹及全量对账](/flink-cdc-series/#evidence-chunk-recovery)。

**源码解释与实测范围**：前文所说“未完成范围重读”在 C1 恢复组表现为只重扫 B。迁移前并不意味着 A 的完成事实也被回滚；A 不重扫，B 中又已没有 id102，后续追赶继续受 before-key/HIGH 过滤。C2 恢复组则直接从保存的 binlog 进度继续。这说明恢复成功不自动证明输入选择完整，也不能用第五篇另一组“当前表收敛”的结果代替本组全量对账。

本组未测试 A 尚未完成时的 Checkpoint、后续再次更新缺失行、全量重建、事务内部恢复、多并行度或物理进程退出。异常注入和状态观察位于独立探针，生产过滤、分配与提交代码未改。真实 Paimon 提交窗口的细节见[第五篇附录 H](/2026/10/18/flink-cdc-5-paimon-commit/#appendix-chunk-recovery)。

## 元数据何时才能安全释放

快照结束后，Enumerator 保存的大量范围和 HIGH 仍可能被 binlog Reader 用来拼接过滤元数据。如果 Split 元数据较多，需要分组传输；收到 binlog Split 的壳，不等于 Reader 已经拿齐这些内容。

这个提交提供了默认关闭的 `releaseSnapshotMetadataEnabled` 优化。释放条件不是“binlog 已分配”，而是 Reader 报告元数据组装完成，并且覆盖该事实的后续 Checkpoint 成功。Reader 重置会取消待释放状态并推进分配 generation，旧尝试发来的组装确认不能代替当前尝试。内联完整元数据另有明确的确认标记。[Hybrid 释放协议](https://github.com/apache/flink-cdc/blob/045e8da45df4b1017849b04e2d8bfe8f0ed7936d/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlHybridSplitAssigner.java#L220)

本轮执行了上游已有的 18 项元数据释放测试，覆盖未确认、Checkpoint 尚未完成、Split 退回、旧 generation、默认关闭、轻量状态恢复等分支。它们是内存协议测试；上面的两次作业实验使用默认配置，没有开启这一优化。因此，对“开启释放选项后的真实集群故障恢复”，证据仍不完整。

## 原始验证与新增覆盖

| 测试类别 | 数量 | 结果 |
| --- | ---: | --- |
| 新增 Assigner 与 Runtime 账本协议测试 | 5 | 全部通过 |
| 新增 Reader 完成 ACK 状态测试 | 1 | 通过 |
| 上游元数据释放测试 | 18 | 全部通过 |
| 上游 Enumerator 状态序列化测试 | 24 | 全部通过 |
| 上游 Split 序列化测试 | 3 | 全部通过 |
| 新增真实 MySQL 与 MiniCluster 重启实验 | 2 | 全部通过 |

原始覆盖合计 53 项，0 失败、0 错误、0 跳过；本次没有重跑这些历史测试。另增上述 4 个真实 Paimon 故障场景，独立计数。原始两项作业实验都是主动抛出异常后由 Flink 重启 task attempt，没有退出物理 TaskManager 或 JobManager 进程，也没有注入 JM leadership 切换。

尚未实测的关键边界包括：Checkpoint 中间位置的事务内恢复、ACK/Barrier 的 RPC 故障注入、并行度变化、savepoint 跨版本恢复、binlog 清理与 MySQL 主从切换、新增表和 DDL。真实外部 Sink 的提交可见性现已补充上述 Paimon 特定窗口，其他 Sink、存储后端和故障位置仍未覆盖。这些范围标为 `evidence_incomplete`，不能从本轮通过数量外推。

## 沿源码继续精读

| 阅读顺序 | 入口 | 要回答的问题 |
| --- | --- | --- |
| 1 | SourceOperator.snapshotState 与 open | Reader 状态在哪保存，又从哪里交还给 Reader |
| 2 | MySqlSourceReader.snapshotState 与 addSplits | 活动、已完成待确认和未组装完的 Split 如何恢复 |
| 3 | SourceCoordinator.subtaskReset 与 resetToCheckpoint | 子任务恢复和 Coordinator 重建为何不同 |
| 4 | SplitAssignmentTracker | 恢复点之后的分配由谁退回 |
| 5 | OperatorCoordinatorHolder 与 SubtaskGatewayImpl | ACK、Split 下发与 Checkpoint 怎样维持事件顺序 |
| 6 | MySqlSnapshotSplitAssigner 的 snapshotState 和 notifyCheckpointComplete | 已保存状态与运行中状态为何可能跨越两个阶段 |
| 7 | MySqlRecordEmitter 与 BinlogOffset | 已发射进度怎样变成下一次恢复的位置 |

复现实验的新增类是 `ArticleSourceProtocolTest`、`ArticleReaderAckTest` 和 `ArticleSourceRecoveryITCase`。本轮证据包 `flink-cdc-article-03-evidence-20261018` 保留了这三个测试源码、三次 Maven 输出、Surefire XML 与对应 Runtime 源码。测试使用独立 MySQL 实例，只重建自己的实验表。

判断一次 Source 恢复，最终要对齐三件事：所选成功 Checkpoint 保存了哪些工作、Runtime 会退回哪些分配、Reader 恢复的进度是否与下游恢复的状态属于同一个切面。快照 HIGH、完成 ACK 和最新读到的 binlog 位置，都不能单独代替这三个条件。


公开测试源码、结果和复现条件见[系列验证材料](/flink-cdc-series/#evidence-3)。

---

[系列导读与验证材料](/flink-cdc-series/) · [一：一致性边界](/2026/10/18/flink-cdc-1-consistency/) · [二：增量快照](/2026/10/18/flink-cdc-2-incremental-snapshot/) · [三·上：Source 状态机](/2026/10/18/flink-cdc-3a-source-state/) · [三·下：故障恢复](/2026/10/18/flink-cdc-3b-checkpoint-recovery/) · [四：Schema 演进](/2026/10/18/flink-cdc-4-schema-evolution/) · [五：Paimon 提交](/2026/10/18/flink-cdc-5-paimon-commit/)
