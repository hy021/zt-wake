English | [简体中文](troubleshooting.zh-CN.md)

# Troubleshooting

| Symptom | Usual cause |
|---|---|
| Tap wake, nothing happens | Relay not running / wrong gateway address / port mismatch |
| Relay log shows no packet | Tunnel not connected / firewall blocking INPUT / wrong destination in the app |
| Relay receives it, host stays off | Wrong broadcast address, cross-VLAN, WOL disabled in BIOS, NIC unpowered after shutdown (ErP) |
| Works exactly once | The relay process exited (no autostart / no respawn) |
| Scan reports a pile of "online" hosts | The router answers RST for addresses that do not exist |

## First, check whether the packet left the phone at all

On the relay: if `logread -t wolrelay` shows a record, the packet arrived → the problem is downstream. If not → the problem is the tunnel or the destination address.

## Send a standard magic packet directly (bypass the app)

```python
import socket
mac = "AA:BB:CC:DD:EE:FF"
pkt = b"\xff"*6 + bytes.fromhex(mac.replace(":",""))*16
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.sendto(pkt, ("192.168.1.255", 9))
```

If this works inside the LAN, the host's WOL setup is fine and the problem is the tunnel or the relay. If it does not, fix the host first (BIOS, NIC power, Fast Startup).

## Host-side checklist

- BIOS: enable WOL / PCIe wake, disable `ErP / EuP`;
- NIC properties: allow this device to wake the computer, allow only a magic packet to wake it;
- Windows: disable Fast Startup (`powercfg /h off`), otherwise "shutdown" is really suspend;
- Switch / AP: client isolation off, broadcast suppression not too aggressive.
