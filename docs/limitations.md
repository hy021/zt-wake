English | [简体中文](limitations.zh-CN.md)

# Known limitations

## MAC addresses are unreadable on some ROMs

- Since Android 10, `targetSdk 29+` cannot read `/proc/net/arp`;
- Local netlink (`RTM_GETNEIGH`) is refused outright by the SELinux policy of some ROMs (`errno=13`, `pkts=0`);
- This is a platform restriction, not an implementation flaw — comparable open-source apps show `MAC address not found` on the same device.

Current state: the MAC must be entered manually; `targetSdk` stays at 28 to keep `/proc/net/arp` usable where possible.

## The tunnel panel cannot control the VPN

Android ZeroTier clients expose no control interface, and the VPN consent dialog is system behavior anyway.
The app only reports gateway reachability; connecting happens in the client itself.

## The scan only lists confirmed-online devices

Some routers answer RST uniformly for addresses that do not exist (typically 280–300 ms and suspiciously consistent).
Such false positives are filtered out entirely — not counted, not shown.

## Broadcast does not cross layer 3

That is the design premise, not a bug. See [tunnel.md](tunnel.md).

## The relay must stay online

While it is offline, power-cycled or rebooting, no host can be woken; configure autostart on the relay.
