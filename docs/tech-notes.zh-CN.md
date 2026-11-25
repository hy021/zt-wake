[English](tech-notes.zh-CN.md) | 简体中文

# 局域网扫描 / WOL App 技术要点（踩坑记录）

面向：以后再写类似的「局域网扫描 + 网络唤醒」Android App，可直接照这份清单走，少踩同样的坑。

## 一、整体结论

- 扫描要准，判定顺序必须是：**ICMP 通 → 端口真开**。凡是"端口被拒绝也算在线"都会在路由器代答的网络上炸出一堆假主机。
- 拿 MAC 只有一条合法路径：**内核邻居表**。Android 上它常被 SELinux 整个封掉，封掉就老老实实显示"MAC 未识别"，不要伪造。
- 局域网唤醒是**二层广播帧**，跨网段（ZeroTier 这类三层 VPN）必然失效，必须由一直在线路上的路由器代发。

## 二、必踩的坑（按出现顺序）

| # | 现象 | 真实原因 | 正确做法 |
|---|---|---|---|
| 1 | 扫描循环一次都没跑 | 掩码补码在小端 int 下算出负数（/24 → `-16777215`），`total > 1` 直接为假 | 按前缀长度算 `1 << (32 - prefix)`，别用补码 |
| 2 | `/proc/net/arp` 读不到 | targetSdk ≥ 29 起被系统拦截；文件不存在时也静默返回空 | targetSdk 用 28，或干脆别依赖它 |
| 3 | 扫出 `235.10.168.0` 这种倒序地址 | `ipInt/ipStr` 字节序写反，且落在公网段后经网关出门，ICMP 全"通" | 一律按大端（网络序）+ 高位掩码 |
| 4 | 扫的是虚拟网卡 | 手机常挂 ZeroTier/OpenVPN，枚举到的第一个 IPv4 是 tun/zt | 网卡名打分：`wlan*/ap*` 优先，跳过 `tun/zt/rmnet/ppp/ccmni/ipsec` |
| 5 | 200+ 台假主机 | ①`ConnectException`（端口拒绝）被判成在线 ②`isReachable` 在部分 ROM 退化成 TCP echo ③掩码 /16 扫了 1000+ 地址 | 只认「ICMP 回应」或「三次握手成功」；扫描范围夹到自己的 /24 |
| 6 | 假主机的延迟一模一样（277~303ms） | 路由器对不存在的地址统一回 RST，耗时等于探测超时值 | 耗时高度一致 = 假信号，直接丢弃这一类，不给用户看 |
| 7 | 邻居表始终为空，诊断行 `pkts=0 msgs=0 errno=13` | SELinux 直接拒绝 netlink socket，请求根本没发出去 | 无法绕开；把 errno 打进诊断行，让用户能反馈 |
| 8 | 只有 1~2 台能 ping 通，其余全丢 | 多线程共用全局 `seq` 做回包校验，A 线程的包被 B 线程改了序号 | 每次 ping 用**局部序号**（原子加），校验只跟本次值比 |
| 9 | ICMP 收到但解析不到 | 手写 ICMP 头时用匿名 struct，`char,char,short,short,short` 会按 2 字节对齐，id/seq 各错位 2 字节 | 用显式结构体：`type, code, cksum, id, seq`，或直接用 `<netinet/ip_icmp.h>` |
| 10 | 探活后仍拿不到 MAC | 先读邻居表、后探活：表在探活时才会被内核填 | **先探活，再读一次邻居表** |
| 11 | 列表没有主机名 | 只在 ICMP 命中的分支里做反查，TCP 命中的设备全跳过 | 不管哪种方式命中，都要走一次名字解析 |

## 三、判活规则（推荐）

```
ICMP echo 有回应            → 在线，记录 RTT
TCP 三次握手成功（80/443/22/445/3389/…) → 在线
只回 RST（ConnectException） → 不算在线（除非本机 ICMP 完全不可用时的降级模式）
无回应                       → 离线
```

- ICMP 用 `SOCK_DGRAM + IPPROTO_ICMP`，不需要 root：Android 把 `net.ipv4.ping_group_range` 开成了 `0 2147483647`，普通 App 就能发。失败再退 `SOCK_RAW`。
- 判断"本机能不能发 ICMP"用返回值区分：`-2` = 没权限，`-1` = 不通，`>0` = RTT。
- 并发建议 48 线程、单次超时 800~1200ms，254 个地址约 6~10 秒扫完。

### 补充：Windows 主机的在线判定（实测踩坑）

Windows 10 有两个默认行为会让扫描/状态判断失灵：

- 默认**不响应 ping**（防火墙「文件和打印机共享(回显请求)」在公用网络配置文件下是关的）；
- 445/139/3389 在公用网络配置文件下**静默丢包**（不是回 RST，是直接 DROP）。

所以只查 445 一个端口 + 只 ping 一次，待机唤醒后的 Windows 会永远显示"离线"。可靠的组合是：

1. ping **两次**（Windows 偶尔丢第一个包）；
2. 端口表放宽到 `445/139/135/3389/22/80/443/2869/5900/8080`，任一握手成功即在线；
3. **快速 RST 也算在线**：同一网段的真机拒绝连接通常在 1~5ms；路由器对不存在地址的合成 RST 是 ~280-300ms。**用 150ms 作分界线**，快=真机，慢=代答；
4. **NetBIOS 名称查询（UDP 137）** 兜底：`NBSTAT` 通配查询，Windows 开着"网络发现"必回。

NBNS 通配查询包（50 字节，标准首级编码）：
`12 34 00 00 00 01 00 00 00 00 00 00 20 43 4B 41…(共 30 个 41)…00 00 21 00 01`
注意名字必须是 `*`+15 个 `\0` 的**首级编码**（`CK` + `AA`×15），直接写 `0x2A` 服务器会当畸形包丢掉。

### 补充：状态刷新"看起来没反应"

- 状态刷新的结果如果一直是同一个错误值，用户就会以为"刷新没作用"。先把判定修对，再谈刷新。
- 加两个保险：`tvPull` 显示 `检测中 … done/total` 进度；8 秒兜底强制收尾，避免某些探测卡住导致一直"检测中"。
- 异步回收时如果 `gen != listGen`（列表已重建），要顺手把 `refreshingStatus` 置回 false，否则后续刷新会被 `if (refreshingStatus) return` 直接吃掉。

## 四、MAC 的唯一来源

- 内核邻居表：netlink `RTM_GETNEIGH`，`family` 先传 `AF_UNSPEC`（与 `ip neigh` 一致），空了再试 `AF_INET`，最后才读 `/proc/net/arp` 兜底。
- 解析：`NDA_DST` 取 IP、`NDA_LLADDR` 取 MAC；**必须过滤 `ndm_family != AF_INET`**，否则 IPv6 条目的前 4 字节会被当成 IPv4 地址（会看到 `255.2.0.0` 这种鬼东西）。
- 被封时的诊断三件套：收包数、解析条数、errno。`errno=13 + pkts=0` 就是 SELinux。
- 已知例外：一旦路由器做了 ARP 代答，即便能读表，拿到的也是路由器自己的 MAC。

## 五、主机名三级来源

1. **系统 PTR**：`InetAddress.getCanonicalHostName()` → `getHostName()`。路由器 dnsmasq 会为 DHCP 客户端登记 PTR，所以能拿到 `xxx.lan`。
2. **mDNS**：组包 `x.y.z.w.in-addr.arpa` 的 PTR 查询，发到 `224.0.0.251:5353`，`qclass` 带上 `0x8000`（QU，要求单播回复），这样用普通 UDP socket 就能收到回包。适合苹果设备、NAS、打印机。
3. **HTTP 标题**：连 80/8080，`GET / HTTP/1.0` 读前 2KB，抓 `<title>`。TP-LINK 这类路由器、电视、群晖都很灵。

三者都失败就显示 IP，别硬编一个名字。

## 六、扫描列表的交互要点

- 边扫边往下长：每命中一台就按 IP 数值升序插入一行，不要弹一个"等待中"的空窗。
- 每行两段式：`主机名 (IP)` / `RTT + 协议 + MAC`，单行不换行会截断，两行更稳。
- 命中方式（PING/TCP）要显示出来，出问题时用户能自证。
- 底部一行版本号，从 manifest 读 `versionName`，别硬编码。

## 七、跨网段唤醒（ZeroTier 场景）

- WOL 魔法包是二层广播帧（`FF:FF:FF:FF:FF:FF` + 16 次 MAC），ZeroTier 只提供三层可达性，手机发出去也到不了目标网段的广播域。
- 唯一解：路由器上跑一个 UDP 9 中继（socat 或 procd 脚本），手机把包发给路由器的 ZT 地址，由路由器用自己的 `br-lan` 广播地址发出去。
- 中继脚本要能自动识别 `zt*` 网卡与 `br-lan` 广播地址，不要写死 `/24`。

## 八、构建流水线（无 Gradle，命令行直编）

```bash
aapt2 compile --dir res -o build/res.zip
aapt2 link -I android.jar --manifest AndroidManifest.xml --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 28 --java build/gen -o build/base.apk build/res.zip
javac -source 8 -target 8 -encoding UTF-8 -cp android.jar -d build/classes \
    build/gen/com/zt/wol/R.java src/com/zt/wol/*.java
d8 --lib android.jar --min-api 21 --output build/ build/classes/com/zt/wol/*.class
# 用 zipfile 追加 classes.dex 与 lib/<abi>/libXxx.so
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks build/wol.jks --ks-pass pass:xxx --key-pass pass:xxx \
    --ks-key-alias wolkey --out build/网络唤醒-vX.Y.apk build/aligned.apk
```

- 只要代码里用到 `R.xxx`，就必须给 `aapt2 link` 加 `--java build/gen` 并把它编进 javac，否则报 `package R does not exist`。
- JDK 17 下不要给 android.jar 加 `-bootclasspath`（会丢 `java.lang`）。
- NDK 交叉编译：`aarch64-linux-android21-clang -shared -O2`，armv7 用 `armv7a-linux-androideabi21-clang`；JNI 符号名要严格匹配 `Java_<包名下划线>_<类名>_<方法名>`。

## 九、当前实现索引

- `src/com/zt/wol/MainActivity.java`：UI + 扫描（`scanLan` / `addScanRow`）+ 判活（`probeHost` / `tcpOpenAt` / `icmpRtt`）+ 名字（`systemPtr` / `mdnsName` / `httpTitle`）
- `src/com/zt/wol/NetHelper.java`：JNI 封装（`neigh()` / `ping()` / `pingErr()`）
- `native/nbl.c`：netlink 邻居表 + ICMP echo
- `openwrt/wolrelay`：路由器 UDP 9 中继（用户实际在用）
- `openwrt/wol-arp.sh` + `wolarp`：可选的邻居表接口（不装也可，只是 MAC 需手填）
