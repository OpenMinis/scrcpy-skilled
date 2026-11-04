package me.openminis.skilled;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * agent 接口：127.0.0.1:28080 的极简 HTTP 服务（GET，参数在 query）。坐标为虚拟屏像素（如 540x960）。
 *   /status                       JSON：视频尺寸、连接状态、接管状态
 *   /screenshot                   PNG（PixelCopy 自小窗 SurfaceView，缩放到视频尺寸）
 *   /tap?x=&y=
 *   /swipe?x1=&y1=&x2=&y2=&ms=300
 *   /key?code=4                   Android keycode（4=BACK,3=HOME,66=ENTER...）
 *   /text?s=hello                 输入文字
 *   /start?pkg=com.xxx            在虚拟屏启动 App
 *   /takeover?on=1|0              人类接管开关；接管期间 agent 的操作返回 423
 * 人类触摸小窗会自动进入接管，空闲 HUMAN_IDLE_MS 后自动交还；agent 加 force=1 可强制操作。
 */
public class AgentServer {
    static final String T = "SKILLED";
    static final long HUMAN_IDLE_MS = 3000;
    public static final int PORT = 28080;

    final PipService svc;
    volatile boolean manualTakeover;
    volatile long lastHumanTouch;
    ServerSocket ss; volatile boolean running;

    AgentServer(PipService s) { svc = s; }

    boolean humanActive() { return manualTakeover || System.currentTimeMillis() - lastHumanTouch < HUMAN_IDLE_MS; }
    void humanTouched() { lastHumanTouch = System.currentTimeMillis(); }

    void start() {
        running = true;
        new Thread(() -> {
            try {
                ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                Log.i(T, "agent server on " + PORT);
                while (running) { final Socket c = ss.accept(); new Thread(() -> handle(c)).start(); }
            } catch (Throwable e) { Log.e(T, "agent server: " + e); }
        }, "agent-http").start();
    }

    void stop() { running = false; try { if (ss != null) ss.close(); } catch (Throwable e) {} }

    void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = r.readLine();
            if (line == null) return;
            String h; while ((h = r.readLine()) != null && !h.isEmpty()) { }
            String[] p = line.split(" ");
            String path = p[1], q = "";
            int qi = path.indexOf('?');
            if (qi >= 0) { q = path.substring(qi + 1); path = path.substring(0, qi); }
            Map<String, String> m = new HashMap<>();
            for (String kv : q.split("&")) { if (kv.isEmpty()) continue; int e = kv.indexOf('='); m.put(dec(e < 0 ? kv : kv.substring(0, e)), e < 0 ? "" : dec(kv.substring(e + 1))); }
            OutputStream out = s.getOutputStream();
            try { route(path, m, out); }
            catch (Throwable e) { json(out, 500, "{\"ok\":false,\"error\":" + quote(String.valueOf(e)) + "}"); }
        } catch (Throwable e) { Log.w(T, "http: " + e); }
    }

    void route(String path, Map<String, String> m, OutputStream out) throws Exception {
        ScrcpyClient cl = svc.client;
        if (path.equals("/status")) {
            json(out, 200, "{\"ok\":true,\"connected\":" + (cl != null && cl.running) + ",\"width\":" + svc.vw + ",\"height\":" + svc.vh
                    + ",\"humanActive\":" + humanActive() + ",\"manualTakeover\":" + manualTakeover + "}");
            return;
        }
        if (path.equals("/takeover")) {
            manualTakeover = "1".equals(m.get("on"));
            if (!manualTakeover) lastHumanTouch = 0;
            json(out, 200, "{\"ok\":true,\"manualTakeover\":" + manualTakeover + "}");
            return;
        }
        if (path.equals("/screenshot")) { screenshot(out); return; }
        if (path.equals("/window")) { window(m, out); return; }
        if (cl == null || !cl.running) { json(out, 503, "{\"ok\":false,\"error\":\"not connected\"}"); return; }
        if (humanActive() && !"1".equals(m.get("force"))) {
            json(out, 423, "{\"ok\":false,\"error\":\"human has control\",\"humanActive\":true}"); return;
        }
        int w = svc.vw, hgt = svc.vh;
        switch (path) {
            case "/tap": {
                int x = i(m, "x"), y = i(m, "y");
                cl.touch(0, -2L, x, y, w, hgt, 1f); Thread.sleep(60); cl.touch(1, -2L, x, y, w, hgt, 0f);
                break; }
            case "/swipe": {
                int x1 = i(m, "x1"), y1 = i(m, "y1"), x2 = i(m, "x2"), y2 = i(m, "y2");
                int ms = m.containsKey("ms") ? i(m, "ms") : 300, steps = Math.max(4, ms / 16);
                cl.touch(0, -2L, x1, y1, w, hgt, 1f);
                for (int k = 1; k <= steps; k++) { Thread.sleep(ms / steps); cl.touch(2, -2L, x1 + (x2 - x1) * k / steps, y1 + (y2 - y1) * k / steps, w, hgt, 1f); }
                cl.touch(1, -2L, x2, y2, w, hgt, 0f);
                break; }
            case "/key": { int code = i(m, "code"); cl.key(0, code); cl.key(1, code); break; }
            case "/text": cl.text(m.get("s")); break;
            case "/start": cl.startApp(m.get("pkg")); break;
            default: json(out, 404, "{\"ok\":false,\"error\":\"unknown path\"}"); return;
        }
        json(out, 200, "{\"ok\":true}");
    }

    /** /window?x=&y=&w=<dp>  移动/缩放小窗；/window?hide=1 移出屏幕且不可触摸（Surface 保持存活，agent 可继续操作和截图）；hide=0 恢复。 */
    void window(Map<String, String> m, OutputStream out) throws Exception {
        final PipService p = svc;
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { p.applyWindow(m); } catch (Throwable e) { Log.e(T, "window " + e); }
            done.countDown();
        });
        done.await(2, TimeUnit.SECONDS);
        json(out, 200, "{\"ok\":true,\"hidden\":" + p.hidden + "}");
    }

    void screenshot(OutputStream out) throws Exception {
        final SurfaceView sv = svc.sv;
        if (sv == null || svc.vw <= 0) { json(out, 503, "{\"ok\":false,\"error\":\"no surface\"}"); return; }
        final Bitmap bmp = Bitmap.createBitmap(svc.vw, svc.vh, Bitmap.Config.ARGB_8888);
        final int[] res = {-1}; final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { PixelCopy.request(sv, bmp, rc -> { res[0] = rc; latch.countDown(); }, new Handler(Looper.getMainLooper())); }
            catch (Throwable e) { Log.e(T, "pixelcopy " + e); latch.countDown(); }
        });
        if (!latch.await(3, TimeUnit.SECONDS) || res[0] != PixelCopy.SUCCESS) {
            json(out, 500, "{\"ok\":false,\"error\":\"pixelcopy failed rc=" + res[0] + "\"}"); return;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        byte[] b = bo.toByteArray();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(b); out.flush();
    }

    static int i(Map<String, String> m, String k) { return (int) Float.parseFloat(m.get(k)); }
    static String dec(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (Throwable e) { return s; } }
    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static void json(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(b); out.flush();
    }
}
