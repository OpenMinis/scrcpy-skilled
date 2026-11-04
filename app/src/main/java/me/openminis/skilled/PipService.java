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
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;

/**
 * 主屏悬浮小窗：圆角卡片 + 流光边框(AI 操作中) + 顶部栏(状态/接管按钮，可拖动、双指缩放) + 右下角缩放手柄。
 * SurfaceView 显示 scrcpy 虚拟屏的 H.264 流；触摸经控制通道回传。
 */
public class PipService extends Service implements SurfaceHolder.Callback, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    static final int PAD_DP = 14, HEADER_DP = 32, RADIUS_DP = 18;

    WindowManager wm; WindowManager.LayoutParams lp;
    FrameLayout root; GlowView glow; LinearLayout card; SurfaceView sv; TextView label, btn; View dot;
    volatile ScrcpyClient client; AgentServer agent; int port = 27183;
    volatile int vw = 540, vh = 960; volatile boolean hidden;
    int winX = 20, winY = 300, contentW; float dens;
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(root, lp);
        relayout();
        agent.start();
        ui.post(refresh);
        Log.i(T, "overlay added, port=" + port);
        return START_STICKY;
    }

    // ---------------- UI ----------------
    void buildUi() {
        int pad = dpi(PAD_DP);
        root = new FrameLayout(this);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF0E0E14);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP)); }
        });
        card.setClipToOutline(true);

        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(0xFF191922);
        dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(0xFF4F8CFF); dot.setBackground(dg);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dpi(7), dpi(7), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        dl.leftMargin = dpi(12); header.addView(dot, dl);
        label = new TextView(this);
        label.setTextColor(0xFFC9CBD6); label.setTextSize(10); label.setSingleLine(true);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        ll.leftMargin = dpi(24); header.addView(label, ll);
        btn = new TextView(this);
        btn.setTextColor(Color.WHITE); btn.setTextSize(11); btn.setGravity(Gravity.CENTER); btn.setSingleLine(true);
        btn.setPadding(dpi(12), dpi(3), dpi(12), dpi(3));
        header.addView(btn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // 拖动（单指）+ 双指缩放，作用于顶部栏
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
                default: return true;
            }
        };
        header.setOnTouchListener(drag);
        final float[] bd = new float[3];   // 按钮：downX, downY, moved
        btn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: bd[0] = e.getRawX(); bd[1] = e.getRawY(); bd[2] = 0; drag.onTouch(header, e); return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - bd[0]) + Math.abs(e.getRawY() - bd[1]) > dp(8)) bd[2] = 1;
                    if (bd[2] == 1) drag.onTouch(header, e);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (bd[2] == 0) { agent.setTakeover(!agent.manualTakeover); updateUi(); }
                    return true;
                default: return true;
            }
        });

        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        sv.setOnTouchListener((v, e) -> {
            if (agent != null) agent.humanTouched();   // 人类触摸 → 自动接管
            if (client == null) return true;
            int a = e.getActionMasked(); // 0 down,1 up,2 move
            if (a > 2) a = 1;
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });

        card.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(HEADER_DP)));
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10));   // 高度在 relayout 里精确设置
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cl.setMargins(pad, pad, pad, pad);
        root.addView(card, cl);

        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View handle = new ResizeHandle(this);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dpi(34), dpi(34), Gravity.BOTTOM | Gravity.RIGHT);
        hl.setMargins(0, 0, pad, pad);
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

    void setContentWidth(int w) {
        int max = getResources().getDisplayMetrics().widthPixels - 2 * dpi(PAD_DP);
        contentW = Math.max(dpi(110), Math.min(max, w));
        relayout();
    }

    void relayout() {
        if (lp == null || root == null) return;
        int pad = dpi(PAD_DP);
        int foot = (int) dp(RADIUS_DP + 2);                       // 底部深色衬底，避免视频直角顶到圆角
        int ch = contentW * vh / Math.max(1, vw);
        lp.width = contentW + 2 * pad;
        lp.height = ch + dpi(HEADER_DP) + foot + 2 * pad;
        if (sv != null && sv.getLayoutParams() != null && sv.getLayoutParams().height != ch) { sv.getLayoutParams().height = ch; sv.requestLayout(); }
        lp.x = hidden ? -lp.width - 50 : winX; lp.y = winY;
        if (hidden) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
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
        int st = agent == null ? 0 : agent.uiState();   // 0 AI 待命  1 AI 操作中  2 人类操作
        boolean take = agent != null && agent.manualTakeover;
        label.setText(st == 1 ? "AI 操作中" : st == 2 ? "你在操作" : "AI 待命");
        int c = st == 2 ? 0xFF34D399 : st == 1 ? 0xFF7C6CFF : 0xFF4F8CFF;
        ((GradientDrawable) dot.getBackground()).setColor(c);
        btn.setText(take ? "交还 AI" : "接管");
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(14));
        bg.setColor(take ? 0xFF16A34A : 0xFF3B3F5C); bg.setStroke(dpi(1), take ? 0xFF4ADE80 : 0xFF6B6F94);
        btn.setBackground(bg);
    }

    // ---------------- Surface / scrcpy ----------------
    @Override public void surfaceCreated(SurfaceHolder h) {
        client = new ScrcpyClient("127.0.0.1", port, h.getSurface(), this);
        client.start();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { if (client != null) client.stop(); client = null; }
    @Override public void onVideoSize(int w, int h) { vw = w; vh = h; ui.post(this::relayout); }
    @Override public void onClosed(String why) { Log.w(T, "closed: " + why); }
    @Override public void onDestroy() {
        ui.removeCallbacks(refresh);
        if (glow != null) glow.stopAnim();
        if (agent != null) agent.stop();
        if (client != null) client.stop();
        if (root != null) try { wm.removeView(root); } catch (Throwable e) {}
    }

    // ---------------- 流光边框 ----------------
    class GlowView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path inner = new Path(); final RectF rc = new RectF(); final Matrix mx = new Matrix();
        float angle; ValueAnimator anim;
        static final int RING = 6;
        GlowView(android.content.Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            anim = ValueAnimator.ofFloat(0f, 360f); anim.setDuration(3200); anim.setRepeatCount(ValueAnimator.INFINITE); anim.setInterpolator(null);
            anim.addUpdateListener(a -> { angle = (Float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }
        void stopAnim() { if (anim != null) anim.cancel(); }
        @Override protected void onDraw(Canvas c) {
            float pad = dp(PAD_DP), r = dp(RADIUS_DP);
            rc.set(pad, pad, getWidth() - pad, getHeight() - pad);
            int st = agent == null ? 0 : agent.uiState();
            int[] cols = st == 2
                    ? new int[]{0xFF34D399, 0xFF86EFAC, 0xFF22C55E, 0xFF5EEAD4, 0xFF34D399}
                    : new int[]{0xFF4F8CFF, 0xFF9B5CFF, 0xFFFF5CAA, 0xFF3FD8FF, 0xFF4F8CFF};
            float inten = st == 1 ? 1f : st == 2 ? 0.75f : 0.5f;
            float speed = st == 1 ? 1f : 0.35f;
            float pulse = st == 1 ? 0.8f + 0.2f * (float) Math.sin(System.nanoTime() / 2.5e8) : 0.9f;
            SweepGradient sg = new SweepGradient(getWidth() / 2f, getHeight() / 2f, cols, null);
            mx.setRotate(angle * speed, getWidth() / 2f, getHeight() / 2f); sg.setLocalMatrix(mx);
            p.setShader(sg);
            // 外发光：只画在卡片外侧
            inner.reset(); inner.addRoundRect(rc, r, r, Path.Direction.CW);
            c.save(); c.clipOutPath(inner);
            for (int k = RING; k >= 1; k--) {
                p.setStrokeWidth(dp(2f) + dp(2.6f) * k);
                p.setAlpha((int) (255 * inten * pulse * (0.30f / k)));
                c.drawRoundRect(rc, r, r, p);
            }
            c.restore();
            // 内发光：窗口层在视频之上，直接叠加在画面边缘
            c.save(); c.clipPath(inner);
            for (int k = RING; k >= 1; k--) {
                p.setStrokeWidth(dp(2f) + dp(3.4f) * k);
                p.setAlpha((int) (255 * inten * pulse * (0.20f / k)));
                c.drawRoundRect(rc, r, r, p);
            }
            c.restore();
            // 锐利边框
            p.setStrokeWidth(dp(2.2f)); p.setAlpha((int) (255 * Math.min(1f, inten + 0.25f)));
            c.drawRoundRect(rc, r, r, p);
        }
    }

    // ---------------- 右下角缩放手柄 ----------------
    class ResizeHandle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ResizeHandle(android.content.Context c) { super(c); p.setColor(0xCCFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2f)); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w - dp(6), h - dp(14), w - dp(14), h - dp(6), p);
            c.drawLine(w - dp(6), h - dp(22), w - dp(22), h - dp(6), p);
        }
    }
}
