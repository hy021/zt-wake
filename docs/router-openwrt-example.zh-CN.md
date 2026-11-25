[English](router-openwrt-example.zh-CN.md) | 简体中文

# OpenWrt 实战配置（含踩过的坑）

> 这份不是"理想步骤"，而是**实际跑通一遍后回头整理的过程**：换源、装 socat、绑错地址导致自回环、开机时序、端口被占、防火墙误判……按顺序照做可以少走很多弯路。
> 适用于 OpenWrt / LEDE / ImmortalWrt / iStoreOS；老固件的"换源"部分尤其重要。

## 0. 先确认底座，别急着装

```sh
ubus call system board
cat /etc/openwrt_release
opkg print-architecture
cat /etc/opkg/distfeeds.conf
```

重点看三样：**发行版与编译版本**（如 `SNAPSHOT` / `R21.6.1`）、**内核版本**（如 `5.4.122`）、**musl/libc 版本**。

判断原则：

- 官方 23.05 / 24.10：`opkg install socat` 一般直接可用，跳到第 3 步；
- 第三方编译（Lean LEDE、iStoreOS 老版本）：官方源通常**对不上代际**，需要换源；
- 源地址里如果出现 `lede/snapshots`，**基本已经失效**（镜像站早清空了），必须换。

## 1. 换源（老固件必做）

以 Lean LEDE R21.6.1（musl 1.1.24、lua 版 LuCI）为例，同代可用的是 **OpenWrt 19.07.10**：

```sh
cp /etc/opkg/distfeeds.conf /etc/opkg/distfeeds.conf.bak

cat > /etc/opkg/distfeeds.conf <<'EOF'
src/gz openwrt_core https://mirrors.cloud.tencent.com/lede/releases/19.07.10/targets/x86/64/packages
src/gz openwrt_base https://mirrors.cloud.tencent.com/lede/releases/19.07.10/packages/x86_64/base
src/gz openwrt_luci https://mirrors.cloud.tencent.com/lede/releases/19.07.10/packages/x86_64/luci
src/gz openwrt_packages https://mirrors.cloud.tencent.com/lede/releases/19.07.10/packages/x86_64/packages
src/gz openwrt_routing https://mirrors.cloud.tencent.com/lede/releases/19.07.10/packages/x86_64/routing
src/gz openwrt_telephony https://mirrors.cloud.tencent.com/lede/releases/19.07.10/packages/x86_64/telephony
EOF

opkg update
```

`x86/64` 与 `x86_64` 按自己架构替换（ARM 路由是 `arm_cortex-a7` 之类）。

**六个 feed 都打印 `Updated list ...` 才算成功**，用条数复核：

```sh
opkg list | wc -l        # 19.07.10 的 x86_64 全量约 7900 条
```

### 换源的三个坑

1. `Signature check failed` → `sed -i 's/# option check_signature.*/option check_signature 0/' /etc/opkg.conf` 后重试；
2. `wget returned 5` / https 握手失败 → 把地址里的 `https://` 换成 `http://`（镜像会 301，opkg 会跟随）；
3. 腾讯抽风 → 整套换阿里云：`https://mirrors.aliyun.com/openwrt/releases/19.07.10/...`，路径结构完全一样。

### 换源后的三条禁令

| 禁止 | 原因 |
|---|---|
| 装任何 `kmod-*` | 源里的 kmod 是针对旧内核编译的，与当前内核版本不符，依赖校验直接失败；`--force-depends` 强装会导致模块加载失败甚至起不来 |
| `opkg upgrade` | 会批量顶掉 libc / libjson-c / luci-base，覆盖第三方固件的补丁，常见后果是 LuCI 打不开 |
| 无预检直接装 | 装前先 `opkg install --noaction <包名>` 看依赖是否会把系统带坏 |

退回旧源：`cp /etc/opkg/distfeeds.conf.bak /etc/opkg/distfeeds.conf`

## 2. 装 socat

```sh
opkg install --noaction socat    # 先预检
opkg install socat
socat -V | head -3               # 应显示 WITH_UDP 1 / WITH_IP4 1
```

> **坑**：`socat` 包会自带一个 UCI 服务（`/etc/init.d/socat` + `/etc/config/socat`，默认 `enable '0'`）。它虽然默认不启动，但会和你的中继抢同一个 9 端口，排查时极容易被误判成"服务自己起来了又自己死了"。建议直接停用：
> ```sh
> /etc/init.d/socat disable
> ```

## 3. 找到隧道的接口名和地址

> 本章所有地址都是**示例值**：隧道地址 `10.147.20.5`、局域网广播 `192.168.1.255`、接口名 `ztab1234cd`。照做时替换成你自己的，别直接抄。

```sh
zerotier-cli listnetworks        # 最后一列是接口名，例如 ztab1234cd
ip -4 addr show | grep -E 'inet |zt'
```

两个关键事实（很多人在这里翻车）：

- ZeroTier 的接口**不一定叫 `zt*`**，可能叫 `ztab1234cd` 这种；按 `zt` 前缀过滤会漏掉它；
- 取到的那个 IP（示例 `10.147.20.5`）才是 **bind 地址**，不是 LAN 网关。下面所有示例地址统一用 `10.147.20.5` / `192.168.1.255`，实际请换成自己的。

## 4. 前台验证（先跑通再做成服务）

```sh
socat -d -d UDP4-RECVFROM:9,bind=10.147.20.5,reuseaddr,fork \
      UDP4-DATAGRAM:192.168.1.255:9,broadcast
```

手机发一次唤醒包，日志应出现一次：

```
received packet with 102 bytes from AF=2 10.147.20.x
```

### **最大的坑：bind 不能写 `0.0.0.0`**

监听所有接口再转发到 LAN 广播，广播包会被同一个 socket 收回来，形成**自回环**：每处理一个包就 fork 一个子进程，源地址一直是本机，端口一路变（58238 → 40218 → 33143 …），CPU 很快打满、日志刷屏。

判断特征：日志里源地址是本机 IP、子进程不断出现、`forked off` 无限增长。见到就立刻 `Ctrl+C`，把 `bind` 改成隧道 IP。

另一个相关的坑：`192.168.1.255` 这种广播地址要按**实际掩码**算，不是所有网段都是 /24。用 `ip -4 addr show br-lan` 看 `brd` 后面的值。

## 5. 做成开机自启（关键是等隧道拿到 IP）

**坑**：路由器重启后 ZeroTier 往往要十几秒才拿到 IP。如果服务在启动瞬间就去 `bind` 一个还不存在的地址，会绑定失败；再配合 `respawn 3600 5 5`（3600 秒内失败 5 次就永久放弃），服务就彻底死了，表现为"手动跑能通、开机就是不行"。

正确做法：**等待逻辑放在进程内部**，进程一直活着，procd 就不会触发放弃。

```sh
cat > /usr/bin/wolrelay.sh <<'EOF'
#!/bin/sh
# 自动找隧道网卡（zt* / tailscale* / wg*），等到拿到 IPv4 再 exec socat
LAN_BCAST=$(ip -4 addr show br-lan 2>/dev/null | awk '/brd /{for(i=1;i<=NF;i++) if($i=="brd"){print $(i+1); exit}}')
[ -z "$LAN_BCAST" ] && LAN_BCAST=255.255.255.255

while :; do
    IF=""
    for c in $(ip -o link show | awk -F': ' '{print $2}' | cut -d@ -f1); do
        case "$c" in zt*|tailscale*|wg*) IF="$c"; break;; esac
    done
    [ -z "$IF" ] && { sleep 2; continue; }
    TIP=$(ip -4 addr show "$IF" 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -n1)
    [ -n "$TIP" ] && break
    sleep 2
done

logger -t wolrelay "ready: $TIP:9 ($IF) -> $LAN_BCAST:9"
exec /usr/bin/socat -ly \
    "UDP4-RECVFROM:9,bind=$TIP,reuseaddr,fork" \
    "UDP4-DATAGRAM:$LAN_BCAST:9,broadcast"
EOF
chmod +x /usr/bin/wolrelay.sh

cat > /etc/init.d/wolrelay <<'EOF'
#!/bin/sh /etc/rc.common
START=99
STOP=10
USE_PROCD=1

start_service() {
    procd_open_instance
    procd_set_param command /usr/bin/wolrelay.sh
    procd_set_param respawn 0 1 5      # 0 = 永不放弃重试
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
EOF
chmod +x /etc/init.d/wolrelay
/etc/init.d/wolrelay enable
/etc/init.d/wolrelay restart
```

## 6. 清残留进程（"端口被占"的元凶）

反复手动跑 socat、或旧服务没停干净时，会有多个进程抢 9 端口，包落到谁手里不确定：

```sh
/etc/init.d/wolrelay stop 2>/dev/null
killall socat 2>/dev/null
netstat -lnup | grep ':9 '     # 应该没有任何输出
```

## 7. 验证服务真的接管了

```sh
logread -e wolrelay | tail -3
ubus call service list '{"name":"wolrelay"}'
```

后者出现 `"instance1": { "running": true, "pid": ... , "command": ["/usr/bin/wolrelay.sh"] }` 才算 procd 真的在管；只靠 `ps` 看 `PPid: 1` 分辨不出来（procd 和孤儿进程的父进程都是 1）。

## 8. 防火墙：多数情况不需要，别被误导

实测中，**不加任何规则也能收到 102 字节**，说明隧道接口入站本来就是通的。只有在你把 INPUT 默认策略改成拒绝、或自定义了防火墙规则时，才需要：

```sh
uci add firewall rule
uci set firewall.@rule[-1].name='Allow-WOL-Relay'
uci set firewall.@rule[-1].src='*'
uci set firewall.@rule[-1].proto='udp'
uci set firewall.@rule[-1].dest_port='9'
uci set firewall.@rule[-1].target='ACCEPT'
uci commit firewall && /etc/init.d/firewall reload
```

如果之前加过临时规则、现在想删：

```sh
iptables -S INPUT | grep 'dport 9'
iptables -D INPUT -i <隧道接口> -p udp --dport 9 -j ACCEPT
/etc/init.d/firewall reload
```

## 9. 如果广播出不去（少数内核限制）

部分 LEDE 内核会丢弃"从非 LAN 接口收到、再转发成 LAN 广播"的包。替代方案是不发广播，直接**二层单播魔术包**：

```sh
opkg install ether-wake
socat UDP4-RECVFROM:9,bind=<隧道IP>,reuseaddr,fork \
      EXEC:"/usr/bin/ether-wake <目标MAC>",nofork
```

原理：`ether-wake` 在 br-lan 上直接发二层帧，不走 IP 广播，绕过内核限制。缺点是每台主机要单独配一个中继。

## 10. 最终验收

1. 手机关 Wi-Fi、走流量，隧道连上，发一次包 → 电脑应被唤醒；
2. `logread -e wolrelay` 有 `ready: ...`、`receiving on ...`；
3. **重启路由器**，等隧道 `ONLINE` 后再发一次 → 仍能唤醒，才算真正自动化。

排错顺序永远是：**日志有没有收到 102 字节** → 抓包看 br-lan 有没有广播 → 主机侧 WOL 设置。
