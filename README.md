# 网络唤醒 · 远程隧道唤醒（ZT Wake）

安卓端 **网络唤醒（Wake-on-LAN）** 客户端，支持**局域网唤醒**与**远程隧道唤醒**。适合没有公网 IP、也不想开端口映射的场景：手机在外面，通过一条三层隧道叫醒家里的电脑。

> **不限于 ZeroTier，也不限于 OpenWrt。**
> 唤醒包永远那 102 字节，任何三层隧道（ZeroTier / Tailscale / WireGuard / frp / 公网映射）+ 一台常在线设备都能用。

界面为原生 View 手写，无第三方依赖，APK 约 200 KB。

## 它解决什么问题

唤醒包（Magic Packet）是**二层广播帧**：6 字节 `FF` + 目标 MAC 重复 16 次，共 102 字节，UDP 9 端口。

- 同网段广播 → 正常唤醒；
- 走 ZeroTier / 任何三层隧道 → **广播不过去**，主机收不到。

于是让一台**始终在线、又接在目标局域网**的设备（路由器 / NAS / 树莓派）收下单播包，再在局域网里广播出去：

```
手机 App  →  隧道  →  常在线中继  →  局域网广播  →  主机网卡  →  开机
```

不暴露公网端口，不需要特殊固件。

## 三步跑起来

1. **中继侧**（OpenWrt 或任何有 socat 的 Linux）：
   ```sh
   opkg install socat          # 非 OpenWrt 用各自包管理器
   cp openwrt/wolrelay /etc/init.d/wolrelay && chmod +x /etc/init.d/wolrelay
   /etc/init.d/wolrelay enable && /etc/init.d/wolrelay start
   logread -t wolrelay         # 看到 listen <隧道地址>:9 -> <局域网广播>:9
   ```
2. **手机**：装 APK，切到「ZT 远程」，点「打开隧道」——App 会列出手机里已安装的隧道客户端（ZeroTier / ZerotierFix / Tailscale / WireGuard / OpenVPN）供你选择，连上隧道后确认顶部 `ZT 网关 x / y 可达` 变成绿色。
3. **加主机**：填名称、MAC、局域网 IP、中继的隧道地址（端口默认 9），点卡片即唤醒。

## 主要功能

- **双模式**：局域网唤醒（直接广播）/ **远程隧道唤醒**（发给中继，由中继广播）
- **任意隧道**：不绑定 ZeroTier，点「打开隧道」自动列出已安装的隧道客户端供选择
- **主机卡片**：自定义名称、MAC、局域网 IP、独立网关地址与端口，点卡片直接唤醒
- **在线状态**：ICMP → 端口握手 → 快速 RST → NetBIOS 多级判定，并发探测约 1 秒出结果；下拉刷新带 `检测中 … n / m` 进度
- **局域网扫描**：按真实掩码计算范围、边扫边出现、过滤路由器代答的假阳性
- **主机名解析**：系统 PTR → mDNS → HTTP `<title>` 三级兜底
- **网关状态**：卡片里的网关地址自动去重，顶部显示 `x / y 可达`，卡片显示 `通 / 通·拒 / 不通`
- **原生 ICMP**：`native/nbl.c` 自写 ICMP echo（普通 App 权限可用），多线程不串包

## 目录

```
src/com/zt/wol/MainActivity.java   主界面：列表、扫描、探测、唤醒
src/com/zt/wol/NetHelper.java      JNI 入口
native/nbl.c                       netlink 邻居表 + ICMP echo
libs/                              预编译 libnbl.so（arm64-v8a / armeabi-v7a）
openwrt/wolrelay                   中继：socat UDP 转发（procd 服务，自动识别 zt*、自动算广播）
docs/                              隧道、中继设备、排错、限制
```

## 文档

- [任意三层隧道](docs/tunnel.md)：Tailscale / WireGuard / frp / 公网映射怎么接
- [中继设备与路由器配置](docs/router.md)：要求、固件支持矩阵、各平台命令
- [排错](docs/troubleshooting.md)
- [已知限制](docs/limitations.md)

## 编译

需要 JDK 17、Android SDK Platform 33、Build-Tools 33.0.2；改 `native/nbl.c` 还需 NDK r26+。

```sh
aapt2 compile --dir res -o build/res.zip
aapt2 link -I $ANDROID_JAR --manifest AndroidManifest.xml --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 28 --java build/gen -o build/base.apk build/res.zip
javac -source 8 -target 8 -encoding UTF-8 -cp $ANDROID_JAR -d build/classes \
    build/gen/com/zt/wol/R.java src/com/zt/wol/MainActivity.java src/com/zt/wol/NetHelper.java
d8 --lib $ANDROID_JAR --min-api 21 --output build/ build/classes/com/zt/wol/*.class
# 把 classes.dex 与 lib/<abi>/libnbl.so 打进 base.apk，zipalign 后用自有 keystore 签名
```

## 安全

- 隧道方案不暴露任何端口到公网；
- 公网 IP + UDP 映射等于把 9 端口开到公网，建议限制来源或换端口；
- 中继无认证（UDP 没有握手），**"谁能进你的隧道"就是安全边界**。

## 许可证

MIT，见 [LICENSE](LICENSE)。
