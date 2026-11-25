# 已知限制

## MAC 地址在部分 ROM 上读不到

- Android 10 起，`targetSdk 29+` 无法读 `/proc/net/arp`；
- 本机 netlink（`RTM_GETNEIGH`）在部分 ROM 的 SELinux 策略下被直接拒绝（`errno=13`、`pkts=0`）；
- 属于系统限制，不是实现问题——同类开源 App 在同一台机器上同样显示 `MAC address not found`。

现状：MAC 需手填；`targetSdk` 保持 28 以尽量保留 `/proc/net/arp` 可用性。

## 隧道面板不能控制 VPN

Android 客户端的 ZeroTier 不对外开放控制接口，VPN 授权弹窗本身也是系统行为。
App 只显示网关可达性，连接动作需在对应客户端里完成。

## 扫描只列"确认在线"设备

某些路由器会对不存在的地址统一回 RST（典型耗时 280~300ms 且高度一致）。
这类假阳性一律过滤，不统计、不显示。

## 广播不跨三层

这是设计前提，不是 bug。见 [tunnel.md](tunnel.md)。

## 中继必须常在线

断网 / 断电 / 重启期间无法唤醒任何主机；中继需配置开机自启。
