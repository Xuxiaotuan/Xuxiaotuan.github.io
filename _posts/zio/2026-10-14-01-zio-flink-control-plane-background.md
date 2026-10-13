---
layout: post
title: ZIO 与 Flink 控制面（一）：从 StreamPark 的旧笔记重新出发
description: 从早期 StreamPark Flink Kubernetes 模块的 ZIO 写法出发，解释为什么把提交、观察和验证拆开，以及独立控制面与 Flink Kubernetes Operator 的职责边界。
keywords: ZIO, Scala 3, StreamPark, Flink Kubernetes Operator, 控制面
categories:
  - ZIO
  - Flink
tags:
  - ZIO
  - Scala
  - StreamPark
  - Kubernetes
permalink: /2026/10/14/zio-flink-control-plane-1/
---

2023 年，我在看 StreamPark 的 Flink Kubernetes 模块时，写过两篇笔记：[《Flink Kubernetes Module 为什么使用 ZIO？》]({% post_url zio/2023-12-24-使用ZIO有什么好处呢? %})和[《内嵌 HTTP 文件服务》]({% post_url streampark/2023-12-24-内嵌 HTTP 文件服务 %})。当时主要在理解一件事：为什么部署 Flink、监听 Kubernetes 事件、启动文件服务，都可以写成一段 ZIO 程序？

这次重新做 Scala 3 项目，我仍然想从那个方向开始。但问题变了：一个控制面向 Kubernetes 发出部署请求之后，怎样向用户解释“这次操作到底完成了没有”？这比把几个方法串起来更接近我日常做大数据平台时遇到的麻烦。

我把这段实践整理成三篇。本文解释设计取舍；[第二篇]({{ '/2026/10/14/zio-flink-control-plane-2/' | relative_url }})沿源码看依赖、错误、资源和后台任务；[第三篇]({{ '/2026/10/14/zio-flink-control-plane-3/' | relative_url }})用 Savepoint 恢复串起多副本协调与验收证据。

## 1. 旧笔记里的几行代码，留下了一个问题

早期笔记摘录过这样的部署流程，下面保留当时的接口名称：

```scala
def deployCluster(
    id: Long,
    spec: FlinkDeploymentDef
): IO[Throwable, TrackKey.ClusterKey] =
  for {
    _ <- k8sCrOpr.applyDeployment(spec.copy(job = None))
    trackKey = TrackKey.cluster(id, spec.namespace, spec.name)
    _ <- obr.track(trackKey)
  } yield trackKey
```

它把“提交资源”和“注册观察”按顺序组织起来。前一步发生可处理的失败，后一步不会继续执行；最终返回的是跟踪标识，而不是一个已经运行起来的 Flink 集群。

最后这点容易被忽略。`applyDeployment` 成功，只能说明这一步提交成功；`track` 成功，只能说明进入了后续观察流程。Pod 是否调度成功、JobManager 是否启动、作业是否从指定状态恢复，都在这个返回值之外。

早期文件服务的例子也类似。`attemptBlocking` 包住文件复制，`Ref` 保存服务地址，后台 Fiber（ZIO 调度的轻量执行单元）维持 HTTP 服务。ZIO 提供了组合这些动作的方式，但文件是否可访问、服务是否随应用退出、后台失败由谁发现，仍然要由应用定义。

这里还需要补上历史背景：不能把当年的代码当作今天 StreamPark 主干的现状。Apache StreamPark 的 [PR #3793](https://github.com/apache/streampark/pull/3793) 在 2024 年将这条 Flink Kubernetes Operator 实现从 `dev` 移往独立分支，讨论涉及推进情况和维护成本。这是项目演进记录，不足以推出“ZIO 不适合控制面”或“当前 StreamPark 仍采用这套实现”的结论。

因此，这组文章不是当前 StreamPark 源码教程，而是从那段经历出发，分析我自己的实验项目 [zio-flink-operator-lab](https://github.com/Xuxiaotuan/zio-flink-operator-lab)。

## 2. 我需要的不是另一个 Operator

Flink Kubernetes Operator 已经负责监听 Flink 自定义资源（Custom Resource，简称 CR）、协调部署和更新作业状态。新项目不应再复制一份相同的调谐循环，更不应同时通过另一条路径改 Flink 作业，让两个控制者互相覆盖。

我要补的是用户请求与 Operator 之间的这一层：接收操作意图，检查策略，记录操作，提交资源，再观察这次意图是否兑现。Operator 仍然负责把 Flink 资源的期望状态落实为运行状态。这个分工与官方的[自定义资源模型](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-release-1.16/docs/custom-resource/overview/)一致。

以“从某个 Savepoint 恢复”为例，用户发出的不是一次普通 JSON 更新，而是一个有约束的操作：目标是哪一个作业，允许什么状态保护方式，恢复路径是什么，失败后是否能重新提交。HTTP 请求只是这个意图的入口。

当前项目的职责可以按下面几层理解：

| 层次 | 负责什么 | 不代表什么 |
| --- | --- | --- |
| HTTP 与 Web 工作台 | 解析请求、返回 operationId、查询进度 | 返回 202 不代表作业已完成 |
| PolicyEngine 与操作模型 | 描述部署、升级、暂停、快照等意图，检查约束 | 策略通过不代表集群一定能执行 |
| OperationStore 与 Worker | 持久化操作阶段、调度执行、恢复未完成工作 | 操作记录不是 Flink 状态文件 |
| KubernetesApi 与 ResourceObserver | 写入 CR，读取并持续观察状态 | API Server 接受 CR 不代表 Operator 已完成调谐 |
| VerificationEngine | 根据版本、身份和状态证据判断结果 | CR 层验证不等于业务数据对账 |
| Flink Operator、Flink 与对象存储 | 部署作业、执行快照、保存和恢复状态 | 不替控制面解释每一次 HTTP 请求的历史 |

这层控制面的价值，主要是让“执行过哪些步骤”和“凭什么判断成功”可以被查询，而不是增加一个启动作业的按钮。

## 3. 为什么继续用 ZIO

如果目标只有“拼一份 JSON，再调用一次 Kubernetes API”，普通 Scala 方法和 Java Client 就够了。引入 ZIO 的理由，要从后续流程找。

一次操作会同时涉及几种不同寿命的工作：HTTP 请求可能很快返回，后台操作可能持续几分钟，Watch 连接可能反复重建，数据库和 HTTP 服务则通常与进程同寿命。把它们都交给线程池，并不能自动得到正确的关闭、取消和恢复语义。

ZIO 让我在代码里分别表达这些问题：

- 用 `ZIO[R, E, A]` 表达程序需要的依赖、可处理的失败和成功结果。
- 用 `ZLayer` 组装 Kubernetes、存储和 Worker，而不是在各处创建客户端。
- 用 `Scope` 管理服务、Watch 和后台 Fiber 的生命周期。
- 用 `ZStream` 表达持续观察，而不是把每条事件都塞进散落的回调。
- 用 `Schedule` 表达有限重试，用类型化错误区分拒绝、超时和结果不确定。

这些能力减少的是流程组织上的负担，不会替我决定业务语义。例如，ZIO 可以重试一个 effect，却不知道重试“查询状态”和重试“触发一次恢复”有何区别；它可以中断 Fiber，却不能撤销已经到达 API Server 的请求。

同样，我没有证据说换成 ZIO 后这个控制面就比 `Future`、Cats Effect 或 Java 实现更快。当前选择主要来自已有经验，以及依赖、资源和异步流程可以在同一种模型里组合。团队是否能持续维护这套代码，比语法是否简洁更重要。

## 4. 先把三种“状态”分开

做到多副本时，最容易出现的问题是：一个 API Pod 接受了操作，另一个 Pod 却不知道它的进度。把进程扩成两个副本，并不会让两个 `Ref` 自动共享内容。

这迫使我区分三种状态：

| 状态 | 回答的问题 | 当前实现中的位置 |
| --- | --- | --- |
| 资源状态 | 这个 FlinkDeployment 现在怎么样？ | Kubernetes CR，以及可选的 PostgreSQL 状态投影 |
| 操作状态 | 这一次请求执行到了哪一步？ | FlinkOperation CR，或 PostgreSQL OperationStore |
| 作业状态数据 | Flink 从哪里恢复算子状态？ | Checkpoint、Savepoint 及对象存储中的实际文件 |

`StateStore`、`OperationStore` 和对象存储不是三个可互换的后端。把操作记录写进 PostgreSQL，不等于把 Flink 的 Checkpoint 存进了 PostgreSQL；把 CR 状态查出来，也不等于找回了用户上一次点击“恢复”的操作历史。

在默认 Kubernetes OperationStore 路径下，我为实验项目增加了 `FlinkOperation` 和 `FlinkOperationLock` 两种 CR。它们是这个项目自定义的协议，**不是 Apache Flink Kubernetes Operator 自带的资源**。其中锁通过 `leaseUntil` 实现租约，也不是 Kubernetes 原生的 `coordination.k8s.io/Lease`。

当前代码把这两种实验 CR 放在 `flink.apache.org/v1beta1` 下。这是已有实现的事实，不是推荐给其他项目照搬的 API 设计。若继续向独立产品演进，应当迁往自有 API Group，并设计迁移和兼容过程。

## 5. 操作结果不能只有成功和失败

假设 API Server 已经接受了更新，但响应返回前网络断了。控制面只看到了连接异常，此时该向用户报告什么？

报告成功，没有证据；报告失败并自动重做，可能重复触发操作。还有一种情况是更新已经成功，但进程在记录“已提交”之前退出了。进程重启后同样不能凭本地记录推断远端没有执行。

所以项目显式保留了 `UNCERTAIN`：不是已成功，也不是已证实未执行。它要求后续核对目标资源和操作证据，而不是把所有异常都归入一个通用重试分支。

对应地，`ACCEPTED` 只代表控制面已经接受并记录请求，`SUBMITTED` 代表提交信息已经被记录，`COMPLETED` 才代表当前验证器要求的证据已经满足。即使到达 `COMPLETED`，也应说明验证到哪一层：作业处于运行状态，与业务聚合结果正确，仍然是两件事。

这比使用哪种异步库更关键。控制面先定义“哪些结论有资格对外报告”，ZIO 再负责把流程组织起来。

## 6. 这组三篇采用什么基线

源码分析固定在 [`681a803fae76`](https://github.com/Xuxiaotuan/zio-flink-operator-lab/tree/681a803fae7680f50911fef3354cd1b7db48397f)，依赖版本来自该提交的 [build.sbt](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/build.sbt)：Scala 3.3.5、ZIO 2.1.11、Kubernetes Java Client 20.0.1。不要按仓库后来变化的 `main` 去逐行对照本文。

这个版本已经以 HTTP 服务为唯一应用入口，HTTP 边界使用 JDK `HttpServer`，不是早期笔记中的 ZIO HTTP 文件服务。历史上的命令行原型，只用于解释演进过程。

项目也已经扩展出 Catalog、Schema Snapshot 和表级 `SQL_STATIC` 血缘。这里的 Schema Snapshot 是数据库结构快照，不是 Flink Savepoint；静态 SQL 表级推导也不是运行时血缘，更不是此前博客里讨论的 Flink 字段血缘。为了不分散主线，这组三篇只把它们视为额外服务，不展开元数据平台设计。

写作时我重新核对了源码，并执行本地回归。真实集群的恢复结果则来自仓库保存的历史验收记录，第三篇会单独说明；这次写文章没有重新执行远端作业，也没有连接私人数据库做新实验。

从旧笔记重新出发，最重要的变化不是 ZIO 写得更熟，而是把“提交任务”进一步拆成了“接受意图、执行副作用、观察结果、验证证据”。[下一篇]({{ '/2026/10/14/zio-flink-control-plane-2/' | relative_url }})从 `Main` 开始，沿源码看看这条链路怎么落地。
