English | [简体中文](router-openwrt-example.zh-CN.md)

# OpenWrt walkthrough (including the pitfalls we actually hit)

> This is not an "ideal steps" page: it is **a walkthrough written after getting it running for real** — replacing feeds, installing socat, binding the wrong address and self-looping, boot ordering, port conflicts, false firewall scares. Following it in order saves a lot of detours.
> Applies to OpenWrt / LEDE / ImmortalWrt / iStoreOS; the "replace feeds" part matters most on older third-party builds.

## 0. Identify the base first, do not install anything yet

```sh
ubus call system board
cat /etc/openwrt_release
opkg print-architecture
cat /etc/opkg/distfeeds.conf
```

Three things matter: **distro and build** (e.g. `SNAPSHOT` / `R21.6.1`), **kernel version** (e.g. `5.4.122`), and **musl/libc version**.

Rules of thumb:

- Official 23.05 / 24.10: `opkg install socat` usually just works — skip to step 3;
- Third-party builds (Lean LEDE, older iStoreOS): the official feeds are usually **the wrong generation** and must be replaced;
- If any feed URL contains `lede/snapshots`, it is **almost certainly dead** (mirrors purged those years ago) and must be replaced.

## 1. Replace the feeds (mandatory on older builds)

Taking Lean LEDE R21.6.1 (musl 1.1.24, lua-based LuCI) as the example, the matching generation is **OpenWrt 19.07.10**:

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

Replace `x86/64` and `x86_64` with your own architecture (ARM routers use something like `arm_cortex-a7`).

**All six feeds must print `Updated list ...`** — verify by count:

```sh
opkg list | wc -l        # a full 19.07.10 x86_64 tree is roughly 7900 packages
```

### Three traps when replacing feeds

1. `Signature check failed` → run `sed -i 's/# option check_signature.*/option check_signature 0/' /etc/opkg.conf` and retry;
2. `wget returned 5` / TLS handshake failure → change `https://` to `http://` in the URLs (mirrors answer 301 and opkg follows it);
3. A mirror having a bad day → switch the whole set to another mirror: `https://mirrors.aliyun.com/openwrt/releases/19.07.10/...` — identical path structure.

### Three prohibitions after replacing feeds

| Don't | Why |
|---|---|
| Install any `kmod-*` | The kmods in the feed were built for a different kernel than yours, so dependency checks fail outright; forcing with `--force-depends` leads to modules that won't load and possibly a router that won't boot |
| Run `opkg upgrade` | It replaces libc / libjson-c / luci-base wholesale, overwriting vendor patches; a very common outcome is a dead LuCI |
| Install without a dry run | Always `opkg install --noaction <pkg>` first to see whether dependencies would wreck the system |

Roll back: `cp /etc/opkg/distfeeds.conf.bak /etc/opkg/distfeeds.conf`

## 2. Install socat

```sh
opkg install --noaction socat    # dry run first
opkg install socat
socat -V | head -3               # should show WITH_UDP 1 / WITH_IP4 1
```

> **Trap**: the `socat` package ships its own UCI service (`/etc/init.d/socat` + `/etc/config/socat`, `enable '0'` by default). It does not start by default, but it competes for the same port 9 as your relay, which makes debugging maddening — it looks like "the service starts and dies by itself". Disable it outright:
> ```sh
> /etc/init.d/socat disable
> ```

## 3. Find the tunnel interface name and address

> Every address in this chapter is an **example**: tunnel address `10.147.20.5`, LAN broadcast `192.168.1.255`, interface name `ztab1234cd`. Replace them with your own — do not copy them verbatim.

```sh
zerotier-cli listnetworks        # the last column is the interface name, e.g. ztab1234cd
ip -4 addr show | grep -E 'inet |zt'
```

Two key facts where many people trip:

- A ZeroTier interface is **not necessarily named `zt*`** — it may look like `ztab1234cd`; filtering on the `zt` prefix can miss it entirely;
- The address you find (example `10.147.20.5`) is the **bind address**, not the LAN gateway. All examples below use `10.147.20.5` / `192.168.1.255`; substitute your own.

## 4. Validate in the foreground (before making it a service)

```sh
socat -d -d UDP4-RECVFROM:9,bind=10.147.20.5,reuseaddr,fork \
      UDP4-DATAGRAM:192.168.1.255:9,broadcast
```

Send one wake packet from the phone; the log should show one line:

```
received packet with 102 bytes from AF=2 10.147.20.x
```

### **The biggest trap: never bind `0.0.0.0`**

Listening on all interfaces and forwarding to the LAN broadcast makes the broadcast come back into the same socket — a **self-loop**: every packet forks a child, the source address is always the local machine, the source port keeps changing (58238 → 40218 → 33143 …), CPU pegs and logs flood.

Signs: source address equals the local IP, children keep appearing, `forked off` grows without bound. When you see it, `Ctrl+C` immediately and change `bind` to the tunnel IP.

A related trap: a broadcast address like `192.168.1.255` must be computed from the **actual netmask**; not every network is /24. Check the value after `brd` with `ip -4 addr show br-lan`.

## 5. Make it start at boot (the key is waiting for the tunnel IP)

**Trap**: after a reboot ZeroTier often needs ten-odd seconds to obtain an address. If the service tries to `bind` an address that does not exist yet, binding fails; combined with `respawn 3600 5 5` (give up permanently after 5 failures within 3600 s) the service dies for good — "works when run by hand, never works after boot".

Correct approach: **put the waiting inside the process**, so the process stays alive and procd never gives up.

```sh
cat > /usr/bin/wolrelay.sh <<'EOF'
#!/bin/sh
# find the tunnel interface (zt* / tailscale* / wg*), wait for an IPv4, then exec socat
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
    procd_set_param respawn 0 1 5      # 0 = never stop retrying
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
EOF
chmod +x /etc/init.d/wolrelay
/etc/init.d/wolrelay enable
/etc/init.d/wolrelay restart
```

## 6. Clear stale processes (the real cause of "port already in use")

After repeated manual socat runs, or a previous service not fully stopped, several processes fight over port 9 and it is anyone's guess which one gets the packet:

```sh
/etc/init.d/wolrelay stop 2>/dev/null
killall socat 2>/dev/null
netstat -lnup | grep ':9 '     # should print nothing
```

## 7. Verify the service really took over

```sh
logread -e wolrelay | tail -3
ubus call service list '{"name":"wolrelay"}'
```

Only when the latter shows `"instance1": { "running": true, "pid": ... , "command": ["/usr/bin/wolrelay.sh"] }` is procd really managing it. `ps` alone cannot tell — procd and orphans both show `PPid: 1`.

## 8. Firewall: usually unnecessary, do not be misled

In our measurements, the 102 bytes arrived **with no firewall rule at all**, which means inbound traffic on the tunnel interface already passes. You only need a rule if you set the INPUT policy to deny or wrote your own firewall rules:

```sh
uci add firewall rule
uci set firewall.@rule[-1].name='Allow-WOL-Relay'
uci set firewall.@rule[-1].src='*'
uci set firewall.@rule[-1].proto='udp'
uci set firewall.@rule[-1].dest_port='9'
uci set firewall.@rule[-1].target='ACCEPT'
uci commit firewall && /etc/init.d/firewall reload
```

To remove a temporary rule you added earlier:

```sh
iptables -S INPUT | grep 'dport 9'
iptables -D INPUT -i <tunnel interface> -p udp --dport 9 -j ACCEPT
/etc/init.d/firewall reload
```

## 9. If the broadcast cannot get out (some kernels)

Some LEDE kernels drop packets that arrive on a non-LAN interface and are re-sent as a LAN broadcast. The alternative is to skip broadcast and send a **layer-2 unicast magic packet**:

```sh
opkg install ether-wake
socat UDP4-RECVFROM:9,bind=<tunnel ip>,reuseaddr,fork \
      EXEC:"/usr/bin/ether-wake <target MAC>",nofork
```

Rationale: `ether-wake` writes a layer-2 frame directly on br-lan, bypassing IP broadcast and the kernel restriction. The downside is one relay per host.

## 10. Final acceptance

1. Turn Wi-Fi off on the phone, use mobile data, connect the tunnel, send one packet → the machine should wake;
2. `logread -e wolrelay` shows `ready: ...` and `receiving on ...`;
3. **Reboot the router**, wait for the tunnel to come back `ONLINE`, send again → it must still wake. Only then is it truly automatic.

The debugging order is always: **did the log show 102 bytes** → tcpdump for a broadcast on br-lan → the host's own WOL settings.
