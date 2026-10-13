---
layout: post
title: ZIO 与 Flink 控制面（二）：沿一次请求读懂依赖、错误与资源管理
description: 沿 Main、ServerProgram、AsyncOperationWorker 和 ResourceObserver 的真实源码，理解 ZLayer、Scope、ZStream、类型化错误和可恢复后台任务分别解决什么问题。
keywords: ZIO, ZLayer, Scope, ZStream, Scala 3, Flink
categories:
  - ZIO
  - Flink
tags:
  - ZIO
  - Scala
  - Kubernetes
  - 源码分析
permalink: /2026/10/14/zio-flink-control-plane-2/
---

[上一篇]({{ '/2026/10/14/zio-flink-control-plane-1/' | relative_url }})把控制面的目标拆成了四件事：接受意图、执行副作用、观察结果、验证证据。本篇沿一次 HTTP 请求读源码，看看 ZIO 在这些边界上具体做了什么。

源码固定在 [zio-flink-operator-lab 的 `681a803fae76`](https://github.com/Xuxiaotuan/zio-flink-operator-lab/tree/681a803fae7680f50911fef3354cd1b7db48397f)，使用 Scala 3.3.5 和 ZIO 2.1.11。代码片段是这个版本的局部摘录，不是可单独复制运行的完整应用。

## 1. 从返回类型开始，而不是从 for 开始

Worker 对外暴露的接口很短：

```scala
trait AsyncOperationWorker:
  def submit(
      requestId: RequestId,
      operation: FlinkOperation
  ): IO[ControlPlaneError, AcceptedOperation]

  def process(id: OperationId): IO[ControlPlaneError, Operation]
```

`IO[E, A]` 是 `ZIO[Any, E, A]` 的类型别名。`submit` 返回的不是已经执行完的 `AcceptedOperation`，而是一段运行后可能得到该结果、也可能以 `ControlPlaneError` 失败的程序描述。

这里的 `Any` 不意味着服务没有依赖。`DefaultAsyncOperationWorker` 已经通过构造参数拿到了 Kubernetes API、观察器、验证器、存储和协调器，因此调用方法时不再需要从 ZIO 环境取这些依赖。不要为了体现 ZIO，把每个方法都强行改成很长的环境类型。

三个类型参数可以这样读：`R` 是运行还需要什么，`E` 是通过类型化错误通道报告什么，`A` 是成功时返回什么。HTTP 层拿到 `AcceptedOperation`，才能返回操作标识；它不能直接把这个结果解释为 Flink 作业运行成功。

早期笔记里对 `Nothing` 的理解，也需要更精确。`E = Nothing` 表示没有类型化失败值，不排除程序缺陷或 Fiber 中断；`A = Nothing` 表示不会产生正常成功值，不等于一定无限循环。`ZIO.never` 可以一直等待，失败或中断也可以使没有成功值的程序结束。官方[错误管理说明](https://zio.dev/reference/error-management/)区分了错误通道、缺陷与中断，读返回类型时不能混在一起。

## 2. Main 只组装服务，不处理业务

[Main.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/Main.scala) 的核心如下：

```scala
val serverLayer = ZLayer.make[
  KubernetesApi & StateStore & OperationStore &
    AsyncOperationWorker & MetadataStore
](
  KubernetesApi.live,
  OperationStore.live,
  MetadataStoreLive.layer,
  StateStore.live,
  PolicyEngine.live,
  ResourceCoordinator.live,
  AsyncOperationWorker.live
)

ServerProgram.run.provideSome[ZIOAppArgs](serverLayer)
```

`ZLayer.make` 根据各层声明的输入与输出，把服务接起来。`AsyncOperationWorker.live` 需要的 `KubernetesApi`、`OperationStore`、`PolicyEngine` 和 `ResourceCoordinator`，都在这里提供。缺少某个依赖，会暴露在组装边界，而不是等某条 HTTP 请求执行到一半才临时查找服务。

这里可以把 [ZLayer](https://zio.dev/reference/contextual/zlayer/) 理解成“如何构造服务的描述”。它解决构建和生命周期的组合，不负责保存业务操作，更不是分布式状态共享机制。

测试也因此有两个清楚的替换点：可以在组装时提供测试层，也可以直接向 Worker 的构造函数传入 fake Kubernetes API 和内存存储。纯粹判断状态的 `VerificationEngine` 则根本不需要启动 HTTP 服务。

当前实现并非所有初始化都已经建模得一样细。比如 `KubernetesApi.live` 用 `ZLayer.succeed` 构造 Java Client；客户端配置读取若抛出异常，会成为缺陷，而不是这个层声明出来的可恢复错误。若要增强启动诊断，可以再把这类初始化移入 `ZIO.attempt`，把客户端关闭纳入资源层。这是后续改进点，不能因为项目用了 ZLayer 就宣称初始化和释放已经全面安全。

## 3. Scope 管服务寿命，不管远端事务

服务入口 [ServerProgram.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/server/ServerProgram.scala) 在 `ZIO.scoped` 中初始化存储、启动状态轮询和 HTTP 服务。下面摘出资源管理部分：

```scala
_ <- StatePoller.run.forkScoped
settings = HttpServerSettings.fromEnv(sys.env)
running <- ZIO.acquireRelease(
  start(settings, api, store, metadataStore, operationStore, worker)
) { case (server, executor) =>
  ZIO.attempt(server.stop(0)).ignore *>
    ZIO.succeed(executor.shutdown())
}
_ <- ZIO.never
```

`forkScoped` 让轮询 Fiber 的寿命跟随这个作用域。HTTP 服务成功获取后，`acquireRelease` 把停止服务、关闭线程池的动作登记进作用域；正常退出或中断时，运行时会执行这些释放动作。相关语义见 ZIO 的[资源管理文档](https://zio.dev/reference/resource/)。

这比单纯启动一个后台线程多表达了一层所有权：谁启动它，谁负责让它结束。但这里仍有两个边界。

第一，进程被强制杀死时，不能指望 JVM 执行清理逻辑。已经发出的 Kubernetes 请求也不会因为 Fiber 中断而回滚。第二，`start` 内部一次性创建多个 Java 资源；如果在返回资源组合之前失败，外层 finalizer 无法自动获知已经创建的部分资源。进一步细化获取步骤，才有机会覆盖这种初始化中途失败。

这正是资源管理与分布式事务的区别：前者管理本进程拥有的对象，后者必须依赖远端协议和可恢复状态。

## 4. Java 边界上的阻塞与 unsafe

这个版本使用 JDK `HttpServer`，默认 HTTP 线程池大小为 8。Java 的 `HttpHandler` 回调里，需要把请求交给 ZIO 路由，再把响应写回 `HttpExchange`，因此边界上出现了：

```scala
val response = Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe.run(
    KubernetesHttpApi.handleWith(
      api, store, metadataStore, operationStore, worker,
      request, KubernetesHttpSettings.fromEnv(sys.env)
    )
  ).getOrThrowFiberFailure()
}
```

这不是推荐在每个业务方法里调用 `unsafe.run`。它只是从 Java 回调进入 ZIO Runtime 的适配点。Worker、存储和观察流程继续返回 effect，让调用者组合；如果中间层反复自行启动 Runtime，生命周期和错误传播就又散开了。

同样，返回 `IO` 并不会让同步 Java API 自动变成非阻塞。项目在 [KubernetesApi.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/kubernetes/KubernetesApi.scala) 里用 `ZIO.attemptBlocking` 包住同步 HTTP 调用，并在 `finally` 中关闭 response；Watch 的迭代读取也放在阻塞边界中。

`attemptBlocking` 让阻塞工作按 ZIO 的阻塞执行模型调度，但 Java 调用仍然可能等待网络。它不自动提供客户端超时、不保证中断能立即打断底层调用，也不提升远端 API 的处理能力。当前 HTTP 适配仍占用请求线程等待路由返回，因此不能把这个项目描述成端到端非阻塞服务。

## 5. 接受请求后，先持久化再调度

在 [AsyncOperationWorker.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operation/AsyncOperationWorker.scala) 中，`submit` 先调用控制面的 `accept`，成功后再把 operationId 放进队列。HTTP 层返回 202 和这个标识，执行过程交给后台。

它的 live 层创建了容量为 256 的 `Queue.dropping`，并启动两个 scoped Fiber：一个消费队列；另一个每隔 5 秒扫描持久化存储，把仍然活动的操作重新加入队列。

这两个机制承担不同职责：队列让新请求尽快被处理，持久化 Operation 才保存工作记录。队列满时，`dropping` 可以丢掉本次入队；进程退出时，队列也会消失。恢复扫描允许后续重新发现尚未处理完的记录，前提是共享存储仍然可用、扫描器和 Worker 能继续运行。

所以不能只看到队列便推断系统“丢任务”，也不能只看到恢复扫描便承诺“绝不丢任务”。是否能完成，还取决于操作状态机、错误分类、调度公平性和外部系统。

当前还有一个明显的吞吐边界：`OperationMutex` 使用一个许可的 Semaphore，保护整个 `process`，包括等待结果验证的时间。也就是说，每个实例的操作执行是串行的。它简化了本地并发，却可能让一个长时间等待的操作阻塞后续操作。把它改成按资源并发，是可以继续研究的方向，但本文不会把尚未实现的优化写成现有能力。

## 6. 重试策略必须和错误语义一起读

底层 Kubernetes 请求使用了有限重试：

```scala
effect.retry(
  (Schedule.exponential(50.millis) &&
    Schedule.recurs(settings.maxRetries))
    .whileInput(isRetryable)
)
```

`isRetryable` 选择 429、5xx 和 `IOException`；权限、参数等其他错误不会因此反复提交。这里的指数退避描述了等待方式，`recurs` 限定额外重试次数。

但是，底层请求方法同时服务于读和写。网络错误之后重试 GET，与重试一个可能已经被接受的变更请求，语义并不相同。有限重试不是 exactly-once 保证；上层仍然需要稳定的请求标识、目标身份以及提交阶段记录。

Worker 对提交结果的处理也做了区分：明确的 Kubernetes 4xx 拒绝会映射为失败；耗尽重试后仍是网络异常、429 或 5xx，则保守地归入 `UncertainFailure`。这是当前项目的分类策略，不是说每个 5xx 都已经产生了副作用。

恢复时，如果持久化记录停在 `VALIDATION_PASSED`，却没有保存提交回执，Worker 不会直接重新执行写操作，而会转为不确定。因为这段空隙里既可能“还没提交”，也可能“提交成功但没来得及记账”。

ZIO 让这些分支可以组合；它不能替应用推断网络另一端发生过什么。[第三篇]({{ '/2026/10/14/zio-flink-control-plane-3/' | relative_url }})会继续解释这个空隙为什么无法靠一个数据库事务消除。

## 7. ZStream 表达的是观察协议

[ResourceObserver.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/operator/observer/ResourceObserver.scala) 的接口返回 `ZStream[Any, Throwable, ResourceObservation]`。它不是周期性取一个布尔值，而是向上层提供一串带身份、版本和原始载荷的观察结果。

每轮观察先 list，取回资源快照及列表的 `resourceVersion`；再从这个版本启动 watch。列表中的对象先作为初始观察发出，之后才消费增量事件。这里发出的初始 `ADDED` 是快照投影，不意味着这些对象刚刚被创建。

Watch 的分支处理如下：

| 信号 | 当前实现的动作 | 原因 |
| --- | --- | --- |
| 普通资源事件 | 转为 ResourceObservation | 交给上层验证 |
| Bookmark | 不作为资源观察发出 | 它不是一份完整资源状态 |
| 410 Gone | 重新 list，再建立 watch | 旧游标已经不可继续使用 |
| 429、5xx、I/O 异常 | 结束本轮 watch，经重连进入新一轮 | 重新获取快照与游标 |
| 正常 EOF | 等待 100 ms 后重新 list/watch | 流正常结束不代表永久停止观察 |
| 其他错误 | 向上失败 | 不把确定错误隐藏成空结果 |

Kubernetes 的 [API Concepts](https://kubernetes.io/docs/reference/using-api/api-concepts/)说明了 list/watch 和过期版本的处理要求。`resourceVersion` 在这里是传回服务端的游标与条件更新令牌，不是业务时间，也不应在客户端转成整数比较大小。

这段代码仍不能被概括为“任何异常都自动恢复”。重连分支包住的是 watch 部分；list 重试耗尽后的失败不在同一个恢复范围内。`parseList` 还直接位于 `map` 中，解析异常可能进入缺陷通道。说明这些边界，是为了分清已经写出的恢复协议与希望它最终达到的鲁棒性。

## 8. 验证器适合写成纯判断

[VerificationEngine.scala](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/main/scala/cn/xuyinyin/flinklab/application/VerificationEngine.scala) 没有再发网络请求，它接收操作意图、目标资源、观察证据和预期 generation，返回 `Either`。

这让一个关键反例很容易测试：如果这次提交的是 generation 4，但观察到 generation 3、observedGeneration 3，即使作业处于 `RUNNING`，也不能算本次操作成功。[VerificationEngineSpec](https://github.com/Xuxiaotuan/zio-flink-operator-lab/blob/681a803fae7680f50911fef3354cd1b7db48397f/src/test/scala/cn/xuyinyin/flinklab/application/VerificationEngineSpec.scala) 就包含这种“旧版本看起来正常”的用例。

快照也一样：`COMPLETED` 但没有结果路径，验证器不会接受。严格 Savepoint 升级缺少状态保护证据，同样不能因为 Job 恢复了 `RUNNING` 就直接通过。Worker 对暂时缺失的证据继续等待，对明确失败结束操作，并对整体观察设定超时。

这种拆法把“如何等到事件”与“什么事件足够证明成功”分开。前者适合 ZStream 和时间控制，后者适合普通 Scala 类型、模式匹配和单元测试，没有必要为了形式统一把所有判断都包装成 effect。

2026 年 10 月 14 日，写作时的本地工作树回归得到 129 个测试通过、0 个失败、5 个跳过。跳过项是一个 PostgreSQL 元数据契约测试和四个尚未提交的外部 HTTP E2E 用例。生产源码与本文固定提交一致；这个数字不是对线上环境的新验收，也不等于固定提交本身含有那四个 E2E 用例。

读完这条源码链，ZIO 的作用可以收敛为一句话：让依赖、失败、资源和持续运行的工作能够被明确组合。至于多个实例如何认领同一操作、恢复结果究竟要查什么证据，还要回到分布式协议本身。下一篇用一次 Savepoint 恢复把这些问题连起来。
