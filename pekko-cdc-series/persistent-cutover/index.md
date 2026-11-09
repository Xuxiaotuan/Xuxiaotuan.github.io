---
layout: page
title: 持久化切换授权实验
permalink: /pekko-cdc-series/persistent-cutover/
---

本页汇总 Pekko CDC 持久化切换授权、旧账号排空、配额迁移和运行时凭据重绑定的验证范围。

- `KILL_T_BEFORE_J`：真实 MySQL、Actor Journal、MySQL Sink，源/目标 10 行、6 字段逐字段一致。
- `KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED`、`STALE_GRANT`：固定窗口已验证。

结果只支持本轮固定环境和场景；DDL、多 owner、共享 CDC、跨实例迁移及未执行历史场景仍未验证。源码版本和原始失败证据保留在 [pekko-reference 实验目录](https://github.com/Xuxiaotuan/pekko-reference/tree/feature/pekkosync-mvp-cleanup-datafusion/docs/experiments/2026-11-09-persistent-cutover-authorization)。
