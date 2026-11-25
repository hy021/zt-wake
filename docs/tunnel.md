English | [简体中文](tunnel.zh-CN.md)

# Any layer-3 tunnel

## In one sentence

The wake packet is always 102 bytes (6×`FF` + MAC×16, UDP 9), and **it does not matter who forwards it**. "ZT wake" is just one example of "delivering the packet over a layer-3 tunnel to an always-on device" — it is not tied to ZeroTier.

A tunnel only affects two things:

1. Whether the phone can reach the relay device at all;
2. Whether that device can broadcast into the target LAN.

## Comparison

| Tunnel | What the relay side needs | What you enter in the app |
|---|---|---|
| ZeroTier | Official client + `wolrelay` (`ZT_IP=auto` finds the `zt*` interface) | The relay's ZeroTier address |
| Tailscale | Install tailscaled, set `ZT_IP` to the `tailscale0` address | The relay's Tailscale address |
| WireGuard | Configure wg0, set `ZT_IP` to the wg address | That wg address |
| frp / nps | Run the client on an always-on device, map UDP 9 | The mapped public address |
| Public IP + UDP mapping | Relay listens on the tunnel IP, router forwards UDP | The public IP (mind the exposure) |
| IPv6 | Only needs to reach the relay; broadcast still happens on IPv4 | The relay's IPv6 address |

**Exactly one variable changes**: `ZT_IP` at the top of `wolrelay`. Broadcast computation, procd supervision and restart-on-failure are all reused as-is.

## Why "just broadcast over the tunnel" does not work

A magic packet is a layer-2 broadcast frame (destination MAC all `FF`). A layer-3 tunnel only guarantees "some IP is reachable"; it does not carry layer-2 broadcasts. Something on the LAN side must receive the unicast packet and re-broadcast it.

IPv6 has **no broadcast address**, so an "IPv6 wake" can only be a directed unicast to the NIC (and requires NIC support).

## Common misconceptions

- "Install ZeroTier and you can wake directly" → no, you still need a relay. The tunnel only solves "packet reaches the relay".
- "A port forward on the router is enough" → a forward delivers UDP to a port; the relay must still broadcast itself. Forwarding straight to `x.x.x.255` is usually refused.
- "DDNS + port forward is simpler" → it works, but it exposes the UDP port to the internet, which is weaker than a tunnel.
