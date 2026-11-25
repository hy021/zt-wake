# 飞牛 NAS（fnOS）做中继

飞牛 fnOS 基于 **Debian 12（bookworm）**，本质就是一台常开的 Linux，很适合当 WOL 中继——尤其适合"路由器是原厂固件、没有 shell"的用户：**路由器完全不用动，让 NAS 代发广播**。

前提：NAS 与目标主机在**同一局域网**，且 NAS 不休眠。

## 1. 打开 SSH

fnOS 的 SSH 默认关闭，需要手动开：

1. 网页后台 → **系统设置 → SSH**
2. 打开 **SSH 功能** 开关
3. 打开 **管理员权限**（否则管理员账号登不上）
4. 端口默认 `22`（可改）

然后在电脑上登录：

```sh
ssh <管理员账号>@<NAS的IP> -p 22
```

## 2. 装 socat

fnOS 是 Debian，直接用 apt：

```sh
sudo apt update
sudo apt install -y socat
socat -V | head -3        # 确认 WITH_UDP 1
```

## 3. 写中继脚本（自动等隧道就绪）

NAS 重启后隧道（ZeroTier / Tailscale / wg）可能晚于服务起来，所以同样把"等地址"放在进程内部：

```sh
sudo tee /usr/local/bin/wolrelay.sh >/dev/null <<'EOF'
#!/bin/sh
# 自动找隧道网卡，拿到 IPv4 后 exec socat 转发到 LAN 广播
LAN_IF=${LAN_IF:-enp1s0}          # 改成 NAS 接局域网那块网卡
LAN_BCAST=$(ip -4 addr show "$LAN_IF" 2>/dev/null | awk '/brd /{for(i=1;i<=NF;i++) if($i=="brd"){print $(i+1); exit}}')
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

echo "ready: $TIP:9 ($IF) -> $LAN_BCAST:9"
exec socat -ly "UDP4-RECVFROM:9,bind=$TIP,reuseaddr,fork" \
             "UDP4-DATAGRAM:$LAN_BCAST:9,broadcast"
EOF
sudo chmod +x /usr/local/bin/wolrelay.sh
```

网卡名查法：`ip -4 addr show | grep -E 'inet |brd'`，找到带局域网 IP 那块。

## 4. 注册成 systemd 服务

```sh
sudo tee /etc/systemd/system/wolrelay.service >/dev/null <<'EOF'
[Unit]
Description=WOL relay (UDP 9 -> LAN broadcast)
After=network-online.target tailscaled.service zerotier-one.service
Wants=network-online.target

[Service]
Type=simple
Environment=LAN_IF=enp1s0
ExecStart=/usr/local/bin/wolrelay.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now wolrelay
sudo systemctl status wolrelay --no-pager
```

看日志：

```sh
journalctl -u wolrelay -f
# 期望：ready: 10.147.20.5:9 (ztab1234cd) -> 192.168.1.255:9
```

## 5. 验证

1. 确认端口在听：`sudo ss -lnup | grep ':9 '`
2. 手机（走流量、隧道已连）发一次唤醒包
3. `journalctl -u wolrelay -n 20` 应出现一次 102 字节的接收记录；主机应被唤醒

若日志没有记录，说明包没进来：先查隧道是否连通、`bind` 地址是否是隧道网卡的地址。

## 6. 防火墙与端口占用

- fnOS 若启用了防火墙（后台"安全/防火墙"里有开关），需放行 **UDP 9**；
- 端口被占：`sudo ss -lnup | grep ':9 '`，有别的进程就 `sudo kill <pid>` 后再 `systemctl restart wolrelay`；
- **不要**把 `bind` 写成 `0.0.0.0`：会把自己发的广播收回来形成自回环，CPU 打满。

## 7. Docker 方式（可选）

习惯用容器的话，把 socat 跑成 host 网络容器也行：

```sh
docker run -d --name wolrelay --restart=always --network host \
  alpine/socat -ly \
  "UDP4-RECVFROM:9,bind=<隧道IP>,reuseaddr,fork" \
  "UDP4-DATAGRAM:<局域网广播>:9,broadcast"
```

缺点是隧道 IP 变了要重建容器，所以更推荐上面的 systemd + 等待脚本方案。

## 8. 注意事项

- **NAS 不能休眠**：系统的硬盘休眠不影响网卡，但整机睡眠/关机就会断掉中继；
- NAS 自身如果也要被唤醒，那是另一件事（需主板/网卡支持 WOL），跟中继无关；
- 隧道地址变化（重连后重新分配）时，脚本会重新取地址，只要进程重启即可：`sudo systemctl restart wolrelay`。
