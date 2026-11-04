package me.openminis.skilled;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.*;
import java.util.Map;

/** 主屏悬浮小窗：SurfaceView 显示 scrcpy 虚拟屏的 H.264 流，触摸经控制通道回传。 */
public class PipService extends Service implements SurfaceHolder.Callback, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    WindowManager wm; SurfaceView sv; volatile ScrcpyClient client; AgentServer agent; int port = 27183;
    volatile int vw = 540, vh = 960;
    volatile boolean hidden; int winX = 20, winY = 300, winWdp = 200;
    WindowManager.LayoutParams lp;


    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null) port = i.getIntExtra("port", port);
        if (sv != null) return START_STICKY;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pip", "pip", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "pip").setContentTitle("scrcpy-skilled").setSmallIcon(android.R.drawable.ic_menu_view).build());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        sv.setOnTouchListener((v, e) -> {
            Log.i(T, "touch action=" + e.getActionMasked() + " agent=" + (agent != null));
            if (agent != null) agent.humanTouched();   // 人类触摸 → 自动接管
            if (client == null) return true;
            int a = e.getActionMasked(); // 0 down,1 up,2 move
            if (a > 2) a = 1;
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });
        if (i != null) { winX = i.getIntExtra("x", winX); winY = i.getIntExtra("y", winY); winWdp = i.getIntExtra("w", winWdp); }
        int w = Math.round(getResources().getDisplayMetrics().density * winWdp);
        int h = w * 16 / 9;
        lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = winX; lp.y = winY;
        wm.addView(sv, lp);
        agent = new AgentServer(this); agent.start();
        Log.i(T, "overlay added, port=" + port);
        return START_STICKY;
    }

    /** 主线程调用：按 x/y(px) w(dp) 调整窗口；hide=1 移出屏幕并设为不可触摸。 */
    void applyWindow(Map<String, String> m) {
        if (lp == null || sv == null) return;
        if (m.containsKey("x")) winX = Integer.parseInt(m.get("x"));
        if (m.containsKey("y")) winY = Integer.parseInt(m.get("y"));
        if (m.containsKey("w")) winWdp = Integer.parseInt(m.get("w"));
        if (m.containsKey("hide")) hidden = "1".equals(m.get("hide"));
        int w = Math.round(getResources().getDisplayMetrics().density * winWdp);
        lp.width = w; lp.height = w * vh / Math.max(1, vw);
        lp.x = hidden ? -lp.width - 50 : winX; lp.y = winY;
        if (hidden) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(sv, lp);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        client = new ScrcpyClient("127.0.0.1", port, h.getSurface(), this);
        client.start();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { if (client != null) client.stop(); client = null; }
    @Override public void onVideoSize(int w, int h) { vw = w; vh = h; }
    @Override public void onClosed(String why) { Log.w(T, "closed: " + why); }
    @Override public void onDestroy() { if (agent != null) agent.stop(); if (client != null) client.stop(); if (sv != null) wm.removeView(sv); }
}
