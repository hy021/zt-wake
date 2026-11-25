English | [简体中文](tech-notes.zh-CN.md)

# LAN scan / WOL app — technical notes and pitfall log

For anyone writing a similar "LAN scan + Wake-on-LAN" Android app later: follow this list and skip the same traps.

## 1. Conclusions up front

- To scan accurately the decision order must be: **ICMP succeeds → the port is genuinely open**. Anything that counts "connection refused" as online blows up into dozens of phantom hosts on networks where the router answers on behalf of absent devices.
- There is only one legitimate way to obtain a MAC: **the kernel neighbour table**. On Android it is often blocked wholesale by SELinux; when blocked, honestly show "MAC unknown" — never fabricate one.
- LAN wake is a **layer-2 broadcast frame**; across subnets (layer-3 VPNs like ZeroTier) it must fail, and an always-on device on the wire has to re-send it.

## 2. The traps you will hit (in order of appearance)

| # | Symptom | Real cause | Correct approach |
|---|---|---|---|
| 1 | The scan loop never runs once | The netmask complement goes negative in a little-endian int (/24 → `-16777215`), so `total > 1` is simply false | Compute `1 << (32 - prefix)` from the prefix length; never use the complement |
| 2 | `/proc/net/arp` is unreadable | Blocked since targetSdk ≥ 29; also silently returns empty when the file is absent | Use targetSdk 28, or do not depend on it at all |
| 3 | Scan yields reversed addresses like `235.10.168.0` | `ipInt/ipStr` byte order flipped, and the bogus address landed in public space so every packet left via the gateway and ICMP "succeeded" | Always big-endian (network order) with a high-order mask |
| 4 | The scan targets a virtual NIC | Phones usually have ZeroTier/OpenVPN attached; the first IPv4 enumerated is tun/zt | Score interface names: prefer `wlan*/ap*`, skip `tun/zt/rmnet/ppp/ccmni/ipsec` |
| 5 | 200+ phantom hosts | ① `ConnectException` (refused) treated as online ② `isReachable` degrades to TCP echo on some ROMs ③ a /16 mask scanned 1000+ addresses | Only accept "ICMP reply" or "three-way handshake succeeded"; clamp the range to your own /24 |
| 6 | Phantom hosts all show identical latency (277–303 ms) | The router answers RST uniformly for absent addresses; the timing equals the probe timeout | Highly uniform latency = false signal; drop that whole class silently |
| 7 | Neighbour table always empty, diagnostics `pkts=0 msgs=0 errno=13` | SELinux rejects the netlink socket outright, the request never leaves | Cannot be worked around; surface errno in the diagnostics so users can report it |
| 8 | Only 1–2 hosts ping, all others lost | Threads shared a global `seq` for reply matching, so thread B overwrote thread A's sequence | Use a **local sequence** per ping (atomic increment) and match only against that value |
| 9 | ICMP arrives but cannot be parsed | Hand-written ICMP headers used an anonymous struct; `char,char,short,short,short` gets 2-byte aligned and id/seq shift by 2 bytes each | Use an explicit struct: `type, code, cksum, id, seq`, or just `<netinet/ip_icmp.h>` |
| 10 | Still no MAC after probing | Neighbour table read before probing; the kernel only fills it during probing | **Probe first, then read the table again** |
| 11 | List has no hostnames | Reverse lookup only happened on the ICMP branch, every TCP hit was skipped | Run name resolution regardless of how the hit was made |

## 3. Liveness rules (recommended)

```
ICMP echo replies                        -> online, record RTT
TCP three-way handshake (80/443/22/445/3389/...) -> online
RST only (ConnectException)              -> NOT online (except as a fallback when local ICMP is entirely unavailable)
No reply                                 -> offline
```

- Use `SOCK_DGRAM + IPPROTO_ICMP` for ICMP — no root needed: Android ships `net.ipv4.ping_group_range` as `0 2147483647`, so a normal app can send. Fall back to `SOCK_RAW` on failure.
- Distinguish "can this device send ICMP at all" by return value: `-2` = no permission, `-1` = unreachable, `>0` = RTT.
- Suggested concurrency: 48 threads, 800–1200 ms per attempt; 254 addresses finish in roughly 6–10 s.

### Addendum: deciding whether a Windows host is online (measured)

Windows 10 has two default behaviours that break scanning and status detection:

- It **does not answer ping by default** (the firewall rule "File and Printer Sharing (Echo Request)" is off on the Public profile);
- 445/139/3389 on the Public profile **silently drops** packets (not RST — DROP).

So probing only port 445 with a single ping means a Windows box woken from sleep shows "offline" forever. The reliable combination is:

1. Ping **twice** (Windows occasionally drops the first packet);
2. Widen the port list to `445/139/135/3389/22/80/443/2869/5900/8080`; any successful handshake counts as online;
3. **A fast RST also counts**: a real machine on the same subnet refusing a connection typically answers in 1–5 ms, while the router's synthesized RST for an absent address takes ~280–300 ms. **Use 150 ms as the dividing line** — fast = real, slow = proxy answer;
4. **NetBIOS name query (UDP 137)** as the last fallback: an `NBSTAT` wildcard query, which Windows answers whenever "Network Discovery" is on.

NBNS wildcard query packet (50 bytes, standard first-level encoding):
`12 34 00 00 00 01 00 00 00 00 00 00 20 43 4B 41…(30 × 41)…00 00 21 00 01`
The name must be `*` plus 15 `\0` in **first-level encoding** (`CK` + `AA`×15). Writing `0x2A` directly makes the server drop it as malformed.

### Addendum: status refresh that "seems to do nothing"

- If a refresh always returns the same wrong value, users conclude "refresh is broken". Fix the decision logic first, then talk about refresh.
- Add two safety nets: `tvPull` shows `probing … done/total`; an 8-second watchdog force-finishes so a stuck probe cannot leave it "probing" forever.
- On async completion, if `gen != listGen` (the list was rebuilt), reset `refreshingStatus` to false — otherwise the next refresh is swallowed by `if (refreshingStatus) return`.

## 4. The only source of MAC addresses

- Kernel neighbour table: netlink `RTM_GETNEIGH`, pass `family` as `AF_UNSPEC` first (same as `ip neigh`), retry with `AF_INET` if empty, and only then fall back to `/proc/net/arp`.
- Parsing: IP from `NDA_DST`, MAC from `NDA_LLADDR`; **you must filter `ndm_family != AF_INET`**, otherwise the first 4 bytes of an IPv6 entry are read as an IPv4 address (you will see nonsense like `255.2.0.0`).
- The three diagnostics you need when blocked: packets received, entries parsed, errno. `errno=13` with `pkts=0` means SELinux.
- Known exception: once the router performs ARP proxy answering, even a readable table yields the router's own MAC.

## 5. Hostnames from three levels

1. **System PTR**: `InetAddress.getCanonicalHostName()` → `getHostName()`. A router's dnsmasq registers PTR records for DHCP clients, so you get `xxx.lan`.
2. **mDNS**: build a PTR query for `x.y.z.w.in-addr.arpa`, send it to `224.0.0.251:5353` with `qclass` carrying `0x8000` (QU, requesting a unicast reply), so a plain UDP socket receives the answer. Good for Apple devices, NAS, printers.
3. **HTTP title**: connect to 80/8080, `GET / HTTP/1.0`, read the first 2 KB, grab `<title>`. Very effective on routers like TP-LINK, TVs, and Synology boxes.

If all three fail, show the IP — never invent a name.

## 6. Interaction details for the scan list

- Grow the list as it scans: insert each hit in ascending numeric IP order; never show an empty "waiting" state.
- Two lines per row: `hostname (IP)` / `RTT + protocol + MAC`; a single line gets truncated, two lines are safer.
- Show how the host was detected (PING/TCP) so users can diagnose problems themselves.
- Version number on the last line, read from the manifest `versionName` — never hard-coded.

## 7. Waking across subnets (ZeroTier scenario)

- A WOL magic packet is a layer-2 broadcast frame (`FF:FF:FF:FF:FF:FF` + the MAC 16 times); ZeroTier only provides layer-3 reachability, so a packet sent from the phone never reaches the target subnet's broadcast domain.
- The only solution: run a UDP 9 relay on the router (socat or a procd script); the phone sends to the router's tunnel address, and the router broadcasts using its own `br-lan` broadcast address.
- The relay script must auto-detect the tunnel interface and the `br-lan` broadcast address — never hard-code `/24`.

## 8. Build pipeline (no Gradle, straight from the command line)

```bash
aapt2 compile --dir res -o build/res.zip
aapt2 link -I android.jar --manifest AndroidManifest.xml --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 28 --java build/gen -o build/base.apk build/res.zip
javac -source 8 -target 8 -encoding UTF-8 -cp android.jar -d build/classes \
    build/gen/com/zt/wol/R.java src/com/zt/wol/*.java
d8 --lib android.jar --min-api 21 --output build/ build/classes/com/zt/wol/*.class
# append classes.dex and lib/<abi>/libXxx.so with a zip tool
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks build/wol.jks --ks-pass pass:xxx --key-pass pass:xxx \
    --ks-key-alias wolkey --out build/app-vX.Y.apk build/aligned.apk
```

- Whenever code references `R.xxx`, you must pass `--java build/gen` to `aapt2 link` and compile that directory with javac, otherwise you get `package R does not exist`.
- Under JDK 17 do not pass `-bootclasspath` with android.jar (you lose `java.lang`).
- NDK cross-compilation: `aarch64-linux-android21-clang -shared -O2`; for armv7 use `armv7a-linux-androideabi21-clang`. JNI symbol names must match `Java_<package with underscores>_<class>_<method>` exactly.

## 9. Current implementation index

- `src/com/zt/wol/MainActivity.java`: UI + scan (`scanLan` / `addScanRow`) + liveness (`probeHost` / `tcpOpenAt` / `icmpRtt`) + names (`systemPtr` / `mdnsName` / `httpTitle`)
- `src/com/zt/wol/NetHelper.java`: JNI wrappers (`neigh()` / `ping()` / `pingErr()`)
- `native/nbl.c`: netlink neighbour table + ICMP echo
- `openwrt/wolrelay`: router-side UDP 9 relay
- `openwrt/wol-arp.sh` + `wolarp`: optional neighbour-table endpoint (skip it and MACs must be entered by hand)
