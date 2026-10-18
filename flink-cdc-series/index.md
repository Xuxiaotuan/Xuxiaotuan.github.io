---
layout: page
title: Flink CDC 深入解析：系列导读与验证材料
description: 五个主题、六个阅读单元，沿论文、固定版本源码与实验理解 Flink CDC 的算法、恢复和端到端边界。
permalink: /flink-cdc-series/
---

这组文章面向已经了解 Flink DataStream、Checkpoint 和 MySQL binlog 的读者。目标是沿实际调用链回答“为什么这样设计、失败后靠什么恢复、证据能支持多大范围的结论”。初次阅读建议按顺序推进；排查具体问题时，可直接从相应篇目和证据入口进入。

五个主题中，第三篇分为上下两篇，因此共六个阅读单元。研究与实验日期为 2026 年 10 月 18 日，版本固定，不表示其他版本或所有部署形态的行为。

## 阅读路线

| 阅读单元 | 核心问题 |
| --- | --- |
| [Flink CDC 深入解析（一）：一致性的含义与边界](/2026/10/18/flink-cdc-1-consistency/) | 最终表相等为什么不等于历史完整或事务原子可见？ |
| [Flink CDC 深入解析（二）：增量快照的水位、回填与多 Chunk 衔接](/2026/10/18/flink-cdc-2-incremental-snapshot/) | LOW/HIGH 和回填如何解决扫描与日志之间的不确定性？ |
| [Flink CDC 深入解析（三·上）：Source 状态机与快照切换](/2026/10/18/flink-cdc-3a-source-state/) | 为什么收齐 Chunk 完成报告后，还可能需要等待 Checkpoint？ |
| [Flink CDC 深入解析（三·下）：Checkpoint 恢复与故障窗口](/2026/10/18/flink-cdc-3b-checkpoint-recovery/) | 恢复点、完成 ACK 与 Split 分配分别由谁负责？ |
| [Flink CDC 深入解析（四）：Schema 演进的捕获、传播与恢复](/2026/10/18/flink-cdc-4-schema-evolution/) | DDL、解码 Schema、目标 Schema 和旧数据怎样衔接？ |
| [Flink CDC 深入解析（五）：从 MySQL 到 Paimon 的提交与恢复](/2026/10/18/flink-cdc-5-paimon-commit/) | flush、真实 DDL、snapshot 可见性与 Checkpoint 完成为什么要分开观察？ |

## 整个系列的验证范围

全系列 CDC 源码统一固定到 release-3.7.0 的 045e8da45df4b1017849b04e2d8bfe8f0ed7936d，运行主线为 Flink 1.20.3、Java 11.0.29、MySQL 8.4.11；Debezium 依赖为 1.9.8.Final，但 CDC 内置改写类必须按固定提交阅读。第五篇另固定 Paimon 2.0.0。第三个主题拆成上下两个阅读单元，仍按同一组验证统计。

| 主题 | 源码或论文支持的内容 | 已执行的证据 | 不能由这些证据推出的结论 |
| --- | --- | --- | --- |
| 第一篇：一致性边界 | 位点、快照归一、事务事件与外部结果的区别 | 18 项 Java 测试；3 组独立真实 MySQL 验证，包括读取视图、提交边界、重复写入模型 | 没有运行 CDC 到外部 Sink 作业；数据库模型不构成 CDC 端到端证明 |
| 第二篇：增量快照 | 对照 DBLog 讨论 LOW/HIGH、回填、从最小 HIGH 追赶及分片过滤 | 8 项工具单测 + 5 项真实 MySQL Reader 测试；多 Chunk 使用手工 metadata、顺序读取 | 不覆盖真实并行分配、故障恢复与外部 Sink 的组合；论文算法与具体实现的前提不能混用 |
| 第三篇上、下：状态与恢复 | Reader/Enumerator 状态、Split 归属、完成确认、Checkpoint 门槛及恢复 | 合计 51 项对象/序列化测试 + 2 个真实 MySQL MiniCluster 故障作业；下游为受管内存 ListState | 两篇不重复计数；所测事务恢复点在整笔事务之前，未验证事务内部 event/row Checkpoint；没有真实外部 Sink 或物理进程 kill |
| 第四篇：Schema 捕获与传播 | DDL 解码、Schema 事件、Coordinator、flush 确认、状态恢复 | 原始 33 项对象/解析器/算子测试 + 2 个模拟源/内存目标作业 + 5 个真实 MySQL Source 作业 | 原始 40 项中没有真实外部 Sink；内存目标重复 ADD 不证明真实 DDL 幂等；Source 动态 Struct 不等于 SQL 动态迁移 |
| 第五篇：外部提交与恢复 | Paimon flush、replace、DDL 分支、precommit snapshot 发布 | 原始 43 项组件测试、2 个真实 MySQL → Paimon 作业、6 组 Catalog 检查；Checkpoint 场景当前表对账及两个快照的重复 changelog | p=1、bucket=1、主键表和 filesystem 的结果不能推广到所有入口、存储后端或故障位置；主键表正确不等于历史无重复 |
| 本次补充：缓冲与完整字段核对 | BucketAssign 物理 Schema、旧缓冲状态移交、恢复后的字段转换 | 新增正常/故障两种缓冲场景；原 Schema 场景重跑补齐全量对账；分别 10 行 × 3 列、10 行 × 3 列、10 行 × 4 列，均通过 | 重跑不算新增独立覆盖；未重跑全部历史组件测试；未另查新增场景的 changelog，也未验证复杂 DDL 或其他并行配置 |

表中的“测试”“数据库探针”“MiniCluster 作业”和“Catalog 直接调用”是不同证据单位，不能相加成一个端到端通过数。整条推理还依赖各篇明确的条件：例如第二篇的稳定主键与 Chunk key、扫描期间 Schema 不变、默认开启回填，不能被第四篇研究过运行期 Schema 演进这一事实自动解除。

跨篇组合中仍为 **evidence_incomplete** 的重点是：真实并行多 Chunk、不同 HIGH、Schema 演进与 Paimon 故障同时发生；事务内部 event/row 恢复；快照中 DDL；多操作部分成功后的恢复；多表原子性；物理 JM/TM/HA 故障；扩缩容和版本迁移。现有结果提供可复核的局部与特定端到端事实，没有证明这些条件任意组合都成立。


## 公开材料怎样使用

这里提供原实验材料的公开子集：新增探针源码、测试结果摘要、逐字段核对、关键窗口日志与快照摘要。机器目录统一替换为 /work 或 /home/author，整理行尾空白，数据值和测试结果保持不变。Surefire 的逐用例结果从原 XML 提取，不公开环境属性和完整 classpath。原始完整日志、失败记录、Paimon warehouse 由作者本地保留，未全部打包公开。

[下载公开材料 ZIP](/assets/flink-cdc-series/evidence.zip) · [来源及内容哈希](/assets/flink-cdc-series/manifest.json) · [公开文件 SHA-256](/assets/flink-cdc-series/SHA256SUMS)

这些材料便于检查断言与结果，但还不是可移植的一键复现工程。探针只允许用于可丢弃的独立实验库，会重建测试表；示例口令只属于已销毁的演示实例。第五篇依赖本机固定版本构建产物，迁移时须重建 classpath。公开材料没有重新跑过一次独立环境验收，不能将“可下载”写成“可复现已验证”。

<h3 id="evidence-1">第1篇</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/article-01/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/article-01)

- [unit-test-summary.json](/assets/flink-cdc-series/article-01/unit-test-summary.json)
- [mysql-visibility-result.json](/assets/flink-cdc-series/article-01/mysql-visibility-result.json)

<h3 id="evidence-2">第2篇</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/article-02/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/article-02)

- [test-summary.json](/assets/flink-cdc-series/article-02/test-summary.json)

<h3 id="evidence-3">第3篇</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/article-03/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/article-03)

- [testcases.json](/assets/flink-cdc-series/article-03/testcases.json)

<h3 id="evidence-4">第4篇</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/article-04/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/article-04)

- [test-summary.json](/assets/flink-cdc-series/article-04/test-summary.json)

<h3 id="evidence-5">第5篇</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/article-05/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/article-05)

- [test-summary.json](/assets/flink-cdc-series/article-05/test-summary.json)
- [durable-readback-results.txt](/assets/flink-cdc-series/article-05/durable-readback-results.txt)
- [snapshot-summary.json](/assets/flink-cdc-series/article-05/snapshot-summary.json)
- [ddl-matrix-results.txt](/assets/flink-cdc-series/article-05/ddl-matrix-results.txt)

<h3 id="evidence-revision">审校补充：旧 Schema 缓冲与全字段核对</h3>

[原实验说明（公开副本）](/assets/flink-cdc-series/revision/README.txt) · [浏览该组公开文件](https://github.com/Xuxiaotuan/Xuxiaotuan.github.io/tree/main/assets/flink-cdc-series/revision)

- [result-summary.json](/assets/flink-cdc-series/revision/result-summary.json)
- [runs/schema/schema-full-reconciliation.txt](/assets/flink-cdc-series/revision/runs/schema/schema-full-reconciliation.txt)
- [runs/buffer-normal-final/buffer-observations.txt](/assets/flink-cdc-series/revision/runs/buffer-normal-final/buffer-observations.txt)
- [runs/buffer-failure-final/buffer-observations.txt](/assets/flink-cdc-series/revision/runs/buffer-failure-final/buffer-observations.txt)

## 论文与固定源码

- [DBLog: A Watermark Based Change-Data-Capture Framework（2020）](https://arxiv.org/abs/2010.12597)：重点阅读第 3 节及 Algorithm 1，注意其输出策略与本文 MySQL 路径的差异。
- [Flink CDC 固定提交](https://github.com/apache/flink-cdc/tree/045e8da45df4b1017849b04e2d8bfe8f0ed7936d)：各篇进一步链接到具体文件和方法。
- [Flink 1.20.3](https://github.com/apache/flink/tree/release-1.20.3)：第三篇、第五篇使用的 Runtime 基线。
- [Paimon 2.0.0 固定提交](https://github.com/apache/paimon/tree/604e6d5e131c74a8d127333a2a3ad6d0319732bf)：第五篇的文件、Schema 与 snapshot 提交依据。
