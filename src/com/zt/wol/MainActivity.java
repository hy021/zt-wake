package com.zt.wol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREFS = "wol2";
    private static final int DEFAULT_PORT = 9;

    private static final int C_BG_TOP = 0xFF080D12;
    private static final int C_LINE = 0xFF1C3340;
    private static final int C_TEXT = 0xFFE8F5FF;
    private static final int C_SUB = 0xFF6FA8BE;
    private static final int C_CYAN_SOFT = 0xFF86ECFF;

    private LinearLayout hostList;
    private TextView tvCount, tvStatus, tvEmpty, tabLan, tabZt;
    private boolean ztMode = true;

    private JSONArray hosts = new JSONArray();
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastWakeAt = 0;

    private static final int ST_UNKNOWN = 0, ST_CHECKING = 1, ST_ONLINE = 2, ST_OFFLINE = 3;
    private final ArrayList<TextView> statusViews = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private int listGen = 0;
    private boolean refreshingStatus = false;
    private int statusGen = 0;          /* 每次刷新自增，旧任务自动作废；下拉不会被上一次刷新吃掉 */
    private int checkDone = 0, checkTotal = 0, onlineCount = 0;
    private TextView tvPull;
    private LinearLayout ztPanel;
    private TextView ztState, ztBtn;
    private boolean ztPolling = false;
    private static final String ZT_PKG = "net.kaaass.zerotierfix";

    /* ZT 网关状态：0 待检测 / 1 ICMP 通 / 2 拒绝或端口通 / 3 不通 */
    private static final int GW_PEND = 0, GW_OK = 1, GW_RST = 2, GW_DOWN = 3;
    private final ArrayList<String> ztGws = new ArrayList<>();
    private final java.util.HashMap<String, Integer> gwState = new java.util.HashMap<>();
    private final java.util.HashMap<String, Integer> gwMs = new java.util.HashMap<>();
    private final java.util.HashMap<String, Boolean> gwFull = new java.util.HashMap<>();
    private final java.util.HashMap<String, Boolean> gwBusy = new java.util.HashMap<>();
    private final ArrayList<TextView> gwViews = new ArrayList<>();
    private final ArrayList<String> gwViewIp = new ArrayList<>();
    private final ArrayList<String> gwViewBase = new ArrayList<>();
    private String scanInfo = "";
    private float pullStartY = -1f, pullDy = 0f;

    @Override
    protected void onResume() {
        super.onResume();
        if (ztMode) startZtPoll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopZtPoll();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF070C11));
        getWindow().setNavigationBarColor(0xFF070C11);

        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        ztMode = sp.getBoolean("zt", true);
        loadHosts();

        FrameLayout root = new FrameLayout(this);
        // 背景固定，不随模式变化
        root.setBackground(new TechBackground());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(0xFF0A141B);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(34), dp(20), dp(110));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 下拉刷新：从顶部往下拽时内容跟随位移，松手超过阈值触发检测
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                View child = scroll.getChildAt(0);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pullStartY = e.getY();
                        pullDy = 0f;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (pullStartY >= 0 && scroll.getScrollY() == 0) {
                            pullDy = e.getY() - pullStartY;
                            if (pullDy > dp(4) && child != null) {
                                float t = Math.min(pullDy * 0.45f, dp(96));
                                child.setTranslationY(t);
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (child != null) {
                            child.animate().translationY(0f).setDuration(180).start();
                        }
                        if (pullDy > dp(120) && scroll.getScrollY() == 0) {
                            refreshStatuses();
                        }
                        pullStartY = -1f;
                        pullDy = 0f;
                        break;
                }
                return false;
            }
        });

        buildHeader(content);
        buildModeSwitch(content);
        buildZtPanel(content);
        buildListHeader(content);
        buildHostArea(content);
        buildFooter(content);

        TextView fab = new TextView(this);
        fab.setText("+");
        fab.setTextSize(30);
        fab.setGravity(Gravity.CENTER);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setTextColor(0xFF04121A);
        GradientDrawable fabBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF5FE3FF, 0xFF17A8CC});
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setStroke(dp(1), 0xFF8FF0FF);
        fab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), fabBg, fabBg));
        fab.setElevation(dp(10));
        fab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editHost(-1); }
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(62), dp(62));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.setMargins(0, 0, dp(22), dp(28));
        root.addView(fab, fabLp);

        setContentView(root);
        refreshList();
        refreshMode();

    }



    // ---------------- 界面构建 ----------------

    private void buildHeader(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(18));

        tvPull = new TextView(this);
        tvPull.setText("↓  下拉刷新状态");
        tvPull.setTextSize(10.5f);
        tvPull.setLetterSpacing(0.12f);
        tvPull.setTextColor(0xFF44606C);
        tvPull.setGravity(Gravity.CENTER);
        tvPull.setSingleLine(true);
        tvPull.setEllipsize(TruncateAt.END);
        tvPull.setPadding(0, 0, 0, dp(14));
        row.addView(tvPull);

        TextView tag = new TextView(this);
        tag.setText("◤  NETWORK WAKE");
        tag.setTextSize(10.5f);
        tag.setLetterSpacing(0.25f);
        tag.setTextColor(0xFF3AAFC8);
        row.addView(tag);

        View line = new View(this);
        GradientDrawable lineBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF2FD8FF, 0x002FD8FF});
        lineBg.setShape(GradientDrawable.RECTANGLE);
        line.setBackground(lineBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(2));
        lp.topMargin = dp(16);
        line.setLayoutParams(lp);
        row.addView(line);

        parent.addView(row);
    }

    private void buildModeSwitch(LinearLayout parent) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        bg.setColor(0xFF0B141B);
        bg.setStroke(dp(1), C_LINE);
        wrap.setBackground(bg);
        wrap.setPadding(dp(5), dp(5), dp(5), dp(5));
        wrap.setElevation(dp(2));

        tabLan = new TextView(this);
        tabZt = new TextView(this);
        setupTab(tabLan, "局域网");
        setupTab(tabZt, "ZT 远程");

        wrap.addView(tabLan, new LinearLayout.LayoutParams(0, dp(46), 1f));
        wrap.addView(tabZt, new LinearLayout.LayoutParams(0, dp(46), 1f));

        tabLan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ztMode = false; refreshMode(); }
        });
        tabZt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ztMode = true; refreshMode(); }
        });

        parent.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void setupTab(TextView tv, String label) {
        tv.setText(label);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.06f);
    }

    /** ZT 远程页顶部：拉起 ZerotierFix + 隧道状态（方案2：只做状态显示，不做连接控制） */
    private void buildZtPanel(LinearLayout parent) {
        ztPanel = new LinearLayout(this);
        ztPanel.setOrientation(LinearLayout.HORIZONTAL);
        ztPanel.setGravity(Gravity.CENTER_VERTICAL);
        ztPanel.setPadding(dp(12), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xFF0E1720);
        bg.setStroke(dp(1), C_LINE);
        ztPanel.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        ztPanel.setLayoutParams(lp);
        ztPanel.setVisibility(View.GONE);
        parent.addView(ztPanel);

        ztBtn = new TextView(this);
        ztBtn.setText("打开 Zerotier Fix");
        ztBtn.setTextSize(12.5f);
        ztBtn.setTypeface(Typeface.DEFAULT_BOLD);
        ztBtn.setTextColor(0xFF7FF3FF);
        ztBtn.setGravity(Gravity.CENTER);
        ztBtn.setSingleLine(true);
        ztBtn.setEllipsize(TruncateAt.END);
        ztBtn.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(10));
        bb.setColor(0xFF12303A);
        bb.setStroke(dp(1), 0xFF2FA6BF);
        ztBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), bb, bb));
        ztBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openZerotierFix(); }
        });
        ztPanel.addView(ztBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ztState = new TextView(this);
        ztState.setText("ZT 隧道：检测中");
        ztState.setTextSize(11.5f);
        ztState.setTextColor(0xFF8A6F2B);
        ztState.setSingleLine(true);
        ztState.setEllipsize(TruncateAt.END);
        ztState.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(12);
        ztPanel.addView(ztState, slp);
        ztState.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { probeGateways(true); }
        });
    }

    private void openZerotierFix() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage(ZT_PKG);
            if (it == null) throw new IllegalStateException("no launch intent");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=" + ZT_PKG));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                toast("未检测到 ZerotierFix，已跳转商店");
            } catch (Throwable t2) {
                toast("未检测到 ZerotierFix，请先安装");
            }
        }
    }

    /** 从所有卡片收集 ZT 网关地址（去掉 :9 端口，按 IP 去重） */
    private void collectZtGateways() {
        ztGws.clear();
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject o = hosts.optJSONObject(i);
            if (o == null) continue;
            String g = ztGwOf(o.optString("zt", ""));
            if (g.isEmpty()) continue;
            if (!ztGws.contains(g)) ztGws.add(g);
        }
    }

    /** "192.168.10.1:9" -> "192.168.10.1" */
    private String ztGwOf(String zt) {
        if (zt == null) return "";
        String t = zt.trim();
        int c = t.lastIndexOf(':');
        if (c > 0 && t.indexOf('.') >= 0) t = t.substring(0, c);
        return t.trim();
    }

    private String gwLabel(String ip) {
        Integer st = gwState.get(ip);
        int s = st == null ? GW_PEND : st;
        if (s == GW_PEND) return "检测中";
        if (s == GW_OK) {
            Integer ms = gwMs.get(ip);
            return (ms != null && ms > 0) ? ("通 " + ms + "ms") : "通";
        }
        if (s == GW_RST) {
            Integer ms = gwMs.get(ip);
            return (ms != null && ms > 0) ? ("通·拒 " + ms + "ms") : "通·拒";
        }
        return "不通";
    }

    private int gwColor(String ip) {
        Integer st = gwState.get(ip);
        int s = st == null ? GW_PEND : st;
        if (s == GW_OK) return 0xFF5FF0A8;
        if (s == GW_RST) return 0xFFE0A030;
        if (s == GW_DOWN) return 0xFF57707C;
        return 0xFF8A6F2B;
    }

    /** 顶部汇总：● ZT 网关 1 / 2 可达 */
    private void paintGwSummary() {
        if (ztState == null) return;
        int total = ztGws.size(), on = 0, pend = 0;
        for (String g : ztGws) {
            Integer st = gwState.get(g);
            int s = st == null ? GW_PEND : st;
            if (s == GW_OK || s == GW_RST) on++;
            if (s == GW_PEND) pend++;
        }
        if (total == 0) {
            ztState.setText("● ZT 网关：未配置");
            ztState.setTextColor(0xFF57707C);
        } else if (pend > 0 && on == 0) {
            ztState.setText("● ZT 网关 " + on + " / " + total + " 可达");
            ztState.setTextColor(0xFF8A6F2B);
        } else {
            ztState.setText("● ZT 网关 " + on + " / " + total + " 可达");
            ztState.setTextColor(on == total ? 0xFF5FF0A8
                    : (on == 0 ? 0xFF57707C : 0xFFE0A030));
        }
    }

    /** 卡片上的单网关状态 */
    private void paintGwViews() {
        for (int i = 0; i < gwViews.size(); i++) {
            String ip = gwViewIp.get(i);
            TextView tv = gwViews.get(i);
            if (tv == null) continue;
            tv.setText(gwLabel(ip));
            tv.setTextColor(gwColor(ip));
        }
    }

    /** 单个网关判定：ICMP 有回应=通；端口可连=通；快速被拒=通·拒；全超时=不通 */
    private void probeOneGw(final String g, final boolean full, final int gen) {
        synchronized (gwBusy) {
            if (Boolean.TRUE.equals(gwBusy.get(g))) return;
            gwBusy.put(g, Boolean.TRUE);
        }
        int st = GW_DOWN, ms = 0;
        try {
            int rtt = icmpRtt(g, full ? 800 : 700);
            if (rtt >= 0) {
                st = GW_OK;
                ms = rtt;
            } else {
                int openPort = 0;
                int openMs = 0;
                for (int p : new int[]{80, 22, 443, 8080}) {
                    long t0 = System.currentTimeMillis();
                    if (tcpOpenAt(g, p)) {
                        openPort = p;
                        openMs = (int) Math.max(1, System.currentTimeMillis() - t0);
                        break;
                    }
                }
                if (openPort > 0) {
                    st = GW_OK;
                    ms = openMs;
                } else if (full) {
                    int rst = -1;
                    for (int p : new int[]{80, 22, 443}) {
                        rst = tcpRstRtt(g, p);
                        if (rst > 0) break;
                    }
                    if (rst > 0) { st = GW_RST; ms = rst; }
                }
            }
        } finally {
            synchronized (gwBusy) { gwBusy.remove(g); }
        }
        synchronized (gwState) {
            if (gwGen != gen) return;      /* 只有新一轮完整刷新才作废旧结果 */
            gwState.put(g, st);
            gwMs.put(g, ms);
            gwFull.put(g, full);
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (gwGen != gen) return;
                paintGwViews();
                paintGwSummary();
            }
        });
    }

    private int gwGen = 0;

    /** full=true：下拉/首屏做完整判定；false：3 秒轮询只做 ICMP 快速确认 */
    private void probeGateways(final boolean full) {
        collectZtGateways();
        final int gen;
        if (full) {
            gen = ++gwGen;
        } else {
            gen = gwGen;
        }
        synchronized (gwState) {
            for (String g : ztGws) {
                if (full) { gwState.put(g, GW_PEND); gwMs.put(g, 0); }
                else if (!gwState.containsKey(g)) gwState.put(g, GW_PEND);
            }
        }
        paintGwViews();
        paintGwSummary();
        final ArrayList<String> list = new ArrayList<>(ztGws);
        for (final String g : list) {
            try {
                probePool.execute(new Runnable() {
                    @Override public void run() { probeOneGw(g, full, gen); }
                });
            } catch (Throwable t) {
                gwState.put(g, GW_DOWN);
            }
        }
    }

    private final Runnable ztTick = new Runnable() {
        @Override public void run() {
            if (!ztPolling) return;
            probeGateways(false);
            ui.postDelayed(this, 10000);
        }
    };

    private void startZtPoll() {
        if (ztPolling) return;
        ztPolling = true;
        ui.removeCallbacks(ztTick);
        probeGateways(true);
        ui.postDelayed(ztTick, 10000);
    }

    private void stopZtPoll() {
        ztPolling = false;
        ui.removeCallbacks(ztTick);
    }

    private void buildListHeader(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText("我的主机");
        t.setTextSize(13);
        t.setTextColor(0xFF3AAFC8);
        t.setLetterSpacing(0.12f);
        row.addView(t);

        tvCount = new TextView(this);
        tvCount.setTextSize(11.5f);
        tvCount.setTextColor(0xFF44606C);
        tvCount.setGravity(Gravity.END);
        row.addView(tvCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private void buildHostArea(LinearLayout parent) {
        hostList = new LinearLayout(this);
        hostList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        hostList.setLayoutParams(lp);
        parent.addView(hostList);

        tvEmpty = new TextView(this);
        tvEmpty.setText("还没有主机\n点右下角 + 添加第一台");
        tvEmpty.setTextSize(13.5f);
        tvEmpty.setTextColor(0xFF4A6570);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(16), dp(44), dp(16), dp(44));
        GradientDrawable eb = new GradientDrawable();
        eb.setCornerRadius(dp(16));
        eb.setColor(0x00000000);
        eb.setStroke(dp(1), 0xFF182A33);
        tvEmpty.setBackground(eb);
        tvEmpty.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(12);
        tvEmpty.setLayoutParams(elp);
        parent.addView(tvEmpty);
    }

    private void buildFooter(LinearLayout parent) {
        tvStatus = new TextView(this);
        tvStatus.setTextSize(11.5f);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(TruncateAt.END);
        tvStatus.setTextColor(0xFF4E7484);
        tvStatus.setPadding(dp(4), dp(22), dp(4), 0);
        parent.addView(tvStatus);

        TextView tvVer = new TextView(this);
        tvVer.setText(appVersion());
        tvVer.setTextSize(10f);
        tvVer.setLetterSpacing(0.08f);
        tvVer.setTextColor(0xFF31474F);
        tvVer.setGravity(Gravity.CENTER);
        tvVer.setSingleLine(true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(18);
        vlp.bottomMargin = dp(10);
        parent.addView(tvVer, vlp);
    }

    /** 版本号取自 manifest，改一处即可 */
    private String appVersion() {
        try {
            return getString(R.string.app_name) + "  v"
                    + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return getString(R.string.app_name);
        }
    }

    // ---------------- 列表 ----------------

    private void refreshList() {
        hostList.removeAllViews();
        statusViews.clear();
        gwViews.clear();
        gwViewIp.clear();
        gwViewBase.clear();
        listGen++;
        refreshingStatus = false;
        tvCount.setText("  " + hosts.length() + " 个设备");
        tvEmpty.setVisibility(hosts.length() == 0 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < hosts.length(); i++) {
            JSONObject o = hosts.optJSONObject(i);
            if (o == null) continue;
            hostList.addView(buildCard(i, o));
        }

        ui.post(new Runnable() {
            @Override public void run() { refreshStatuses(); }
        });
    }

    private View buildCard(final int index, JSONObject o) {
        final String name = o.optString("name", "未命名");
        final String mac = o.optString("mac", "");
        final String zt = o.optString("zt", "");
        final int port = o.optInt("port", DEFAULT_PORT);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(16), dp(13));

        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF13202A, 0xFF0E1720});
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), C_LINE);
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x402FD8FF), shape, null));
        card.setElevation(dp(2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        TextView badge = new TextView(this);
        String initial = name.trim().isEmpty() ? "?" : name.trim().substring(0, 1);
        badge.setText(initial);
        badge.setTextSize(15);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(C_CYAN_SOFT);
        GradientDrawable bshape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0E2A36, 0xFF103440});
        bshape.setCornerRadius(dp(11));
        bshape.setStroke(dp(1), 0xFF1F4E5E);
        badge.setBackground(bshape);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(13);
        card.addView(badge, blp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        card.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(15.5f);
        tvName.setTextColor(C_TEXT);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TruncateAt.END);
        mid.addView(tvName);

        TextView tvMac = new TextView(this);
        tvMac.setText(mac);
        tvMac.setTextSize(11.5f);
        tvMac.setTypeface(Typeface.MONOSPACE);
        tvMac.setTextColor(C_SUB);
        tvMac.setLetterSpacing(0.04f);
        tvMac.setSingleLine(true);
        tvMac.setEllipsize(TruncateAt.END);
        tvMac.setPadding(0, dp(3), 0, 0);
        mid.addView(tvMac);

        /* ZT 行：网关状态在左（定宽不受挤压），地址在右可省略 */
        LinearLayout ztRow = new LinearLayout(this);
        ztRow.setOrientation(LinearLayout.HORIZONTAL);
        ztRow.setGravity(Gravity.CENTER_VERTICAL);
        ztRow.setPadding(0, dp(2), 0, 0);

        TextView tvGw = new TextView(this);
        tvGw.setTextSize(10.5f);
        tvGw.setTypeface(Typeface.DEFAULT_BOLD);
        tvGw.setSingleLine(true);
        tvGw.setPadding(0, 0, dp(8), 0);
        tvGw.setVisibility(zt.isEmpty() ? View.GONE : View.VISIBLE);
        ztRow.addView(tvGw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvRoute = new TextView(this);
        tvRoute.setText(zt.isEmpty() ? "未绑定 ZT 网关" : "ZT → " + zt + ":" + port);
        tvRoute.setTextSize(10.5f);
        tvRoute.setTypeface(Typeface.MONOSPACE);
        tvRoute.setTextColor(zt.isEmpty() ? 0xFF48606C : 0xFF3E9FB8);
        tvRoute.setSingleLine(true);
        tvRoute.setEllipsize(TruncateAt.END);
        ztRow.addView(tvRoute, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.addView(ztRow);

        if (!zt.isEmpty()) {
            String gwIp = ztGwOf(zt);
            gwViews.add(tvGw);
            gwViewIp.add(gwIp);
            gwViewBase.add("ZT → " + zt + ":" + port);
            tvGw.setText(gwLabel(gwIp));
            tvGw.setTextColor(gwColor(gwIp));
        }

        final String ip = o.optString("ip", "");
        if (!ip.isEmpty()) {
            TextView tvIp = new TextView(this);
            tvIp.setText("IP " + ip);
            tvIp.setTextSize(10.5f);
            tvIp.setTypeface(Typeface.MONOSPACE);
            tvIp.setTextColor(0xFF3E9FB8);
            tvIp.setSingleLine(true);
            tvIp.setEllipsize(TruncateAt.END);
            tvIp.setPadding(0, dp(1), 0, 0);
            mid.addView(tvIp);
        }

        // 右侧：状态 + 唤醒（定宽，保证「检测中」不会被挤掉）
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                dp(62), ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);

        TextView status = new TextView(this);
        status.setTextSize(10.5f);
        status.setSingleLine(true);
        status.setGravity(Gravity.END);
        paintStatus(status, ST_UNKNOWN, ip);
        right.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusViews.add(status);

        final TextView action = new TextView(this);
        action.setText("唤醒 ▸");
        action.setTextSize(11.5f);
        action.setTextColor(0xFF3FD8F0);
        action.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(6);
        right.addView(action, alp);

        card.addView(right, rlp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { wake(name, mac, zt, port, action, v); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showHostMenu(index, name);
                return true;
            }
        });
        return card;
    }

    private void showHostMenu(final int index, final String name) {
        String[] items = {"重命名 / 改 MAC", "修改 ZT 网关", "删除"};
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(name);
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0 || which == 1) {
                    editHost(index);
                } else {
                    hosts.remove(index);
                    saveHosts();
                    refreshList();
                    toast("已删除「" + name + "」");
                }
            }
        });
        AlertDialog dlg = b.create();
        styleDialog(dlg);
        dlg.show();
    }

    // ---------------- 编辑主机 ----------------

    private void editHost(int index) {
        final boolean isEdit = index >= 0;
        JSONObject o = isEdit ? hosts.optJSONObject(index) : new JSONObject();
        if (o == null) o = new JSONObject();
        final JSONObject base = o;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(6), dp(24), 0);

        EditText nameIn = new EditText(this);
        nameIn.setHint("名称，如：书房台式机");
        nameIn.setHintTextColor(0xFF4E6B77);
        nameIn.setTextColor(C_TEXT);
        nameIn.setTextSize(15);
        nameIn.setSingleLine(true);
        nameIn.setInputType(InputType.TYPE_CLASS_TEXT);
        nameIn.setBackground(fieldBg());
        nameIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        nameIn.setText(base.optString("name", ""));
        box.addView(nameIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText macIn = new EditText(this);
        macIn.setHint("MAC，如 AA:BB:CC:DD:EE:FF");
        macIn.setHintTextColor(0xFF4E6B77);
        macIn.setTextColor(C_TEXT);
        macIn.setTextSize(15);
        macIn.setTypeface(Typeface.MONOSPACE);
        macIn.setSingleLine(true);
        macIn.setInputType(InputType.TYPE_CLASS_TEXT);
        macIn.setBackground(fieldBg());
        macIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        macIn.setText(base.optString("mac", ""));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        box.addView(macIn, mlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText ztIn = new EditText(this);
        ztIn.setHint("该主机所在路由器的 ZT 地址");
        ztIn.setHintTextColor(0xFF4E6B77);
        ztIn.setTextColor(C_TEXT);
        ztIn.setTextSize(14);
        ztIn.setTypeface(Typeface.MONOSPACE);
        ztIn.setSingleLine(true);
        ztIn.setInputType(InputType.TYPE_CLASS_TEXT);
        ztIn.setBackground(fieldBg());
        ztIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        ztIn.setText(base.optString("zt", ""));
        row.addView(ztIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText portIn = new EditText(this);
        portIn.setHint("9");
        portIn.setHintTextColor(0xFF4E6B77);
        portIn.setTextColor(C_TEXT);
        portIn.setTextSize(14);
        portIn.setGravity(Gravity.CENTER);
        portIn.setSingleLine(true);
        portIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        portIn.setBackground(fieldBg());
        portIn.setPadding(dp(8), dp(12), dp(8), dp(12));
        portIn.setText(String.valueOf(base.optInt("port", DEFAULT_PORT)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(10);
        row.addView(portIn, plp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        box.addView(row, rlp);

        EditText ipIn = new EditText(this);
        ipIn.setHint("主机 IP，如 192.168.1.20（用于判断在线）");
        ipIn.setHintTextColor(0xFF4E6B77);
        ipIn.setTextColor(C_TEXT);
        ipIn.setTextSize(14);
        ipIn.setTypeface(Typeface.MONOSPACE);
        ipIn.setSingleLine(true);
        ipIn.setInputType(InputType.TYPE_CLASS_TEXT);
        ipIn.setBackground(fieldBg());
        ipIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        ipIn.setText(base.optString("ip", ""));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        box.addView(ipIn, ilp);

        TextView hint = new TextView(this);
        hint.setText(isEdit
                ? "IP 用于判断在线状态，留空则不检测"
                : "ZT 地址留空 = 只在局域网唤醒；IP 留空 = 不检测在线状态");
        hint.setTextSize(11.5f);
        hint.setTextColor(0xFF4E6B77);
        hint.setPadding(dp(4), dp(10), 0, 0);
        box.addView(hint);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        if (!isEdit) {
            final TextView scanBtn = new TextView(this);
            scanBtn.setText("⌕  扫描局域网在线主机，一键添加");
            scanBtn.setTextSize(13);
            scanBtn.setTextColor(0xFF2FD8FF);
            scanBtn.setGravity(Gravity.CENTER);
            scanBtn.setSingleLine(true);
            scanBtn.setEllipsize(TruncateAt.END);
            scanBtn.setBackground(fieldBg());
            scanBtn.setPadding(dp(12), dp(14), dp(12), dp(14));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(14);
            box.addView(scanBtn, slp);
            scanBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    scanLan();
                }
            });
        }

        ScrollView boxScroll = new ScrollView(this);
        boxScroll.setFillViewport(false);
        boxScroll.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(isEdit ? "编辑主机" : "添加主机")
                .setView(boxScroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        dlgRef[0] = dlg;
        styleDialog(dlg);
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF2FD8FF);
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF6FA8BE);
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String nm = nameIn.getText().toString().trim();
                        String mc = macIn.getText().toString().trim().toUpperCase();
                        String zt = ztIn.getText().toString().trim();
                        String ip = ipIn.getText().toString().trim();
                        int port = DEFAULT_PORT;
                        try {
                            int p = Integer.parseInt(portIn.getText().toString().trim());
                            if (p > 0 && p < 65536) port = p;
                        } catch (Exception ignored) { }

                        if (nm.isEmpty()) { toast("给主机起个名字"); return; }
                        if (!validMac(mc)) { toast("MAC 格式不对，应为 AA:BB:CC:DD:EE:FF"); return; }
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("name", nm);
                            obj.put("mac", mc);
                            obj.put("zt", zt);
                            obj.put("port", port);
                            obj.put("ip", ip);
                            if (isEdit) {
                                hosts.put(index, obj);
                            } else {
                                hosts.put(obj);
                            }
                            saveHosts();
                            refreshList();
                        } catch (Exception e) {
                            toast("保存失败：" + e.getMessage());
                            return;
                        }
                        dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
    }

    private void styleDialog(AlertDialog dlg) {
        Window w = dlg.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(0xFF0D161D);
            bg.setStroke(dp(1), 0xFF26414E);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.65f);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setAttributes(lp);
        }
    }

    // ---------------- 唤醒 ----------------

    private void wake(String name, String mac, String zt, int port,
                      TextView action, View card) {
        long now = System.currentTimeMillis();
        if (now - lastWakeAt < 900) return;
        lastWakeAt = now;

        final byte[] macBytes = macToBytes(mac);
        if (macBytes == null) {
            toast("MAC 无效，长按卡片修改");
            return;
        }

        if (ztMode && zt.isEmpty()) {
            toast("这台没有绑定 ZT 网关，长按卡片补填");
            return;
        }

        action.setText("发送中…");
        action.setTextColor(0xFFFFC66B);
        card.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        card.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    }
                }).start();

        final boolean useZt = ztMode;
        final String fZt = zt;
        final int fPort = port;

        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    InetAddress target = useZt
                            ? InetAddress.getByName(fZt)
                            : InetAddress.getByName("255.255.255.255");
                    byte[] pkt = magicPacket(macBytes);
                    DatagramSocket sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    for (int i = 0; i < 3; i++) {
                        sock.send(new DatagramPacket(pkt, pkt.length, target, fPort));
                        if (i < 2) Thread.sleep(300);
                    }
                    sock.close();
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fErr == null) {
                            action.setText("已发送 ✓");
                            action.setTextColor(0xFF5FF0A8);
                            tvStatus.setText("● 已唤醒「" + name + "」");
                            tvStatus.setTextColor(0xFF4E7484);
                        } else {
                            action.setText("失败");
                            action.setTextColor(0xFFFF7B72);
                            tvStatus.setText("✕ 发送失败：" + fErr);
                            tvStatus.setTextColor(0xFF4E7484);
                        }
                        ui.postDelayed(new Runnable() {
                            @Override public void run() {
                                action.setText("唤醒 ▸");
                                action.setTextColor(0xFF3FD8F0);
                            }
                        }, 2300);
                    }
                });
            }
        }).start();
    }

    // 只切换胶囊高亮，其余颜色/背景一律不动
    private void refreshMode() {
        sp.edit().putBoolean("zt", ztMode).apply();
        paintTab(tabZt, ztMode);
        paintTab(tabLan, !ztMode);
        if (ztPanel != null) ztPanel.setVisibility(ztMode ? View.VISIBLE : View.GONE);
        if (ztMode) startZtPoll(); else stopZtPoll();
        if (tvStatus != null) {
            tvStatus.setText("● " + (ztMode ? "ZT 远程模式" : "局域网模式"));
            tvStatus.setTextColor(0xFF4E7484);
        }
    }

    private void paintTab(TextView tv, boolean active) {
        if (tv == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        if (active) {
            g.setColor(0xFF12303A);
            g.setStroke(dp(1), 0xFF2FA6BF);
            tv.setTextColor(0xFF7FF3FF);
        } else {
            g.setColor(0x00000000);
            tv.setTextColor(0xFF57707C);
        }
        tv.setBackground(g);
    }

    // ---------------- 局域网扫描 ----------------

    /** 只认 Wi‑Fi/有线这类真实局域网网卡，排除 ZeroTier(tun)、蜂窝(rmnet)、VPN 等 */
    private int[] localRange() {
        ArrayList<String> names = new ArrayList<>();
        int bestScore = -1;
        int[] best = null;
        String bestName = "";
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a == null || a instanceof java.net.Inet6Address) continue;
                    String name = ni.getName().toLowerCase();
                    String ip = a.getHostAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    names.add(name + "=" + ip + "/" + prefix);
                    if (prefix < 8 || prefix > 32) continue;

                    int score;
                    if (name.startsWith("wlan") || name.startsWith("ap")) score = 5;
                    else if (name.startsWith("eth")) score = 4;
                    else if (name.contains("tun") || name.contains("zt") || name.contains("rmnet")
                            || name.contains("ppp") || name.contains("ccmni")
                            || name.contains("ipsec") || name.contains("dummy")
                            || name.contains("clat") || name.contains("v4-")) score = 0;
                    else score = 2;
                    if (score <= bestScore) continue;

                    int pfx = prefix;
                    if (pfx < 24) pfx = 24;   // 只扫自己所在的 /24，避免 /16 之类的大网扫出一堆瞎猜
                    if (pfx > 30) pfx = 30;
                    int m = pfx >= 32 ? -1 : (int) (0xFFFFFFFFL << (32 - pfx));
                    int base = ipInt(a) & m;
                    int total = 1 << (32 - pfx);
                    if (total > 1024) total = 1024;

                    bestScore = score;
                    best = new int[]{base, total, pfx};
                    bestName = name;
                    selfIp = ip;
                }
            }
        } catch (Exception ignored) { }
        ifaceList = names;
        usedIface = bestName;
        return best;
    }

    private String selfIp = "";
    private String usedIface = "";
    private ArrayList<String> ifaceList = new ArrayList<>();

    private int ipInt(java.net.InetAddress a) {
        byte[] b = a.getAddress();
        if (b == null || b.length != 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static String ipStr(int v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "."
                + (v & 0xFF);
    }

    private AlertDialog scanDlg;
    private LinearLayout scanRowsList;
    private TextView scanHead;
    private final Map<String, TextView> scanRowViews = new LinkedHashMap<>();
    private final Map<String, String> scanMacs = new LinkedHashMap<>();
    private final Map<String, String> scanNames = new LinkedHashMap<>();
    private final Map<String, Integer> scanRtt = new LinkedHashMap<>();
    private final Map<String, String> scanKind = new LinkedHashMap<>();
    private volatile boolean scanRunning = false;

    private static int ipToInt(String ip) {
        try {
            String[] p = ip.split("\\.");
            return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                    | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void scanLan() {
        if (scanRunning) { toast("正在扫描中…"); return; }
        scanRunning = true;
        scanRowViews.clear();
        scanMacs.clear();
        scanNames.clear();
        scanRtt.clear();
        scanKind.clear();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), 0);

        scanHead = new TextView(this);
        scanHead.setText("扫描中…");
        scanHead.setTextSize(12);
        scanHead.setTextColor(0xFF3AAFC8);
        scanHead.setPadding(0, 0, 0, dp(8));
        box.addView(scanHead);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0A141B);
        scanRowsList = new LinearLayout(this);
        scanRowsList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(scanRowsList);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420));
        box.addView(sv, slp);

        scanDlg = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("局域网扫描")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        styleDialog(scanDlg);
        scanDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                scanRunning = false;
                scanRowsList = null;
            }
        });
        scanDlg.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int[] r = localRange();
                    if (r == null) {
                        final StringBuilder sb = new StringBuilder("取不到局域网地址，现有网卡：\n");
                        for (String n : ifaceList) sb.append(n).append("\n");
                        ui.post(new Runnable() {
                            @Override public void run() {
                                scanHead.setText(sb.toString());
                                scanRunning = false;
                            }
                        });
                        return;
                    }
                    final int base = r[0];
                    final int total = Math.min(r[1], 1022);
                    final int prefix = r[2];
                    scanInfo = "网卡 " + usedIface + " · 本机 " + selfIp + " · /" + prefix;

                    // A) 内核邻居表（很多 ROM 会拦，能拿到就先把 MAC 补上）
                    final Map<String, String> macs = NetHelper.read();
                    for (Map.Entry<String, String> e : macs.entrySet()) {
                        final String ip = e.getKey();
                        final String mac = e.getValue();
                        ui.post(new Runnable() {
                            @Override public void run() { addScanRow(ip, mac, "ARP", -1); }
                        });
                    }

                    // B) 探活：ICMP 优先，其次端口真开（只回 RST 的一律不显示）
                    ExecutorService tp = Executors.newFixedThreadPool(48);
                    for (int i = 1; i < total - 1; i++) {
                        final String t = ipStr(base + i);
                        if (t.equals(selfIp) || macs.containsKey(t)) continue;
                        tp.execute(new Runnable() {
                            @Override public void run() {
                                int rtt = icmpRtt(t, 1000);
                                String kind = "PING";
                                if (rtt < 0) {
                                    rtt = tcpOpenRtt(t);
                                    kind = "TCP";
                                }
                                if (rtt < 0) return;
                                final int rttF = rtt;
                                final String kindF = kind;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (scanRtt != null) scanRtt.put(t, rttF);
                                        addScanRow(t, "", kindF, rttF);
                                    }
                                });
                                String name = reverseName(t);
                                if (name != null) {
                                    final String nameF = name;
                                    ui.post(new Runnable() {
                                        @Override public void run() {
                                            if (scanNames != null) scanNames.put(t, nameF);
                                            refreshScanRow(t);
                                        }
                                    });
                                }
                            }
                        });
                    }
                    tp.shutdown();
                    try { tp.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception ignored) { }

                    // C) 探活结束，邻居表这时才有内容，再读一次补 MAC
                    final Map<String, String> macs2 = NetHelper.read();
                    for (Map.Entry<String, String> e : macs2.entrySet()) {
                        final String ip = e.getKey();
                        final String mac = e.getValue();
                        ui.post(new Runnable() {
                            @Override public void run() { addScanRow(ip, mac, "ARP", -1); }
                        });
                    }

                    scanInfo = scanInfo + " · 邻居表 " + macs2.size() + " · icmpErr " + NetHelper.pingErr();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            scanRunning = false;
                            int withMac = 0;
                            for (String m : scanMacs.values()) if (m != null && !m.isEmpty()) withMac++;
                            if (scanHead != null) {
                                scanHead.setText("确认在线 " + scanRowViews.size() + " 台 · MAC 识别 " + withMac + " 台\n"
                                        + scanInfo);
                            }
                        }
                    });
                } catch (Exception e) {
                    final String msg = "扫描异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            scanRunning = false;
                            if (scanHead != null) scanHead.setText(msg);
                        }
                    });
                }
            }
        }).start();
    }

    /** 汇总所有能拿到 MAC 的来源：netlink 原生 > /proc/net/arp > ip neigh >（兜底）路由器接口 */
    private void addScanRow(String ip, String mac, String kind, int rtt) {
        if (scanRowsList == null) return;
        String m = mac == null ? "" : mac;
        TextView row = scanRowViews.get(ip);
        if (row != null) {                       /* 已有这行，只补 MAC */
            String old = scanMacs.get(ip);
            if ((old == null || old.isEmpty()) && !m.isEmpty()) {
                scanMacs.put(ip, m);
                row.setText(rowLabel(ip, m));
            }
            return;
        }
        if (!m.isEmpty() && macExists(m)) return;
        if (!m.isEmpty()) scanMacs.put(ip, m);
        if (rtt > 0 && scanRtt != null) scanRtt.put(ip, rtt);
        if (kind != null && scanKind != null) scanKind.put(ip, kind);

        TextView tv = new TextView(this);
        tv.setText(rowLabel(ip, m));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(kind != null && kind.equals("RST") ? 0xFF5B8A9B : C_TEXT);
        tv.setPadding(dp(4), dp(10), dp(4), dp(10));
        tv.setTag(ip);
        tv.setBackgroundColor(0xFF0F1A22);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmAdd(ip, scanMacs.get(ip) == null ? "" : scanMacs.get(ip));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);

        int key = ipToInt(ip);
        int index = scanRowsList.getChildCount();
        for (int i = 0; i < scanRowsList.getChildCount(); i++) {
            Object tag = scanRowsList.getChildAt(i).getTag();
            if (tag instanceof String && ipToInt((String) tag) > key) { index = i; break; }
        }
        scanRowsList.addView(tv, index, lp);
        scanRowViews.put(ip, tv);
        if (scanHead != null) scanHead.setText("扫描中… 已发现 " + scanRowViews.size() + " 台");
    }

    /** 主机名三级来源：系统 PTR（路由器 dnsmasq）→ mDNS 反查 → 网页标题 */
    private String reverseName(String ip) {
        String n = systemPtr(ip);
        if (n != null) return n;
        n = mdnsName(ip);
        if (n != null) return n;
        return httpTitle(ip);
    }

    /** 系统解析器反查 */
    private String systemPtr(String ip) {
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            String n = a.getCanonicalHostName();
            if (okName(n, ip)) return n.trim();
            n = a.getHostName();
            if (okName(n, ip)) return n.trim();
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean okName(String n, String ip) {
        if (n == null) return false;
        n = n.trim();
        return !n.isEmpty() && !n.equals(ip) && n.indexOf(' ') < 0;
    }

    /** mDNS（5353）反查：手机、打印机、NAS、Apple 设备常用 */
    private String mdnsName(String ip) {
        java.net.DatagramSocket s = null;
        try {
            byte[] q = dnsPtrQuery(ip, 0x8001);            /* QU：要求单播回复 */
            if (q.length == 0) return null;
            s = new java.net.DatagramSocket();
            s.setSoTimeout(1200);
            s.send(new java.net.DatagramPacket(q, q.length,
                    java.net.InetAddress.getByName("224.0.0.251"), 5353));
            byte[] buf = new byte[1024];
            long end = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < end) {
                java.net.DatagramPacket rp = new java.net.DatagramPacket(buf, buf.length);
                s.receive(rp);
                String name = dnsFirstPtr(rp.getData(), rp.getLength());
                if (name != null && !name.equalsIgnoreCase(ip)) return name;
            }
        } catch (Throwable ignored) {
        } finally { if (s != null) try { s.close(); } catch (Exception ignored) { } }
        return null;
    }

    /** 路由器/电视/群晖这类带网页的：抓 <title> 当名字 */
    private String httpTitle(String ip) {
        for (int port : new int[]{80, 8080}) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(ip, port), 900);
                s.setSoTimeout(1200);
                java.io.OutputStream os = s.getOutputStream();
                os.write(("GET / HTTP/1.0\r\nHost: " + ip + "\r\nUser-Agent: Mozilla/5.0\r\n\r\n")
                        .getBytes("UTF-8"));
                os.flush();
                java.io.InputStream is = s.getInputStream();
                byte[] buf = new byte[2048];
                int n, total = 0;
                while (total < buf.length && (n = is.read(buf, total, buf.length - total)) > 0) {
                    total += n;
                }
                String body = new String(buf, 0, total, "UTF-8");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<title[^>]*>(.*?)</title>",
                                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                        .matcher(body);
                if (m.find()) {
                    String t = m.group(1).replaceAll("<[^>]*>", " ")
                            .replaceAll("\\s+", " ").trim();
                    if (!t.isEmpty() && t.length() <= 40) return t;
                }
            } catch (Throwable ignored) {
            } finally { try { s.close(); } catch (Exception ignored) { } }
        }
        return null;
    }

    /** 组 DNS 查询包：PTR 查 x.y.z.w.in-addr.arpa */
    private static byte[] dnsPtrQuery(String ip, int qclass) {
        String[] o = ip.split("\\.");
        if (o.length != 4) return new byte[0];
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        q.write(0x1A); q.write(0x2B);
        q.write(0x01); q.write(0x00);
        q.write(0x00); q.write(0x01);
        q.write(0x00); q.write(0x00);
        q.write(0x00); q.write(0x00);
        q.write(0x00); q.write(0x00);
        for (int i = 3; i >= 0; i--) {
            q.write(o[i].length());
            q.write(o[i].getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, o[i].length());
        }
        q.write(0x00);
        q.write(0x00); q.write(0x0C);        /* PTR */
        q.write((qclass >> 8) & 0xFF); q.write(qclass & 0xFF);
        return q.toByteArray();
    }

    /** 从 DNS 响应里取第一条 PTR 名字 */
    private static String dnsFirstPtr(byte[] d, int len) {
        try {
            if (len < 12) return null;
            int an = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
            if (an <= 0) return null;
            int p = skipDnsName(d, 12) + 4;
            for (int k = 0; k < an && p + 10 < len; k++) {
                p = skipDnsName(d, p);
                int type = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
                int rdlen = ((d[p + 8] & 0xFF) << 8) | (d[p + 9] & 0xFF);
                p += 10;
                if (type == 12 && rdlen > 0 && p + rdlen <= len) {
                    String name = readDnsName(d, p, len);
                    if (name != null) {
                        name = name.trim();
                        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
                        if (!name.isEmpty()) return name;
                    }
                }
                p += rdlen;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static int skipDnsName(byte[] d, int p) {
        while (p < d.length) {
            int l = d[p] & 0xFF;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += 1 + l;
        }
        return p;
    }

    private static String readDnsName(byte[] d, int p, int len) throws Exception {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (p < len && guard++ < 128) {
            int l = d[p] & 0xFF;
            if (l == 0) break;
            if ((l & 0xC0) == 0xC0) {
                String tail = readDnsName(d, ((l & 0x3F) << 8) | (d[p + 1] & 0xFF), len);
                if (tail != null && !tail.isEmpty()) sb.append(tail);
                break;
            }
            sb.append(new String(d, p + 1, l, "UTF-8"));
            p += 1 + l;
            if (p < len && d[p] != 0) sb.append('.');
        }
        return sb.toString();
    }

    /** 名字/耗时拿到后刷新已有那一行 */
    private void refreshScanRow(String ip) {
        TextView tv = scanRowViews.get(ip);
        if (tv == null) return;
        String mac = scanMacs.get(ip);
        tv.setText(rowLabel(ip, mac == null ? "" : mac));
    }

    private String rowLabel(String ip, String mac) {
        String name = scanNames == null ? null : scanNames.get(ip);
        String head = (name != null && !name.isEmpty() && !name.equals(ip)) ? name + " (" + ip + ")" : ip;
        Integer r = scanRtt == null ? null : scanRtt.get(ip);
        String rt = (r != null && r > 0) ? r + "ms" : "";
        String kd = scanKind == null ? "" : scanKind.get(ip);
        String tail = (!rt.isEmpty() ? rt + "  " : "") + (kd == null ? "" : kd + "  ")
                + (mac.isEmpty() ? "MAC 地址未找到" : mac);
        return head + "\n" + tail;
    }

    private boolean macExists(String mac) {
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject o = hosts.optJSONObject(i);
            if (o != null && mac.equalsIgnoreCase(o.optString("mac", ""))) return true;
        }
        return false;
    }

    /** 名字/耗时拿到后刷新已有那一行 */    /** 取默认网关；取不到就用网段第一个地址 */
    private String gatewayIp(int base) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (android.net.RouteInfo ri : lp.getRoutes()) {
                    if (ri.isDefaultRoute() && ri.getGateway() != null
                            && ri.getGateway() instanceof java.net.Inet4Address) {
                        return ri.getGateway().getHostAddress();
                    }
                }
                if (lp.getDhcpServerAddress() != null) {
                    return lp.getDhcpServerAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return ipStr(base + 1);
    }

    /** ICMP 判活，返回 RTT 毫秒；-1 不通。ping 不可用（-2）时退回端口探测 */
    private int icmpRtt(String ip, int timeoutMs) {
        int r = -2;
        try { r = NetHelper.ping(ip, timeoutMs); } catch (Throwable ignored) { r = -2; }
        return r;
    }

    /** 端口真开才算在线，返回连接耗时毫秒；-1 不活 */
    private int tcpOpenRtt(String ip) {
        for (int port : TCP_PORTS) {
            Socket s = new Socket();
            long t0 = System.currentTimeMillis();
            try {
                s.connect(new InetSocketAddress(ip, port), 400);
                return (int) Math.max(1, System.currentTimeMillis() - t0);
            } catch (Exception ignored) {
            } finally { try { s.close(); } catch (IOException ignored) { } }
        }
        return -1;
    }
    private static final int[] TCP_PORTS = {80, 443, 22, 445, 3389, 5000, 8080, 139, 9100, 62078, 9};

    /** 选中一台后确认：可改名 / 补 MAC，确认才写入，一次只加一台 */
    private void confirmAdd(final String ip, String mac0) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView info = new TextView(this);
        info.setText("IP  " + ip + "\nMAC " + (mac0.isEmpty() ? "未识别，请手动填写" : mac0));
        info.setTextSize(12f);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextColor(0xFF3AAFC8);
        info.setPadding(0, 0, 0, dp(12));
        box.addView(info);

        EditText nameIn = new EditText(this);
        nameIn.setHint("名称");
        nameIn.setHintTextColor(0xFF4E6B77);
        nameIn.setTextColor(C_TEXT);
        nameIn.setTextSize(15);
        nameIn.setSingleLine(true);
        nameIn.setInputType(InputType.TYPE_CLASS_TEXT);
        nameIn.setBackground(fieldBg());
        nameIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        nameIn.setText(ip);
        box.addView(nameIn);

        EditText macIn = new EditText(this);
        macIn.setHint("MAC，如 AA:BB:CC:DD:EE:FF");
        macIn.setHintTextColor(0xFF4E6B77);
        macIn.setTextColor(C_TEXT);
        macIn.setTextSize(15);
        macIn.setTypeface(Typeface.MONOSPACE);
        macIn.setSingleLine(true);
        macIn.setInputType(InputType.TYPE_CLASS_TEXT);
        macIn.setBackground(fieldBg());
        macIn.setPadding(dp(14), dp(12), dp(14), dp(12));
        macIn.setText(mac0);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        box.addView(macIn, mlp);

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("添加这台主机")
                .setView(box)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        styleDialog(dlg);
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF2FD8FF);
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF6FA8BE);
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String nm = nameIn.getText().toString().trim();
                        String mc = macIn.getText().toString().trim().toUpperCase();
                        if (nm.isEmpty()) { toast("给它起个名字"); return; }
                        if (!validMac(mc)) { toast("MAC 格式不对，应为 AA:BB:CC:DD:EE:FF"); return; }
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("name", nm);
                            obj.put("mac", mc);
                            obj.put("zt", "");
                            obj.put("port", DEFAULT_PORT);
                            obj.put("ip", ip);
                            hosts.put(obj);
                            saveHosts();
                            refreshList();
                            dlg.dismiss();
                            toast("已添加 " + nm);
                        } catch (Exception e) {
                            toast("添加失败");
                        }
                    }
                });
            }
        });
        dlg.show();
    }

    // ---------------- 在线状态 ----------------

    private void refreshStatuses() {
        if (ztMode) probeGateways(true);      /* 下拉时网关一起重测 */
        if (hosts.length() == 0) return;
        refreshingStatus = true;
        final int gen = ++statusGen;      /* 重复下拉直接作废旧一轮，绝不再“没反应” */
        checkDone = 0;
        checkTotal = 0;
        onlineCount = 0;
        if (tvPull != null) {
            tvPull.setText("检 测 中 … 0 / " + hosts.length());
            tvPull.setTextColor(0xFF3AAFC8);
        }

        for (int i = 0; i < hosts.length(); i++) {
            JSONObject o = hosts.optJSONObject(i);
            if (o == null) continue;
            final String ip = o.optString("ip", "");
            final int idx = i;
            if (ip.isEmpty()) {
                applyStatus(idx, gen, ST_UNKNOWN);
                continue;
            }
            applyStatus(idx, gen, ST_CHECKING);
            checkTotal++;
            pool.execute(new Runnable() {
                @Override public void run() {
                    final boolean up = probeHost(ip);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != statusGen) return;          /* 被新一轮刷新取代 */
                            applyStatus(idx, gen, up ? ST_ONLINE : ST_OFFLINE);
                            checkDone++;
                            if (up) onlineCount++;
                            if (tvPull != null) {
                                tvPull.setText("检 测 中 … " + checkDone + " / " + checkTotal);
                            }
                            if (checkDone >= checkTotal) finishRefresh(gen);
                        }
                    });
                }
            });
        }
        if (checkTotal == 0) { finishRefresh(gen); return; }

        /* 兜底：万一某个探测卡住，8 秒后强制收尾，避免“一直检测中、刷新没反应” */
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen == statusGen && refreshingStatus && checkDone < checkTotal) finishRefresh(gen);
            }
        }, 8000);
    }

    private void applyStatus(int idx, int gen, int st) {
        if (gen != statusGen || idx < 0 || idx >= statusViews.size()) return;
        JSONObject o = hosts.optJSONObject(idx);
        String ip = o == null ? "" : o.optString("ip", "");
        paintStatus(statusViews.get(idx), st, ip);
    }

    private void paintStatus(TextView tv, int st, String ip) {
        switch (st) {
            case ST_ONLINE:
                tv.setText("● 在线");
                tv.setTextColor(0xFF4EE39A);
                break;
            case ST_OFFLINE:
                tv.setText("● 离线");
                tv.setTextColor(0xFFF17D7D);
                break;
            case ST_CHECKING:
                tv.setText("● 检测中");
                tv.setTextColor(0xFFFFC66B);
                break;
            default:
                tv.setText(ip.isEmpty() ? "● 未设 IP" : "● 待检测");
                tv.setTextColor(0xFF55666F);
        }
    }

    private void finishRefresh(int gen) {
        if (gen != statusGen) return;
        refreshingStatus = false;
        if (tvPull == null) return;
        tvPull.setText("✓ " + onlineCount + " / " + hosts.length() + " 在线 · 再下拉可刷新");
        tvPull.setTextColor(0xFF4E7484);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (tvPull == null) return;
                tvPull.setText("↓  下拉刷新状态");
                tvPull.setTextColor(0xFF44606C);
            }
        }, 2800);
    }

    // 先用 TCP 探几个常见端口，拒绝连接也说明主机在线；都不通再退回 ping
    /** 在线判定：第一波并发快查（谁先回就算谁），全落空再走慢速补充链 */
    private boolean probeHost(final String ip) {
        if (quickProbe(ip)) return true;

        /* 第二波：其余端口 + 慢速链路，命中就返回 */
        for (int p : STATUS_PORTS2) {
            if (tcpOpenAt(ip, p)) return true;
        }
        for (int p : STATUS_PORTS) {
            int ms = tcpRstRtt(ip, p);
            if (ms > 0 && ms <= 150) return true;      /* 快速拒绝 = 主机自己在回 */
        }
        return nbnsAlive(ip);
    }

    /** 并发快查：ICMP / 445 / 135 / 80 / 139 同时发，谁先成功算谁，最多等 1 秒 */
    private boolean quickProbe(final String ip) {
        final java.util.concurrent.atomic.AtomicBoolean hit =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Object lock = new Object();
        Runnable[] tasks = new Runnable[]{
                new Runnable() { public void run() {
                    if (hit.get()) return;
                    if (icmpRtt(ip, 700) >= 0 && hit.compareAndSet(false, true)) wake(lock);
                } },
                new Runnable() { public void run() {
                    if (hit.get()) return;
                    if (tcpOpenAt(ip, 445) && hit.compareAndSet(false, true)) wake(lock);
                } },
                new Runnable() { public void run() {
                    if (hit.get()) return;
                    if (tcpOpenAt(ip, 135) && hit.compareAndSet(false, true)) wake(lock);
                } },
                new Runnable() { public void run() {
                    if (hit.get()) return;
                    if (tcpOpenAt(ip, 80) && hit.compareAndSet(false, true)) wake(lock);
                } },
                new Runnable() { public void run() {
                    if (hit.get()) return;
                    if (tcpOpenAt(ip, 139) && hit.compareAndSet(false, true)) wake(lock);
                } },
        };
        int pending = tasks.length;
        for (Runnable r : tasks) {
            try { probePool.execute(r); } catch (Throwable t) { /* 线程池忙就跳过 */ }
        }
        synchronized (lock) {
            long end = System.currentTimeMillis() + 1100;
            while (!hit.get()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { break; }
            }
        }
        return hit.get();
    }

    private void wake(Object lock) {
        synchronized (lock) { lock.notifyAll(); }
    }

    private static final int[] STATUS_PORTS = {445, 139, 135, 80};
    private static final int[] STATUS_PORTS2 = {3389, 22, 443, 2869, 5900, 8080, 5000, 9100};
    private static final java.util.concurrent.ExecutorService probePool =
            java.util.concurrent.Executors.newCachedThreadPool();

    /** 只有真正三次握手成功才算在线 */
    private boolean tcpOpenAt(String ip, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally { try { s.close(); } catch (IOException ignored) { } }
    }

    /** 连不上但很快被拒 → 返回耗时毫秒；超时或没回应 → -1 */
    private int tcpRstRtt(String ip, int port) {
        Socket s = new Socket();
        long t0 = System.currentTimeMillis();
        try {
            s.connect(new InetSocketAddress(ip, port), 500);
            return -1;                                 /* 连上了，交给 tcpOpenAt */
        } catch (ConnectException e) {
            return (int) Math.max(1, System.currentTimeMillis() - t0);
        } catch (Exception ignored) {
            return -1;
        } finally { try { s.close(); } catch (IOException ignored) { } }
    }

    /** NetBIOS 名称查询（UDP 137，通配名 *）：Windows 开着“网络发现”时会应答 */
    private boolean nbnsAlive(String ip) {
        java.net.DatagramSocket ds = null;
        try {
            byte[] q = new byte[50];
            q[0] = 0x12; q[1] = 0x34;              /* 会话号 */
            q[2] = 0x00; q[3] = 0x00;              /* flags：查询 */
            q[4] = 0x00; q[5] = 0x01;              /* QDCOUNT = 1 */
            /* 6..11 全 0 */
            q[12] = 0x20;                          /* 名字长度 32 */
            q[13] = 'C'; q[14] = 'K';              /* "*" 的首级编码（0x2A -> C K） */
            for (int i = 15; i < 45; i++) q[i] = 'A';   /* 其余 15 个 0 字节 -> AA */
            q[45] = 0x00;
            q[46] = 0x00; q[47] = 0x21;            /* QTYPE = NBSTAT */
            q[48] = 0x00; q[49] = 0x01;            /* QCLASS = IN */
            ds = new java.net.DatagramSocket();
            ds.setSoTimeout(1200);
            ds.send(new java.net.DatagramPacket(q, q.length,
                    java.net.InetAddress.getByName(ip), 137));
            java.net.DatagramPacket rp = new java.net.DatagramPacket(new byte[512], 512);
            ds.receive(rp);
            return rp.getLength() >= 12;
        } catch (Throwable ignored) {
            return false;
        } finally { if (ds != null) try { ds.close(); } catch (Exception ignored) { } }
    }

    private int tcpResultLoose(String ip, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 500);
            return 1;
        } catch (ConnectException e) {
            return 1;
        } catch (SocketTimeoutException e) {
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
    // ---------------- 数据 ----------------

    private void loadHosts() {
        try {
            hosts = new JSONArray(sp.getString("hosts", "[]"));
        } catch (Exception e) {
            hosts = new JSONArray();
        }
    }

    private void saveHosts() {
        sp.edit().putString("hosts", hosts.toString()).apply();
    }

    private boolean validMac(String s) {
        return s != null && s.matches("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");
    }

    private byte[] macToBytes(String s) {
        if (s == null) return null;
        String up = s.trim().toUpperCase();
        if (!validMac(up)) return null;
        byte[] b = new byte[6];
        String[] parts = up.split(":");
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }

    private byte[] magicPacket(byte[] mac) {
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(mac, 0, pkt, 6 + i * 6, 6);
        return pkt;
    }

    // ---------------- 小工具 ----------------

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable fieldBg() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0xFF0B141B);
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    private class TechBackground extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();

            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{C_BG_TOP, 0xFF0A141B, 0xFF070C11},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            paint.setShader(new RadialGradient(w * 0.82f, -h * 0.02f, h * 0.55f,
                    new int[]{0x2E2FD8FF, 0x0A1D6070, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            paint.setShader(null);
            paint.setColor(0x0C2FD8FF);
            paint.setStrokeWidth(1f);
            float step = dp(46);
            for (float x = 0; x < w; x += step) canvas.drawLine(x, 0, x, h, paint);
            for (float y = 0; y < h; y += step) canvas.drawLine(0, y, w, y, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
