[English](troubleshooting.zh-CN.md) | 简体中文

# 排错

| 现象 | 常见原因 |
|---|---|
| 点唤醒没反应 | 中继没跑 / 网关地址填错 / 端口不一致 |
| 中继日志没收到包 | 隧道没连通 / 防火墙拦 INPUT / App 目标地址错 |
| 中继收到了但主机不醒 | 广播地址算错、跨 VLAN、BIOS 未开 WOL、关机后网卡断电（ErP） |
| 只能唤醒一次 | 中继进程退出（没配自启 / 没 respawn） |
| 扫描出一堆"在线" | 路由器对不存在地址代答 RST |

## 先确认包到底发出去没有

在中继上：`logread -t wolrelay` 有记录 → 包到了；没记录 → 问题在隧道或目标地址。

## 直接发一个标准魔法包（跳过 App）

```python
import socket
mac = "AA:BB:CC:DD:EE:FF"
pkt = b"\xff"*6 + bytes.fromhex(mac.replace(":",""))*16
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.sendto(pkt, ("192.168.1.255", 9))
```

在局域网里跑通 → 说明主机 WOL 配置没问题，问题在隧道/中继；跑不通 → 先修主机侧（BIOS、网卡供电、快速启动）。

## 主机侧检查

- BIOS 开启 WOL / PCIe 唤醒，关闭 `ErP / EuP`；
- 网卡属性：允许唤醒、只允许幻数据包唤醒；
- Windows 关闭"快速启动"（`powercfg /h off`），否则关机其实是休眠；
- 交换机 / AP 未开启客户端隔离，广播抑制不要过严。
