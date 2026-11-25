#!/bin/sh
# 给手机 App 用的只读接口：返回本机邻居表（IP + MAC），纯文本
printf 'HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n'

# 1) 内核邻居表（最准，含刚通信过的设备）
ip neigh show 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="lladdr") print $1" "toupper($(i+1))}'

# 2) DHCP 租约兜底（离线但登记过的设备也能给出 MAC）
[ -f /tmp/dhcp.leases ] && awk '{print $3" "toupper($2)}' /tmp/dhcp.leases 2>/dev/null
exit 0
