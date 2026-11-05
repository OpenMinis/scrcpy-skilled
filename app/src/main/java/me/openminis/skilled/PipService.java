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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;

/**
 * 主屏悬浮小窗。
 * 布局（全部叠在画面之上，不占额外高度）：
 *   card(圆角裁剪) ─ TextureView 副屏画面
 *                  ├ GlowView：由外向内的渐变光晕 + 旋转彩虹边框
 *                  ├ header：状态点 + 动态标题（无底色；拖动窗口 / 双指缩放）
 *                  ├ hint：中央「点击任意位置接管」，与光晕同步闪烁
 *                  ├ 右上角：红色「停止」/ 绿色「交还 AI」
 *                  └ 右下角：小号半透明缩放手柄
 *   arrow：贴边折叠后的箭头拉手
 */
public class PipService extends Service implements TextureView.SurfaceTextureListener, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    static final int HEADER_DP = 30, RADIUS_DP = 20, SNAP_DP = 28, TAB_DP = 22;

    WindowManager wm; WindowManager.LayoutParams lp;
    FrameLayout root, card; TextureView sv; Surface surf;
    GlowView glow; LinearLayout header, stopPill, resumePill; TextView label, hint; View dot, handle; ArrowTab arrow;
    volatile ScrcpyClient client; AgentServer agent; int port = 27183;
    volatile int vw = 540, vh = 960; volatile boolean hidden;
    int winX = 20, winY = 300, contentW, docked; float dens; String lastLabel = "";
    final Handler ui = new Handler(Looper.getMainLooper());

    int dpi(float v) { return Math.round(v * dens); }
    float dp(float v) { return v * dens; }
    FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }
    FrameLayout.LayoutParams wrap(int gravity) { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        dens = getResources().getDisplayMetrics().density;
        int wdp = 168;
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(root, lp);
        relayout();
        agent.start();
        ui.post(refresh);
        Log.i(T, "overlay added, port=" + port + ", version=" + agent.versionName());
        return START_STICKY;
    }

    // ======================= UI 构建 =======================
    void buildUi() {
        root = new FrameLayout(this);

        card = new FrameLayout(this);
        card.setBackgroundColor(0xFF0B0B12);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP)); }
        });
        card.setClipToOutline(true);                       // TextureView 可被圆角裁剪（SurfaceView 不行）
        root.addView(card, match());

        // ① 副屏画面
        sv = new TextureView(this);
        sv.setSurfaceTextureListener(this);
        // AI 操作中：整块画面 = 悬浮窗的拖动区。拖动 → 移动窗口（松手靠边则吸附折叠）；轻点 → 接管。都不传给副屏。
        // 非 AI 操作中（待命 / 人已接管）：触摸正常传给副屏。
        final float[] dgr = new float[5];          // downRawX, downRawY, winX0, winY0, moved
        final boolean[] eat = {false};
        sv.setOnTouchListener((v, e) -> {
            if (docked != 0) return true;
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                eat[0] = agent != null && agent.uiState() == 1 && !agent.held();
                dgr[0] = e.getRawX(); dgr[1] = e.getRawY(); dgr[2] = winX; dgr[3] = winY; dgr[4] = 0;
            }
            if (eat[0]) {
                switch (act) {
                    case MotionEvent.ACTION_MOVE:
                        if (dgr[4] == 0 && Math.hypot(e.getRawX() - dgr[0], e.getRawY() - dgr[1]) > dp(8)) dgr[4] = 1;
                        if (dgr[4] == 1) { winX = (int) (dgr[2] + e.getRawX() - dgr[0]); winY = (int) (dgr[3] + e.getRawY() - dgr[1]); relayout(); }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (dgr[4] == 1) maybeDock(); else { agent.setTakeover(true); updateUi(); }
                        eat[0] = false; break;
                    case MotionEvent.ACTION_CANCEL:
                        if (dgr[4] == 1) maybeDock();
                        eat[0] = false; break;
                }
                return true;
            }
            if (agent != null) agent.humanTouched();
            if (client == null) return true;
            int a = act > 2 ? 1 : act;                        // 0 down, 1 up, 2 move
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });
        card.addView(sv, match());

        // ② 光晕 + 边框（不消费触摸，事件落到画面）
        glow = new GlowView(this);
        card.addView(glow, match());

        // ③ 标题：无底色，叠在画面顶部；拖动窗口、双指缩放
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(glassPill());
        header.setPadding(0, dpi(4), dpi(11), dpi(4));
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(0xFF4F8CFF); dot.setBackground(dg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dpi(7), dpi(7)); dl.leftMargin = dpi(10); dl.rightMargin = dpi(6);
        header.addView(dot, dl);
        label = new TextView(this);
        label.setTextColor(0xFFF4F5FA); label.setTextSize(10.5f); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(3), 0, dp(1), 0xDD000000);
        label.setMaxWidth(dpi(150));
        header.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ScaleGestureDetector sd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { setContentWidth((int) (contentW * d.getScaleFactor())); return true; }
        });
        final float[] dn = new float[4];
        header.setOnTouchListener((v, e) -> {
            sd.onTouchEvent(e);
            if (sd.isInProgress() || e.getPointerCount() > 1) { dn[0] = e.getRawX(); dn[1] = e.getRawY(); dn[2] = winX; dn[3] = winY; return true; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dn[0] = e.getRawX(); dn[1] = e.getRawY(); dn[2] = winX; dn[3] = winY; return true;
                case MotionEvent.ACTION_MOVE: winX = (int) (dn[2] + e.getRawX() - dn[0]); winY = (int) (dn[3] + e.getRawY() - dn[1]); relayout(); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: maybeDock(); return true;
                default: return true;
            }
        });
        FrameLayout.LayoutParams hl = wrap(Gravity.TOP | Gravity.LEFT);
        hl.topMargin = dpi(8); hl.leftMargin = dpi(10);
        card.addView(header, hl);

        // ④ 中央提示：与光晕同一节奏闪烁；本身不可点击（点击由画面处理）
        hint = new TextView(this);
        hint.setText("点击任意位置接管"); hint.setTextColor(Color.WHITE); hint.setTextSize(16); hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setLetterSpacing(0.08f); hint.setSingleLine(true); hint.setIncludeFontPadding(false);
        hint.setShadowLayer(dp(8), 0, 0, 0xCCB46BFF);
        hint.setBackground(glassPill()); hint.setPadding(dpi(16), dpi(9), dpi(16), dpi(9));
        hint.setVisibility(View.GONE);
        card.addView(hint, wrap(Gravity.CENTER));

        // ⑤ 右上角：停止 = 红色圆形图标按钮；交还 AI = 绿色胶囊
        stopPill = makeRoundButton(3, 0xF2EF4444, 0xFFFCA5A5);
        resumePill = makePill("交还 AI", 2, 0xEB16A34A, 0xFF86EFAC);
        stopPill.setOnClickListener(v -> { agent.stopByUser(); updateUi(); });
        resumePill.setOnClickListener(v -> { agent.setTakeover(false); updateUi(); });
        for (View b : new View[]{stopPill, resumePill}) {
            FrameLayout.LayoutParams bl = wrap(Gravity.TOP | Gravity.RIGHT); bl.topMargin = dpi(8); bl.rightMargin = dpi(10);
            b.setVisibility(View.GONE); card.addView(b, bl);
        }

        // ⑥ 右下角缩放手柄：小、半透明、贴角
        handle = new ResizeHandle(this);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(dpi(22), dpi(22), Gravity.BOTTOM | Gravity.RIGHT);
        card.addView(handle, rl);
        final float[] rs = new float[2];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: rs[0] = e.getRawX(); rs[1] = contentW; return true;
                case MotionEvent.ACTION_MOVE: setContentWidth((int) (rs[1] + (e.getRawX() - rs[0]))); return true;
                default: return true;
            }
        });

        // ⑦ 贴边折叠后的箭头拉手（在 card 外，折叠时 card 隐藏）
        arrow = new ArrowTab(this);
        arrow.setVisibility(View.GONE);
        root.addView(arrow, new FrameLayout.LayoutParams(dpi(TAB_DP), dpi(72), Gravity.CENTER_VERTICAL | Gravity.RIGHT));
        final float[] ad = new float[3];
        arrow.setOnTouchListener((v, e) -> {              // 点击展开；上下拖动可沿边缘移动
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: ad[0] = e.getRawY(); ad[1] = winY; ad[2] = 0; return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawY() - ad[0]) > dp(8)) { ad[2] = 1; winY = (int) (ad[1] + e.getRawY() - ad[0]); relayout(); }
                    return true;
                case MotionEvent.ACTION_UP: if (ad[2] == 0) undock(); return true;
                default: return true;
            }
        });
    }

    /** 玻璃胶囊：半透明暗底 + 由上到下渐变（顶部微亮）+ 细白描边，模拟毛玻璃 */
    GradientDrawable glassPill() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C3A3F5C, 0x99141625});
        g.setCornerRadius(dp(40)); g.setStroke(Math.max(1, dpi(0.8f)), 0x55FFFFFF);
        return g;
    }

    /** 圆形图标按钮（无文字）：实心圆底 + 细描边 + 居中白色图标 */
    LinearLayout makeRoundButton(int icon, int fill, int stroke) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER);
        int size = dpi(30);
        l.setMinimumWidth(size); l.setMinimumHeight(size);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill});
        g.setShape(GradientDrawable.OVAL); g.setStroke(Math.max(1, dpi(0.8f)), stroke);
        l.setBackground(g);
        l.setContentDescription("停止 AI");
        l.addView(new IconView(this, icon), new LinearLayout.LayoutParams(dpi(14), dpi(14)));
        return l;
    }

    LinearLayout makePill(String text, int icon, int fill, int stroke) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dpi(9), dpi(5), dpi(11), dpi(5));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill}); g.setCornerRadius(dp(40)); g.setStroke(Math.max(1, dpi(0.8f)), stroke);
        l.setBackground(g);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dpi(16), dpi(16)); il.rightMargin = dpi(5);
        l.addView(new IconView(this, icon), il);
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.WHITE); tv.setTextSize(12); tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setSingleLine(true); tv.setIncludeFontPadding(false);
        l.addView(tv);
        return l;
    }

    static int lighten(int c) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        r = r + (255 - r) / 5; g = g + (255 - g) / 5; b = b + (255 - b) / 5;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ======================= 尺寸 / 位置 / 吸附 =======================
    int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    void setContentWidth(int w) {
        int maxW = Math.min(screenW(), (int) (screenH() * 0.92f * vw / Math.max(1, vh)));
        contentW = Math.max(dpi(110), Math.min(maxW, w));
        relayout();
    }

    int targetX() {
        if (docked == -1) return -(lp.width - dpi(TAB_DP));      // 只露出左边缘一条（拉手）
        if (docked == 1) return screenW() - dpi(TAB_DP);          // 只露出右边缘一条
        return winX;
    }

    void relayout() {
        if (lp == null || root == null) return;
        lp.width = contentW;
        lp.height = contentW * vh / Math.max(1, vw);
        winY = Math.max(0, Math.min(screenH() - lp.height, winY));
        lp.x = hidden ? -lp.width - 50 : targetX(); lp.y = winY;
        if (hidden) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        applyDockedUi();
        try { wm.updateViewLayout(root, lp); } catch (Throwable e) { Log.w(T, "relayout " + e); }
    }

    /** 松手时：靠近左右边缘就折叠吸附，否则留在屏内 */
    void maybeDock() {
        int sw = screenW(), snap = dpi(SNAP_DP);
        if (winX < snap) docked = -1;
        else if (sw - (winX + lp.width) < snap) docked = 1;
        else { docked = 0; winX = Math.max(0, Math.min(sw - lp.width, winX)); }
        animateTo();
    }

    void undock() {
        int gap = dpi(12);
        winX = docked == 1 ? screenW() - lp.width - gap : gap;   // 离边缘一点，避免立刻再次吸附
        docked = 0; animateTo();
    }

    void animateTo() {
        if (lp == null) return;
        applyDockedUi();
        ValueAnimator a = ValueAnimator.ofInt(lp.x, targetX()); a.setDuration(220);
        a.addUpdateListener(v -> { lp.x = (Integer) v.getAnimatedValue(); try { wm.updateViewLayout(root, lp); } catch (Throwable e) {} });
        a.start();
        updateUi();
    }

    void applyDockedUi() {
        if (arrow == null) return;
        boolean d = docked != 0;
        card.setVisibility(d ? View.INVISIBLE : View.VISIBLE);
        arrow.setVisibility(d ? View.VISIBLE : View.GONE);
        arrow.dir = docked;
        FrameLayout.LayoutParams al = (FrameLayout.LayoutParams) arrow.getLayoutParams();
        al.gravity = Gravity.CENTER_VERTICAL | (docked == -1 ? Gravity.RIGHT : Gravity.LEFT);
        arrow.setLayoutParams(al); arrow.invalidate();
    }

    /** 来自 /window 接口（主线程）：x/y(px)、w(dp)、hide */
    void applyWindow(Map<String, String> m) {
        if (m.containsKey("x")) { winX = Integer.parseInt(m.get("x")); docked = 0; }
        if (m.containsKey("y")) winY = Integer.parseInt(m.get("y"));
        if (m.containsKey("w")) contentW = dpi(Integer.parseInt(m.get("w")));
        if (m.containsKey("hide")) hidden = "1".equals(m.get("hide"));
        setContentWidth(contentW);
    }

    // ======================= 状态 → 界面 =======================
    final Runnable refresh = new Runnable() { @Override public void run() { updateUi(); ui.postDelayed(this, 250); } };

    void updateUi() {
        if (agent == null || label == null) return;
        int st = agent.uiState();                 // 0 待命  1 AI 操作中  2 人类操作/已持有
        boolean held = agent.held(), stopped = agent.stopped;
        String t = agent.title == null ? "" : agent.title.trim();
        String text = stopped ? "已停止 AI" : held ? "你已接管" : st == 1 ? (t.isEmpty() ? "AI 操作中" : t) : st == 2 ? "你在操作" : "AI 待命";
        if (!text.equals(lastLabel)) { lastLabel = text; label.setText(text); }
        ((GradientDrawable) dot.getBackground()).setColor(stopped ? 0xFFEF4444 : st == 2 ? 0xFF34D399 : st == 1 ? 0xFFB46BFF : 0xFF4F8CFF);
        boolean show = !hidden && docked == 0, ai = st == 1 && !held;
        hint.setVisibility(ai && show ? View.VISIBLE : View.GONE);
        stopPill.setVisibility(ai && show ? View.VISIBLE : View.GONE);
        resumePill.setVisibility(held && show ? View.VISIBLE : View.GONE);
        float sc = Math.max(0.7f, Math.min(1f, contentW / dp(190)));
        if (hint.getScaleX() != sc) { hint.setScaleX(sc); hint.setScaleY(sc); }
    }

    // ======================= Surface / scrcpy =======================
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surf = new Surface(st);
        client = new ScrcpyClient("127.0.0.1", port, surf, this);
        client.start();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (client != null) client.stop(); client = null;
        if (surf != null) { surf.release(); surf = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    @Override public void onVideoSize(int w, int h) { vw = w; vh = h; ui.post(() -> setContentWidth(contentW)); }
    @Override public void onClosed(String why) {
        Log.w(T, "closed: " + why);
        // 自愈：桥晚于小窗启动或被重启时连接会失败；surface 还在就重连
        ui.postDelayed(() -> { if (surf != null && surf.isValid() && (client == null || !client.running)) restartClient(); }, 1500);
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

    // ======================= 光晕 + 旋转彩虹边框 =======================
    /**
     * Android 13+：一个 AGSL 着色器在 GPU 上一次画完全部效果（每帧零分配）：
     *   · 边缘光：彩虹色从四周边缘向中心平滑衰减，覆盖大半个画面；
     *   · 由外到内的光波：AI 操作中每 1.6 秒一次，光从边缘涌入、向中心推进并淡出（循环无缝）；
     *   · 旋转彩虹边框 + 绕行的白色高光。
     * 中央提示文字与光波共用同一个亮度包络。低于 Android 13 时退化为旋转边框 + 简单边缘光。
     */
    static final String GLOW_AGSL =
            "uniform float2 size; uniform float radius; uniform float bw;\n" +
            "uniform float rot; uniform float rot2; uniform float wave; uniform float amp; uniform float inten; uniform float active; uniform float time;\n" +
            "layout(color) uniform half4 c0; layout(color) uniform half4 c1; layout(color) uniform half4 c2;\n" +
            "layout(color) uniform half4 c3; layout(color) uniform half4 c4; layout(color) uniform half4 c5;\n" +
            "float sdRR(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n" +
            "half3 pal(float a) {\n" +
            "  float x = fract(a) * 6.0; half3 c = c0.rgb;\n" +
            "  c = mix(c, c1.rgb, half(smoothstep(0.0, 1.0, clamp(x - 0.0, 0.0, 1.0))));\n" +
            "  c = mix(c, c2.rgb, half(smoothstep(0.0, 1.0, clamp(x - 1.0, 0.0, 1.0))));\n" +
            "  c = mix(c, c3.rgb, half(smoothstep(0.0, 1.0, clamp(x - 2.0, 0.0, 1.0))));\n" +
            "  c = mix(c, c4.rgb, half(smoothstep(0.0, 1.0, clamp(x - 3.0, 0.0, 1.0))));\n" +
            "  c = mix(c, c5.rgb, half(smoothstep(0.0, 1.0, clamp(x - 4.0, 0.0, 1.0))));\n" +
            "  c = mix(c, c0.rgb, half(smoothstep(0.0, 1.0, clamp(x - 5.0, 0.0, 1.0))));\n" +
            "  return c; }\n" +
            "half4 main(float2 fc) {\n" +
            "  float2 p = fc - size * 0.5;\n" +
            "  float d = max(0.0, -sdRR(p, size * 0.5, radius));            // 到边缘的距离（px）\n" +
            "  float n = clamp(d / (min(size.x, size.y) * 0.5), 0.0, 1.0);   // 0=边缘 1=中心\n" +
            "  float ang = atan(p.y, p.x) / 6.2831853 + 0.5;\n" +
            "  half3 col = pal(ang + rot + 0.12 * n);                          // 越往内颜色略微旋开，更有流动感\n" +
            "  float edge = exp(-n * 4.2) * 0.62 + exp(-n * 1.6) * 0.14;      // 常驻：边缘亮，向内柔和延伸\n" +
            "  float front = wave * 0.95;                                      // 光波前沿：0 边缘 → 中心\n" +
            "  float band = exp(-pow((n - front) / 0.14, 2.0));                // 前沿亮带\n" +
            "  float trail = smoothstep(front + 0.05, front - 0.35, n) * 0.38; // 前沿身后的余光\n" +
            "  float a = inten * (edge + active * amp * (band * 0.55 + trail));\n" +
            "  a = clamp(a, 0.0, 0.72);                                         // 上限，保证画面始终可读\n" +
            "  // 旋转边框 + 绕行高光\n" +
            "  float ring = 1.0 - smoothstep(bw - 1.0, bw + 0.6, d);\n" +
            "  float hl = pow(max(0.0, cos(6.2831853 * (ang - rot2))), 60.0);\n" +
            "  half3 rc = mix(pal(ang + rot), half3(1.0), half(hl * 0.85));\n" +
            "  float ra = ring * (0.8 + 0.2 * inten);\n" +
            "  half3 outc = rc * half(ra) + col * half(a * (1.0 - ra));\n" +
            "  return half4(outc, half(ra + a * (1.0 - ra)));\n" +
            "}\n";

    class GlowView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), gl = new Paint(Paint.ANTI_ALIAS_FLAG), ep = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rc = new RectF(), rb = new RectF(); final Matrix mx = new Matrix(), mx2 = new Matrix();
        static final float[] POS = {0f, 0.17f, 0.34f, 0.5f, 0.67f, 0.84f, 1f};
        final int[] cur = new int[7]; boolean inited;
        float rot, wave, t, curSpeed = 18f, curPeriod = 4f, curInten = 0.45f, curActive; long last;
        ValueAnimator ticker;
        Object rs;                                   // android.graphics.RuntimeShader（API 33+）
        final Paint sp = new Paint();
        SweepGradient fbRing, fbHl; int[] fbCols = new int[7]; boolean loggedPath;

        GlowView(android.content.Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE); gl.setStyle(Paint.Style.STROKE); gl.setStrokeCap(Paint.Cap.ROUND);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try { rs = new RuntimeShader(GLOW_AGSL); sp.setShader((RuntimeShader) rs); }
                catch (Throwable e) { Log.w(T, "AGSL unavailable: " + e); rs = null; }
            }
            ticker = ValueAnimator.ofFloat(0f, 1f); ticker.setDuration(1000); ticker.setRepeatCount(ValueAnimator.INFINITE); ticker.setInterpolator(null);
            ticker.addUpdateListener(a -> invalidate());
            ticker.start();
        }
        void stopAnim() { if (ticker != null) ticker.cancel(); }

        int[] palette(boolean stopped, int st) {   // 首尾同色，色环闭合
            if (stopped) return new int[]{0xFFEF4444, 0xFFF97316, 0xFFFB7185, 0xFFFBBF24, 0xFFE11D48, 0xFFF97316, 0xFFEF4444};
            if (st == 2) return new int[]{0xFF34D399, 0xFF22D3EE, 0xFF86EFAC, 0xFF2DD4BF, 0xFF4ADE80, 0xFF5EEAD4, 0xFF34D399};
            return new int[]{0xFF3D8BFF, 0xFFB45CFF, 0xFFFF4D8D, 0xFFFF9A3D, 0xFF38D9F5, 0xFF7C6CFF, 0xFF3D8BFF};
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            rc.set(0, 0, w, h);
            float s = dp(2.4f) / 2f; rb.set(s, s, w - s, h - s);
        }

        @Override protected void onDraw(Canvas c) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1e9f); last = now; t += dt;
            int st = agent == null ? 0 : agent.uiState();
            boolean stopped = agent != null && agent.stopped;
            // 目标参数：边框旋转(度/秒)、闪烁周期(秒)、强度
            float tSpeed = st == 1 ? 72f : st == 2 ? 30f : 18f;
            float tPeriod = st == 1 ? 1.8f : st == 2 ? 3f : 4.2f;
            float tInten = stopped ? 0.8f : st == 1 ? 1f : st == 2 ? 0.7f : 0.45f;
            int[] tgt = palette(stopped, st);
            if (!inited) { System.arraycopy(tgt, 0, cur, 0, cur.length); inited = true; }
            float k = Math.min(1f, dt * 3f);                       // 约 0.35 秒平滑过渡
            curSpeed += (tSpeed - curSpeed) * k; curPeriod += (tPeriod - curPeriod) * k; curInten += (tInten - curInten) * k;
            for (int i = 0; i < cur.length; i++) cur[i] = lerp(cur[i], tgt[i], k);
            cur[cur.length - 1] = cur[0];
            rot = (rot + curSpeed * dt) % 360f;
            wave = (wave + dt / curPeriod) % 1f;                    // 连续相位，切换速度也不跳变
            curActive += ((st == 1 ? 1f : 0.35f) - curActive) * k;  // 光波强度：操作中满额，其它状态弱一些

            float w = getWidth(), h = getHeight(), r = dp(RADIUS_DP);
            if (w <= 0 || h <= 0) return;
            // 光波亮度包络：前沿推进时先亮后暗，wave=0/1 时为 0 → 循环处无缝
            float env = (float) Math.sin(Math.PI * wave); env = env * env;

            if (rs != null) {
                if (!loggedPath) { loggedPath = true; Log.i(T, "glow path=agsl hw=" + c.isHardwareAccelerated() + " size=" + w + "x" + h); }
                RuntimeShader s = (RuntimeShader) rs;
                s.setFloatUniform("size", w, h);
                s.setFloatUniform("radius", r);
                s.setFloatUniform("bw", dp(2.4f));
                s.setFloatUniform("rot", rot / 360f);
                s.setFloatUniform("rot2", (rot * 1.6f / 360f) % 1f);
                s.setFloatUniform("wave", wave);
                s.setFloatUniform("amp", env);
                s.setFloatUniform("inten", curInten);
                s.setFloatUniform("active", curActive);
                s.setFloatUniform("time", t);
                s.setColorUniform("c0", cur[0]); s.setColorUniform("c1", cur[1]); s.setColorUniform("c2", cur[2]);
                s.setColorUniform("c3", cur[3]); s.setColorUniform("c4", cur[4]); s.setColorUniform("c5", cur[5]);
                c.drawRect(0, 0, w, h, sp);
            } else {
                if (!loggedPath) { loggedPath = true; Log.i(T, "glow path=fallback sdk=" + android.os.Build.VERSION.SDK_INT); }
                drawFallback(c, w, h, r);
            }

            // 中央文字与光波同步
            if (hint != null && hint.getVisibility() == View.VISIBLE) hint.setAlpha(0.4f + 0.6f * env);
        }

        /** Android 13 以下：旋转彩虹边框 + 绕行高光（着色器按需重建，不每帧分配矩阵） */
        void drawFallback(Canvas c, float w, float h, float r) {
            boolean changed = fbRing == null;
            for (int i = 0; i < 7 && !changed; i++) if (fbCols[i] != cur[i]) changed = true;
            if (changed) {
                System.arraycopy(cur, 0, fbCols, 0, 7);
                fbRing = new SweepGradient(w / 2f, h / 2f, fbCols, POS);
                fbHl = new SweepGradient(w / 2f, h / 2f, new int[]{0x00FFFFFF, 0x00FFFFFF, 0xE6FFFFFF, 0x00FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.80f, 0.88f, 0.96f, 1f});
            }
            mx.setRotate(rot, w / 2f, h / 2f); fbRing.setLocalMatrix(mx);
            mx2.setRotate(rot * 1.6f, w / 2f, h / 2f); fbHl.setLocalMatrix(mx2);
            p.setShader(fbRing);
            for (int i = 6; i >= 1; i--) {                          // 简易边缘光
                p.setStrokeWidth(dp(6f) * i); p.setAlpha((int) (255 * curInten * 0.06f));
                c.drawRoundRect(rc, r, r, p);
            }
            p.setStrokeWidth(dp(2.4f)); p.setAlpha(230); c.drawRoundRect(rb, r, r, p);
            gl.setShader(fbHl); gl.setStrokeWidth(dp(2.4f)); gl.setAlpha((int) (255 * curInten)); c.drawRoundRect(rb, r, r, gl);
        }

        int lerp(int a, int b, float t) {
            int ar = a >>> 24, rr = (a >> 16) & 255, gg = (a >> 8) & 255, bb = a & 255;
            int br = b >>> 24, r2 = (b >> 16) & 255, g2 = (b >> 8) & 255, b2 = b & 255;
            return ((int) (ar + (br - ar) * t) << 24) | ((int) (rr + (r2 - rr) * t) << 16) | ((int) (gg + (g2 - gg) * t) << 8) | (int) (bb + (b2 - bb) * t);
        }
    }

    // ======================= 小部件 =======================
    /** 折叠后的箭头拉手：贴边一侧直边，外侧圆角 */
    class ArrowTab extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ar = new Paint(Paint.ANTI_ALIAS_FLAG); int dir = -1; final Path pth = new Path(), ch = new Path();
        ArrowTab(android.content.Context c) {
            super(c);
            bg.setColor(0xE61B1D2E);
            ar.setColor(Color.WHITE); ar.setStyle(Paint.Style.STROKE); ar.setStrokeWidth(dp(2.2f)); ar.setStrokeCap(Paint.Cap.ROUND); ar.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = dp(11);
            pth.reset();
            float[] radii = dir == -1 ? new float[]{0, 0, r, r, r, r, 0, 0} : new float[]{r, r, 0, 0, 0, 0, r, r};
            pth.addRoundRect(new RectF(0, 0, w, h), radii, Path.Direction.CW);
            c.drawPath(pth, bg);
            float cx = w / 2f, cy = h / 2f, a = dp(5), sgn = dir == -1 ? 1f : -1f;   // 贴左→箭头朝右
            ch.reset(); ch.moveTo(cx - sgn * a * 0.5f, cy - a); ch.lineTo(cx + sgn * a * 0.5f, cy); ch.lineTo(cx - sgn * a * 0.5f, cy + a);
            c.drawPath(ch, ar);
        }
    }

    /** 右下角缩放手柄：两道半透明细线 */
    class ResizeHandle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ResizeHandle(android.content.Context c) { super(c); p.setColor(0x66FFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.4f)); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w - dp(5), h - dp(10), w - dp(10), h - dp(5), p);
            c.drawLine(w - dp(5), h - dp(15), w - dp(15), h - dp(5), p);
        }
    }

    /** 胶囊图标：0 停止（圆环+方块），2 交还（圆环+三角） */
    class IconView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final int type; final Path path = new Path();
        IconView(android.content.Context c, int type) { super(c); this.type = type; p.setColor(Color.WHITE); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            if (type == 3) {                                   // 圆形按钮内：纯实心圆角方块
                c.drawRoundRect(new RectF(w * 0.12f, h * 0.12f, w * 0.88f, h * 0.88f), w * 0.18f, w * 0.18f, p);
                return;
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.09f);
            c.drawCircle(w / 2f, h / 2f, w * 0.43f, p);
            p.setStyle(Paint.Style.FILL);
            if (type == 0) {
                c.drawRoundRect(new RectF(w * 0.33f, h * 0.33f, w * 0.67f, h * 0.67f), w * 0.07f, w * 0.07f, p);
            } else {
                path.reset(); path.moveTo(w * 0.42f, h * 0.31f); path.lineTo(w * 0.71f, h * 0.50f); path.lineTo(w * 0.42f, h * 0.69f); path.close();
                c.drawPath(path, p);
            }
        }
    }
}
