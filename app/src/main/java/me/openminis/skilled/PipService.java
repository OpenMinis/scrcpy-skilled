package me.openminis.skilled;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.*;

/** 主屏悬浮小窗：SurfaceView 显示 scrcpy 虚拟屏的 H.264 流，触摸经控制通道回传。 */
public class PipService extends Service implements SurfaceHolder.Callback, ScrcpyClient.Listener {
    static final String T = "SKILLED";
    WindowManager wm; SurfaceView sv; ScrcpyClient client; int port = 27183;
    volatile int vw = 540, vh = 960;


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
            if (client == null) return true;
            int a = e.getActionMasked(); // 0 down,1 up,2 move
            if (a > 2) a = 1;
            int x = (int) (e.getX() * vw / v.getWidth()), y = (int) (e.getY() * vh / v.getHeight());
            client.touch(a, -2L /* POINTER_ID_GENERIC_FINGER */, x, y, vw, vh, a == 1 ? 0f : 1f);
            return true;
        });
        int w = Math.round(getResources().getDisplayMetrics().density * 200);
        int h = w * 16 / 9;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 20; lp.y = 300;
        wm.addView(sv, lp);
        Log.i(T, "overlay added, port=" + port);
        return START_STICKY;
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        client = new ScrcpyClient("127.0.0.1", port, h.getSurface(), this);
        client.start();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { if (client != null) client.stop(); client = null; }
    @Override public void onVideoSize(int w, int h) { vw = w; vh = h; }
    @Override public void onClosed(String why) { Log.w(T, "closed: " + why); }
    @Override public void onDestroy() { if (client != null) client.stop(); if (sv != null) wm.removeView(sv); }
}
