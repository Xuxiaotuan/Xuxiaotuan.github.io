---
layout: post
title: 小米 AX3000T 原厂系统配置 ShellClash 与 Mihomo
date: 2023-07-21 00:00:00 +0800
categories: [Linux, Xiaomi, ShellClash]
description: 在小米 AX3000T 原厂系统上，以不刷机的方式安装 ShellClash、运行 Mihomo，并配置局域网透明代理。
keywords: 小米路由器, AX3000T, ShellClash, Mihomo, 透明代理
mermaid: false
sequence: false
flow: false
mathjax: false
mindmap: false
mindmap2: false
---

这篇文章记录一条不刷机的方案：保留小米原厂系统，只在可写分区安装 ShellClash 和 Mihomo，再由 ShellClash 管理透明代理。

这里需要先澄清名称：**ClashX 是 macOS 客户端**，路由器上运行的是 Mihomo（或 Clash 兼容核心）和 ShellClash 管理脚本。订阅链接是凭据，不应写进博客、截图或公开仓库。

## 当前验证环境

本文按以下设备和版本验证：

| 项目 | 实测值 |
| --- | --- |
| 路由器 | 小米 AX3000T，硬件标识 RD03 |
| 原厂固件 | MiWiFi 1.0.98（CN） |
| 内核架构 | `aarch64` |
| 内存 | 256 MB（系统可见约 244 MB） |
| ShellClash | 安装到 `/data/ShellCrash` |
| Mihomo | `v1.19.17`，Mix 模式 |
| 面板 | 本地 Zashboard，`http://192.168.31.1:9999/ui/` |

`/data` 是有限的持久化空间。实测安装管理脚本、Mihomo 和本地面板后还剩约 6.8 MB，因此安装前必须检查空间，不要继续堆叠多个核心、日志或大型规则库。

## 旧教程为什么不再直接适用

旧版本教程常见的做法有三个问题：

1. `set_config_iotdev` 和固定的 `admin` 密码并不是当前 RD03/1.0.98 的可靠通用路径。
2. 把 root 密码清空或写死为 `admin` 会留下空密码 SSH，风险很高。
3. 安装脚本、核心、面板和订阅是四件事，安装脚本成功不等于代理已经运行，更不等于透明代理已经验证。

OpenWrt 的 AX3000T 资料将 RD03 的 1.0.98 对应到 `xqsystem/start_binding`，但这属于固件接口漏洞利用，版本变化后可能失效。执行前应先确认型号、固件和当前登录会话。[AX3000T 资料](https://openwrt.org/toh/xiaomi/ax3000t)

## 1. 准备和安全边界

- 只在自己的局域网内操作，先备份小米路由器配置。
- SSH 只在安装和维护期间临时开启；完成后关闭 22 端口。
- 第一次进入 root shell 后设置专用 root 密码，不要使用公开教程中的固定密码，也不要清空密码。
- 安装前检查：

```sh
df -h /data /tmp
uname -a
command -v curl
command -v iptables
```

- 不要把管理页面 URL 中的 `stok`、订阅链接、节点信息或 root 密码提交到 Git。

## 2. 临时开启 SSH

登录路由器网页后台，在“路由状态”页面取得当前会话的 `stok`。不要把真实值写进文章或命令历史。

当前 RD03/1.0.98 应按 AX3000T 资料中的 `xqsystem/start_binding` 方法临时启动 dropbear。接口调用成功后先验证：

```sh
nc -vz 192.168.31.1 22
```

然后使用兼容旧 RSA host key 的 SSH 参数连接：

```sh
ssh \
  -o HostKeyAlgorithms=+ssh-rsa \
  -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  root@192.168.31.1
```

成功进入 shell 后，立即设置 root 密码：

```sh
passwd
```

不要在公共文章中记录密码，也不要把 `passwd -d root` 当作默认步骤。完成安装和验证后关闭 SSH：

```sh
nvram set ssh_en=0
nvram commit
/etc/init.d/dropbear stop
```

再次检查 22 端口应为关闭状态。

## 3. 安装 ShellClash

ShellClash 官方说明支持小米官方系统。安装目录应选 `/data`，不要写满根分区；安装过程中选择一个不会和系统命令冲突的别名，例如 `crash`。[ShellClash README](https://github.com/liyaoxuan/ShellClash/blob/master/README.md)

```sh
export url='https://raw.githubusercontent.com/juewuy/ShellClash/stable'
curl -kfsSL "$url/install.sh" -o /tmp/shellclash-install.sh
sh /tmp/shellclash-install.sh
```

安装完成后，重新加载环境变量并进入管理菜单：

```sh
. /etc/profile
crash
```

在小米官方系统上，ShellClash 会把文件放在 `/data/ShellCrash`。如果安装器检测不到存储空间或下载失败，先停止，不要改刷固件来“解决”安装问题。

## 4. 导入订阅和启动 Mihomo

在 ShellClash 菜单中：

1. 选择“路由设备配置局域网透明代理”。
2. 在“配置文件管理”中添加订阅提供者。
3. 订阅链接只在路由器本地输入，不要写入 Markdown 或截图。
4. 选择 Mihomo 核心，生成配置并先执行配置校验。
5. 确认校验成功后再启动服务。

本次验证中，Mihomo 使用 Mix 模式，TCP/UDP 监听端口为 `7890`，透明 TCP 转发使用内部端口 `7892`，DNS 转发使用 `1053`。这些端口由 ShellClash 管理，不建议手工改配置文件。

## 5. 安装本地面板

如果访问 `http://192.168.31.1:9999/ui/` 只看到“未安装本地面板”，在 ShellClash 的“更新与支持”菜单中进入面板安装，选择 Zashboard。安装后强制刷新浏览器。

面板只应在局域网访问。不要把 9999 端口映射到 WAN，也不要把面板 token 或订阅信息公开。

节点选择有两层：

- 在 `🚀 节点选择` 中选择实际节点，例如 `🇯🇵 日本W02 | IEPL`。
- 如果需要全局模式默认跟随该组，将 `GLOBAL` 设置为 `🚀 节点选择`。

“日本2”可能对应不同命名。本文使用的是订阅中的 `🇯🇵 日本W02 | IEPL`，应以你当前订阅返回的实际名称为准。

## 6. 验证，不把“启动成功”当成“代理可用”

至少检查以下几层：

```sh
# 服务与端口
ps | grep CrashCore
netstat -lntup | grep -E '7890|7892|7893|9999'

# 资源
free
df -h /data
```

网页和显式代理可以从局域网客户端验证：

```sh
curl -L http://192.168.31.1:9999/ui/
curl --proxy http://192.168.31.1:7890 https://www.gstatic.com/generate_204
```

透明代理还要检查路由器的 NAT 规则和计数器：

```sh
iptables -t nat -L shellcrash -v -n
iptables -t nat -L shellcrash_dns -v -n
ip6tables -t nat -S | grep -E 'shellcrash|7892|1053'
```

本次验证得到的证据是：本地面板返回 HTTP 200，显式代理请求返回 HTTP 204，`192.168.31.0/24` 的 TCP 和 DNS UDP 重定向规则有实际计数，IPv6 规则也已写入。SSH 在验证结束后关闭，22 端口为关闭状态。

以下内容仍需单独验证，不能由上述结果推断：

- 路由器重启后 Mihomo 是否自动恢复；
- UDP/QUIC、游戏和特殊应用的端到端代理效果；
- 订阅过期、节点不可用和自动更新后的行为；
- 所有其他终端的实际访问效果。

## 7. 回滚

如果透明代理影响网络，先在 ShellClash 菜单中停止服务，确认路由规则已清理；必要时关闭开机启动。保留 `/data/ShellCrash` 目录和配置备份，确认不再需要后再卸载。这个方案不涉及刷写固件，回滚边界比更换 OpenWrt 小，但仍应保留小米原厂配置。

## 参考资料

- [Xiaomi AX3000T（OpenWrt 设备资料）](https://openwrt.org/toh/xiaomi/ax3000t)
- [ShellClash README](https://github.com/liyaoxuan/ShellClash/blob/master/README.md)
- [小米路由器原始文章（2023）](https://xuyinyin.cn/2023/07/21/route-clashx/)
