---
layout: post
title: "从 Flink CDC 到 Pekko（四）：批转流恢复的证明链——固定范围、封存身份与激活边界"
description: "固定范围、封存内容、稳定身份、T/J/D 及资源准入的恢复前提与失效边界。"
permalink: /2026/10/18/pekko-cdc-4-protocol-design/
keywords: Pekko, Flink CDC, MySQL, Actor Journal, 故障恢复
categories:
  - Pekko
tags:
  - CDC
  - Pekko
  - Flink CDC
  - 源码分析
mermaid: true
last_modified_at: 2026-10-27
---

[系列导读与验证范围](/pekko-cdc-series/) · [1：目标架构](/2026/10/18/pekko-cdc-1-resident-tasks/) · [2：批转流源码](/2026/10/18/pekko-cdc-2-recovery-contract/) · [3：真实实验](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/) · [4：恢复协议](/2026/10/18/pekko-cdc-4-protocol-design/) · [5：自建与复用](/2026/10/18/pekko-cdc-5-flink-comparison/) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **文章修订：2026-10-27。** G1—G4 的原实验链接保留原冻结版本；启动门已提交于 [`74b6d9a`](https://github.com/Xuxiaotuan/pekko-reference/tree/74b6d9ad1ebcfe19a786f4e1869aed276a63bb52)。有界 step、SQL owner/grant 与受管释放按本轮候选源码论证；其冻结版本及真实验证结果见[平台实验报告](/pekko-cdc-series/cdc-platform/)。源码分支不是自动恢复的实验结果。

五表增加到七表时，f/g 的初始化需要跨越多个数据库事务、一次或多次进程故障。本文回答：哪些持久事实足以让恢复安全地继续，哪些事实缺失时必须暂停？我们先推导 G1—G4，再说明有界调度为什么不能削弱这些数据协议。第三篇的 I08/I11/I16/I21 给出指定窗口的证据；旧启动反例与新版安全拒绝，则划出了 G0 尚不能无条件成立的边界。

这里讨论固定 Schema、单列有符号整数主键、最初为空且独占的事务目标。结论限于选定表的行状态与当前页/组提交契约；不包含跨表事务原子可见、任意外部副作用或多 owner 接管。

<a id="g0"></a>

## 1. G0：先声明所有恢复推理依赖的条件

| 条件 | 内容 | 当前证据或缺口 |
|---|---|---|
| A0：捕获连续 | B 之后所需的完整组与 marker 持续进入 L；恢复不跳过尚未捕获的区间 | 旧 INITIAL-D 反例保留；新版启动门拒绝已识别风险，不追溯补齐旧缺口 |
| A1：输入可解释 | 稳定源谱系、InnoDB、ROW/FULL、`binlog_order_commits=1`、固定 Schema、合法单主键 | 当前校验与首版类型限制；不等于支持 DDL 演进 |
| A2：窗口可定位 | B、U、spec 和本次 LOW/HIGH 可核对，所有窗口 HIGH 在固定 F 之前 | 固定范围、fence 与完成链审计 |
| A3：效果可重放 | 页与组身份稳定、完整行替换/删除按规定顺序应用；证据与效果同目标事务 | 当前目标 receipt/组账本；不允许额外目标写者改写业务行 |
| A4：材料可恢复 | J、L、RAW/FINAL、manifest 和目标证据保留且可验证，执行不发生未隔离重叠 | 缺失/冲突应暂停；本轮增加持久 grant 与受管释放，未扩展新链路自动接管或 GC |

A0 不是一句“代码应当如此”的声明。新版可以在创建 Engine 前拒绝旧 B+INITIAL，然而已经静默越过变化、后来又写回完成态 D 的旧历史，并不一定可由现有证据识别。因此下面仍是满足前提时的推理和已检验的局部性质，不能宣称全部历史都已连续。

T、J、D 和 L 分别描述目标效果、Actor 接受进度、SDK 恢复上下文和已保存输入。它们有不同坐标，无法用一个最大值修齐。源变化若从未进入 L，现有 ACK 链与目标账本都不能把它补出来。

## 2. 哪些状态参与决策，哪些资源不能持久化恢复

[BootstrapProgress](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala#L44)主要保存：

```scala
upper, planned, nextLower, index,
attempt, window, manifest, appliedPage,
fence, deltaAfter, batchSequence,
completionRoot, recoveryAttempts, cancelRequested, failure
```

U 和 spec 回答“原来负责哪个范围”；manifest 回答“可否重交同一输出”；appliedPage 和 deltaAfter 回答“J 已经接受哪项效果”。相应的 RAW/FINAL、范围链和目标 receipt 在各自存储中核对。

数据库连接、RR 视图和 SDK 提交句柄不在 Journal 里。JVM 重启后重新创建这些资源，必须重新证明它们与持久事实的关系。当前正常阶段为 `QUEUED → SCANNING → REPLAYING → ACTIVATING → STREAMING`，另有取消和暂停分支。

<a id="g1"></a>

## 3. G1：丢失读视图后，为什么只能保留范围整段重读

要求是：同一个逻辑范围在失败前后不改变，未封存输入不能被当成跨读视图可拼接的结果。

[原实验版本的恢复分支](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L202)检查窗口 manifest。没有封存时，新 attempt 登记 `spool.spec(w)` 的原值，appliedPage 归零，再读取原范围；旧 RAW 与失败材料保留。

推理分三步：RR 的一致性属于旧连接事务；故障后只剩若干 RAW 页，并没有“剩余页面属于同一时刻”的证明；这些 RAW 尚未对目标产生 baseline 效果，因此可以放弃本 attempt，重新夹住 LOW/HIGH 并整段读取。重算范围会改变原责任边界，接续旧游标则会把不同视图拼在一起。

新调度路径还要处理同 owner 的缓存替换。`step` 正常返回、JDBC close 已确认，只能证明这一轮资源可让出；它没有让旧 RR 事务跨越 Runner 对象重建。当前 [`BootstrapStepContinuation`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStep.scala)区分两个条件：

```text
有 window + 没有 spool manifest + J 未接受 manifest 且 appliedPage=0
    → 原 RAW 放弃，原 spec 新 attempt 整段重读

J 已接受 manifest 或已接受页效果 + 对应 seal 丢失
    → 材料破坏，暂停；禁止回源重定义该页
```

第一个分支避免把“干净让出”误当成“读视图还在”；第二个分支防止以重读掩盖已接受输出丢失。clean continuation 本身还须属于同一 runtime owner，并与完整 J 相符；单个 phase、revision 或最新游标都不足以替代这项核对。允许的并发变化仅是保留全部数据进度的取消接受。对象重建并非新的源故障，不能无依据地再扣一次相同恢复预算。

[I11](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i11)直接检查这项性质：边界键 32 被删除后，新旧 spec 仍为 `(null,"32"]`，窗口由 `a_0_0` 变为 `a_0_1`，最终 378 字段相等。范围不漂移与终值相等是两项断言，缺一不可。

<a id="g2"></a>

## 4. G2：封存身份怎样跨过 T 已提交、J 未接受的缺口

要求是：一旦输出封存，同一窗口/页 identity 只允许同一 manifest 和内容；T 领先 J 时，恢复核实原效果，不重新定义那项效果。

[BootstrapStore.seal](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStore.scala#L76)在状态事务保存 FINAL 页和 manifest；[BootstrapTarget](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapTarget.scala#L44)在目标事务内核对认领、查询 receipt，必要时一起提交页 DML 与 receipt。Runner 随后才更新 J.appliedPage。

因此目标事务只有两类已知结果：未提交时没有这项 receipt 与效果，重交原页；已提交时二者同时存在，核对同一 payload 后不再次执行该页 DML。若客户端不知道 COMMIT 结果，仍协调同一 identity，不能换一个内容后重试。已有 receipt 但内容不同，意味着证据冲突，应停止。

[I16](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i16)的真实缺口中，原 receipt 存在、J 仍停第 0 页，恢复产生 `BASELINE_T new=false`，再推进其余页。这支持该指定窗口的页重交机制。完整组账本在 B→F 与 tail 中承担对应职责，交付重新分批不应改变组身份。

| 观察到的故障事实 | 当前协议要求 |
|---|---|
| COMMIT 结果未知 | 用原 identity 核实/重试，遵守恢复预算及释放证据 |
| J 明确拒绝 | 保留上下文暂停，不把明确拒绝当作结果未知消耗重试 |
| J 声称页已接受，目标证据缺失 | 验证失败；不能直接从下一页继续 |
| Future 超时或连接 close 未确认 | 不能推定在途事务停止；释放未知保留占用 |

这没有把一个源事务整体包成一个目标事务。baseline 可能已经包含某行的新值，B→F 还会按组应用更早再更新的行值；目标副作用的次数不是本文的通用承诺。

<a id="g3"></a>

## 5. G3：固定 F 与激活为什么需要两个提交点

要求是：恢复不能移动原有限回放终点；只有完整范围与目标应用证据审计通过，J 才接受 STREAMING。

每完成一段，`spool.complete`接到范围摘要链，J 推进 index/nextLower。全部范围完成后登记固定 operation 的 F，J 进入 REPLAYING，起点是原 B。重复请求核实原 F，不选择当前更新的位点替代它。

[replay](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L156)先提交目标完整组，再推进 J.deltaAfter；没有剩余 GROUP 时，游标明确落在 F 帧序号。空表也不需要伪造一个业务组。随后[audit](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L87)核对范围连续、封存证明、目标页 receipt 和 B→F 组账本；它审计保存的证明，不是重新全量查询源表对账。

Runner 先写激活 T，再由 J 接受 STREAMING。若在两者之间崩溃，恢复重新审计、复用同一激活 receipt，不能凭它单独声称业务已经发布。I21 切点的 J 仍为 ACTIVATING、F 序号 54；恢复保留证明后转入 STREAMING，继续到 L 序号 57。这就是 G3 的代表性实测窗口。

<a id="g4"></a>

## 6. G4：完整 B→F 回放为何能够得到 F 的行状态

令 `S_F(k)` 为源在固定 F 边界处主键 k 的行或不存在状态，`E_(B,F]` 为应保留区间内的变化。baseline 由不同窗口读出并回填，它不是全表同一个 RR 时刻。满足 A0—A4，并完成有限 B→F 回放、尚未应用 F 之后事件时，可按每个主键分两类推理。以下证明这个切面的行状态；G3 激活接受这份基线，STREAMING 随后继续推进，不承诺任意时刻查询目标都得到 F 快照，也不证明固定完成时限：

**区间内没有触及 k 的事件。** 该键的存在与行内容在 B 到 F 之间不变。被固定范围覆盖的快照读取能保留这个值；源与目标都不存在的键保持不存在。初始空目标排除了无来源的额外旧行。

**区间内存在触及 k 的事件。** 不论 baseline 暂时包含哪个窗口时刻的值，按顺序回放至最后一次触及 k：完整 after image 把该键设为对应行，DELETE 把它设为不存在；在 F 之前再无后续变化，因此这一结果就是 `S_F(k)`。主键修改由 d/c 成员分别处理两个键，必要成员不能丢失。

这说明采用完整行替换/删除且身份、顺序正确时，B→F 的最后一次有效作用可消除混合 baseline 时刻带来的差异。窗口 fold 负责局部输出，完整回放负责所有窗口间和 U 之外的后续变化；[I08](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i08)检验了其中双向迁移的一个实例。

推理依赖必要事件真正进入 L，baseline 与原始组去重命名空间分开，目标提交不发生未隔离重叠，并且激活之前不向业务发布中间状态。若目标有其他写者、使用增量累加而非完整替换、提前清掉账本，或漏掉一笔源变化，这个结论都不能沿用。

## 7. INITIAL-D 反例具体破坏了哪一步

[E0](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-startup)中，源确实存在 id1 的更新，但它没有进入这次可回放的 L；SDK 从 INITIAL 恢复时重新选了更晚起点。按“L 里最后一次触及 k”的推理仍会得到旧值，因为输入集合已经不完整。后续 id2 witness 到达，不会修复缺失输入。

当前顺序也必须分开理解：

```text
完整组/Schema/marker持久到L → cover记录ACK引用
→ 原始连续回调句柄调用 → SDK后续flush → 实际D
                  └→ 表消费者应用目标T → Actor接受J
```

[accept](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L181)先执行 `store.cover(...)` 再调用原始句柄；covered 不等于 D 已落盘，ACK 链完整也不等于未观察区间连续。已有 B 而原始 D=INITIAL 时，必须在 SDK 重选点前作连续性判定，不能用之后变成非 INITIAL 的 D 覆盖启动事实。

[`CaptureStartupGuard`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureStartupGuard.scala)已经把这项判定接到 Engine 创建之前。[A1/A2 真实实验](/pekko-cdc-series/capture-admission/)确认旧 PREPARED/INITIAL 与 B-bound/INITIAL 被拒绝，Engine 创建次数为 0；后者的旧五字段差异保留。新 B 另需本 attempt 的生命周期、原始 ACK 和完成态实际 D，而不是仅凭旧 Schema 或一次 start Future。

这闭合的是“能否从这些已识别状态继续”的准入决策，没有证明旧缺失事件被补进 L。若旧命名空间已静默跳点再写出合法完成态 D，现有引用校验只能核实已交付输入，不能重建从未交付的变化。源 marker COMMIT 不确定也保留原 operation 暂停，不重新写一个 B 来逃避判断。

## 8. SQL 调度资格如何衔接 T/J/D，而不成为另一套数据进度

把阻塞 `run` 拆成 `step` 后，持久控制索引记录 task 定义、期望状态和待执行条件。`BootstrapJournal` 继续记录数据协议的接受事实。两者职责不同：SQL 中“已领取”不是 J 已接受，“已初始化”投影也不能替代目标激活 receipt。

[`CdcTaskStore`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)先在事务中核对 owner、预算和候选，写 ACTIVE grant，然后才交给有限 worker。worker 使用真实 Journal、L 与目标执行一步。确认本轮资源释放后，事务同时记下 clean progress、让出 grant 和安排下次工作；结果未知时 grant 进入 UNKNOWN，仍计入预算。

对每个资源池，安全不变量是：

```text
持久 ACTIVE grant 数 + UNKNOWN grant 数 ≤ 配置预算
发起实际 step ⇒ 存在属于本 owner、本 task 的 ACTIVE grant
归还预算 ⇒ 本轮资源释放已确认，且 disposition 已持久化
```

内存里 worker 已结束，不能跳过最后一条持久 disposition。若它在 J 已接受之后、grant 结束之前崩溃，新 runtime 看到旧 ACTIVE/UNKNOWN，不能凭 J 看起来完整而领取替代资格。当前 owner 启动要求上一个状态为 STOPPED 且无 ACTIVE/UNKNOWN grant。拒绝自动接管是这一版的安全边界，不是 HA 已经完成。

clean continuation 是这种让出协议的附属证据：它只对原 owner 生效，完整内容须与真实 J 匹配；跨 owner 恢复不能拿 SQL 缓存的 clean 标记跳过重验。维护停止也不是取消：它不写业务 K，不把 desiredState 改成 CANCELED。只有步骤排空、Journal 停止、Reader 物理释放及 grant 清理都确认，owner 才能持久为 STOPPED。

### 空闲实时任务怎样避免重新占满 worker

已完成审计且无新组的 tail 可以退出活动会话，不保留每表循环。调度器按 capture 有界扫描 L，新 GROUP 对应 task 时，在同一事务更新 `wake_seq`、待执行标记与 dispatch 游标。task 完成时再比较 wake_seq 与本轮接受的 deltaAfter。新变化若在执行期间抵达，不应因完成旧 grant 而丢掉唤醒。

这里的 wake_seq 只回答“还有工作吗”；它既不覆盖 J.deltaAfter，也不推进 D。实现用原有完整组、T/J 和 ACK 前缀保证数据语义，SQL 索引只安排谁先执行。本轮 P02 已实测十表持续同步后新增第十一表并逐字段相等，活动步骤不超过 snapshot=2 加 tail=2 的四个预算；这支持指定负载下跨越旧八会话限制，不证明任意负载都不饥饿。

## 9. 取消、close 和资源可复用是三件事

取消是持久业务命令。未执行的 QUEUED 任务可以在尚未获得 execution/J/B 时终止，保存独立的 `PREEXEC_CANCEL` 证明，初始化证明仍为空。已经绑定 B 的订阅则经 K 结束捕获，并核实在途 T/J 与资源释放。取消不删除目标，未激活目标可能保留部分数据；PAUSED 没有通用 Resume，不能强行改状态逃过原证据。

停止 Reader 是另一个协议。[受管释放](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureReleaseGuard.scala)把事实拆开：

| 事实 | 要证明什么 | 证明不了什么 |
|---|---|---|
| 本地终止 | SDK close 已调用，runner/executor 确实结束 | completion callback 成功不代表服务端 binlog dump 消失 |
| 服务端释放 | 原 observer 锚点仍有效；专属 source/state 账号下无遗留会话和事务 | 新连一次查询为空不能证明原观察连续，权限不足也不能判空 |
| owner 释放 | 原 owner 连接已关，并在关闭后再次核实服务端 | 本地方法返回不等于命名锁与服务器资源均释放 |
| reusable | 上述事实及 observer 关闭都成立 | 仅超时、仅无事务或仅本地线程退出都不成立 |

[`CaptureResourceObserver`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureResourceObserver.scala)用启动前打开、禁止重连的源/状态连接绑定 server UUID、connection id 和 canary lock，并要求 PROCESS 可见性。账号专属于该 capture；source 的 marker/snapshot 短连接也在其观察域内。全局 Journal/控制库账号与该 capture 的专属状态账号分离。专属账号覆盖 SDK offset/history、Reader owner 和全部 L 连接；L 仍在原 controlState 数据库，改变的是连接凭据。无法确认归属的孤儿事务保守保留未知。

observer 锚点丢失、权限不足、服务端仍有会话时返回 UNKNOWN，不能以另连一个 observer 或等待超时恢复额度。同一 close operation 可以在较晚确认真实释放后变为 reusable，但不可不断创建新 close 线程。兼容旧 `close` 的本地行为和受管 `requestClose/refreshRelease` 必须单列；旧接口本地结束不能被调用方升级为资源复用许可。

这个账号约束有真实反例。旧装配让 L 使用共享 control 账号，观察器却只识别 SDK-state 账号；在代理保留的 L 事务仍 RUNNING 时，旧 Kernel 返回 reusable，并将平台 owner 置为 STOPPED。修复把同一 L URL 的连接纳入专属账号，真实新样本在事务结束前保持 UNKNOWN 和 owner 占用，实际回滚后才可复用。该样本没有执行目标和 Journal，不是数据恢复对账；它验证的是 G0 中不允许遗漏旧执行资源的前提。原失败与修后证据按不同版本保留于[平台报告](/pekko-cdc-series/cdc-platform/)。

健康同样需要时间边界。已获得 B、已经初始化、当前可捕获和资源已释放是独立事实。capture 的 HEALTHY 还要求当前 SDK Connected 与五秒内的本 capture 观察；过期为 STALE。一个域观察阻塞，不能让另一个域无限保持旧健康。API 请求失败时，控制台把缓存详情的健康遮为 UNKNOWN 并禁用取消，历史初始化证明仍可保留。历史 proof 和当前动作许可不能混用。

本轮 P06/P07 从两个相反方向检验运行资格：只有真实 STOPPED 后的新 owner 才继续；ACTIVE/UNKNOWN 遗留时新 JVM 创建 Engine 数为 0。两个真实 close 样本还保留本地已停而服务端仍有会话的阶段，之后才确认同 operation 可复用。这些样本分别支持其边界，不证明未知状态能够自动消除。

控制面的过载也不能被当成存储事实。一次已复现的问题是：HTTP 请求占满控制预算后，调度尚未进入执行体就收到 BUSY，却把健康 owner 永久改成 UNKNOWN。修复仅让这类前置拒绝等待下次调度；已获准工作内的 BUSY 或 SQLException 仍暂停。三个有界 H2/假 Kernel 检查支持这个分类，不能将它说成 MySQL 故障恢复实测。

G1—G4 由此仍是一条条件性证明：范围固定，输出封存，效果可核实，终点不移动，完整回放后才激活。新增调度与释放协议负责保护它的运行前提，不自动扩大它的输入类型、恢复范围或规模结论。[本轮平台实验](/pekko-cdc-series/cdc-platform/)分别记录源码结论、定点实测和未验证边界；尚未取得结果的路径保持 `evidence_incomplete`。

## 技术附录与证据入口

[历史源码分析与实验档案](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/articles/cdc-design/appendices/04-group-protocol-history.md)保留本篇此前全文；[附录索引](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/articles/cdc-design/appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。

<details markdown="1">
<summary>展开历史基线分析与实验细节</summary>

第三篇已经把问题缩小到一个明确边界：目标提交了一组事件，恢复后同一组事件换了回调划分，批次 ID 与内容的对应关系失效。修复不能只换一个哈希公式，也不能让 Sink 遇到账本冲突时直接忽略。

我们真正需要的是一个性质：**同一段源历史，无论怎样分成传输批次、在允许的提交窗口崩溃多少次，都应得到规定的目标效果；重复读取不应重复制造业务效果。** 本篇从这个性质推导身份、目标事务、Actor 确认和源进度的契约。

旧协议分析固定为 `d88ec80`，完整组生产实现固定为 [`65ab541`](https://github.com/Xuxiaotuan/pekko-reference/tree/65ab5410c32933d0aa3d23510e6c62252d84009b)。2026-10-19 的[身份反例](/pekko-cdc-series/source-record-identity/)和[手动覆盖探针](/pekko-cdc-series/deferred-ack/)先收窄设计，后续[真实 Source/Actor Journal/Sink 实验](/pekko-cdc-series/group-recovery/)再验收指定恢复窗口。本文分别标出已实现契约、条件性证明和未验证扩展，不再把完整组路径全部列为待实现。

## 1. 先写出事件历史，才能谈“同一批”

设一个捕获分区的业务历史为有序序列：

```text
E = e1, e2, …, en
```

其中每个事件包含足以确定目标操作的内容。传输层将它切成连续批次，比如 `[e1,e2] [e3]`；恢复后可以变成 `[e1] [e2,e3]`。这些括号只描述一次交付，不应改变事件的业务身份。

若 `Apply(e, S)` 表示事件 e 对目标状态 S 的作用，理想结果是按源顺序折叠整段历史：

```text
S* = Apply(en, … Apply(e2, Apply(e1, S0)) …)
```

这里不能把 E 换成无序集合。对同一主键，先 UPDATE 后 DELETE，与先 DELETE 后 UPDATE，结果可能不同。去重解决重复执行，顺序解决新旧覆盖；两个约束都需要存在。

第一阶段先限定在：单个可排序捕获分区、一个主动 writer、固定 Schema、已完成可信初始化的目标、可继续重放的 streaming 日志、单个事务型 MySQL 目标。目标表与去重证据位于同一事务边界，且没有绕开复制协议的任意目标写入。

这些假设不是为了回避工程问题，而是为了知道证明在哪一步使用了什么。若目标被其他程序修改，重复事件被正确跳过也不能自动修复外部修改；若初始全量不可信，仅证明后续每条事件一次，也不能证明整张表一致。

## 2. 逻辑事件、完整分组与交付身份各自承担什么职责

旧 [`BatchId`][models] 对 executionId、sourceNodeId、partitionId 和序号做散列。新模式保留这个公式，但序号按完整交付递增，用于 Journal 连续性，不再承担 T 的业务去重。缺少稳定源身份的问题由 groupId/eventId 解决，不能靠增加哈希强度解决。

新协议需要区分：

| 身份 | 负责什么 | 恢复后的要求 |
|---|---|---|
| deliveryToken | 关联本读取会话的一次完整交付与覆盖信号 | 可以变化，不能作为跨进程去重键 |
| groupId | 标识一个物理行变更及其全部必要逻辑分量 | 不受回调边界影响，完整后才允许确认 |
| eventId | 标识一条可重放的源事件或操作分量 | 同一事件不变，不同事件不混淆 |
| 目标覆盖证据（设计上可称 commitToken） | 证明某完整组及成员内容已提交 | 最小实现直接保存组证据，不新增一个独立 commitToken 字段或批次收据表 |

Bridge 的 deliveryToken 保留为局部关联。生产 [MySQLCdcGroup][group-model]已定义组与成员的固定编码；T 保存完整规范化证据，J 保存本次交付的 Checkpoint；Source 另用内存 acknowledgementId 隔离会话。Actor/Engine 的持久化模型没有新增独立覆盖收据，也没有扫描 T 最大值来重建 J。

### 2.1 主键、payload 哈希和末尾 cursor 为什么都不够

表主键标识一行，不标识一次变更。相同主键可以连续更新多次；把主键当 eventId，会把后续合法更新误删为重复。

payload 哈希标识内容，也不必标识一次发生。两次合法业务事件可以写入相同值；若承诺包含审计等副作用，将它们合并同样错误。

整批末尾 cursor 只描述这一批结束时的恢复上下文。将它分配给批内所有行，会把多条事件合并；将它作为整个批的身份，则在拆批后改变。第二篇已说明 Debezium 的 source position 与 restart offset 也不应混为一谈。

2026-10-19 的真实身份实验在一笔事务中生成 39 条逻辑记录：四次同键更新、32 行单个 binlog event、一次主键修改的两个分量和一条尾部更新。实际 `[16,16,7]`、39 个单条回调和 `[39]` 的身份与规范化内容一致；从多行事件第六行之后的真实 D 恢复也保持原后缀。

去掉 op，39 条只有 38 个不同元组；去掉 source.row，仅剩 8 个。第一次与第三次同键更新的 before/after 完全相同，但位置不同。顶层处理时间 ts_ms 在重启后变化，不应混入源发生身份。这个样本支持候选字段，却没有证明所有谱系或版本都唯一。[原始记录与独立核验](/pekko-cdc-series/source-record-identity/)

### 2.2 身份稳定，为什么仍然可能无法重放

主键修改产生 DELETE 与 CREATE。二者的 file、source.pos、source.row 相同，关联 headers 分别指向新键与旧键；完整 restart offset 也相同。真实实验只确认 DELETE 后持久化 D，再启动新 JVM，CREATE 没有重放，只有后续普通更新到达。

这反驳了“给每个逻辑成员稳定 ID，就可以逐成员 ACK”的隐含假设。事件账本只能识别再次到达的事件，无法补回读取器不再交付的成员。

因此，最小设计区分：

```text
namespace = replicationId + generation + identityVersion
groupId   = namespace + sourceLineage + db + table + file + source.pos + source.row
eventId   = groupId + componentRole
```

普通 c/u/d 是单成员组；带主键关联 headers 的 DELETE/CREATE 是双成员组。生产 componentRole 编码为 `single.c/u/d` 或有序的 `pk.d, pk.c`；上面的表达式描述组成关系，实际 ID 是固定 canonical JSON 数组的 SHA-256。配对时校验同一 groupId、分区、Schema、共享 offset，以及 newkey/oldkey 与对应 key 的双向一致性。缺少必要成员或配对冲突必须拒绝。

第一版还限定 Debezium 3.6.1.Final、ORDERED、一个处理线程、无改变记录结构的 SMT，并保留主键关联 headers、关闭 tombstone。没有 newkey 的普通 DELETE 与被错误剥离 header 的主键 DELETE，仅凭剩余字段未必可区分；不能承诺任意消息变换后仍可发现所有不完整组。

本次源谱系由固定实例和未重置的日志历史约束，不能把 server_uuid 当作完整谱系协议。生产编码已实现：装配器直接读取原始 SourceRecord headers 完成配对，Envelope v1 本身不新增 headers 字段；组包装保存明确的成员角色与规范化 Envelope。snapshot 的 r 事件在新模式中拒绝，不套用这套 streaming 规则。

### 2.3 owner epoch 与复制 generation 不能混用

owner epoch 表示当前哪一任执行者有写权限。节点接管时它应变化，但同一源事件的去重身份不应因为换 owner 而变化，否则接管本身会绕过去重。

复制 generation 则表示一次有明确初始化与配置语义的数据复制历史。重新全量装载、目标映射语义变化或重新定义基线，可能需要新 generation；普通进程恢复不应随意换 generation。一次失败后生成新 executionId，也不应自动让已提交事件失去旧去重证据。

因此可以把账本命名空间描述为“目标复制任务 + generation + 身份规范版本”，再在其中识别稳定 eventId。Schema 与转换规则参与内容解释和冲突校验；如果改变规则，必须定义迁移方式，不能仅靠当前配置重新解释旧记录。

## 3. 目标事务：把完整组的效果与证据绑在一起

以下伪代码对应 [生产组 Sink][group-sink]。目标表与组账本要求 InnoDB；generation/Schema 来自固定边界，单 writer 是部署前提，当前没有 owner fencing：

```text
解析并校验边界、全部组成员、Schema/类型，构造 DML 计划
BEGIN
  按源顺序遍历完整组：
    SELECT evidence WHERE group_id = ? FOR UPDATE
    若已有且完整规范化证据一致：跳过全组 DML
    若已有但不一致：ROLLBACK 并失败
    若没有：写入组证据，再应用全组成员 DML
COMMIT
返回本次交付的 Checkpoint（不是旧组最初的批次 Checkpoint）
```

原有 [`MySQLCdcApplySinkNode`][sink] 的事务边界得以保留，新模式改用 `pekko_sync_cdc_group_ledger`，证据包括完整边界 identity、目标表名、组身份、全部成员 eventId 与规范化内容。旧批次账本路径仍保留，但不作为新模式的去重依据。普通组的一条事件可以独立重放；主键组则先配齐 d/c，再查询完整组证据。两者都不要求重新组成旧的 16 条传输批次。

主键组的 DELETE、CREATE 和组账本在同一目标事务内提交，不保存“只完成 DELETE”的持久组状态。这只保证组内原子性，不保证整笔源事务的所有组原子可见。

检查与写入必须形成一个原子决定。若只是事务外查询“groupId 不存在”，两个 writer 都可能通过检查。第一阶段的单 writer 限制减少并发问题，目标唯一约束仍应是最终防线；引入多 owner 后，还必须结合 fencing。

遇到相同 groupId、不同成员集合或规范化内容时，整次交付回滚并沿原失败路径停止，保留诊断证据。不能选择“以新内容为准”，因为此时无法知道是身份冲突、Schema 解释变化还是源历史发生变化。当前实现比较完整 canonical 内容，不以 payload 哈希代替发生身份。若后续改用摘要，仍需固定摘要规则和版本；有限模型没有验证该替代方案。

### 3.1 从原子性推出“每个事件至多应用一次”

假设 groupId 和成员 eventId 稳定唯一，所有完整组的目标写入经过上述事务，去重证据不被提前删除。

第一次处理 e 所属的完整组 g 时，如果事务未提交，全组效果与证据都不可见，恢复可以重新处理；如果事务提交，二者同时存在。以后再遇到完整 g，账本命中且成员内容相同，跳过整组。因此，在这个假设集合内，e 不会产生第二次目标业务效果。

这条证明没有使用批次大小。所以 e 与哪些邻居一起被交付，不影响其判定。

### 3.2 “至多一次”为什么还不是最终正确

永远拒绝所有事件也满足至多一次，却完全不同步。还需要至少一次最终覆盖：源日志保留，恢复不会跳过缺口，依赖最终可用，未提交事件能再次获得执行机会。再加上正确的初始目标状态与同分区顺序，才有条件得到最终状态 S*。

如果两个不同的新事件乱序进入同一主键，即使它们各执行一次，目标仍可能错误。事件级去重也不保证整笔源事务在目标同时可见。64 行 UPDATE 被分成多个目标事务时，中间状态仍然可见；事务级一致性需要另建源事务缓冲与提交机制。

## 4. T、J、D 应该保持哪些关系

定义 `Covered(T)` 为已经有目标持久证据的事件集合；`Acked(J)` 为 Actor 宣告已确认的事件覆盖；`Skip(D)` 为按照 D 正常恢复时不再交付的必需事件。还要给允许忽略的元数据记录定义明确的“已处理”规则。

可以表达三条安全约束：

```text
Acked(J) ⊆ Covered(T)
Skip(D) ⊆ Covered(T)
每个被跳过的记录，必须有已提交或按固定规则无需应用的证据
```

这里不要求 T、J、D 每时每刻相等。T 先提交、J 稍后确认、D 更晚刷盘，是正常路径。正确协议需要能够容忍这种滞后，而不是靠降低 flush 间隔假装滞后消失。

J 仍然是工作流意图和生命周期的权威来源，例如是否启用、处于什么 generation、是否被人工停止。T 则是目标业务提交的事实来源。不能用 T 中存在一些新提交，推翻 J 中明确的停止意图并擅自继续运行。

### 4.1 为什么完成前缀不能取最大值

假设共享日志顺序为 `a1, b1, a2`，分别属于两张表。B 的 writer 已完成 b1，A 的 a1 尚未完成。取最大完成位置会得到 b1，并越过 a1。

正确前缀是：从原安全位置起，直到第一个未完成记录之前，所有必需记录都有处理结果。可记录乱序完成集合，但只有填上连续缺口，才能推进全局确认。

这里“位置”是已定义的源顺序，不是将不同 binlog file、GTID 集合或 JSON pos 随意做数值 max。过滤记录、事务边界和分区都要纳入源恢复语义。

### 4.2 ACK 完成仍不替代读取 D

第二篇已经核实 `markBatchFinished` 存在 flush 未成功却返回的源码分支。因此运行状态至少应分别记录：目标提交、J 确认、ACK 调用返回、观测到的 D 持久化。D 滞后不是立即判定数据损坏，而是意味着下一次恢复可能重复更多历史。

协议必须能消化重复，监控也应区分“数据已到目标，但源恢复点未推进”与“目标尚未收到数据”。两种状态的处理方式不同。

### 4.3 回调返回与允许推进 D，是两个不同的动作

当前 Bridge.publish 会等待确认。若先发布半组 d，再等下个回调里的 c，等待本身就会阻止配对。因此生产 [MySQLCdcGroupAssembler][group-assembler]在 Bridge 前装配完整组，尾部半组留在装配器里；只有完整组进入 Bridge。

```text
回调 A: [u0, d1]
  发布完整 [u0]，等待其覆盖
  保留 d1 和 A 的原始提交句柄
  返回 A，不调用 A 句柄

回调 B: [c1, d2]
  配齐 g1=[d1,c1]，发布并等待其覆盖
  A 涉及的 u0/g1 均完成，调用 A 句柄
  B 仍有 d2 未配齐，保留 B 句柄并返回
```

原始回调和下游交付之间不再一一对应。确认协调器保存“原始回调 → 涉及的 groupId”，只有所有组都完整且获得 T/J 覆盖，并且前面的回调已确认，才能调用该原始句柄。普通前缀可以先写入 T/J，D 则因尾部半组继续滞后。

2026-10-19 的真实探针让 `[d] [c]` 和 `[u,d] [c,d] [c,d] [c,u]` 经由测试装配器进入**未修改的生产 Bridge**，均完成交付。第二组的实际原始 ACK 为 0→1→2→3，待确认回调最多 2 个；旧 A 句柄确实在 B 回调中调用。覆盖前崩溃后 D 等于基线，恢复仍能配对；全部确认后的第三个 JVM 又读到新变更。[延迟确认原始证据](/pekko-cdc-series/deferred-ack/)

这个历史探针的覆盖信号来自手动 CONFIRM，没有 T/J。后续生产路径才由 Engine 的真实 T→J 成功触发 Bridge 代理完成，发布等待解除后由回调线程把组记入 covered；代理本身不调用 SDK。新生产实验验证了 `[d][c]` 完整组在 T/J 缺口恢复；不能把它倒灌为历史手动探针的端到端能力。

### 4.4 确认串行化、cursor 与会话边界

固定版本 committer 的实现不是线程安全的。生产 Stream 侧只调用完整交付的无 SDK 操作代理，解除 Bridge 等待；原始 markProcessed/markBatchFinished 由装配器在有序回调路径串行执行。每次确认检查队首原始回调的全部组是否已覆盖，确认后只保留后续回调仍引用的覆盖记录。SDK 调用与 close 由 ackLock 排序：已认领调用可以先完成，关闭生效后不再开始新的原始确认。

原始 A 句柄可以在 B 句柄之前推进 D，即使 CREATE 位于 B：此时 d/c 的完整组已经覆盖。需要禁止的是“只覆盖 d 就推进共享 offset”，不是强行要求两个原始句柄同时执行。

交付 cursor 必须取已交付完整组的安全末尾，不能继续直接使用原回调 records.last；它可能是尚未配齐的 DELETE。回调观察序号与交付序号也要分离。`SourceBatch.acknowledgementId` 是内存会话标识，注册项同时核对 batchId，再关联 Bridge token；它不进入持久 cursor/Checkpoint。旧会话迟到或错误 batchId 不会消费新会话的确认项。交付序号从 J+1 继续，原始回调可以只保留半组而不产生交付。

生产装配器限制原始待确认回调不超过 2 个、每回调记录数不超过配置上限，保留内容按 16 MiB 编码体积计预算，并清除已无引用的覆盖记录。下一条记录不匹配时失败；若没有后续回调，半组保持未确认，不靠超时宣布完整。预算、配对错误、关闭等待和迟到 ACK 有单测，不能据此声称完整 MySQL 作业取消已验收，更不能把预算称为整个 JVM/SDK 内存上限。

## 5. 恢复状态机：先协调事实，再恢复交付

现有 [`EventSourcedWorkflowActor`][actor] 的恢复入口保留。最小实现不新增 Actor 协调态：J 负责执行意图、固定边界和下一交付编号，D 负责重读，T 负责确认完整组效果。实际顺序为：

```text
恢复工作流意图、固定边界/generation 与下一交付编号
  → 校验配置中的 server UUID、Schema 和已有 streaming D
  → 从 D 重放，在 Bridge 前装配完整物理行组
  → 查询 T 中各组证据：旧组跳过，新组提交
  → J 持久化本次交付 Checkpoint
  → Bridge 代理解除等待 → 原始回调连续前缀串行 ACK

单 writer 为前提；本轮没有获取/提升 owner epoch 的步骤
```

这是 Source/Sink 接入现有 Engine 的调用顺序，不是新增 Actor 状态类。最小阶段选择从 D 重放、命中目标组证据后补齐 J，不实现扫描整个目标账本的启动协调器。未确认的内存半组与旧句柄在重启时丢弃；旧协议若已把 D 推过缺失成员，新协议不能凭空补回它，不做透明升级。

若 T 已提交、J 尚未确认，当前只采用重放到达后命中组证据再确认的路径；直接查询 T 并补写 J 的独立协调器仍是后续选择。已经全命中的交付也返回当前 Checkpoint，因此不要求恢复原回调划分。

若 J 已确认、D 仍旧，恢复读取会再次交出旧事件。按新源码，稳定组身份仍可命中 T，不受新交付编号影响；但本轮没有固定这个真实窗口。它是需要后续验收的源码推演，不能从 T 已提交/J 未持久化样本直接升级为实测结论。

若目标 COMMIT 回复丢失，完善协议需要先判定结果不确定，再查询相同组证据；当前没有新增专门的结果不确定协调态或自动查询重试器。查不到不一定意味着永远没提交，查询需要遵循目标事务隔离与最终结果可观察的契约；不能在旧请求仍可能提交时无限发起不受约束的新写入。

如果源日志已清理、源谱系不匹配、证据缺失或同身份内容冲突，应拒绝未知数据并保留证据。当前校验/冲突沿原 Engine 失败路径处理，没有新增一个名为 blocked 的持久 Actor 状态。恢复的“活性”不能建立在跳过未知数据的基础上。

直接把 T 的最大 cursor 写回 Debezium offset store 并不是上述流程的一部分。T 保存的是提交覆盖，D 还包含读取器恢复上下文；未经证明的 seek 加速可能跳过事件或破坏事务内跳过状态。

## 6. 有限模型检查了什么

为了检查重分批不变性，本轮写了一个独立 Python 模型：[protocol_model.py](/assets/pekko-cdc-series/2026-10-18-series-review/protocol_model.py)。它使用六条有序事件，包含同一键多次更新、删除、重新插入和另一键更新。模型直接提供稳定唯一 eventId，目标事务以复制状态后整体提交模拟。

六条事件有 32 种连续分批方式。程序枚举原始分批、每个原批提交边界、允许滞后的源恢复前缀，以及恢复后剩余事件的全部连续分批方式。共检查 **7,696 个组合**。

所有组合都检查两件事：最终业务状态等于不发生故障的顺序执行；每个事件在目标审计列表中恰好出现一次。最终状态为 `a=3, b=8`。模型还检查同 eventId 不同内容必须拒绝，并保留三个负例：旧 after-image 覆盖新值、最大完成位置越过缺口、提前清理账本后旧事件再次执行。[实际输出](/assets/pekko-cdc-series/2026-10-18-series-review/protocol-model-result.json)

**模型结果不能升级成实现结果。** 这个旧模型没有构造真实 SourceRecord eventId，也没有建模主键双分量、跨回调装配与原始句柄的确认边界，没有执行 J 持久化、offset flush、并发 writer、DDL 或 MySQL 事务。原子性、稳定身份和单 writer 是输入假设，不是被程序发现成立的性质。

它的价值在于让第四篇的论证有一个可执行的有限检查，能够暴露算法说明中“换个批次就改变含义”的错误；完整正确性仍依赖上文推理及实际实现对假设的兑现。

## 7. 两个会破坏证明的工程边界

### 7.1 旧 owner 不会因为 Actor 被重新分配就失去数据库权限

Cluster Sharding 解决实体归属，进程内 KillSwitch 解决本进程执行取消。它们不能自动让网络分区另一侧的旧进程失去 MySQL 写权限。当前 [`registerReliableExecution`][engine-register] 的取消协调只发生在本进程。

多节点阶段需要目标可验证的 owner epoch。一个可推理的单目标实现是：更新所有权与业务提交都锁定同一条所有权记录；业务事务检查 epoch 后，在锁保护范围内写 DML 和证据；新 owner 增加 epoch 时也经过这条串行边界。

这样旧事务要么在接管前完成，要么在接管后检查失败。若只是事务外读一次 epoch，旧进程可以读完后暂停，新 owner 接管，再由旧进程继续提交，fencing 就失效了。

这只是候选实现。热点锁、接管等待、源 offset 写入权限和多目标提交仍需另外设计；没有分布式实验前，不能用一个 epoch 字段宣称防住了双写。

### 7.2 去重证据清理可能让已经证明的性质失效

假设 e1、e2 已提交，目标当前为 e2 的新值。删除 e1 的去重证据后，从老恢复点读到 e1，目标会把它当新事件应用，造成回退。模型已经给出这个最小反例。

因此清理水位不能只跟随最近 D。还要考虑允许恢复的最老 Checkpoint/备份、旧 owner 是否失效、源历史是否仍可能重读、重新初始化策略和审计保留需求。

连续区间压缩可以减少每事件账本成本，但前提是区间没有洞，事件顺序和分区定义稳定。把离散已完成集合压成一个最大值，只是把缺口藏起来，不是压缩证明。

## 8. 共享读取、快照和 Schema 为什么要扩展模型

第一篇指出当前一表一 Engine 的成本。共享捕获器有机会降低连接和线程数量，却让确认进度覆盖多张表。

慢表阻塞时，可以停止共享 Source 的确认，让同捕获域一起背压；也可以先把未完成事件写入持久 spool，再允许 D 前进。后一种情况下，安全关系变为“目标已完成，或已有可重放 spool 证据”，系统多了一份持久边界及其消费进度。它不是免费获得隔离，必须再验证 spool 丢失、清理和恢复。

并行快照同样不能套用 streaming eventId。当前 [`MySQLSnapshotSourceNode`][snapshot-source] 使用主键上界和 keyset 分页。上界限制扫描范围，不代表每一页来自同一个可恢复数据库视图，更不等于 Flink CDC 的 LOW/HIGH 回填。

快照若重跑后读取到了新值，不能仅用“表主键 + 同一个 snapshot generation”把新内容解释成旧快照行。需要定义快照代次、分片范围、日志衔接和目标覆盖规则；否则稳定 ID 可能稳定地认错内容。

Schema 变化也会改变事件解释。旧 Schema 数据仍在 writer 缓冲时，先执行 DDL 可能使旧数据按新结构写入。一个演进协议至少需要说明：哪个源位置是 Schema 切换边界，旧数据如何排空或保留旧版本解释，DDL 成功而 J 未记录时如何根据目标状态重复确认。

当前 Schema identity 检查与不支持类型拒绝应该保留。它们是边界保护，不是 DDL 演进实现。把检查删除，并不会自动生成排空屏障或幂等迁移。

## 9. 怎样把证明义务落实到现有类

| 组件 | 已保留 / 已实现 | 本轮没有实现 |
|---|---|---|
| Source / Envelope / Group | 直接读取配对 headers，固定成员角色/身份，完整组装配；Envelope v1 仍作成员内容 | snapshot 组身份、新谱系迁移与 DDL |
| Bridge / 原始确认 | Bridge 不变；半组在前暂存；发布完整组；会话隔离；原始前缀串行确认及局部关闭保护 | 共享池耗尽时的完整作业取消保证、SDK 全局缓存预算 |
| Apply Sink | 组证据与全组 DML 同事务、InnoDB 校验、内容冲突回滚、当前交付 Checkpoint | owner fencing、独立持久 commitToken 表、未知提交结果协调器 |
| Engine / Actor | 原 T→J→Source 顺序、J+1、生命周期与现有失败/恢复入口；boundary 持久绑定 generation | 新 Actor 协调状态、自动旧协议迁移、扫描目标最大进度 |
| 测试设施 | 真 MySQL/Journal/Sink、COMMIT 后闸门、新 JVM、原始回调与 ACK、全字段/审计对账 | 其余故障窗口、集群接管与容量验收 |

新模式显式要求 `initializedTarget=true` 与已有持久 streaming D，配置包含稳定 replicationId/generation/sourceLineage；启动对照 `@@server_uuid`，并拒绝旧版本 Checkpoint。boundary v2 与 `mysql.binlog.groups.v1` 区分旧模式；`snapshot.mode=no_data` 不代表可以丢失 D 后从当前时间继续。初始化声明依赖操作者建立可信全量基线，代码不自动证明源目标全表一致。旧 batch ledger 为兼容现有 readiness 仍需保留，新组账本按独立 DDL 预先创建。具体启用步骤见[生产实验报告](/pekko-cdc-series/group-recovery/)。

第一篇已复现的共享阻塞池饥饿，也必须计入活性条件。数学上允许事件重试，不代表执行池里真的有线程执行提交和关闭。协议证明与资源验证不能各自假定另一方永远工作。

## 10. 故障矩阵应当检验哪些证据

下面分别列出组件门槛和新协议验收要求，未执行的格子不标通过。真实目标/Actor 场景必须保存 T/J/D、交付内容、目标操作审计和静止源的全量逐字段结果；源/Bridge 探针只有 D，不能填充 T/J 那一栏。

| 固定窗口或变体 | 必须核实的事实 | 期望的新协议行为 | 当前证据 |
|---|---|---|---|
| 主键组跨回调与延迟句柄 | 原始 d/c、返回/进入顺序、覆盖信号和实际 D | 完整组覆盖前不确认，旧句柄按前缀串行调用 | 历史手动探针单独保留；新增真实生产 `[d][c]` 恢复有 T/J/D、审计与全字段对账 |
| 主键仅 d 确认后的旧恢复点 | 实际 D 与恢复记录 | 新协议不能制造这种不完整覆盖 | 身份探针实测 c 不再交付；不是 Sink 丢行验收 |
| T 已提交，J 未持久化；拆批、合批、主键组 | T 中完整组覆盖、恢复记录与实际分批 | 旧组不重写，新组继续，最终全表一致 | 新协议 16→1、1→max128（实际 72 组/73 成员）、`[d][c]` 三组通过；每组 64 行×6 字段零差异 |
| J 已持久化，D 未更新 | Journal 实际有凭据，D 仍旧 | 旧日志重读不重复产生效果 | 组身份源码可消化重复；该真实窗口仍未固定实测 |
| COMMIT 成功、客户端回复丢失 | 独立查询目标事务事实 | 查询后协调，不凭异常猜回滚 | 未测 |
| ACK 返回但 flush 失败 | ACK 日志与实际 D 分开读取 | 容忍更旧重放，告警可辨识 | 上游源码分支；未注入 |
| 相同组身份、不同内容 | 规范化成员与目标证据 | 整次交付回滚并失败，不吞掉冲突 | H2 上真实 Sink 代码拒绝并回滚；未伪装成真实 MySQL 故障注入 |
| 关闭/迟到 ACK、配对错误、预算 | 线程等待、确认能力及 SDK 调用次数 | 取消解除等待；错误输入不提前确认 | 相关单测通过；未验证共享池耗尽的完整 MySQL 作业 |
| owner 交接时旧请求晚到 | epoch 与目标提交线性化位置 | 拒绝接管后的旧 owner 写入 | 未测 |
| 慢表完成有洞，共享 Source 继续 | 每路提交与持久 spool/前缀 | 不跳过未处理事件，资源有明确上限 | 模型有越洞反例；共享实现未做 |
| 账本清理后恢复旧点 | 允许的最老恢复点与被删证据 | 拒绝非法旧恢复或仍正确去重 | 模型有提前清理反例；真实未测 |
| 旧 Schema 缓冲未清空时 DDL | 缓冲版本、DDL 事实、J 的位置 | 不错解释旧数据，DDL 可安全协调 | 本项目未测 |

**本次真实结果：**三组恢复都在 T 已提交/J 未持久化处退出，再从真实 Journal/D 恢复。普通场景恢复后审计为 139，后续新写后 140；主键场景为 68→68→69，已提交组没有重放副作用。实际 D 与最新 J 的 offset 对齐后，全表 64 行、六字段一致。源码实现、102 项相关检查与 4 项真实套件的完整说明见[报告](/pekko-cdc-series/group-recovery/)；其中第四项只是 MyISAM 拒绝，不是第四组恢复。全库 414 项通过、两个 Calcite 套件缺类中止，不能称全库全绿。

后续仍需固定：收到半组即崩溃、组内 DML 后 COMMIT 前崩溃、J 已持久化/原始句柄未调用、两个原始句柄之间崩溃、COMMIT 回复丢失及 ACK flush 失败。关闭/迟到 ACK 与预算目前只有组件检查；前两组 Reader/Bridge 探针不能替这些新生产窗口提供目标证据。

这份矩阵的通过标准不是“没有异常”。冲突输入应该安全拒绝；正常重复应该恢复推进；每个结果都要与预先规定的行为一致。

本文的最小实现与样本验收仍有严格边界，但它们已经能够回答为什么重新分批不应改变业务结果、为什么仅有最大进度不够、以及每一处代码变化需要什么证据。下一篇将这些责任放回 70 万表场景，比较自建 Pekko CDC 与使用 Flink CDC 分别承担的成本。

上一篇：[真实故障实验](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/)；下一篇：[Pekko 与 Flink CDC 的取舍](/2026/10/18/pekko-cdc-5-flink-comparison/)。

[models]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/checkpoint/CheckpointModels.scala#L23
[envelope]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelope.scala#L110
[sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala#L82
[actor]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L164
[engine-register]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala#L232
[snapshot-source]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNode.scala#L25

[group-model]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcGroup.scala#L36
[group-assembler]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcGroupAssembler.scala#L34
[group-sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala#L83

## 共享捕获阶段一：把证明义务移到可持久核对的位置

增加 L 后，旧的完整组身份和 T/J 提交次序继续存在，但源 ACK 的充分条件变化了。不能把原公式中的 J 简单替换为 L，然后省略订阅、Schema 与保留期。

**模型前提：**一个固定源谱系、一个受管 Reader；源 ROW/FULL；状态表和目标组账本都使用事务存储；L 不被提前删除；选中表的活动 Schema 不变化；目标基线已由其他可信流程建立。当前实现要求独立状态服务器，且不允许通过修改 profile 重新解释已持久化记录。进程锁只阻止重复启动，不构成跨网络分区的自动 fencing 或接管证明。

在这些前提下，新增路径检查以下不变量：

1. **边界绑定。**每个生效订阅的 B/K、generation、Schema 与不可变 BARRIER 一致。`CAPTURE_READY` 只能表示区间捕获生效，不能推出目标包含 B 之前的数据。
2. **完整覆盖。**原始回调只有全部成员已有持久分类或完整组覆盖，才进入 ACK 前缀。PK 两成员不能靠各自 eventId 独立确认。
3. **D 可解释。**实际 D 对应一个 ACK 证据；其父链和引用帧全部存在且摘要匹配。L 的“最新位置”或本地观察到 ACK 返回，都不能代替实际 D。
4. **表级可重放。**J 的 L 序号必须指向该订阅保留的 GROUP。目标以稳定组身份去重；重新分页只改变交付批次，不改变组内容。
5. **失败不回退到猜测。**容量、Schema、源身份或恢复证据不满足时停止；不能借新 B、latest offset 或清空账本绕过保护。

反例也随之具体化。如果 L 已提交但路由行的 B 被改大，仅校验 GROUP 内容仍可能跳过事件，因此读取订阅时核对 BARRIER。如果 D 已持久化却删除了其引用的 GROUP，仅保留一条“我覆盖过”的回执无法恢复，因此 ACK 要链接实际帧。如果把重新订阅当成补全取消期间的数据，新 B 之后捕获完全正确，目标仍可能永久缺少空档变化。

还有两项刻意没有证明。第一，当前证明链在启动时可能扫描很多历史 ACK，安全性不等于恢复时间有界；第二，L 没有 GC，不能推出有限磁盘能无限期容纳慢表。容量耗尽时暂停是这版的明确行为，后续清理协议必须共同考虑表级 J、旧恢复点与保留期。

**实验结果：**真实矩阵检查 B 提交与回复、回调前缀、重新分批、T/J 缺口和缺失证据拒绝；独立脚本复算保存的帧摘要、引用、D、Journal cursor 与全字段对账。脚本成功属于保存材料的再验证，不是又一次数据库运行。详细结果见[本轮报告](/pekko-cdc-series/shared-capture/)。共享捕获没有扩展到 LOW/HIGH 回填、在线初始化、DDL 演进或自动接管。

</details>
