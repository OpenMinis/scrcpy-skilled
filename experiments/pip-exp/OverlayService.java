package me.wsen.pip;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.util.Log;
import android.view.*;
import java.io.*;
import java.net.Socket;

public class OverlayService extends Service implements SurfaceHolder.Callback {
    static final String T = "PIPEXP";
    static final int W = 540, H = 960, DPI = 240;
    WindowManager wm; SurfaceView sv; VirtualDisplay vd; PrintWriter out;
    int dispId = -1;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (sv != null) return START_STICKY;
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new android.app.NotificationChannel("pip", "pip", android.app.NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new android.app.Notification.Builder(this, "pip").setContentTitle("PipExp").setSmallIcon(android.R.drawable.ic_menu_view).build());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sv = new SurfaceView(this);
        sv.getHolder().setFixedSize(W, H);
        sv.getHolder().addCallback(this);
        sv.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a > 2) a = 1;
            float x = e.getX() * W / v.getWidth(), y = e.getY() * H / v.getHeight();
            send("T " + a + " " + x + " " + y + " " + dispId);
            return true;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                W * 2 / 3, H * 2 / 3,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 20; lp.y = 200;
        wm.addView(sv, lp);
        Log.i(T, "overlay added");
        return START_STICKY;
    }

    void send(String s) {
        new Thread(() -> {
            try {
                synchronized (this) {
                    if (out == null) out = new PrintWriter(new Socket("127.0.0.1", 37911).getOutputStream(), true);
                    out.println(s);
                }
            } catch (Throwable e) { Log.e(T, "send fail " + e); out = null; }
        }).start();
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | (1 << 6) /*SUPPORTS_TOUCH*/ | (1 << 10) /*TRUSTED, expected to be rejected*/;
            try {
                vd = dm.createVirtualDisplay("pipexp", W, H, DPI, h.getSurface(), flags);
            } catch (Throwable e) {
                Log.w(T, "trusted flag rejected: " + e);
                vd = dm.createVirtualDisplay("pipexp", W, H, DPI, h.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | (1 << 6));
            }
            dispId = vd.getDisplay().getDisplayId();
            Log.i(T, "VIRTUAL_DISPLAY_ID=" + dispId);
        } catch (Throwable e) { Log.e(T, "createVD fail " + e); }
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { if (vd != null) vd.release(); }
    @Override public void onDestroy() { if (sv != null) wm.removeView(sv); }
}
