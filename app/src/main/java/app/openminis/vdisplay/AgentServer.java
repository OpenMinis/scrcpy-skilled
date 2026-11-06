package app.openminis.vdisplay;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.TextureView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Agent API: a minimal HTTP server on 127.0.0.1:28080 (GET, params in query). Coordinates are virtual-display pixels (e.g. 540x960).
 *   /status                       JSON: video size, connection state, takeover state
 *   /screenshot                   PNG (PixelCopy from the floating SurfaceView, scaled to video size)
 *   /tap?x=&y=
 *   /swipe?x1=&y1=&x2=&y2=&ms=300
 *   /key?code=4                   Android keycode（4=BACK,3=HOME,66=ENTER...）
 *   /text?s=hello                 Input text
 *   /start?pkg=com.xxx            Launch an app on the virtual display
 *   /takeover?on=1|0              Human takeover switch; agent actions return 423 during takeover
 * Touching the floating window auto-enters takeover; control returns after HUMAN_IDLE_MS idle. Agent can pass force=1 to override.
 */
public class AgentServer {
    static final String T = "SKILLED";
    static final long HUMAN_IDLE_MS = 3000;
    public static final int PORT = 28080;

    final PipService svc;
    volatile boolean manualTakeover;
    volatile long lastHumanTouch;
    volatile long lastAgentAction;
    volatile long lastActivity = System.currentTimeMillis();   // bumped by ANY request or touch; drives the idle auto-close
    void activity() { lastActivity = System.currentTimeMillis(); }
    volatile String closedReason = "";
    volatile String title = ""; volatile boolean stopped; volatile boolean closedByUser;
    ServerSocket ss; volatile boolean running;

    AgentServer(PipService s) { svc = s; }

    boolean humanActive() { return manualTakeover || System.currentTimeMillis() - lastHumanTouch < HUMAN_IDLE_MS; }
    void humanTouched() { lastHumanTouch = System.currentTimeMillis(); activity(); }
    void setTakeover(boolean on) { manualTakeover = on; if (!on) { lastHumanTouch = 0; stopped = false; } }
    /** User tapped "Stop now": all agent actions return 423 until "Hand back to AI" or /resume. */
    void stopByUser() { stopped = true; manualTakeover = false; title = ""; }
    boolean held() { return manualTakeover || stopped; }
    String versionName() { try { return svc.getPackageManager().getPackageInfo(svc.getPackageName(), 0).versionName; } catch (Throwable e) { return "unknown"; } }
    long versionCode() { try { return svc.getPackageManager().getPackageInfo(svc.getPackageName(), 0).getLongVersionCode(); } catch (Throwable e) { return -1; } }
    /** 0 = AI idle, 1 = AI acted recently (within 2.5s), 2 = human holds control */
    int uiState() {
        if (stopped || humanActive()) return 2;
        return System.currentTimeMillis() - lastAgentAction < 4000 ? 1 : 0;
    }

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
        activity();                                  // any request (even /status polling) means someone is still using the display
        ScrcpyClient cl = svc.client;
        if (path.equals("/status")) {
            long fr = cl == null ? 0 : cl.frames;
            long rd = cl == null ? 0 : cl.rendered;
            boolean ready = cl != null && cl.running && rd > 0;   // ready = a decoded picture was actually rendered (not merely packets received)
            json(out, 200, "{\"ok\":true,\"connected\":" + (cl != null && cl.running) + ",\"ready\":" + ready + ",\"frames\":" + fr
                    + ",\"rendered\":" + rd + ",\"dropped\":" + (cl == null ? 0 : cl.dropped) + ",\"decodeErrors\":" + (cl == null ? 0 : cl.decodeErrors)
                    + ",\"port\":" + svc.port + ",\"version\":" + quote(versionName()) + ",\"versionCode\":" + versionCode() + ",\"stopped\":" + stopped + ",\"closed\":" + closedByUser + ",\"closedReason\":" + quote(closedReason) + ",\"idleMs\":" + (System.currentTimeMillis() - lastActivity) + ",\"idleLimitMs\":" + svc.idleLimitMs + ",\"width\":" + svc.vw + ",\"height\":" + svc.vh
                    + ",\"displayId\":" + UiDump.ownDisplay(svc) + ",\"serviceEnabled\":" + UiDump.serviceEnabled(svc) + ",\"serviceConnected\":" + (UiService.instance != null)
                    + ",\"humanActive\":" + humanActive() + ",\"manualTakeover\":" + manualTakeover
                    + ",\"error\":" + quote(cl == null ? "no client" : cl.lastError) + "}");
            return;
        }
        if (m.get("title") != null) title = m.get("title");
        if (path.equals("/version")) {
            json(out, 200, "{\"ok\":true,\"app\":\"app.openminis.vdisplay\",\"version\":" + quote(versionName()) + ",\"versionCode\":" + versionCode()
                    + ",\"scrcpy\":\"5.0.1\",\"protocol\":1,\"sdk\":" + android.os.Build.VERSION.SDK_INT + ",\"device\":" + quote(android.os.Build.MODEL) + "}");
            return;
        }
        if (path.equals("/title")) { title = m.containsKey("s") ? m.get("s") : ""; lastAgentAction = System.currentTimeMillis(); json(out, 200, "{\"ok\":true}"); return; }
        if (path.equals("/resume")) { setTakeover(false); json(out, 200, "{\"ok\":true}"); return; }
        if (path.equals("/ping")) { json(out, 200, "{\"ok\":true,\"t\":" + System.currentTimeMillis() + "}"); return; }   // no-op: isolates HTTP transport cost
        if (path.equals("/takeover")) {
            setTakeover("1".equals(m.get("on")));
            json(out, 200, "{\"ok\":true,\"manualTakeover\":" + manualTakeover + "}");
            return;
        }
        if (path.equals("/screenshot")) { lastAgentAction = System.currentTimeMillis(); screenshot(m, out); return; }
        if (path.equals("/window")) { window(m, out); return; }
        if (path.equals("/ui")) { ui(m, out); return; }   // read-only: allowed during takeover/stop, counts as activity
        if (stopped && !"1".equals(m.get("force"))) {
            json(out, 423, "{\"ok\":false,\"error\":\"stopped by user\",\"hint\":\"The user pressed Stop. Ask the user before continuing, or ask them to tap Hand back to AI.\"}"); return;
        }
        if (cl == null || !cl.running) { json(out, 503, "{\"ok\":false,\"error\":\"not connected\"}"); return; }
        if (humanActive() && !"1".equals(m.get("force"))) {
            json(out, 423, "{\"ok\":false,\"error\":\"human has control\",\"humanActive\":true}"); return;
        }
        int w = svc.vw, hgt = svc.vh;
        float rf = 1f; try { if (m.containsKey("ref")) rf = w / Math.max(1f, Float.parseFloat(m.get("ref"))); } catch (Throwable e) {}
        lastAgentAction = System.currentTimeMillis();
        switch (path) {
            case "/tap": {
                int x = c(m, "x", rf), y = c(m, "y", rf);
                cl.touch(0, -2L, x, y, w, hgt, 1f); Thread.sleep(60); cl.touch(1, -2L, x, y, w, hgt, 0f);
                break; }
            case "/swipe": {
                int x1 = c(m, "x1", rf), y1 = c(m, "y1", rf), x2 = c(m, "x2", rf), y2 = c(m, "y2", rf);
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

    /** /window?x=&y=&w=<dp>  Move/resize the floating window; /window?hide=1 moves it off-screen and untouchable (Surface stays alive, agent can still act and screenshot); hide=0 restores. */
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

    void screenshot(Map<String, String> m, OutputStream out) throws Exception {
        final long t0 = System.nanoTime();
        final TextureView sv = svc.sv;
        if (sv == null || svc.vw <= 0) { json(out, 503, "{\"ok\":false,\"error\":\"no surface\"}"); return; }
        final Bitmap bmp = Bitmap.createBitmap(svc.vw, svc.vh, Bitmap.Config.ARGB_8888);
        final int[] res = {-1}; final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { sv.getBitmap(bmp); res[0] = 0; latch.countDown(); }
            catch (Throwable e) { Log.e(T, "pixelcopy " + e); latch.countDown(); }
        });
        if (!latch.await(3, TimeUnit.SECONDS) || res[0] != 0) {
            json(out, 500, "{\"ok\":false,\"error\":\"pixelcopy failed rc=" + res[0] + "\"}"); return;
        }
        final long t1 = System.nanoTime();                                    // bitmap captured
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        float sc = 1f; try { if (m.containsKey("w")) sc = Math.max(0.05f, Math.min(1f, Float.parseFloat(m.get("w")) / svc.vw)); else if (m.containsKey("scale")) sc = Math.max(0.1f, Math.min(1f, Float.parseFloat(m.get("scale")))); } catch (Throwable e) {}
        Bitmap ob = sc < 0.99f ? Bitmap.createScaledBitmap(bmp, Math.max(1, (int) (bmp.getWidth() * sc)), Math.max(1, (int) (bmp.getHeight() * sc)), true) : bmp;
        final long t2 = System.nanoTime();                                    // scaled
        // fmt=jpg|webp: much faster to encode than PNG (PNG ignores the quality setting and is always slow). Default stays PNG (lossless).
        String fmt = m.containsKey("fmt") ? m.get("fmt").toLowerCase() : "png";
        int q = 80; try { if (m.containsKey("q")) q = Math.max(30, Math.min(100, Integer.parseInt(m.get("q")))); } catch (Throwable e) {}
        Bitmap.CompressFormat cf = Bitmap.CompressFormat.PNG; String mime = "image/png";
        if (fmt.equals("jpg") || fmt.equals("jpeg")) { cf = Bitmap.CompressFormat.JPEG; mime = "image/jpeg"; }
        else if (fmt.equals("webp")) { cf = Bitmap.CompressFormat.WEBP_LOSSY; mime = "image/webp"; }
        ob.compress(cf, q, bo);
        byte[] b = bo.toByteArray();
        final long t3 = System.nanoTime();                                    // PNG encoded
        // Server-side timings (ms) so clients can separate app cost from transport cost
        String tm = "X-Timing-Ms: capture=" + (t1 - t0) / 1000000 + ",scale=" + (t2 - t1) / 1000000 + ",encode=" + (t3 - t2) / 1000000 + ",size=" + ob.getWidth() + "x" + ob.getHeight() + "\r\n";
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\n" + tm + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(b); out.flush();
    }


    static final String ENABLE_HINT = "Run: sh scripts/start.sh (enables the service, idempotent; appends to enabled_accessibility_services and keeps other services). Manual: adb shell 'settings put secure enabled_accessibility_services <current>:app.openminis.vdisplay/app.openminis.vdisplay.UiService; settings put secure accessibility_enabled 1'. Meanwhile use agent.sh shot.";

    /** GET /ui — read-only widget tree of a display. See references/agent-api.md. */
    void ui(Map<String, String> m, OutputStream out) throws Exception {
        final UiService us = UiService.instance;
        boolean en = UiDump.serviceEnabled(svc);
        if (us == null) {
            json(out, 503, "{\"ok\":false,\"error\":" + quote(en ? "accessibility service enabled but not connected yet" : "accessibility service not enabled") + ",\"serviceEnabled\":" + en
                    + ",\"serviceConnected\":false,\"hint\":" + quote(ENABLE_HINT) + "}");
            return;
        }
        final String dp = m.containsKey("display") ? m.get("display") : "";
        final int own = UiDump.ownDisplay(svc);
        final boolean listAll = dp.equals("all");
        final int disp = listAll ? -1 : (dp.isEmpty() ? own : Integer.parseInt(dp));
        float ref = 540f; try { if (m.containsKey("ref")) ref = Float.parseFloat(m.get("ref")); } catch (Throwable e) { }
        final String find = m.containsKey("find") ? m.get("find").toLowerCase() : "";
        boolean fClick = "1".equals(m.get("clickable")), fEdit = "1".equals(m.get("editable")), fScroll = "1".equals(m.get("scrollable"));
        int limit = 200; try { if (m.containsKey("limit")) limit = Math.max(1, Integer.parseInt(m.get("limit"))); } catch (Throwable e) { }
        boolean lines = !"json".equals(m.get("format")), all = "1".equals(m.get("all"));
        final float refF = ref;
        // The accessibility calls are binder round trips: run them off the HTTP thread with a deadline so a wedged system service cannot hang the request.
        final Object[] box = new Object[1];
        java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(() -> {
            DisplayManagerHelper h = new DisplayManagerHelper();
            if (listAll) {
                StringBuilder sb = new StringBuilder();
                android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>> map = us.getWindowsOnAllDisplays();
                for (int k = 0; k < map.size(); k++) {
                    sb.append("display ").append(map.keyAt(k)).append(map.keyAt(k) == own ? " (second screen)" : "").append(": ");
                    for (android.view.accessibility.AccessibilityWindowInfo w : map.valueAt(k)) {
                        android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                        sb.append("[type=").append(w.getType()).append(" pkg=").append(r == null ? "-" : r.getPackageName()).append("] ");
                    }
                    sb.append('\n');
                }
                return sb.toString();
            }
            return h.dump(us, disp);
        });
        // run directly on a helper thread
        Thread t = new Thread(task, "ui-dump"); t.start();
        Object res;
        try { res = task.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { json(out, 504, "{\"ok\":false,\"error\":\"accessibility query timed out\"}"); return; }
        if (listAll) {
            String body = (String) res;
            if (lines) { byte[] b = body.getBytes(StandardCharsets.UTF_8); out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes()); out.write(b); out.flush(); }
            else json(out, 200, "{\"ok\":true,\"displays\":" + quote(body) + ",\"displayId\":" + own + "}");
            return;
        }
        @SuppressWarnings("unchecked") Object[] r = (Object[]) res;
        @SuppressWarnings("unchecked") List<UiDump.Win> wins = (List<UiDump.Win>) r[0];
        int dw = (Integer) r[1], dh = (Integer) r[2];
        float sc = dw > 0 ? refF / dw : 1f;
        int off = 0, total = 0, shown = 0; String pkgs = "";
        StringBuilder sb = new StringBuilder(); StringBuilder jb = new StringBuilder();
        boolean filtering = !find.isEmpty() || fClick || fEdit || fScroll;
        for (UiDump.Win w : wins) {
            UiDump.centres(w, sc);
            total += w.total;
            if (!w.pkg.isEmpty() && !pkgs.contains(w.pkg)) pkgs += (pkgs.isEmpty() ? "" : ",") + w.pkg;
            StringBuilder wl = new StringBuilder(); int wc = 0;
            for (UiDump.Node n : w.nodes) {
                n.idx += off; if (n.parent >= 0) n.parent += off;
                if (!filtering && !all && n.container) continue;
                if (!find.isEmpty() && !UiDump.match(n, find)) continue;
                if (fClick && !n.click) continue; if (fEdit && !n.edit) continue; if (fScroll && !n.scroll) continue;
                if (shown >= limit) continue;
                shown++; wc++;
                if (lines) wl.append(UiDump.lineOf(n, sc)).append('\n'); else { if (jb.length() > 0) jb.append(','); jb.append(UiDump.jsonOf(n, sc)); }
            }
            off += w.nodes.size();
            if (lines && (wc > 0 || !filtering)) sb.append("# window type=").append(w.type).append(" pkg=").append(w.pkg).append(w.title.isEmpty() ? "" : " title=\"" + clipT(w.title) + "\"").append(w.focused ? " focused" : "").append('\n').append(wl);
        }
        int rh = dw > 0 ? Math.round(dh * sc) : 0;
        String head = "display=" + disp + (disp == own ? " (second screen)" : "") + " ref=" + Math.round(refF) + "x" + rh + " size=" + dw + "x" + dh + " windows=" + wins.size() + " nodes=" + total + " count=" + shown;
        if (lines) {
            byte[] b = (head + "\n" + sb).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
            out.write(b); out.flush();
        } else {
            json(out, 200, "{\"ok\":true,\"display\":" + disp + ",\"ownDisplay\":" + own + ",\"ref\":[" + Math.round(refF) + "," + rh + "],\"size\":[" + dw + "," + dh + "],\"windows\":" + wins.size() + ",\"packages\":" + quote(pkgs)
                    + ",\"nodes\":" + total + ",\"count\":" + shown + ",\"items\":[" + jb + "]}");
        }
    }
    static String clipT(String s) { return s.length() > 40 ? s.substring(0, 40) : s; }

    class DisplayManagerHelper {
        Object[] dump(UiService us, int disp) {
            int[] sz = {0, 0};
            try {
                android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) svc.getSystemService(android.content.Context.DISPLAY_SERVICE);
                android.view.Display d = dm.getDisplay(disp);
                if (d != null) { android.graphics.Point p = new android.graphics.Point(); d.getRealSize(p); sz[0] = p.x; sz[1] = p.y; }
            } catch (Throwable e) { }
            return new Object[]{UiDump.windows(us, disp, sz, false), sz[0], sz[1]};
        }
    }

    static int c(Map<String, String> m, String k, float rf) { return (int) (Float.parseFloat(m.get(k)) * rf); }
    static int i(Map<String, String> m, String k) { return (int) Float.parseFloat(m.get(k)); }
    static String dec(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (Throwable e) { return s; } }
    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static void json(OutputStream out, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(b); out.flush();
    }
}
