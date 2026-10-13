---
layout: post
title: ZIO 与 Flink 控制面（三）：用一次 Savepoint 恢复检验多副本设计
description: 从 requestId、Operation 持久化、resourceVersion 条件更新和租约锁讲到 Savepoint 恢复验收，区分代码契约、历史集群证据与尚未完成的生产级保证。
keywords: ZIO, Flink, Savepoint, Kubernetes, 幂等, 多副本, 控制面
categories:
  - ZIO
  - Flink
tags:
  - ZIO
  - Kubernetes
  - Savepoint
  - 分布式系统
permalink: /2026/10/14/zio-flink-control-plane-3/
---

一个 Flink 控制面有两个副本，用户提交恢复请求后刷新页面，第二次请求落到了另一个副本。它能否查到同一次操作？如果第一个副本恰好在提交后退出，另一个副本应该继续观察，还是重新提交？最后，凭什么说作业确实从指定 Savepoint 恢复了？

这是本篇要回答的问题。[第一篇]({{ '/2026/10/14/zio-flink-control-plane-1/' | relative_url }})解释职责划分，[第二篇]({{ '/2026/10/14/zio-flink-control-plane-2/' | relative_url }})分析 ZIO 源码。本篇继续使用 [`681a803fae76`](https://github.com/Xuxiaotuan/zio-flink-operator-lab/tree/681a803fae7680f50911fef3354cd1b7db48397f) 基线，将同一个案例放到三层证据下讨论：代码规定了什么、历史环境观察到了什么、目前还不能保证什么。

## 1. 先定义恢复成功，而不是先写恢复接口

实验作业使用仓库里的 `StatefulCounterJob`。完整案例是：启动有状态作业，确认 Checkpoint 正常，创建 Savepoint，再让作业从选定的路径恢复。对象存储采用 RustFS，通过 S3 接口保存状态文件。

这次操作有五种容易被混淆的“成功”：

| 观察到的结果 | 可以得出的结论 | 还缺什么 |
| --- | --- | --- |
| HTTP 202 与 operationId | 控制面接受了操作 | Kubernetes 是否接受变更 |
| 提交返回 CR | API Server 接受了资源 | Operator 是否处理本次版本 |
| 新 generation 被观察，作业运行 | 当前资源版本达到验证器要求 | 是否用了指定恢复路径 |
| 恢复日志、REST 与快照文件相互对应 | 有实际加载目标状态的证据 | 业务计算结果是否连续、正确 |
| 业务状态与预期对账一致 | 对这个输入、作业版本和验收口径成立 | 更大规模、故障组合和长期运行保证 |

控制面能自动收集其中一部分，但不应替没有执行的业务对账下结论。本文的“恢复验收”也不是 Flink exactly-once 语义的重新证明。

## 2. requestId 标识操作意图，不是每次 HTTP 的流水号

如果客户端没有收到响应，重试时需要复用同一个 `requestId`。否则服务端看到的是两个新的操作意图，无法判断用户是在重试还是又点了一次恢复。

项目在 [ControlPlaneDomain.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/domain/ControlPlaneDomain.scala) 中，使用 `RequestId`、`OperationId` 两个 opaque type 区分它们，并从 requestId 稳定生成 operationId：

```scala
def forRequest(requestId: RequestId): OperationId =
  UUID.nameUUIDFromBytes(
    requestId.requestIdValue.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  ).toString
```

接受操作时，[DefaultFlinkControlPlane](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operation/OperationStore.scala) 先查 requestId。已经存在且命令相同，就返回原来的操作；已经绑定不同命令，则拒绝复用。

这也解释了为什么失败后不能随意改一下 JSON，继续使用旧 requestId。改变目标、恢复路径或策略，意味着新的意图，需要新的标识。但如果旧操作处于 `UNCERTAIN`，应先核对远端结果，不能靠生成新 ID 绕过不确定性。

这个实现仍有值得继续补齐的并发边界：首次查询未发现记录、随后创建撞到唯一键时，冲突恢复分支会再次查找已有操作，但没有重新比较命令内容。因此，顺序请求下的冲突测试，并不能证明“相同 requestId、不同请求体同时到达两个副本”已经得到完整约束。正式扩展前，应补上这次二次校验和并发回归，而不是仅凭稳定 UUID 宣称幂等协议完备。

## 3. 共享记录、条件更新、资源锁解决不同问题

最初把操作状态留在内存中，可以跑通单实例。扩成两个副本后，A 接受的操作可能在 B 上查不到。共享的 `OperationStore` 首先解决了可见性问题。

默认实现把操作写进项目自定义的 `FlinkOperation` CR。两个副本通过同一个 Kubernetes API Server 读写，不依赖负载均衡一直把用户分配到同一个 Pod。可选的 PostgreSQL OperationStore 提供另一条共享存储路径。

但共享存储只说明大家能看到记录，不意味着大家不会同时修改它。这里又分成两个层次。

### 操作记录更新：用 resourceVersion 防止覆盖

[KubernetesOperationStore.transition](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operation/KubernetesOperationStore.scala) 先读当前 CR，由领域状态机推进事件，再带上读到的 `resourceVersion` 提交 patch。

如果出现 409，就重新读取、重新计算状态转换，而不是反复提交带旧版本的整份文档。它还检查相同事件是否已经存在，处理“写入成功但响应丢失”后对同一事件的重试。

这是一种乐观并发控制，保护的是 Operation 记录。它本身不阻止两个 Worker 同时调用 Flink 资源 API，也不会为任意外部操作提供原子性。

### 同一目标的执行：用可续租的锁协调

[ResourceCoordinator.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operation/ResourceCoordinator.scala) 按 namespace、kind、name 生成锁名，通过项目自定义的 `FlinkOperationLock` CR 协调执行。

创建成功者获得租约；锁仍有效时，其他副本返回资源忙；锁已过期时，接管者带旧 UID 和 resourceVersion 删除旧锁，再尝试创建新锁。续租 patch 同样带身份和版本条件，释放时用 UID 防止误删新持有者的锁。

ZIO 在这里组织“业务执行”和“后台续租”的生命周期：

```scala
result <- renew(resource.namespace, lockName, current)
  .forever
  .raceFirst(effect)
  .ensuring(release(resource.namespace, lockName, lock.uid))
```

正常情况下业务先结束，续租 Fiber 随之结束；续租先失败，则中断本地业务 Fiber。默认租期为 120 秒，续租间隔按租期约三分之一计算。

这个协议比一个 JVM 内的 Semaphore 多解决了跨副本协调，但不能写成严格的分布式 exactly-once 保证。它依赖实例时钟判断过期；已经发出的请求不一定能被中断；目标 Flink 资源写入也没有统一携带一个由远端校验的 fencing token。发生长暂停、网络分区或锁所有权切换时，仍需要专门的故障注入验证。

还有一个更具体的边界：接受请求时的“查找同资源活动操作”是 list 后再 create；不同 requestId 会生成不同 Operation CR，不能靠它们各自的创建唯一性实现跨记录原子排他。资源锁负责执行阶段的协调，两者不能混为一谈。

## 4. 崩溃恢复为什么不能简单重放

Worker 将操作事件写入共享存储，但操作记录与目标 Flink CR 是不同对象。一次普通数据库事务，或单个 Kubernetes 对象的条件更新，都不能把它们变成一个原子事务。

因此，要按崩溃位置讨论恢复：

| 持久化记录停留的位置 | 当前 Worker 的恢复方式 | 考虑的风险 |
| --- | --- | --- |
| ACCEPTED，或尚未通过验证的 VALIDATING | 重新验证，随后尝试执行 | 尚未越过执行边界 |
| 已记录 VALIDATION_PASSED，没有 SUBMITTED 回执 | 转入 UNCERTAIN | 可能已经写入远端，只是回执没记下来 |
| SUBMITTED、等待观察或验证中 | 从事件中取提交信息，继续观察 | 避免重复提交已经持久化确认的写操作 |
| COMPLETED、FAILED、TIMED_OUT、UNCERTAIN 等终态 | 普通活动扫描不再自动执行 | 不替用户擅自发起新的操作 |

这套选择偏保守：它宁可把部分“其实还没提交”的请求留给人工核对，也不冒险自动重放可能已经生效的操作。

注意 `TIMED_OUT` 的语义也有限。控制面等待超时，不代表 Operator 已停止调谐，更不代表远端资源会自动回到原样。用户需要查询目标状态，再决定下一次操作。

相关行为可对照 [AsyncOperationWorker](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operation/AsyncOperationWorker.scala) 和 [Worker 测试](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/test/scala/cn/xuyinyin/flinklab/operation/AsyncOperationWorkerSpec.scala)。测试证明的是指定状态和故障输入下的代码行为，不是所有崩溃时序都已经覆盖。

## 5. 用 Savepoint 把接口与证据对应起来

下面是请求格式示例，不是写作时再次执行集群操作的记录。执行前需要准备好控制面、相应 CRD、Operator 监听的 namespace、运行中的有状态作业，以及可用的快照存储。这里假定本地转发后的 API 为 `127.0.0.1:8080`，目标作业名为 `orders-stateful`。

```bash
request_id="$(uuidgen)"

curl --fail-with-body --silent --show-error \
  -X POST \
  "http://127.0.0.1:8080/v1/snapshots?namespace=bigdata-lab&requestId=$request_id" \
  -H 'Content-Type: application/json' \
  --data '{
    "targetKind": "deployment",
    "targetName": "orders-stateful",
    "type": "savepoint"
  }'
```

不要把返回的 202 当成快照完成。保存响应中的 `operationId`，再查询 `/v1/operations/{operationId}` 和对应的 Snapshot CR。网络失败重试同一次请求时，复用上面的 `request_id`，不要重新执行生成 ID 的那一行。

当前 Worker 会为未指定名称的快照生成与操作关联的资源名，并使用提交回执中的 Snapshot UID 约束观察目标。这可以避免仅凭“有一个同名快照已完成”就接受旧对象的结果。验证要求 Snapshot 达到 `COMPLETED` 且有结果路径；明确的失败或 `ABANDONED` 会结束操作，尚未完成则继续等待。

接下来应把拿到的路径用于恢复。对于已有资源，`initialSavepointPath` 与变化的 `savepointRedeployNonce` 配合，表达从指定路径重新部署的意图。官方 [1.16 分支的 Job Management 文档](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-release-1.16/docs/managing/job-management/#redeploying-from-a-savepoint)说明了这个入口。下面只是完整部署文档中 `spec.job` 的字段片段，不能直接当作完整请求提交：

```json
{
  "initialSavepointPath": "s3://flink-savepoints/orders/savepoints/savepoint-example",
  "savepointRedeployNonce": 1
}
```

路径必须换成实际验证过的结果，nonce 也要相对于这个资源此前的值发生变化。新请求还需保留镜像、JAR、并行度、状态保护与存储等完整配置。修改 nonce 会触发真实恢复，不是一个无副作用的探测动作。

这里必须指出当前自动验证的范围：部署路径主要检查本次 generation、Operator 调谐结果和 Job 状态，不会自动连到 JobManager 读取恢复日志，也不会去 RustFS 检查 `_metadata`。因此，即使 Operation 达到 `COMPLETED`，要证明“确实从这个路径恢复”，仍需补齐运行时证据。

## 6. 一个 JSON 类型错误，暴露了整条协议链

历史恢复验收里有一个很具体的失败：Scala 模型中的 nonce 是 `Long`，经过当时的 JSON 编码路径后，输出却成了字符串。CRD 要求数值，API Server 因类型不匹配拒绝资源。

从 Scala 类型看，字段存在，赋值也合法；从最终 Kubernetes 文档看，协议却错了。这说明只测试 case class 构造成功还不够，必须检查最后发到线上边界的 JSON。

当前 [FlinkResources.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/domain/FlinkResources.scala) 显式写入数值：

```scala
savepointRedeployNonce.foreach { nonce =>
  value("savepointRedeployNonce") = ujson.Num(nonce.toDouble)
}
```

与之配套，[OperationFactory](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/application/OperationFactory.scala) 在 HTTP 输入边界限制为正的安全 JSON 整数，避免把任意 `Long` 经 `Double` 转换后悄悄舍入。内部直接构造模型的调用者仍需遵守这个范围。

回归测试不仅检查字段没丢，还检查无效、带小数或超范围的 nonce 会被拒绝。这个修复的价值不在于多了一次类型转换，而在于让“入口允许什么”和“出口发出什么”重新对齐。

## 7. 历史现场记录究竟证明了什么

以下内容来自固定提交中的 [docs/testing.md](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/docs/testing.md)，不是本次写作新跑出的结果。该记录描述的实验环境为 Kubernetes 1.23.17、Flink Kubernetes Operator 1.16.1、Flink 1.20 系列作业与 RustFS；这些是历史版本，不是今天的生产版本推荐。

2026 年 10 月 10 日，修复后的恢复验收使用提交 `c44502a`，Pipeline build 为 `#37`。记录包含：

- Operation 达到 `COMPLETED`，保存了提交、观察和验证成功事件。
- generation 与 observedGeneration 都是 3，`lastReconciledSpec` 包含源 Savepoint 和 `savepointRedeployNonce=1`。
- 恢复前后 Job ID 发生变化，JobManager 日志记录从 Savepoint 恢复。
- Flink REST 中 `restored=1`、`completed=10`，恢复后继续产生 Checkpoint。
- RustFS 中源 Savepoint 的 `_metadata` 对象 HEAD 返回 200。

这些证据组合起来，比“页面显示绿色”更有说服力。但 Job ID 变化单独不能证明恢复来源，`_metadata` 可读单独不能证明所有状态文件都完整，计数器恢复实验也不能替代真实业务的输入输出对账。

同一文档还保存了 10 月 8 日的跨副本与锁接管记录：两个节点对同一 requestId 查到同一个完成操作，过期锁被新执行者接管，并完成了 PostgreSQL 共享 OperationStore 的临时实验。这些记录支持特定环境中的实践结果，不覆盖所有网络分区、时钟偏差和长时间停顿。

这次写作复核的是源码、测试与历史记录之间能否对应，没有重新下载当时所有原始日志，也没有重新执行远端恢复。因此，不把历史摘要进一步升级为本次独立端到端验证。

## 8. 用分层验证结束，而不是用“测试都过了”结束

写作时在本地执行了以下命令，主动关闭外部 E2E 和 PostgreSQL 契约测试的环境开关：

```bash
env -u ZIO_FLINK_E2E_ENABLED \
    -u ZIO_FLINK_METADATA_TEST_JDBC_URL \
    JAVA_HOME="$(/usr/libexec/java_home -v 17)" \
    sbt -batch test
```

这是一条 macOS 示例；其他系统设置自己的 JDK 17 路径即可。本次输出为 129 passed、0 failed、5 ignored。运行对象是 2026 年 10 月 14 日的本地工作树：生产源码与本文基线一致，另有四个未提交的外部 HTTP E2E 用例，均未启用；剩余一个跳过项为真实 PostgreSQL 元数据契约测试。

这些测试覆盖了领域状态机、HTTP 请求协议、Watch 重连、generation 验证、操作恢复和锁协议等代码契约。它们不证明本次连通了真实集群，也不证明数据库、Flink 作业或对象存储正在正常运行。

对这个项目，后续验证应继续按层推进：先补并发幂等冲突和资源身份变化的反例，再验证锁丢失时已经发出的请求，最后围绕指定作业做故障注入与状态对账。鉴权授权、API Group 迁移、队列公平性、历史记录容量和长期运维，也都是走向生产之前必须另行处理的问题。

这组三篇从 StreamPark 的几行 ZIO 代码开始，最后落到了一个朴素结论：ZIO 能把异步控制流程写得更明确，但可靠性仍来自每一条边界上的协议。请求是不是同一次，记录能不能被另一个副本继续使用，旧状态会不会被当成新结果，恢复到底留下了哪些证据——这些问题逐一回答清楚，控制面才开始值得信任。
