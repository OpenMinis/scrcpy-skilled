package me.openminis.skilled;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;

/**
 * 主屏悬浮小窗：圆角卡片 + 流光边框 + 顶栏(动态标题/接管按钮，可拖动、双指缩放) + 右下角缩放手柄
 * + AI 操作中时居中的「停止 / 接管」胶囊按钮。SurfaceView 显示 scrcpy 虚拟屏的 H.264 流，触摸经控制通道回传。
 */
public class PipService extends Service implements TextureView.SurfaceTextureListener, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    static final int PAD_DP = 14, HEADER_DP = 32, RADIUS_DP = 18;

    WindowManager wm; WindowManager.LayoutParams lp;
    LinearLayout stopPill, takePill, resumePill; FrameLayout root, card; GlowView glow; LinearLayout header, centerPanel; TextureView sv; Surface surf; TextView label; View dot;
    volatile ScrcpyClient client; AgentServer agent; int port = 27183;
    volatile int vw = 540, vh = 960; volatile boolean hidden;
    int winX = 20, winY = 300, contentW; float dens; String lastLabel = "";
    int docked = 0; ArrowTab arrow; static final int SNAP_DP = 28, TAB_DP = 22;
    final Handler ui = new Handler(Looper.getMainLooper());

    int dpi(float v) { return Math.round(v * dens); }
    float dp(float v) { return v * dens; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        dens = getResources().getDisplayMetrics().density;
        int wdp = 200;
        if (i != null) { port = i.getIntExtra("port", port); winX = i.getIntExtra("x", winX); winY = i.getIntExtra("y", winY); wdp = i.getIntExtra("w", wdp); }
        if (sv != null) return START_STICKY;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pip", "pip", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "pip").setContentTitle("scrcpy-skilled").setSmallIcon(android.R.drawable.ic_menu_view).build());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        contentW = dpi(wdp);
        agent = new AgentServer(this);
        buildUi();
        lp = new WindowManager.LayoutParams(10, 10, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(root, lp);
        relayout();
        agent.start();
        ui.post(refresh);
        Log.i(T, "overlay added, port=" + port + ", version=" + agent.versionName());
        return START_STICKY;
    }

    // ---------------- UI ----------------
    GradientDrawable pillBg(int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(40)); g.setColor(fill);
        if (stroke != 0) g.setStroke(Math.max(1, dpi(strokeDp)), stroke);
        return g;
    }

    /** 胶囊按钮：左侧 icon + 文字。 */
    LinearLayout makePill(String text, int iconType, int fill, int stroke, float textSp, int padHdp, int padVdp, boolean bold) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dpi(padHdp), dpi(padVdp), dpi(padHdp + 3), dpi(padVdp));
        l.setBackground(pillBg(fill, stroke, 1f));
        View ic = new IconView(this, iconType);
        int isz = dpi(textSp + 8);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(isz, isz); il.rightMargin = dpi(7);
        l.addView(ic, il);
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.WHITE); tv.setTextSize(textSp); tv.setSingleLine(true); tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    void buildUi() {
        int pad = dpi(PAD_DP);
        root = new FrameLayout(this);

        card = new FrameLayout(this);
        card.setBackgroundColor(0xFF0E0E14);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP)); }
        });
        card.setClipToOutline(true);

        // 顶栏：状态点 + 动态标题（省略号）+ 接管按钮
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.TRANSPARENT);
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(0xFF4F8CFF); dot.setBackground(dg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dpi(7), dpi(7)); dl.leftMargin = dpi(12); dl.rightMargin = dpi(7);
        header.addView(dot, dl);
        label = new TextView(this);
        label.setTextColor(0xFFF2F3F8); label.setShadowLayer(dp(3), 0, dp(1), 0xCC000000); label.setTextSize(10.5f); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 拖动（单指）+ 双指缩放，作用于顶栏
        final ScaleGestureDetector sd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { setContentWidth((int) (contentW * d.getScaleFactor())); return true; }
        });
        final float[] dn = new float[4];
        final View.OnTouchListener drag = (v, e) -> {
            sd.onTouchEvent(e);
            if (sd.isInProgress() || e.getPointerCount() > 1) { dn[0] = e.getRawX(); dn[1] = e.getRawY(); dn[2] = winX; dn[3] = winY; return true; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dn[0] = e.getRawX(); dn[1] = e.getRawY(); dn[2] = winX; dn[3] = winY; return true;
                case MotionEvent.ACTION_MOVE:
                    winX = (int) (dn[2] + e.getRawX() - dn[0]); winY = (int) (dn[3] + e.getRawY() - dn[1]); relayout(); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: maybeDock(); return true;
                default: return true;
            }
        };
        header.setOnTouchListener(drag);
        sv = new TextureView(this);
        sv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP)); }
        });
        sv.setClipToOutline(true);
        sv.setSurfaceTextureListener(this);
        sv.setOnTouchListener((v, e) -> {
            if (docked != 0) return true;
            if (agent != null) agent.humanTouched();   // 人类触摸 → 自动接管
            if (client == null) return true;
            int a = e.getActionMasked(); // 0 down,1 up,2 move
            if (a > 2) a = 1;
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });

        card.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(HEADER_DP), Gravity.TOP));   // 叠在画面上，不占高度
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cl.setMargins(pad, pad, pad, pad);
        root.addView(card, cl);

        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // AI 操作中：居中的「立即停止」(醒目) + 「接管」
        centerPanel = new LinearLayout(this);
        centerPanel.setOrientation(LinearLayout.VERTICAL); centerPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        stopPill = makePill("立即停止", 0, 0xF2EF4444, 0xFFFCA5A5, 15f, 18, 12, true);
        takePill = makePill("接管", 1, 0xE6262A4A, 0xCCFFFFFF, 15f, 18, 12, true);
        resumePill = makePill("交还 AI", 2, 0xE616A34A, 0xFF86EFAC, 15f, 18, 12, true);
        resumePill.setOnClickListener(v -> { agent.setTakeover(false); updateUi(); });
        stopPill.setOnClickListener(v -> { agent.stopByUser(); updateUi(); });
        takePill.setOnClickListener(v -> { agent.setTakeover(true); updateUi(); });
        int pw = dpi(150);
        stopPill.setGravity(Gravity.CENTER); takePill.setGravity(Gravity.CENTER);
        centerPanel.addView(stopPill, new LinearLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT); tl.topMargin = dpi(12);
        centerPanel.addView(takePill, tl);
        centerPanel.addView(resumePill, new LinearLayout.LayoutParams(pw, ViewGroup.LayoutParams.WRAP_CONTENT));
        resumePill.setGravity(Gravity.CENTER); resumePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        centerPanel.setVisibility(View.GONE);
        root.addView(centerPanel, pl);

        // 折叠箭头：折叠后留在屏幕边缘的小拉手，点击展开；展开状态隐藏
        arrow = new ArrowTab(this);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(dpi(TAB_DP), dpi(72), Gravity.CENTER_VERTICAL | Gravity.LEFT);
        arrow.setLayoutParams(al); arrow.setVisibility(View.GONE);
        root.addView(arrow);
        arrow.setOnClickListener(v -> undock());
        final float[] ad = new float[3];
        arrow.setOnTouchListener((v, e) -> {          // 点击展开；纵向拖动可沿边缘调整位置
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: ad[0] = e.getRawY(); ad[1] = winY; ad[2] = 0; return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawY() - ad[0]) > dp(8)) { ad[2] = 1; winY = (int) (ad[1] + e.getRawY() - ad[0]); relayout(); }
                    return true;
                case MotionEvent.ACTION_UP: if (ad[2] == 0) undock(); return true;
                default: return true;
            }
        });

        // 右下角缩放手柄：小尺寸，线条半透明，贴进圆角
        View handle = new ResizeHandle(this);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dpi(26), dpi(26), Gravity.BOTTOM | Gravity.RIGHT);
        hl.setMargins(0, 0, pad + dpi(2), pad + dpi(1));
        root.addView(handle, hl);
        final float[] rs = new float[2];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: rs[0] = e.getRawX(); rs[1] = contentW; return true;
                case MotionEvent.ACTION_MOVE: setContentWidth((int) (rs[1] + (e.getRawX() - rs[0]))); return true;
                default: return true;
            }
        });
    }

    void maybeDock() {
        int sw = getResources().getDisplayMetrics().widthPixels, pad = dpi(PAD_DP);
        int left = winX + pad, right = winX + lp.width - pad;           // 画面可见区左右边
        if (left < dpi(SNAP_DP)) docked = -1; else if (sw - right < dpi(SNAP_DP)) docked = 1; else docked = 0;
        if (docked == 0) { winX = Math.max(-pad / 2, Math.min(sw - lp.width + pad / 2, winX)); }
        animateTo();
    }
    void undock() {
        int sw = getResources().getDisplayMetrics().widthPixels, pad = dpi(PAD_DP), gap = dpi(10);
        // 展开后让可见画面离开边缘 gap 距离，且整体完全在屏内（避免立刻再次吸附）
        if (docked == 1) winX = sw - (lp.width - pad) - gap - pad;
        else winX = gap - pad;
        winX = Math.max(-pad + gap, Math.min(sw - lp.width + pad - gap, winX));
        docked = 0; animateTo();
    }
    /** 平滑动画到目标 x（折叠/展开） */
    void animateTo() {
        if (lp == null) return;
        final int from = lp.x; applyDockedUi();
        int sw = getResources().getDisplayMetrics().widthPixels, pad = dpi(PAD_DP);
        final int to = docked == -1 ? -(lp.width - dpi(TAB_DP) - pad / 2) : docked == 1 ? sw - dpi(TAB_DP) - pad / 2 : winX;
        ValueAnimator a = ValueAnimator.ofInt(from, to); a.setDuration(220);
        a.addUpdateListener(v -> { lp.x = (Integer) v.getAnimatedValue(); try { wm.updateViewLayout(root, lp); } catch (Throwable e) {} });
        a.start();
    }
    void applyDockedUi() {
        if (arrow == null) return;
        arrow.setVisibility(docked != 0 ? View.VISIBLE : View.GONE);
        arrow.dir = docked;                                              // 贴左→箭头朝右；贴右→箭头朝左
        FrameLayout.LayoutParams al = (FrameLayout.LayoutParams) arrow.getLayoutParams();
        al.gravity = Gravity.CENTER_VERTICAL | (docked == -1 ? Gravity.RIGHT : Gravity.LEFT);
        al.rightMargin = docked == -1 ? dpi(PAD_DP) / 2 : 0; al.leftMargin = docked == 1 ? dpi(PAD_DP) / 2 : 0; al.gravity = Gravity.CENTER_VERTICAL | (docked == -1 ? Gravity.RIGHT : Gravity.LEFT);
        arrow.setLayoutParams(al); arrow.invalidate();
        // 折叠时隐藏其它覆盖元素，避免在窄条里露出半截
        int vis = docked != 0 ? View.INVISIBLE : View.VISIBLE;
        card.setVisibility(vis); header.setVisibility(vis); if (glow != null) glow.setVisibility(docked != 0 ? View.INVISIBLE : View.VISIBLE);
        if (docked != 0) centerPanel.setVisibility(View.GONE);
    }

    void setContentWidth(int w) {
        int max = getResources().getDisplayMetrics().widthPixels - 2 * dpi(PAD_DP);
        contentW = Math.max(dpi(110), Math.min(max, w));
        relayout();
    }

    void relayout() {
        if (lp == null || root == null) return;
        int pad = dpi(PAD_DP);
        int ch = contentW * vh / Math.max(1, vw);
        lp.width = contentW + 2 * pad;
        lp.height = ch + 2 * pad;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        winY = Math.max(0, Math.min(sh - lp.height, winY));
        int x = winX;
        if (docked == -1) x = -(lp.width - dpi(TAB_DP) - pad / 2);          // 只留左边缘一条（箭头贴窗口右缘）
        else if (docked == 1) x = sw - dpi(TAB_DP) - pad / 2;               // 只留右边缘一条
        lp.x = hidden ? -lp.width - 50 : x; lp.y = winY;
        if (hidden) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        applyDockedUi();
        try { wm.updateViewLayout(root, lp); } catch (Throwable e) { Log.w(T, "relayout " + e); }
    }

    /** 主线程调用（来自 /window 接口）：x/y(px)、w(dp)、hide。 */
    void applyWindow(Map<String, String> m) {
        if (m.containsKey("x")) winX = Integer.parseInt(m.get("x"));
        if (m.containsKey("y")) winY = Integer.parseInt(m.get("y"));
        if (m.containsKey("w")) contentW = dpi(Integer.parseInt(m.get("w")));
        if (m.containsKey("hide")) hidden = "1".equals(m.get("hide"));
        setContentWidth(contentW);
    }

    final Runnable refresh = new Runnable() { @Override public void run() { updateUi(); ui.postDelayed(this, 250); } };

    void updateUi() {
        if (agent == null) return;
        int st = agent.uiState();   // 0 AI 待命  1 AI 操作中  2 人类操作/已持有控制权
        boolean held = agent.held(), stopped = agent.stopped;
        String t = agent.title == null ? "" : agent.title.trim();
        String text = stopped ? "已停止 AI" : held ? "你已接管" : st == 1 ? (t.isEmpty() ? "AI 操作中" : t) : st == 2 ? "你在操作" : "AI 待命";
        if (!text.equals(lastLabel)) { lastLabel = text; label.setText(text); }
        int c = stopped ? 0xFFEF4444 : st == 2 ? 0xFF34D399 : st == 1 ? 0xFF7C6CFF : 0xFF4F8CFF;
        ((GradientDrawable) dot.getBackground()).setColor(c);
        boolean ai = st == 1 && !held;
        stopPill.setVisibility(ai ? View.VISIBLE : View.GONE);
        takePill.setVisibility(ai ? View.VISIBLE : View.GONE);
        resumePill.setVisibility(held ? View.VISIBLE : View.GONE);
        centerPanel.setVisibility((ai || held) && !hidden && docked == 0 ? View.VISIBLE : View.GONE);
        float sc = Math.max(0.65f, Math.min(1f, contentW / dp(190)));
        if (centerPanel.getScaleX() != sc) { centerPanel.setScaleX(sc); centerPanel.setScaleY(sc); }
    }

    // ---------------- Surface / scrcpy ----------------
    @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
        surf = new Surface(st);
        client = new ScrcpyClient("127.0.0.1", port, surf, this);
        client.start();
    }
    @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
        if (client != null) client.stop(); client = null;
        if (surf != null) { surf.release(); surf = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
    @Override public void onVideoSize(int w, int h) { vw = w; vh = h; ui.post(this::relayout); }
    @Override public void onClosed(String why) {
        Log.w(T, "closed: " + why);
        // 自愈：桥晚于小窗启动或被重启时连接会失败；surface 还在就重连
        ui.postDelayed(() -> { if (sv != null && surf != null && surf.isValid() && (client == null || !client.running)) restartClient(); }, 1500);
    }
    void restartClient() {
        if (client != null) client.stop();
        client = new ScrcpyClient("127.0.0.1", port, surf, this);
        client.start();
    }
    @Override public void onDestroy() {
        ui.removeCallbacks(refresh);
        if (glow != null) glow.stopAnim();
        if (agent != null) agent.stop();
        if (client != null) client.stop();
        if (root != null) try { wm.removeView(root); } catch (Throwable e) {}
    }

    // ---------------- 流光边框 ----------------
    class GlowView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path inner = new Path(); final RectF rc = new RectF(); final Matrix mx = new Matrix();
        PathMeasure pm = new PathMeasure(); float plen = 1f; final float[] pos = new float[2], tan = new float[2];
        // 边框：彗星式色环，首尾同色
        static final float[] POS = {0f, 0.22f, 0.42f, 0.60f, 0.76f, 0.88f, 0.95f, 1f};
        // 内部光团：5 团柔光沿边缘游走（Apple Intelligence 风格），方向/速度各不相同，并向画面内部呼吸式伸缩
        static final int N = 5;
        static final float[] DIR = {1f, -0.8f, 1.35f, -1.1f, 0.9f};
        static final float[] BASE = {0f, 0.2f, 0.4f, 0.6f, 0.8f};
        float phase, prog, curSpeed = 30f, curLoop = 0.08f, curInten = 0.6f, t; long last; boolean inited;
        final int[] cur = new int[8], blob = new int[N]; final RadialGradient[] rg = new RadialGradient[N]; float rgR = -1;
        ValueAnimator ticker;
        GlowView(android.content.Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            ticker = ValueAnimator.ofFloat(0f, 1f); ticker.setDuration(1000); ticker.setRepeatCount(ValueAnimator.INFINITE); ticker.setInterpolator(null);
            ticker.addUpdateListener(a -> invalidate());
            ticker.start();
        }
        void stopAnim() { if (ticker != null) ticker.cancel(); }
        int[] palette(boolean stopped, int st) {
            if (stopped) return new int[]{0x55FB7185, 0x88EF4444, 0xBBF97316, 0xE6FB923C, 0xFFFBBF24, 0xFFFFFFFF, 0x99F97316, 0x55FB7185};
            if (st == 2) return new int[]{0x555EEAD4, 0x8834D399, 0xBB22C55E, 0xE64ADE80, 0xFF86EFAC, 0xFFFFFFFF, 0x9934D399, 0x555EEAD4};
            return new int[]{0x554FD8FF, 0x885B9BFF, 0xBB9B5CFF, 0xE6C76BFF, 0xFFFF5CAA, 0xFFFFFFFF, 0x99FF8CC8, 0x554FD8FF};
        }
        int[] blobs(boolean stopped, int st) {
            if (stopped) return new int[]{0xFFEF4444, 0xFFF97316, 0xFFFB7185, 0xFFFBBF24, 0xFFE11D48};
            if (st == 2) return new int[]{0xFF34D399, 0xFF22D3EE, 0xFF86EFAC, 0xFF2DD4BF, 0xFF4ADE80};
            return new int[]{0xFFFF9F43, 0xFFFF5CAA, 0xFFB46BFF, 0xFF5B9BFF, 0xFF4FE3D8};   // 橙、粉、紫、蓝、青
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = dp(PAD_DP), r = dp(RADIUS_DP);
            rc.set(pad, pad, w - pad, h - pad);
            inner.reset(); inner.addRoundRect(rc, r, r, Path.Direction.CW);
            pm.setPath(inner, true); plen = Math.max(1f, pm.getLength());
            rgR = -1;
        }
        void buildBlobShaders() {
            float R = Math.min(rc.width(), rc.height()) * 0.62f;
            if (R == rgR) return; rgR = R;
            for (int i = 0; i < N; i++)
                rg[i] = new RadialGradient(0, 0, R, new int[]{(blob[i] & 0xFFFFFF) | 0xCC000000, (blob[i] & 0xFFFFFF) | 0x66000000, (blob[i] & 0xFFFFFF) | 0x1A000000, blob[i] & 0xFFFFFF},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1e9f); last = now; t += dt;
            int st = agent == null ? 0 : agent.uiState();
            boolean stopped = agent != null && agent.stopped;
            // 速度：边框(度/秒)、光团(圈/秒)、强度
            float tSpeed = st == 1 ? 110f : st == 2 ? 60f : 34f;
            float tLoop  = st == 1 ? 0.22f : st == 2 ? 0.12f : 0.07f;     // 操作中约 4.5 秒绕一圈
            float tInten = st == 1 ? 1f : st == 2 ? 0.85f : 0.6f;
            int[] tgt = palette(stopped, st), tb = blobs(stopped, st);
            if (!inited) { System.arraycopy(tgt, 0, cur, 0, 8); System.arraycopy(tb, 0, blob, 0, N); inited = true; }
            float k = Math.min(1f, dt * 2.5f);
            curSpeed += (tSpeed - curSpeed) * k; curLoop += (tLoop - curLoop) * k; curInten += (tInten - curInten) * k;
            boolean changed = false;
            for (int i = 0; i < cur.length; i++) cur[i] = lerpColor(cur[i], tgt[i], k);
            cur[cur.length - 1] = cur[0];
            for (int i = 0; i < N; i++) { int nc = lerpColor(blob[i], tb[i], k); if (nc != blob[i]) changed = true; blob[i] = nc; }
            if (changed) rgR = -1;
            phase = (phase + curSpeed * dt) % 360f;
            prog += curLoop * dt; if (prog > 1000f) prog -= 1000f;
            if (rc.width() <= 0) return;
            buildBlobShaders();

            c.save(); c.clipPath(inner);
            // ① 内部光团：沿边缘游走，同时向画面中心呼吸式内收，覆盖整块屏幕
            float cx = rc.centerX(), cy = rc.centerY();
            for (int i = 0; i < N; i++) {
                float u = (BASE[i] + DIR[i] * prog) % 1f; if (u < 0) u += 1f;
                pm.getPosTan(u * plen, pos, tan);
                float in = 0.16f + 0.20f * (0.5f + 0.5f * (float) Math.sin(t * (1.1f + 0.35f * i) + i * 1.7f));   // 向中心内收比例
                float px = pos[0] + (cx - pos[0]) * in, py = pos[1] + (cy - pos[1]) * in;
                bp.setShader(rg[i]);
                bp.setAlpha((int) (255 * Math.min(1f, curInten) * 0.62f));
                c.save(); c.translate(px, py); c.drawCircle(0, 0, rgR, bp); c.restore();
            }
            // ② 紧贴边缘的一圈亮带，让色彩从边框向内自然扩散
            SweepGradient sg = new SweepGradient(getWidth() / 2f, getHeight() / 2f, cur, POS);
            mx.setRotate(phase, getWidth() / 2f, getHeight() / 2f); sg.setLocalMatrix(mx);
            p.setShader(sg);
            for (int i = 9; i >= 1; i--) {
                p.setStrokeWidth(2f * dp(1.0f + 1.7f * i));
                p.setAlpha((int) (255 * Math.min(1f, curInten) * 0.05f));
                c.drawRoundRect(rc, dp(RADIUS_DP), dp(RADIUS_DP), p);
            }
            c.restore();
            // ③ 锐利的旋转边框
            p.setShader(sg); p.setStrokeWidth(dp(2.6f)); p.setAlpha(255);
            c.drawRoundRect(rc, dp(RADIUS_DP), dp(RADIUS_DP), p);
        }
        int lerpColor(int a, int b, float t) {
            int ar = a >>> 24, rr = (a >> 16) & 255, gg = (a >> 8) & 255, bb = a & 255;
            int br = b >>> 24, r2 = (b >> 16) & 255, g2 = (b >> 8) & 255, b2 = b & 255;
            return ((int) (ar + (br - ar) * t) << 24) | ((int) (rr + (r2 - rr) * t) << 16) | ((int) (gg + (g2 - gg) * t) << 8) | (int) (bb + (b2 - bb) * t);
        }
    }

    // ---------------- 折叠后的箭头拉手 ----------------
    class ArrowTab extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ar = new Paint(Paint.ANTI_ALIAS_FLAG); int dir = -1; final Path pth = new Path();
        ArrowTab(android.content.Context c) {
            super(c);
            bg.setColor(0xE61B1D2E);
            ar.setColor(0xFFFFFFFF); ar.setStyle(Paint.Style.STROKE); ar.setStrokeWidth(dp(2.2f)); ar.setStrokeCap(Paint.Cap.ROUND); ar.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = dp(11);
            // 贴屏幕边缘一侧为直边，外侧为圆角，像一个从边缘探出的小标签
            RectF rc = new RectF(0, 0, w, h);
            pth.reset();
            if (dir == -1) { pth.addRoundRect(rc, new float[]{0, 0, r, r, r, r, 0, 0}, Path.Direction.CW); }
            else { pth.addRoundRect(rc, new float[]{r, r, 0, 0, 0, 0, r, r}, Path.Direction.CW); }
            c.drawPath(pth, bg);
            float cx = w / 2f, cy = h / 2f, a = dp(5);
            float sgn = dir == -1 ? 1f : -1f;                                  // 贴左→指向右（展开方向）
            Path ch = new Path();
            ch.moveTo(cx - sgn * a * 0.5f, cy - a); ch.lineTo(cx + sgn * a * 0.5f, cy); ch.lineTo(cx - sgn * a * 0.5f, cy + a);
            c.drawPath(ch, ar);
        }
    }

    // ---------------- 右下角缩放手柄：小、半透明，贴角 ----------------
    class ResizeHandle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ResizeHandle(android.content.Context c) { super(c); p.setColor(0x66FFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.4f)); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w - dp(4), h - dp(9), w - dp(9), h - dp(4), p);
            c.drawLine(w - dp(4), h - dp(14), w - dp(14), h - dp(4), p);
        }
    }

    // ---------------- 胶囊按钮 icon ----------------
    class IconView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final int type; final Path path = new Path();
        IconView(android.content.Context c, int type) { super(c); this.type = type; p.setColor(Color.WHITE); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (type == 0) {                                           // 停止：圆环 + 实心圆角方块
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.085f);
                c.drawCircle(w / 2f, h / 2f, w * 0.44f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(w * 0.33f, h * 0.33f, w * 0.67f, h * 0.67f), w * 0.07f, w * 0.07f, p);
            } else if (type == 2) {                                    // 交还：圆环 + 播放三角
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.085f);
                c.drawCircle(w / 2f, h / 2f, w * 0.44f, p);
                p.setStyle(Paint.Style.FILL); path.reset();
                path.moveTo(w * 0.42f, h * 0.32f); path.lineTo(w * 0.70f, h * 0.50f); path.lineTo(w * 0.42f, h * 0.68f); path.close();
                c.drawPath(path, p);
            } else {                                                   // 接管：圆角屏幕框 + 光标
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.085f);
                c.drawRoundRect(new RectF(w * 0.07f, h * 0.15f, w * 0.93f, h * 0.85f), w * 0.17f, w * 0.17f, p);
                p.setStyle(Paint.Style.FILL); path.reset();
                path.moveTo(w * 0.36f, h * 0.30f); path.lineTo(w * 0.36f, h * 0.70f); path.lineTo(w * 0.47f, h * 0.60f);
                path.lineTo(w * 0.55f, h * 0.76f); path.lineTo(w * 0.63f, h * 0.72f); path.lineTo(w * 0.55f, h * 0.57f);
                path.lineTo(w * 0.69f, h * 0.55f); path.close();
                c.drawPath(path, p);
            }
        }
    }
}
