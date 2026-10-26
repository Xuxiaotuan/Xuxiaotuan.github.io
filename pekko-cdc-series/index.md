---
layout: page
title: "从 Flink CDC 到 Pekko：共享捕获、全量转流与恢复协议"
description: "五篇源码与实验文章：共享Reader、Split分页、LOW/HIGH、B到F、T/J/D恢复及Flink CDC比较。"
permalink: /pekko-cdc-series/
last_modified_at: 2026-10-27
---

[系列导读与验证范围](/pekko-cdc-series/) · [1：目标架构](/2026/10/18/pekko-cdc-1-resident-tasks/) · [2：批转流源码](/2026/10/18/pekko-cdc-2-recovery-contract/) · [3：真实实验](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/) · [4：恢复协议](/2026/10/18/pekko-cdc-4-protocol-design/) · [5：自建与复用](/2026/10/18/pekko-cdc-5-flink-comparison/) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

一个 MySQL 实例有 70 多万张表，按需选择其中几张，先同步已有数据再持续追实时，并允许不停其他表地增加、取消订阅。本系列围绕这个目标解释当前代码、实验和剩余设计。70 万是业务目录背景，不是已完成的活跃复制规模验收。

2026-10-27 的修订把持久任务索引、有界步骤和平台入口接回原来的五篇主线：目标与执行资源 → 分页/LOW/HIGH/B→F 源码 → 真实实验与反例 → 恢复状态与确认协议 → 与 Flink CDC 的具体取舍。源码变化、当前实测和历史材料分别标识；Actor 机制、旧 T/J/D 推导及失败仍保留在独立[技术附录](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/articles/cdc-design/appendices/README.md)。文章更新不自动改变旧实验的版本或结果。

## 阅读顺序

| 篇目 | 本篇要讲透的问题 |
|---|---|
| [一：70 万表的常驻 CDC——共享 Reader、表级 Actor 与有界执行](/2026/10/18/pekko-cdc-1-resident-tasks/) | 一个源实例怎样共享采集；冷索引、活动 Journal、步骤预算和 L 唤醒怎样配合 |
| [二：从分页快照到持续同步——沿源码走通 LOW/HIGH 与 B→F](/2026/10/18/pekko-cdc-2-recovery-contract/) | 固定 U、范围 spec、RR 分页、真实 marker、窗口 fold、封存、B→F 与步骤恢复 |
| [三：批转流怎样验收——从恢复命题到真实故障证据](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/) | 以 I08/I11/I16/I21 检验命题，再用启动反例检验捕获连续性前提 |
| [四：批转流恢复的证明链——固定范围、封存身份与激活边界](/2026/10/18/pekko-cdc-4-protocol-design/) | 推导固定范围、封存身份和激活条件，并解释完整 B→F 的逐键收敛及其失效条件 |
| [五：海量常驻 CDC 的自建边界——Pekko 控制面与数据运行时的取舍](/2026/10/18/pekko-cdc-5-flink-comparison/) | 在同一业务承诺下，哪些控制值得自建、哪些运行时能力应复用，正面证据与成本缺口是什么 |

## 统一业务案例与论证入口

全文区分源目录 N、已订阅 S 和活动工作 A(t)：70 万是 N 的需求背景，5→7 是本次案例中的 S，不能互换为容量证据。正面证据包括历史共享捕获、动态初始化及指定恢复样本；后续 A1/A2 又增加启动恢复分类和准入实测。本轮又经过真实平台 P01 的动态 5→7、P02 的十表 tail 后新增第十一表，以及独立的 700,000 条冷索引实验。它们各自支持对应层次，不从旧内核直接继承结论，也不合并成 70 万真实表验收。

第三篇围绕[跨窗口迁移 I08](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i08)、[丢失读视图 I11](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i11)、[T 领先 JI16](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-i16)给出“命题—切点—证据—判定”。第四篇以[G0—G4](/2026/10/18/pekko-cdc-4-protocol-design/#g0)统一前提与推理；[启动反例 E0](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/#e-startup)继续保留。新准入门会拒绝特定不安全状态，但安全暂停不等于历史漏数已经补齐。

## 主流程与关键术语

```text
持久接受意图 → 步骤预算准入 → capture启动证据
→ Schema准备 → B绑定 → 冻结键上界U
→ 每个固定范围：LOW → RR分页RAW → HIGH → 日志回填 → FINAL封存 → baseline T/J
→ F绑定 → 从B完整回放到F → 审计 → 激活T → STREAMING J
→ L dispatch唤醒 → 一批完整组T/J → 无待处理组则park
→ 取消使用K与分批排空；维护停止不隐式退订
```

| 术语 | 含义 | 不能混同 |
|---|---|---|
| U | 固定的全表扫描最大主键 | binlog offset、事件时间 Watermark |
| B / K | 一代订阅的捕获开始/停止边界 | 全量完成、取消命令刚被接受 |
| LOW / HIGH | 一次范围读视图前后的真实日志 marker | 全局 Checkpoint、D 已经落盘 |
| F | 全量之后有限追赶的固定终点 | 最后一个业务 GROUP、业务已原子发布 |
| L | 持久完整组、Schema、marker 与 ACK 引用链 | 单一最大 offset、目标 T |
| T / J / D | 目标事务 / Actor 接受进度 / SDK 持久 offset | 可以直接比较大小的三个计数器 |
| grant / clean continuation | 一次有限工作许可 / 同 owner 已确认释放步骤的完整 J 证据 | 新执行身份、RR 读视图或内容封存 |
| dispatch / park | capture 级 L 分派 / 无待处理组的持久休眠 | 每表定时器、数据已被 GC |

## 版本与证据口径

本轮平台源码以仓库相对链接进入，最终提交、生产/测试摘要与运行环境由[平台报告](/pekko-cdc-series/cdc-platform/)固定。项目依赖仍为 Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final；已归档实验使用 MySQL 9.5.0、JDK 17.0.14，没有借此升级 SDK。

版本分为三层，不能互换：

| 层次 | 源码与证据口径 |
|---|---|
| 历史 LOW/HIGH 内核与旧反例 | 文章此前固定 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)；原冻结摘要和失败保留 |
| 已提交的启动分类与准入 | [`74b6d9a`](https://github.com/Xuxiaotuan/pekko-reference/tree/74b6d9a)，实际候选源树与定点结果见[A1/A2](/pekko-cdc-series/capture-admission/) |
| 本次生命周期、有界调度与入口 | 当前源码及[平台报告](/pekko-cdc-series/cdc-platform/)；按冻结候选分别记录 B/P/H、L 归属反例与修后结果，测试设施不同的全源 hash 不互换 |

初始化矩阵在 `00ad18e` 加未提交实现的冻结工作区运行，全源树摘要为 `c71f260870325aa54bb6818200a875689afaace93d10dc8496a11c99e5f362da`。后续启动探针候选 1—4 生产文件一致，但测试设施不同，不能用一个全源树 hash 冒充所有运行。candidate4 保留 339 项源码清单，与发布后的相应源码逐项匹配；发布提交、运行时 HEAD 和材料复算是三种事实。[初始化版本记录](/assets/pekko-cdc-series/2026-10-25-bootstrap/final-version.json) · [启动候选 4](/assets/pekko-cdc-series/2026-10-25-capture-startup/candidate-4.json)

历史版本 `d88ec80`、`65ab541` 和 `00ad18e` 的来源、运行 HEAD 与证据见[独立版本档案](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/articles/cdc-design/appendices/00-versions-and-evidence-history.md)。第五篇的 Flink CDC 源码对照固定 3.6.0；另一组[Flink CDC 系列](https://xuyinyin.cn/flink-cdc-series/)的 3.7.0 与 Paimon 实验不混作同版或同 Sink 结果。没有同负载性能基准。

全文区分：**源码结论**、**真实实验结果**、**保存材料复算**、**条件性推理/模型**、**设计提案/未验证**。小规模终值一致、组件 suite 通过或归档校验都不能替代更高层验收。

<a id="全系列验证范围汇总"></a>

## 当前验证范围汇总

| 对象 | 证据与结果 | 支持的结论 | 未覆盖/失败边界 |
|---|---|---|---|
| Actor 执行模型 | 历史 10,000 普通 Actor、40,000 消息、2 线程 | 本条件下 handler 串行及共享线程机制 | 持久 CDC 实体、70 万真实表与资源斜率 |
| 共享捕获阶段一 | 历史 R01—R16 展开 31 项、可信复制基线 | 对应版本的动态 B/K、L 和指定表级恢复语义 | 不是在线全量初始化；旧 R02 的启动失败保留 |
| LOW/HIGH 与批转流 | 历史 I01—I24 展开 61 项，全部预期断言成立 | 对应版本的分页、窗口回填、封存、B→F、激活及故障窗口 | 含安全暂停负例，不能写成 61 次自动恢复收敛 |
| 全量逐字段核验 | 历史 61 项材料复算，17,874 个必需相等字段，另有新 generation 对账 | 保存材料的行集、字段比较与证明链一致 | 复算不是数据库重跑；不证明任意外部副作用 |
| 相关检查与旧回归 | 历史同冻结源树 80 通过；旧真实 58 项中 57 通过、R02 失败 | 分别支持其检查范围 | 不与后续数字相加称完整验收通过 |
| 历史后端检查 | 593 通过 | 当时明确选择集合的回归 | 排除 ExternalIntegration 与旧 ActorSpec |
| 启动自然/确定性反例 | 自然样本 PASS，选点前 B 提交 FAIL；B-bound＋INITIAL 样本实际五字段旧值 | 证明限定时序下旧行为会越过必要更新 | copied baseline、batch/TLS/双门条件保留；不推断失败频率 |
| A0/B0 信号与资源探针 | 七个实际组合、一个真实 Journal/Sink witness；heartbeat 静默对照与最终会话确认 | snapshot、当前 callback/ACK、实际 D、本地结束和服务端释放必须分开观察 | 原 5 秒自然释放未通过；SDK-only 不能替代 L/目标保证 |
| A1/A2 生产准入 | 最终候选 6 项启动/Reader 检查、5 个定点 Bootstrap variant；603 项常规后端、24 项取证/runner 工具检查 | 特定风险状态启动前拒绝、当前 attempt 准入及所选 T/J 恢复窗口 | 不补齐旧漏数；不是完整 A/B 26 项或全部 I 矩阵重跑 |
| 本轮关闭与释放 | 两个正常 Reader 场景、全局关闭容量与有界响应检查；真实 Kernel 另验证未决 L 事务的账号归属、UNKNOWN 与 owner 保留 | 修复 L 在共享控制账号下被漏观察的真实反例；只有实际事务结束才可复用 | 旧正常关闭不补作 L 故障证明；不是 B01—B10 全扩展矩阵或快速释放 SLA |
| 本轮平台与入口 | 真实 P01—P08：动态 5→7、11 表、三类取消、干净重启、遗留 owner 拒绝与 API；真实 Journal 解码和全字段复算 | 按 CONVERGED / CANCELED / SAFE_PAUSED / CONTROL_PLANE_ONLY 分开；所需相等切面 7,680 字段 | 八项不都是恢复收敛；旧静态快照 guardian 证据不等于新共享链路 HA |
| 冷元数据与资源 | 50,000 pilot 后，700,000 条精确 COUNT、7,000 页遍历、EXPLAIN、60 秒固定观察；无 ActorSystem/Reader/源表 | 对应配置的索引与查询可执行，测试进程堆/线程/磁盘另列原值 | 不是同量真实源表、CDC 吞吐、长期稳定或总 RSS 上界 |
| 本轮内核及相关回归 | 最终生产代码的所选常规后端 650 项、前端 13 项和构建；完整 61 项初始化检查含 13 个源 marker 未知的 SAFE_PAUSED；31 项共享检查与原 27 项按报告分版 | 失败期待、正确拒绝、功能恢复与保存材料复算分别报告 | 常规集合排除 ExternalIntegration 和已知旧 ActorSpec；61 项不是 61 次自动恢复，旧反例不删除 |
| 规模与长期维护 | 当前有机制、有限运行样本及独立容量口径 | 可继续分级验证 | 70 万真实表、DDL、GC、长时 churn 与性能胜负未验证 |

整体验收保持 **`evidence_incomplete`**。新增门改变了特定风险状态的行为，旧不一致没有因此被改成成功；新的平台代码也不能直接继承历史内核的实验数字。捕获就绪、初始化完成、当前持续健康和资源已释放分别报告，不用一个 running 字段概括。

## 当前材料与源码入口

- [BootstrapRunner](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)及[步骤结果/continuation](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStep.scala)：内核与有界调度边界。
- [CdcTaskStore](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)、[CdcPlatformRuntime](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcPlatformRuntime.scala)、[CdcBootstrapKernel](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcBootstrapKernel.scala)：持久意图、grant、L 唤醒及活动会话。
- [初始化报告、范围与故障清单](/pekko-cdc-series/bootstrap/)、[独立复算](/assets/pekko-cdc-series/2026-10-25-bootstrap/final-summary.json)、[归档索引](/assets/pekko-cdc-series/2026-10-25-bootstrap/artifacts/archive-index.json)。
- [启动恢复反例](/pekko-cdc-series/capture-startup/)、[结果摘要](/assets/pekko-cdc-series/2026-10-25-capture-startup/summary.json)、[材料摘要清单](/assets/pekko-cdc-series/2026-10-25-capture-startup/SHA256SUMS.json)。
- [双槽与资源释放报告](/pekko-cdc-series/lab-pool/)、[双槽 suite 与后置结果](/assets/pekko-cdc-series/2026-10-25-cdc-lab-pool/scala-dual-slot-summary.json)。
- [A0/B0 探针](/pekko-cdc-series/capture-admission-release/)、[A1/A2 准入实测](/pekko-cdc-series/capture-admission/)、[本轮平台报告](/pekko-cdc-series/cdc-platform/)：按版本追踪后续变化。
- [启动准入设计](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/development/specs/2026-10-25-shared-reader-startup-recovery-design.md)、[平台生命周期设计](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)：原设计背景；实际实施范围以源码和对应报告为准。

## 独立技术附录

[技术附录索引](https://github.com/Xuxiaotuan/pekko-reference/blob/0690063b9111017c64684623ecbfce16247716ad/docs/articles/cdc-design/appendices/README.md)提供六份独立历史档案、固定版本入口和完整实验报告。原始证据、失败样本与校验脚本留在既有实验目录；后续报告另存新结果，不覆盖原始实验内容，也不把文章整理当作新增运行证据。

<details markdown="1">
<summary>展开2026-10-18至阶段一的历史版本与验证范围</summary>

我研究 Flink CDC，是为了判断怎样把可靠的数据同步能力引入 `pekko-reference`。业务起点是上一家公司 70 多万张表、需要长期管理同步任务的场景。这个数量是需求背景，不是本项目已经通过的容量测试。

五篇文章沿同一条主线展开：Actor 如何复用执行资源；现有代码如何提交与恢复；真实故障怎样反驳隐含假设；新协议应满足哪些不变量；最终如何在同一业务约束下比较 Pekko 与 Flink CDC。文章于 **2026-10-18** 重写，**2026-10-19** 先补充 SourceRecord 身份与跨回调探针，再完成[完整组生产改造及真实恢复实验](/pekko-cdc-series/group-recovery/)。本次更新保留旧协议失败反例，同时将新协议的源码结论、运行样本与尚未验证的窗口分开。

## 阅读顺序

| 篇目 | 本篇展开的核心论证 |
|---|---|
| [一：70 万张表如何常驻——从 Pekko 的执行模型理解 CDC 的资源优势](/2026/10/18/pekko-cdc-1-resident-tasks/) | Mailbox 调度、串行不变量、线程复用与实体驻留；实际配置和阻塞成本 |
| [二：一次提交，三份进度——沿源码推导 Pekko CDC 的恢复契约](/2026/10/18/pekko-cdc-2-recovery-contract/) | 旧回调批次与新完整组的身份/确认边界；目标事务、Journal 与 offset flush |
| [三：从恢复失败到完整组验收——真实 MySQL、Actor Journal 与重放证据](/2026/10/18/pekko-cdc-3-mysql-journal-experiment/) | 旧失败与新验收分开保留；因果闸门、真实 T/J/D、审计与全字段对账 |
| [四：重新分批之后，恢复为什么仍应成立——从不变量构造 T/J/D 协议](/2026/10/18/pekko-cdc-4-protocol-design/) | 稳定身份、原子覆盖证据、确认前缀、所有权和保留边界；有限模型及故障矩阵 |
| [五：70 万表的 CDC 平台，Pekko 与 Flink CDC 各自解决什么](/2026/10/18/pekko-cdc-5-flink-comparison/) | 对照 Source 状态机与控制粒度，计算共享、隔离和恢复成本，形成有条件的选型判断 |

## 版本与证据口径

旧协议分析的生产源码链接固定到公开提交 [`d88ec80f2a7166cfdaddf1284cc268a68bc62e28`](https://github.com/Xuxiaotuan/pekko-reference/tree/d88ec80f2a7166cfdaddf1284cc268a68bc62e28)。真实 MySQL 实验运行于本地 `ad58cf3d861597b577e2f5745584fe56e81fb621`；Actor 机制实验运行时 HEAD 为 `72088fef0b8f691316410ee7a24ef4220c956848`。2026-10-19 两组源侧实验运行时 HEAD 为 `436056d8608e0d2d27ddc24988005cf70a42ff4d`。以上历史实验对应的生产代码与构建依赖仍相同，源侧探针仅位于 Test 源集。新增完整组生产实现另行固定到 [`65ab541`](https://github.com/Xuxiaotuan/pekko-reference/tree/65ab5410c32933d0aa3d23510e6c62252d84009b)；不将它冒充此前探针运行的版本。无关 Calcite 测试及任务文件不纳入文章发布分支。

新协议真实实验取证时位于本地 `2274691` 的未提交工作区。该状态的 23 个生产源码、Schema 及测试文件摘要保存在 [source-manifest.json](/assets/pekko-cdc-series/2026-10-19-group-recovery/source-manifest.json)，已与上述发布提交逐文件核对；证据包同时保留源码快照。运行 HEAD、发布提交和源码字节匹配是三种不同事实，不能只替换一个 SHA 就宣称重跑过实验。

| 项目版本 | 本系列用途 | 恢复语义 |
|---|---|---|
| `d88ec80` | 历史批次账本路径、失败反例与未改动的 Actor/Bridge/Engine 机制 | `mysql.binlog.v1`，回调批次去重 |
| `65ab541` | 完整组生产实现及本次指定窗口验收 | 显式 `groupProtocol`，boundary v2、cursor `mysql.binlog.groups.v1`；可信 streaming 基线，组证据去重 |

项目依赖为 Scala 2.13.12、Pekko 1.1.3、Pekko Persistence JDBC 1.1.1、Debezium 3.6.1.Final。上游精读分别固定为：

| 对象 | 固定版本 / 提交 | 用途 |
|---|---|---|
| Pekko | 1.1.3 / `38facf752b4e7d5fb43ed7c671eb91888b41bd10` | Mailbox、Dispatcher、Sharding、Persistence 与异步 Source |
| Debezium | 3.6.1.Final / `63371830ceff75437f95c775b91eeeaf55a056c1` | 恢复 offset 的语义，ACK 与 flush 的区别 |
| Flink CDC | 3.6.0 / `bf160337ff558d70302e7884f68937611873117e` | 第五篇 SourceReader、split 分配和快照交接源码对照 |
| Flink | 1.20 文档分支，本轮页面显示 1.20.3 | 执行架构、托管状态与连接器容错边界 |

前一组 [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/) 使用自己的 3.7.0 研究基线与 Reader/Flink/Paimon 实验。本组没有将那些实验重新标为 3.6.0 结果，也没有进行 Flink 与 Pekko 同负载基准。Pekko 在线 1.1 文档不作为项目运行版本，具体机制以固定 1.1.3 源码为准。

全文区分以下证据层级：

- **源码结论 / 源码推演**：固定版本实现、因果路径与条件性推理；不等于已运行所有分支。
- **真实实验结果**：明确组件、环境、数据集和窗口的运行观察。
- **历史证据复算**：从已保存的原始行数据重新计算，检查材料一致性；不是重跑数据库。
- **模型推导 / 有限模型检查**：依赖显式假设的推理和枚举；不是生产协议实现验证。
- **设计提案 / 未验证**：尚未实现或证据不足，保留 `evidence_incomplete`。

## 历史验证范围汇总（2026-10-19 基线）

| 判断对象 | 当前证据 | 支持的结论 | 未覆盖边界 |
|---|---|---|---|
| 大量 Actor 共享线程 | Pekko 源码 + 10,000 Actor / 40,000 消息 / 2 线程 | 本次普通 Typed Actor 运行无重叠、无本条件下顺序错误 | 持久化实体容量、70 万表、P99 与内存斜率 |
| 阻塞处理占用 Dispatcher | 两个 handler 的门闩实验 | 同池第三个 handler 等待，释放后执行 | 生产负载公平性与延迟上界 |
| remember 与钝化 | 1.1.3 源码 + 三套配置解析 | 都选择 NoPassivationStrategy | 线上覆盖配置、真实钝化/迁移 |
| 空 Bridge 占用共享 JDBC 池 | 真实 Bridge + Pekko Dispatcher，线程栈门槛 | 8 个读取阻塞，sentinel 等待；外部 close 后释放 | 完整 MySQL 作业是否进入同一窗口、生产取消路径 |
| ACK 返回与 D 持久化 | 项目调用链 + Debezium 固定源码 | 存在 flush 未成功而确认方法返回的路径 | 本轮未注入真实 offset 写故障 |
| 旧协议 T 已提交/J 未持久化 | 2026-10-18 真实 MySQL Source/Sink/Journal，两个 JVM | 固定截获 T/J=6/5、5/4；D 未推进 | 该历史结果不证明新组协议 |
| 旧协议完整批次重放 | 实际 1→1 回调 | AlreadyCommitted；审计未重复；后续追平 | 任意重新分批、任意旧恢复点 |
| 旧协议拆批重放 | 真实 Debezium 批大小 16→1 | 同 batchId 内容不同，账本冲突，Actor failed | 相同配置自然拆批频率；不得作为新协议当前行为 |
| 旧协议全量逐字段一致性 | 历史真实行数据 + 独立复算 | 成功组零差异；失败组缺 65、多 64、216 字段差异 | 全 MySQL 类型、跨库表示、70 万表 |
| 默认时区 | 原实验前置基线失败 | 64 行 DATETIME 偏移 8 小时；正式用例固定 UTC | 生产转换问题未修复 |
| 抽象事件去重模型 | 6 事件 / 7,696 个抽象组合 | 模型假设下的分批不变性和审计成立 | 模型未包含 d/c 分组、真实确认边界、T/J/D |
| SourceRecord 身份与操作区分 | 新增真实 MySQL，正式 9 JVM、39 条同事务逻辑记录 | 实际拆/合批及事务内后缀重放的候选身份和规范化内容相同 | 源谱系切换、快照、DDL、所有类型；不构成通用规范 |
| 主键成员 ACK 边界 | 上述身份探针的一个恢复窗口 | 仅确认 d 后 D 跳过共同物理行，c 未再交付 | 没有目标，不能宣称真实 Sink 缺行；不是独立新增一组实验 |
| 回调返回后的延迟确认与装配 | 另行两组真实 Engine/Bridge、6 JVM，实际 `[1,1]` 和 `[2,2,2,2]` | 完整组经测试信号覆盖后，旧句柄在后续回调按序调用；确认前 D 不推进；后续新 JVM 可继续读 | 没有真实 T/J、新组账本或生产 Source Stream；关闭竞争、字节预算、长期容量未验 |
| 完整组生产路径 | `65ab541` 源码；102 项相关检查通过 | 完整组进入 Bridge；T 组证据与 DML 同事务；当前交付确认 J；原始回调前缀 ACK | 单测不替代真实 MySQL；本次未修改 Actor/Engine 或引入 owner fencing |
| 新协议拆批恢复 | 真 MySQL/Actor JDBC Journal/生产 Sink，T 已提交/J 未持久化，reader 16→1 | 旧组不重写，新数据继续；64 行×6 字段差异 0，已提交组重放新增审计 0 | 任意自然分批频率、长期多次恢复 |
| 新协议合批恢复 | 同一窗口，reader max 1→128；实际首个恢复交付 72 组/73 成员 | 混合旧/新组正确覆盖，64 行×6 字段差异 0 | 128 是配置上限，不是实际收到 128 条；不是全部批量组合 |
| 新协议跨回调主键恢复 | 原始 `[d][c]`，完整组 T 已提交/J 未持久化后新 JVM 恢复 | 两成员整体去重；重放前后审计 68→68；后续更新后 69；64 行×6 字段差异 0 | 收到半组即崩溃、两个原始 SDK 句柄之间崩溃仍未固定实测 |
| 冲突、关闭、迟到 ACK 与预算 | Source/Bridge 单测；组冲突和 PK 双成员回滚使用 H2 | 错误 batchId 不消费确认能力；错误组/冲突拒绝，目标事务回滚；取消释放等待 | 未做完整 MySQL 作业的共享池耗尽/取消、组内 DML 后 COMMIT 前崩溃 |
| 新目标存储前提 | 真实 MySQL 的独立拒绝检查 | MyISAM 目标在组数据提交前被拒绝 | 不是第四组恢复实验；非事务外部副作用不在保证内 |
| 构建与全库检查 | 最终 102 项相关检查、4 项真实套件通过；全模块执行 414 项通过、2 个 Calcite 套件中止 | 明确记录已执行检查及缺类错误 | 完整测试门为 evidence_incomplete；不能写成全库全绿 |
| 多 owner、证据清理、共享前缀 | 协议分析与部分模型反例 | 明确证明义务和故障矩阵 | 没有生产实现后的端到端证据 |
| Flink/Pekko 成本与取舍 | 两边机制源码 + 条件性成本模型 | 能区分控制粒度、数据运行时与工程责任 | 没有同负载资源或吞吐胜负 |

两组源侧材料不与端到端恢复场景合并计数。新协议实际是三组恢复，每组初始化、新协议故障、恢复三个 JVM；真实套件的第四项是 MyISAM 拒绝检查，不是第四组恢复。2026-10-18 的旧协议成功/失败对照仍单独保留。身份实验此前的七 JVM 核心运行是重复检查，不另累计覆盖；有限模型和只读复算也不是新增数据库运行。

## 可复查材料

- [真实 MySQL / Journal 实验报告与复跑命令](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/docs/experiments/2026-10-18-cdc-journal-window.md)，[原始证据包](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/docs/experiments/evidence/2026-10-18-cdc-journal-window.tar.gz)，[压缩包哈希](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/docs/experiments/evidence/2026-10-18-cdc-journal-window.tar.gz.sha256)。包含日志、数据库导出、全部行数据、T/J/D、审计和测试源码。
- [Actor 执行机制实验](/pekko-cdc-series/actor-execution/)。包含探针、运行器、原始输出、环境与 Pekko 源码清单。
- [本轮复算、有限模型及上游源码清单](/pekko-cdc-series/series-review/)。可独立运行 Python 标准库脚本核对。

- [真实 SourceRecord 身份及主键 ACK 反例](/pekko-cdc-series/source-record-identity/)：原始回调、九 JVM、D、失败记录与只读核验。
- [延迟句柄与跨回调装配](/pekko-cdc-series/deferred-ack/)：两组、六 JVM、实际确认顺序和 D；覆盖来自测试信号。
- [完整组生产恢复报告](/pekko-cdc-series/group-recovery/)、[运行结果](/assets/pekko-cdc-series/2026-10-19-group-recovery/result.json)、[原始回调/T/J/D/源码归档](/assets/pekko-cdc-series/2026-10-19-group-recovery/evidence.tar.gz)、[SHA256SUMS](/assets/pekko-cdc-series/2026-10-19-group-recovery/SHA256SUMS)、[独立核验脚本](/assets/pekko-cdc-series/2026-10-19-group-recovery/verify.py)：真实提交、Journal 与 Sink 的指定恢复样本。

以下结论保留在 2026-10-19 基线上；当前进度见下方补充。旧 `docs/blog` 是另一组调度平台文章，其中历史性能数字和“生产就绪”等表述不作为本系列证据。本次已完成的是收窄范围内的完整组生产实现与指定窗口验收；DDL、多 owner、共享读取、快照初始化和其余故障窗口仍未完成。文章或代码发布不会扩大这些保证。


## 2026-10-25：共享捕获阶段一验证范围

[本轮报告、复跑入口和原始材料](/pekko-cdc-series/shared-capture/)记录新增实现。上面的历史表描述其当时版本，不表示此后 C2、执行会话、静态快照 Split/调度和受管接管等工作没有发生；这些各有独立实验，适用范围不自动扩展到共享 Reader。

| 对象 | 源码结论 | 本轮实验结果 | 未验证 / 限制 |
|---|---|---|---|
| 共享 Reader | 一个捕获会话，独立 L，与表级 Actor/消费者分离 | 同一 Reader 五表增至七表，实际变更后全字段核对 | 单实例 70 万表元数据、吞吐与内存容量未验 |
| 新增订阅 B | Reader Schema + JDBC 结构校验；持久意图、唯一源 marker、事务 END 和原子 B 绑定 | marker/L 提交前后、回复丢失、延迟提交/回滚、重启 | CAPTURE_READY ≠ 初始化完成；没有 B 前全量数据导入 |
| 完整组与原始 ACK | d/c 跨回调先完整落 L，再按原始回调前缀确认 | 真实主键修改、多行/多次更新、ACK 中途与两 ACK 之间硬停恢复 | 固定版本、受控样本；不是任意重排序或全类型保证 |
| L 与实际 D | 不可变帧与 ACK 引用链；启动核验 D 的实际覆盖 | 缺 D/History/GROUP、谱系/源 UUID、必需 binlog 缺失走拒绝/暂停 | 无自动重建 D、GC 或任意旧点恢复 |
| 表级 T/J | 独立 L cursor；沿用稳定组账本与 T→J | 真实 Actor Journal/Sink，1→128 重批与 T/J 缺口对账，J 明确拒绝 blocked | 不自动继承原单表全部故障结果；不宣称外部副作用恰好一次 |
| 取消、Schema | `(B,K]`，保留旧 Schema 帧及历史区间，新 generation 不补空档 | K、待生效取消、停订后 DDL 的旧日志恢复检查；活动 DDL/DROP/TRUNCATE 暂停 | 无 DDL 演进，停订空档允许目标不一致 |
| 慢表、容量、资源 | 分表进度独立；回调/单页/Store 并发有预算；关闭未知阻断 | A 暂停时 B 继续；容量暂停；自有 worker 和连接清理 | L 总容量共享，无永久慢表隔离；非全进程内存上界 |
| 部署前提 | L 实际 UUID/数据库与 owner 状态库绑定，源/状态物理实例不同 | 同实例反馈循环失败保留；错误 Store 连接拒绝检查 | 单业务源仍需独立状态基础设施；控制面 API/启动编排未产品化 |

最新结果以本轮报告和源码清单为准。五篇文章的新增部分解释以上变化；历史失败材料和原固定版本分析继续保留。下一阶段仍是 LOW/HIGH、窗口回填和在线全量转流，不能把捕获阶段通过写成“新增表已经全量同步”。

</details>
