[English](router.zh-CN.md) | 简体中文

# 中继设备与路由器配置

**不限 OpenWrt。** 前提只有一条：有一台**常在线**、**接在目标局域网**、**能跑 `socat`** 的 Linux。
OpenWrt 只是最省事的形态（有 procd、有 opkg、脚本零配置）。

## 硬性要求

1. 始终在线（休眠/断电期间无法唤醒任何主机）
2. 与目标主机同一广播域（跨 VLAN 需把广播地址写死）
3. 能跑 UDP 监听 + 广播转发
4. 能被手机透过隧道打到
5. 开机自启（断电恢复后能自动拉起）

反例：光猫、纯 web 管理的原厂路由、锁了 SSH 的运营商网关。

## 固件支持矩阵

| 平台 | 支持度 | 落地方式 |
|---|---|---|
| OpenWrt / LEDE / ImmortalWrt / iStoreOS | 完整 | `opkg install socat` + `wolrelay` |
| Asuswrt-Merlin | 可用 | 开 JFFS + Entware，`/jffs/scripts/services-start` 拉起 |
| Padavan | 可用 | Entware 装 socat，启动脚本拉起 |
| DD-WRT | 可用 | 启动命令 + 外部存储 |
| pfSense / OPNsense | 可用 | shell 装 socat 或写 rc 脚本 |
| MikroTik RouterOS | 部分 | 内置 `/tool wol` 能发唤醒包，但没有 UDP 中继，需 SSH/API 触发；**本 App 不支持这种触发** |
| Synology / QNAP | 可用 | 任务计划跑 socat 脚本 |
| 树莓派 / 软路由 / 常在线小主机 | 可用 | systemd / rc.local |
| 常在线 Windows 小主机 | 可用 | `wol_relay.py`，见下 |
| 原厂家用路由（无 shell） | 不可用 | 改用局域网内任一常在线设备当中继 |

## OpenWrt 步骤

```sh
opkg update && opkg install socat

scp wolrelay root@<路由器>:/etc/init.d/wolrelay
ssh root@<路由器> 'chmod +x /etc/init.d/wolrelay'

/etc/init.d/wolrelay enable
/etc/init.d/wolrelay start
logread -t wolrelay    # listen <隧道地址>:9 -> <局域网广播>:9
```

三个变量：

```sh
ZT_IP=auto          # 自动找 zt* 网卡；Tailscale 写 tailscale0 的 IP；也可写死
LAN_IFACE=br-lan
LAN_BCAST=auto      # 自动按掩码算广播（已验证 /20、/26）
```

### 防火墙

中继是"本地监听 + 出站广播"，默认策略下无需额外放行。若自定义了 INPUT 拒绝策略：

```sh
uci add firewall rule
uci set firewall.@rule[-1].name='Allow-WOL-Relay'
uci set firewall.@rule[-1].src='*'
uci set firewall.@rule[-1].proto='udp'
uci set firewall.@rule[-1].dest_port='9'
uci set firewall.@rule[-1].target='ACCEPT'
uci commit firewall && /etc/init.d/firewall reload
```

## 任何 Linux 的通用写法

```sh
socat UDP4-RECV:9,bind=<隧道地址>,fork UDP4-DATAGRAM:<局域网广播>:9,broadcast
```

配开机自启即可：

- Merlin：`/jffs/scripts/services-start`
- Padavan / DD-WRT：启动脚本
- 群晖：控制面板 → 任务计划 → 开机触发
- 树莓派：systemd unit 或 `/etc/rc.local`
- 普通 Linux：systemd（`Restart=always`）

## 没有路由器权限：用局域网内任一常在线设备

Linux 用上面的 socat 命令。常在线 Windows 小主机用这个脚本（Python 3，无依赖）：

```python
# wol_relay.py —— 监听 UDP 9，收到即转发到局域网广播
import socket
LISTEN_PORT = 9
BROADCAST = "192.168.1.255"      # 改成自己的广播地址
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.bind(("0.0.0.0", LISTEN_PORT))
print("relay", LISTEN_PORT, "->", BROADCAST)
while True:
    data, addr = s.recvfrom(1024)
    s.sendto(data, (BROADCAST, LISTEN_PORT))
```

配合任务计划程序开机启动。注意：这台 Windows 自己不能被唤醒，它只当中继。

## 网络侧注意事项

- 关闭 AP 隔离 / 客户端隔离（隔离会拦广播）
- 与目标主机同一广播域；跨 VLAN 时把 `LAN_BCAST` 写死为目标网段的广播地址
- UDP 9 未被占用；占用时改端口，App 里同步改
- 交换机的广播抑制 / 风暴控制不要过严
- 主机 BIOS 开启 WOL、关闭 ErP/EuP、Windows 关闭快速启动

## 安全取舍

| 方案 | 暴露面 | 建议 |
|---|---|---|
| ZeroTier / Tailscale / WireGuard | 无公网端口 | 推荐 |
| frp / nps | 仅 frps 端口 | 中 |
| 公网 IP + UDP 映射 | UDP 9 对公网 | 限制来源或换端口 |

中继本身不做认证（UDP 无握手），"谁能进你的隧道"就是安全边界。
