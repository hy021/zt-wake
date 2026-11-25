package com.zt.wol;

import java.util.LinkedHashMap;
import java.util.Map;

/** 用原生 netlink 读邻居表（IP + MAC）。失败时返回空表，不影响其它探测方式。 */
public final class NetHelper {
    private static boolean loaded = false;
    public static String lastError = "";

    static {
        try {
            System.loadLibrary("nbl");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            lastError = "so 未加载";
        }
    }

    private static native String[] neigh();
    /** 返回 RTT 毫秒；-1 不通；-2 无发 ICMP 权限 */
    public static native int ping(String ip, int timeoutMs);
    public static native int pingErr();

    public static boolean available() {
        return loaded;
    }

    public static Map<String, String> read() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        lastError = "";
        if (!loaded) { lastError = "so 未加载"; return m; }
        String[] arr = null;
        try {
            arr = neigh();
        } catch (Throwable t) {
            lastError = "调用失败";
            return m;
        }
        if (arr == null) { lastError = "返回空"; return m; }
        for (String line : arr) {
            if (line == null) continue;
            if (line.startsWith("#")) { lastError = line.substring(1); continue; }
            if (line.startsWith("!ERR")) { lastError = line.substring(4); continue; }
            String[] c = line.trim().split("\\s+");
            if (c.length < 2) continue;
            if (!c[1].matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) continue;
            m.put(c[0], c[1].toUpperCase());
        }
        return m;
    }
}
