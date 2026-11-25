English | [简体中文](router.zh-CN.md)

# Relay devices and router configuration

**OpenWrt is not required.** There is exactly one prerequisite: a Linux box that is **always on**, **attached to the target LAN**, and **can run `socat`**.
OpenWrt is simply the most convenient shape (procd, opkg, zero-config script).

## Hard requirements

1. Always powered on (nothing can be woken while it sleeps or is off)
2. In the same broadcast domain as the target host (across VLANs you must hard-code the broadcast address)
3. Able to listen on UDP and forward a broadcast
4. Reachable from the phone through the tunnel
5. Auto-start on boot (so it comes back after a power cut)

Counter-examples: ISP gateways, stock routers with web UI only, carrier gateways with SSH locked down.

## Firmware support matrix

| Platform | Support | How |
|---|---|---|
| OpenWrt / LEDE / ImmortalWrt / iStoreOS | Full | `opkg install socat` + `wolrelay` |
| Asuswrt-Merlin | Works | Enable JFFS + Entware, start from `/jffs/scripts/services-start` |
| Padavan | Works | socat from Entware, start from a boot script |
| DD-WRT | Works | Boot command + external storage |
| pfSense / OPNsense | Works | Install socat via shell or write an rc script |
| MikroTik RouterOS | Partial | Built-in `/tool wol` can send a magic packet, but there is no UDP relay; needs SSH/API triggering, **which this app does not do** |
| Synology / QNAP | Works | Task scheduler running a socat script |
| Raspberry Pi / x86 soft router / small always-on host | Works | systemd / rc.local |
| Always-on Windows mini PC | Works | `wol_relay.py`, see below |
| Stock consumer router (no shell) | No | Use any always-on device on the LAN as the relay instead |

## OpenWrt steps

```sh
opkg update && opkg install socat

scp wolrelay root@<router>:/etc/init.d/wolrelay
ssh root@<router> 'chmod +x /etc/init.d/wolrelay'

/etc/init.d/wolrelay enable
/etc/init.d/wolrelay start
logread -t wolrelay    # listen <tunnel addr>:9 -> <LAN broadcast>:9
```

Three variables:

```sh
ZT_IP=auto          # auto-detect a tunnel interface; for Tailscale use the tailscale0 IP; can also be hard-coded
LAN_IFACE=br-lan
LAN_BCAST=auto      # derive the broadcast from the netmask (verified on /20 and /26)
```

### Firewall

The relay only listens locally and sends outbound broadcasts, so the default policy needs no extra rule. If you changed INPUT to deny:

```sh
uci add firewall rule
uci set firewall.@rule[-1].name='Allow-WOL-Relay'
uci set firewall.@rule[-1].src='*'
uci set firewall.@rule[-1].proto='udp'
uci set firewall.@rule[-1].dest_port='9'
uci set firewall.@rule[-1].target='ACCEPT'
uci commit firewall && /etc/init.d/firewall reload
```

## Generic Linux form

```sh
socat UDP4-RECV:9,bind=<tunnel address>,fork UDP4-DATAGRAM:<LAN broadcast>:9,broadcast
```

Then just make it start at boot:

- Merlin: `/jffs/scripts/services-start`
- Padavan / DD-WRT: boot script
- Synology: Control Panel → Task Scheduler → triggered at boot
- Raspberry Pi: a systemd unit or `/etc/rc.local`
- Plain Linux: systemd (`Restart=always`)

## No router access: use any always-on device on the LAN

Linux uses the socat command above. An always-on Windows mini PC can use this script (Python 3, no dependencies):

```python
# wol_relay.py -- listen on UDP 9, re-broadcast every packet to the LAN
import socket
LISTEN_PORT = 9
BROADCAST = "192.168.1.255"      # replace with your own broadcast address
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.bind(("0.0.0.0", LISTEN_PORT))
print("relay", LISTEN_PORT, "->", BROADCAST)
while True:
    data, addr = s.recvfrom(1024)
    s.sendto(data, (BROADCAST, LISTEN_PORT))
```

Start it from Task Scheduler. Note: this Windows box cannot be woken by itself — it is only a relay.

## Network-side notes

- Disable AP isolation / client isolation (isolation blocks broadcasts)
- Keep the relay in the same broadcast domain as the target; across VLANs hard-code `LAN_BCAST` to the target subnet's broadcast address
- UDP 9 must be free; if taken, change the port in the relay and in the app together
- Do not set switch broadcast suppression / storm control too aggressively
- On the target host: enable WOL in BIOS, disable ErP/EuP, disable Windows Fast Startup

## Security trade-offs

| Approach | Exposure | Recommendation |
|---|---|---|
| ZeroTier / Tailscale / WireGuard | No public port | Recommended |
| frp / nps | Only the frps port | Medium |
| Public IP + UDP mapping | UDP 9 open to the internet | Restrict the source or change the port |

The relay itself performs no authentication (UDP has no handshake). **"Who can get into your tunnel" is the security boundary.**
