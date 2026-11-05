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
 * Floating mini window on the main screen.
 * Layout (everything overlays the video, no extra height):
 *   card (rounded clip) ─ TextureView secondary-display video
 *                  ├ GlowView: outside-in gradient glow + rotating rainbow border
 *                  ├ header: status dot + dynamic title (no background; drag to move / pinch to resize)
 *                  ├ hint: centered "tap anywhere to take over", pulses with the glow
 *                  ├ top-right: red "Stop" / green "Hand back to AI"
 *                  └ bottom-right: small translucent resize handle
 *   arrow: pull tab shown when docked to an edge
 */
public class PipService extends Service implements TextureView.SurfaceTextureListener, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    static final int HEADER_DP = 30, RADIUS_DP = 20, SNAP_DP = 28, TAB_DP = 22;
    static final long HOLD_MS = 1000;          // long-press duration to take over while AI is acting
    static final int BAR_DP = 30;              // unified height for all top-bar controls (title pill, round buttons, pills)

    WindowManager wm; WindowManager.LayoutParams lp;
    FrameLayout root, card; TextureView sv; Surface surf;
    GlowView glow; LinearLayout header, stopPill, resumePill, closeBtn; TextView label, hint; View dot, handle; ArrowTab arrow;
    HoldRing holdRing; long closeArmedAt; LinearLayout topRight;
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
        nm.createNotificationChannel(new NotificationChannel("pip", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "pip").setContentTitle(getString(R.string.notif_title)).setSmallIcon(android.R.drawable.ic_menu_view).build());
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

    // ======================= UI construction =======================
    void buildUi() {
        root = new FrameLayout(this);

        card = new FrameLayout(this);
        card.setBackgroundColor(0xFF0B0B12);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP)); }
        });
        card.setClipToOutline(true);                       // TextureView can be clipped to rounded corners (SurfaceView cannot)
        root.addView(card, match());

        // ① secondary-display video
        sv = new TextureView(this);
        sv.setSurfaceTextureListener(this);
        // While AI is acting: the whole view only adjusts the window — one-finger drag = move (snaps/docks at edges on release), pinch = resize,
        //          1s stationary long-press = human takeover (progress ring while held). Taps do nothing and are not forwarded.
        // When AI is not acting (idle / human in control): touches are forwarded to the secondary display.
        final float[] dgr = new float[5];          // downRawX, downRawY, winX0, winY0, moved
        final boolean[] eat = {false};
        final float[] pinch = new float[8];        // span, w, focusX, focusY (screen), winScreenX, winScreenY, parentOffX, parentOffY
        final Runnable takeoverByHold = () -> {
            if (agent != null && !agent.held()) {                // works whether AI is acting or idle
                agent.setTakeover(true); holdRing.done(); updateUi();
            }
        };
        sv.setOnTouchListener((v, e) -> {
            if (docked != 0) return true;
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                // Until the human holds control (AI working OR idle), the video area only moves/resizes the window;
                // the ONLY way to take over is a stationary 1s long-press. Nothing is forwarded to the phone.
                eat[0] = agent != null && !agent.held();
                dgr[0] = e.getRawX(); dgr[1] = e.getRawY(); dgr[2] = winX; dgr[3] = winY; dgr[4] = 0;
                if (eat[0]) { ui.postDelayed(takeoverByHold, HOLD_MS); holdRing.start(e.getX(), e.getY(), HOLD_MS); }
            }
            if (eat[0]) {
                // Pinch = our own span tracking (ScaleGestureDetector ignores spans < ~27mm, i.e. most pinches on a small window).
                boolean multi = e.getPointerCount() > 1;
                if (multi) {
                    if (dgr[4] != 2) { dgr[4] = 2; ui.removeCallbacks(takeoverByHold); holdRing.cancel(); }   // second finger: resize, never takeover
                    if (act == MotionEvent.ACTION_POINTER_DOWN || pinch[0] <= 0) pinchUpdate(e, pinch, true);
                    else if (act == MotionEvent.ACTION_MOVE) pinchUpdate(e, pinch, false);
                }
                switch (act) {
                    case MotionEvent.ACTION_MOVE:
                        if (multi) break;
                        if (dgr[4] != 1 && Math.hypot(e.getRawX() - dgr[0], e.getRawY() - dgr[1]) > dp(8)) {
                            dgr[4] = 1; ui.removeCallbacks(takeoverByHold); holdRing.cancel();   // finger moved: it's a drag
                            dgr[0] = e.getRawX(); dgr[1] = e.getRawY(); dgr[2] = winX; dgr[3] = winY;
                        }
                        if (dgr[4] == 1) { winX = (int) (dgr[2] + e.getRawX() - dgr[0]); winY = (int) (dgr[3] + e.getRawY() - dgr[1]); relayout(); }
                        break;
                    case MotionEvent.ACTION_POINTER_UP:                  // pinch ended; the remaining finger may start a drag (needs real movement)
                        pinch[0] = 0;
                        int keep = e.getActionIndex() == 0 ? 1 : 0;
                        dgr[0] = e.getRawX(keep); dgr[1] = e.getRawY(keep); dgr[2] = winX; dgr[3] = winY;
                        break;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(takeoverByHold); holdRing.cancel(); pinch[0] = 0;
                        if (dgr[4] == 1) maybeDock();                     // only snap/dock after an actual drag
                        eat[0] = false; break;
                }
                return true;
            }
            // Human holds control: one finger is forwarded to the phone; two fingers resize the window (not forwarded)
            if (e.getPointerCount() > 1) {
                if (act == MotionEvent.ACTION_POINTER_DOWN || pinch[0] <= 0) {
                    pinchUpdate(e, pinch, true);
                    if (client != null) client.touch(1, -2L, 0, 0, vw, vh, 0f);   // release the first finger on the phone
                } else if (act == MotionEvent.ACTION_MOVE) pinchUpdate(e, pinch, false);
                return true;
            }
            if (pinch[0] > 0) { if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) pinch[0] = 0; return true; }  // swallow the tail of a pinch
            if (agent != null) agent.humanTouched();
            if (client == null) return true;
            int a = act > 2 ? 1 : act;                        // 0 down, 1 up, 2 move
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });
        card.addView(sv, match());

        // ② glow + border (does not consume touches; events fall through to the video)
        glow = new GlowView(this);
        card.addView(glow, match());

        // long-press takeover ring: appears under the finger, takes over when full after 1s; disappears on release/move
        holdRing = new HoldRing(this);
        card.addView(holdRing, match());

        // ③ title: no background, overlaid at top; drag to move, pinch to resize
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(glassPill());
        header.setPadding(0, 0, dpi(11), 0);              // height fixed to BAR_DP below; text centered by gravity
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
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(BAR_DP), Gravity.TOP | Gravity.LEFT);
        hl.topMargin = dpi(8); hl.leftMargin = dpi(10);
        card.addView(header, hl);

        // ④ center hint: plain text (no capsule), thin dark outline + soft shadow so it reads on any background;
        //    pulses in sync with the glow; not clickable itself (taps handled by the video view)
        hint = new OutlinedText(this);
        hint.setText(R.string.hold_to_take_over); hint.setTextColor(Color.WHITE); hint.setTextSize(16); hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setLetterSpacing(0.04f); hint.setMaxLines(2); hint.setGravity(Gravity.CENTER); hint.setIncludeFontPadding(false);
        hint.setShadowLayer(dp(6), 0, 0, 0x99000000);
        hint.setPadding(dpi(4), dpi(4), dpi(4), dpi(4));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = wrap(Gravity.CENTER); hintLp.leftMargin = hintLp.rightMargin = dpi(14);
        card.addView(hint, hintLp);                           // side margins → long text (e.g. English) wraps instead of clipping

        // ⑤ top-right: AI acting = red round Stop; taken over/stopped = green "Hand back to AI";
        //          AI idle / taken over = gray round "Close display" (first tap turns red to confirm; tap again within 3s to close)
        stopPill = makeRoundButton(3, 0xF2EF4444, 0xFFFCA5A5);
        resumePill = makePill(getString(R.string.give_back), 2, 0xEB16A34A, 0xFF86EFAC);
        closeBtn = makeRoundButton(4, 0xCC2A2D40, 0x88FFFFFF);
        closeBtn.setContentDescription(getString(R.string.cd_close));
        stopPill.setOnClickListener(v -> { agent.stopByUser(); updateUi(); });
        resumePill.setOnClickListener(v -> { agent.setTakeover(false); updateUi(); });
        closeBtn.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - closeArmedAt < 3000) { closeAll(); return; }
            closeArmedAt = now; armClose(true);
            ui.postDelayed(() -> { if (System.currentTimeMillis() - closeArmedAt >= 3000) armClose(false); }, 3050);
        });
        topRight = new LinearLayout(this);
        topRight.setOrientation(LinearLayout.HORIZONTAL); topRight.setGravity(Gravity.CENTER_VERTICAL);
        for (View b : new View[]{resumePill, stopPill, closeBtn}) {
            boolean round = b != resumePill;                 // round icon buttons are BAR_DP × BAR_DP; pills are BAR_DP tall
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(round ? dpi(BAR_DP) : ViewGroup.LayoutParams.WRAP_CONTENT, dpi(BAR_DP));
            bl.leftMargin = dpi(6);
            b.setVisibility(View.GONE); topRight.addView(b, bl);
        }
        FrameLayout.LayoutParams tr = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(BAR_DP), Gravity.TOP | Gravity.RIGHT);
        tr.topMargin = dpi(8); tr.rightMargin = dpi(10);     // same top margin + height as the title pill → aligned
        card.addView(topRight, tr);

        // ⑥ bottom-right resize handle: small, translucent, in the corner
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

        // ⑦ pull tab when docked (outside card; card is hidden while docked)
        arrow = new ArrowTab(this);
        arrow.setVisibility(View.GONE);
        root.addView(arrow, new FrameLayout.LayoutParams(dpi(TAB_DP), dpi(72), Gravity.CENTER_VERTICAL | Gravity.RIGHT));
        final float[] ad = new float[3];
        arrow.setOnTouchListener((v, e) -> {              // tap to expand; drag vertically to move along the edge
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

    /** Glass capsule: translucent dark fill + top-to-bottom gradient (slightly lighter top) + thin white stroke, frosted-glass look */
    GradientDrawable glassPill() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C3A3F5C, 0x99141625});
        g.setCornerRadius(dp(40)); g.setStroke(Math.max(1, dpi(0.8f)), 0x55FFFFFF);
        return g;
    }

    /** Round icon button (no text): solid circle + thin stroke + centered white icon */
    LinearLayout makeRoundButton(int icon, int fill, int stroke) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER);
        int size = dpi(BAR_DP);
        l.setMinimumWidth(size); l.setMinimumHeight(size);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill});
        g.setShape(GradientDrawable.OVAL); g.setStroke(Math.max(1, dpi(0.8f)), stroke);
        l.setBackground(g);
        l.setContentDescription(getString(icon == 4 ? R.string.cd_close : R.string.cd_stop));
        l.addView(new IconView(this, icon), new LinearLayout.LayoutParams(dpi(14), dpi(14)));
        return l;
    }

    LinearLayout makePill(String text, int icon, int fill, int stroke) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dpi(9), 0, dpi(11), 0);              // height comes from BAR_DP layout params; content centered
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(fill), fill}); g.setCornerRadius(dp(40)); g.setStroke(Math.max(1, dpi(0.8f)), stroke);
        l.setBackground(g);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dpi(13), dpi(13)); il.rightMargin = dpi(5);
        l.addView(new IconView(this, icon), il);
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(Color.WHITE); tv.setTextSize(10.5f); tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setSingleLine(true); tv.setIncludeFontPadding(false);
        l.addView(tv);
        return l;
    }

    static int lighten(int c) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        r = r + (255 - r) / 5; g = g + (255 - g) / 5; b = b + (255 - b) / 5;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ======================= size / position / docking =======================
    int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    static final int MIN_W_DP = 120;            // window never smaller than this
    static final float MAX_W_FRAC = 0.85f;      // window never wider/taller than this fraction of the screen
    static final float CHROME_MIN = 0.75f, CHROME_MAX = 1.15f;   // clamp for scaling buttons/title/hint with the window

    /** Two-finger resize that "sticks" to the fingers: scales by span ratio around the finger midpoint and
     *  translates with it, so the content point under the fingers stays under the fingers (like a photo viewer).
     *  Finger coords are raw screen px; lp.x/lp.y live in the window's parent frame (offset by status bar etc.),
     *  so we convert using the window's actual on-screen origin at gesture start. */
    void pinchUpdate(MotionEvent e, float[] p, boolean begin) {
        float x0 = e.getRawX(0), y0 = e.getRawY(0), x1 = e.getRawX(1), y1 = e.getRawY(1);
        float span = Math.max(1f, (float) Math.hypot(x0 - x1, y0 - y1));
        float fx = (x0 + x1) / 2f, fy = (y0 + y1) / 2f;
        if (begin || p[0] <= 0) {
            int[] loc = new int[2]; root.getLocationOnScreen(loc);
            // p[4],p[5] = window's on-screen origin; p[6],p[7] = parent-frame offset (screen - lp)
            p[0] = span; p[1] = contentW; p[2] = fx; p[3] = fy; p[4] = loc[0]; p[5] = loc[1];
            p[6] = loc[0] - lp.x; p[7] = loc[1] - lp.y;
            return;
        }
        contentW = clampW((int) (p[1] * span / p[0]));               // no relayout yet: one window update per frame
        float k = contentW / p[1];                                   // actual applied scale after clamping
        // keep the window point that was under the start midpoint under the current midpoint (screen coords)
        float sx = fx - (p[2] - p[4]) * k, sy = fy - (p[3] - p[5]) * k;
        winX = Math.round(sx - p[6]);
        winY = Math.round(sy - p[7]);
        relayout();
    }

    int clampW(int w) {
        int maxByW = (int) (screenW() * MAX_W_FRAC);
        int maxByH = (int) (screenH() * MAX_W_FRAC * vw / Math.max(1, vh));
        return Math.max(dpi(MIN_W_DP), Math.min(Math.min(maxByW, maxByH), w));
    }

    void setContentWidth(int w) {
        contentW = clampW(w);
        relayout();
    }

    int targetX() {
        if (docked == -1) return -(lp.width - dpi(TAB_DP));      // only a strip shows at the left edge (pull tab)
        if (docked == 1) return screenW() - dpi(TAB_DP);          // only a strip shows at the right edge
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
        scaleChrome();
        try { wm.updateViewLayout(root, lp); } catch (Throwable e) { Log.w(T, "relayout " + e); }
    }

    /** Scale overlay controls with the window so they stay proportional when the window is resized.
     *  Default width (168dp) = 1.0; clamped to [0.6, 1.25]. Each control is scaled around the corner it's pinned to. */
    void scaleChrome() {
        if (header == null) return;
        float s = Math.max(CHROME_MIN, Math.min(CHROME_MAX, contentW / dp(168)));
        header.setPivotX(0); header.setPivotY(0); header.setScaleX(s); header.setScaleY(s);
        if (topRight != null) {
            topRight.setPivotX(topRight.getWidth() > 0 ? topRight.getWidth() : dp(BAR_DP)); topRight.setPivotY(0);
            topRight.setScaleX(s); topRight.setScaleY(s);
        }
        if (hint != null) { hint.setScaleX(s); hint.setScaleY(s); }       // centered: default pivot (center) is correct
    }

    /** On release: dock if near the left/right edge, otherwise stay on screen */
    void maybeDock() {
        int sw = screenW(), snap = dpi(SNAP_DP);
        if (winX < snap) docked = -1;
        else if (sw - (winX + lp.width) < snap) docked = 1;
        else { docked = 0; winX = Math.max(0, Math.min(sw - lp.width, winX)); }
        animateTo();
    }

    void undock() {
        int gap = dpi(12);
        winX = docked == 1 ? screenW() - lp.width - gap : gap;   // keep a small gap from the edge to avoid re-docking immediately
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

    /** From the /window API (main thread): x/y (px), w (dp), hide */
    void applyWindow(Map<String, String> m) {
        if (m.containsKey("x")) { winX = Integer.parseInt(m.get("x")); docked = 0; }
        if (m.containsKey("y")) winY = Integer.parseInt(m.get("y"));
        if (m.containsKey("w")) contentW = dpi(Integer.parseInt(m.get("w")));
        if (m.containsKey("hide")) hidden = "1".equals(m.get("hide"));
        setContentWidth(contentW);
    }

    // ======================= state → UI =======================
    final Runnable refresh = new Runnable() { @Override public void run() { updateUi(); ui.postDelayed(this, 250); } };

    void updateUi() {
        if (agent == null || label == null) return;
        int st = agent.uiState();                 // 0 idle  1 AI acting  2 human acting/holding
        boolean held = agent.held(), stopped = agent.stopped;
        String t = agent.title == null ? "" : agent.title.trim();
        String text = getString(stopped ? R.string.state_stopped : held ? R.string.state_taken : st == 1 ? R.string.state_working : st == 2 ? R.string.state_human : R.string.state_idle);
        if (st == 1 && !held && !stopped && !t.isEmpty()) text = t;                      // AI acting: show the task title set by the agent
        if (System.currentTimeMillis() - closeArmedAt < 3000) text = getString(R.string.confirm_close);   // keep the prompt while close confirmation is pending
        if (!text.equals(lastLabel)) { lastLabel = text; label.setText(text); }
        ((GradientDrawable) dot.getBackground()).setColor(stopped ? 0xFFEF4444 : st == 2 ? 0xFF34D399 : st == 1 ? 0xFFB46BFF : 0xFF4F8CFF);
        boolean show = !hidden && docked == 0, ai = st == 1 && !held;
        hint.setVisibility(!held && show ? View.VISIBLE : View.GONE);   // shown whenever a long-press would take over
        stopPill.setVisibility(ai && show ? View.VISIBLE : View.GONE);
        resumePill.setVisibility(held && show ? View.VISIBLE : View.GONE);
        header.setVisibility(held ? View.GONE : View.VISIBLE);              // the green pill already says "taken over"; avoid two overlapping pills
        closeBtn.setVisibility(!ai && show ? View.VISIBLE : View.GONE);   // display can be closed when idle / taken over / stopped
        scaleChrome();                                   // topRight's width changes with which button is visible → re-pin pivot
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
        // self-heal: connecting fails if the bridge starts after the window or is restarted; reconnect while the surface is alive
        ui.postDelayed(() -> { if (surf != null && surf.isValid() && (client == null || !client.running)) restartClient(); }, 1500);
    }
    void restartClient() {
        if (client != null) client.stop();
        client = new ScrcpyClient("127.0.0.1", port, surf, this);
        client.start();
    }
    /** Enter/exit the close button's "pending confirm" state: turn red and show a prompt */
    void armClose(boolean armed) {
        if (closeBtn == null) return;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                armed ? new int[]{lighten(0xF2EF4444), 0xF2EF4444} : new int[]{lighten(0xCC2A2D40), 0xCC2A2D40});
        g.setShape(GradientDrawable.OVAL); g.setStroke(Math.max(1, dpi(0.8f)), armed ? 0xFFFCA5A5 : 0x88FFFFFF);
        closeBtn.setBackground(g);
        if (armed) { label.setText(R.string.confirm_close); lastLabel = ""; }
    }

    /** User tapped "Close display": notify the agent (closed=true in /status), then stop the service and remove the window.
     *  The secondary display is destroyed when the scrcpy server disconnects; the skill's stop.sh cleans up the server. */
    void closeAll() {
        Log.i(T, "closed by user");
        if (agent != null) agent.closedByUser = true;
        if (client != null) { client.stop(); client = null; }
        // give the agent a moment to read closed=true, then stop the service; onDestroy() ends the process
        ui.postDelayed(() -> { stopForeground(true); stopSelf(); }, 600);
    }

    @Override public void onDestroy() {
        ui.removeCallbacks(refresh);
        if (glow != null) glow.stopAnim();
        if (agent != null) agent.stop();
        if (client != null) client.stop();
        if (root != null) try { wm.removeView(root); } catch (Throwable e) {}
        // The app has no UI and nothing else to keep alive: end the process so no idle threads/state linger
        // (a stopped service otherwise leaves a cached process with ~25 threads and stale in-memory state).
        // Delayed slightly so the system can finish tearing the service down cleanly.
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    // ======================= glow + rotating rainbow border =======================
    /**
     * Android 13+: a single AGSL shader draws all effects on the GPU in one pass (zero allocations per frame):
     *   · Edge glow: rainbow colors fade smoothly from the edges toward the center, covering most of the view;
     *   · Outside-in light wave: every 1.6s while AI is acting, light flows in from the edges, moves toward the center and fades (seamless loop);
     *   · Rotating rainbow border + an orbiting white highlight.
     * The center hint text shares the wave's brightness envelope. Below Android 13 it falls back to a rotating border + simple edge glow.
     */
    static final String GLOW_AGSL =
            "uniform float2 size; uniform float radius; uniform float bw;\n" +
            "uniform float rot; uniform float rot2; uniform float amp; uniform float inten; uniform float active; uniform float time;\n" +
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
            "  float d = max(0.0, -sdRR(p, size * 0.5, radius));            // distance to edge (px)\n" +
            "  float n = clamp(d / (min(size.x, size.y) * 0.5), 0.0, 1.0);   // 0=edge 1=center\n" +
            "  float ang = atan(p.y, p.x) / 6.2831853 + 0.5;\n" +
            "  half3 col = pal(ang + rot + 0.12 * n);                          // hue shifts slightly inward for a flowing feel\n" +
            "  float2 q = abs(p) / (size * 0.5);                                // normalized: 0 center → 1 edge\n" +
            "  float m = pow(pow(q.x, 3.0) + pow(q.y, 3.0), 1.0 / 3.0);         // superellipse distance: rounded contours, no sharp corners\n" +
            "  float rim = exp(-n * 4.5);                                       // bright edge band (by pixel distance, equal thickness on all sides)\n" +
            "  float body = pow(clamp(m, 0.0, 1.0), 2.2);                       // soft glow spreading inward, no square hole in the center\n" +
            "  float breath = mix(1.0, amp, active);                            // fade opacity in/out only, no movement\n" +
            "  float a = inten * (rim * 0.55 + body * 0.32) * (0.55 + 0.45 * breath);\n" +
            "  a = clamp(a, 0.0, 0.72);                                         // cap so the video stays readable\n" +
            "  // rotating border + orbiting highlight\n" +
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

        int[] palette(boolean stopped, int st) {   // first and last colors match to close the ring
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
            // targets: border rotation (deg/s), pulse period (s), intensity
            float tSpeed = st == 1 ? 72f : st == 2 ? 30f : 18f;
            float tPeriod = st == 1 ? 2.6f : st == 2 ? 3.6f : 4.8f;   // breathing period (s)
            float tInten = stopped ? 0.8f : st == 1 ? 1f : st == 2 ? 0.7f : 0.45f;
            int[] tgt = palette(stopped, st);
            if (!inited) { System.arraycopy(tgt, 0, cur, 0, cur.length); inited = true; }
            float k = Math.min(1f, dt * 3f);                       // ~0.35s smooth transition
            curSpeed += (tSpeed - curSpeed) * k; curPeriod += (tPeriod - curPeriod) * k; curInten += (tInten - curInten) * k;
            for (int i = 0; i < cur.length; i++) cur[i] = lerp(cur[i], tgt[i], k);
            cur[cur.length - 1] = cur[0];
            rot = (rot + curSpeed * dt) % 360f;
            wave = (wave + dt / curPeriod) % 1f;                    // continuous phase, no jump when speed changes
            curActive += ((st == 1 ? 1f : 0.35f) - curActive) * k;  // wave intensity: full while acting, weaker otherwise

            float w = getWidth(), h = getHeight(), r = dp(RADIUS_DP);
            if (w <= 0 || h <= 0) return;
            // wave brightness envelope: brightens then dims as the front advances; 0 at wave=0/1 → seamless loop
            float env = (float) Math.sin(Math.PI * wave); env = env * env;

            if (rs != null) {
                if (!loggedPath) { loggedPath = true; Log.i(T, "glow path=agsl hw=" + c.isHardwareAccelerated() + " size=" + w + "x" + h); }
                RuntimeShader s = (RuntimeShader) rs;
                s.setFloatUniform("size", w, h);
                s.setFloatUniform("radius", r);
                s.setFloatUniform("bw", dp(2.4f));
                s.setFloatUniform("rot", rot / 360f);
                s.setFloatUniform("rot2", (rot * 1.6f / 360f) % 1f);
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

            // center text: pulses with the glow while AI is acting; steady and dimmer when idle
            if (hint != null && hint.getVisibility() == View.VISIBLE) hint.setAlpha(st == 1 ? 0.4f + 0.6f * env : 0.55f);
        }

        /** Below Android 13: rotating rainbow border + orbiting highlight (shader rebuilt on demand, no per-frame matrix allocation) */
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
            for (int i = 6; i >= 1; i--) {                          // simple edge glow
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

    // ======================= widgets =======================
    /** Docked pull tab: straight on the edge side, rounded on the outer side */
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
            float cx = w / 2f, cy = h / 2f, a = dp(5), sgn = dir == -1 ? 1f : -1f;   // docked left → arrow points right
            ch.reset(); ch.moveTo(cx - sgn * a * 0.5f, cy - a); ch.lineTo(cx + sgn * a * 0.5f, cy); ch.lineTo(cx - sgn * a * 0.5f, cy + a);
            c.drawPath(ch, ar);
        }
    }

    /** Bottom-right resize handle: two thin translucent lines */
    class ResizeHandle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ResizeHandle(android.content.Context c) { super(c); p.setColor(0x66FFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.4f)); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w - dp(5), h - dp(10), w - dp(10), h - dp(5), p);
            c.drawLine(w - dp(5), h - dp(15), w - dp(15), h - dp(5), p);
        }
    }

    /** Capsule icon: 0 stop (ring + square), 2 hand back (ring + triangle) */
    /** Long-press takeover ring: draws a filling ring under the finger; after 1s it briefly highlights and fades */
    class HoldRing extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx, cy; long startAt, dur; boolean active, finished; long finishedAt;
        final RectF oval = new RectF();
        HoldRing(android.content.Context c) {
            super(c);
            bg.setStyle(Paint.Style.STROKE); bg.setStrokeWidth(dp(4)); bg.setColor(0x55FFFFFF);
            fg.setStyle(Paint.Style.STROKE); fg.setStrokeWidth(dp(4)); fg.setStrokeCap(Paint.Cap.ROUND); fg.setColor(0xFF34D399);
            setClickable(false); setFocusable(false);
        }
        void start(float x, float y, long d) { cx = x; cy = y; dur = d; startAt = System.currentTimeMillis(); active = true; finished = false; invalidate(); }
        void cancel() { active = false; finished = false; invalidate(); }
        void done() { active = false; finished = true; finishedAt = System.currentTimeMillis(); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float r = dp(26);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            if (active) {
                long el = System.currentTimeMillis() - startAt;
                if (el < 120) { postInvalidateOnAnimation(); return; }      // skip very short taps to avoid a flash
                float f = Math.min(1f, (float) (el - 120) / Math.max(1, dur - 120));
                c.drawCircle(cx, cy, r, bg);
                c.drawArc(oval, -90, 360 * f, false, fg);
                postInvalidateOnAnimation();
            } else if (finished) {
                float a = 1f - Math.min(1f, (System.currentTimeMillis() - finishedAt) / 350f);
                if (a > 0) { fg.setAlpha((int) (255 * a)); c.drawCircle(cx, cy, r, fg); fg.setAlpha(255); postInvalidateOnAnimation(); }
                else finished = false;
            }
        }
    }

    /** TextView with a thin dark outline: draws the text once as a stroke, then normally on top.
     *  Keeps white text legible on any background without a capsule behind it. */
    class OutlinedText extends TextView {
        boolean stroking;
        OutlinedText(android.content.Context c) { super(c); }
        @Override public void invalidate() { if (!stroking) super.invalidate(); }   // setTextColor() inside onDraw must not re-trigger a draw
        @Override protected void onDraw(Canvas c) {
            android.text.TextPaint tp = getPaint();
            int fill = getCurrentTextColor();
            float sr = tp.getShadowLayerRadius(), sx = tp.getShadowLayerDx(), sy = tp.getShadowLayerDy(); int sc = tp.getShadowLayerColor();
            stroking = true;
            tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(dp(1.6f)); tp.setStrokeJoin(Paint.Join.ROUND);
            setTextColor(0x8C000000);                           // faint outline (≈55% black)
            super.onDraw(c);                                     // pass 1: outline + shadow
            tp.setStyle(Paint.Style.FILL); tp.clearShadowLayer();
            setTextColor(fill);
            super.onDraw(c);                                     // pass 2: white fill on top
            tp.setShadowLayer(sr, sx, sy, sc);
            stroking = false;
        }
    }

    class IconView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final int type; final Path path = new Path();
        IconView(android.content.Context c, int type) { super(c); this.type = type; p.setColor(Color.WHITE); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            if (type == 4) {                                   // close: ×
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * 0.16f); p.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f, p);
                c.drawLine(w * 0.8f, h * 0.2f, w * 0.2f, h * 0.8f, p);
                return;
            }
            if (type == 3) {                                   // inside round button: solid rounded square
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
