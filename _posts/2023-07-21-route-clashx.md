---
layout: post
title: 小米路由器开启clashx科学
categories: [Linux, Clashx]
description: some word here
keywords: 小米路由器, clashx
mermaid: false
sequence: false
flow: false
mathjax: false
mindmap: false
mindmap2: false
---

分为以下几个步骤：

- 首先开启路由器SSH功能
- SSH登录到路由器
- 在路由器下载Clash
- 配置Clash，重启生效
### 开启SSH权限
首先登陆你的路由器管理界面，点击路由状态页签，此时地址栏应该显示如下地址：
> http://miwifi.com/cgi-bin/luci/;stok=xxxxxxx****/web/home#router

或者

> http://192.168.31.1/cgi-bin/luci/;stok=xxxxxxx****/web/home#router

将/web/home#router 替换为如下文本，之后输入回车访问，此时页面返回{“code”:0}，即可使用ssh工具测试是否开启成功。

```shell
/api/misystem/set_config_iotdev?bssid=Xiaomi&user_id=longdike&ssid=-h%3B%20nvram%20set%20ssh_en%3D1%3B%20nvram%20commit%3B%20sed%20-i%20's%2Fchannel%3D.*%2Fchannel%3D%5C%22debug%5C%22%2Fg'%20%2Fetc%2Finit.d%2Fdropbear%3B%20%2Fetc%2Finit.d%2Fdropbear%20start%3B%20echo%20-e%20'admin%5Cnadmin'%20%7C%20passwd%20root%3B
```

### 登陆SSH
Mac下的iTerm即可 ssh root@192.168.31.1 默认密码为 admin （刚刚那串破解码中设定的密码）
如果出现Unable to negotiate with 192.168.31.1 port 22: no matching host key type found. Their offer: ssh-rsa
则修改为
>ssh -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedKeyTypes=+ssh-rsa root@192.168.31.1

成功登陆SSH后，直接输入以下命令安装ShellClash
```shell
export url='https://fastly.jsdelivr.net/gh/juewuy/ShellClash@master' && wget -q --no-check-certificate -O /tmp/install.sh $url/install.sh  && sh /tmp/install.sh && source /etc/profile &> /dev/null
```
按照提示即可完成安装！  
安装完成后，直接在SSH中使用：

```shell
clash
```

命令即可管理脚本

#### 导入clash配置文件


最后添加clash配置链接到路由器即可使用

- 可视化界面：clash服务成功启动后可以通过在浏览器访问 http://192.168.31.1:9999/ui 设置代理

## Refer to or reading

- [x] [小米路由器通过Clash设置代理](https://xtrojan.pro/author/xtrojan)
- [x] [恩山无线论坛-ssh 小米路由器](https://www.right.com.cn/forum/thread-8238692-1-1.html)
- [x] [shellClash](https://github.com/juewuy/ShellClash/blob/master/README_CN.md)