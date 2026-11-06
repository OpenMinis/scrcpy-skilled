package app.openminis.vdisplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * scrcpy 5.0.1 protocol client (video + control streams, via the local TCP bridge).
 * Video stream: [dummy 1B][device name 64B][codec id 4B], then repeated [session (12B) or packet (12B header + payload)].
 * Frame header 12B: 8B ptsAndFlags (bit63=session, bit62=config, bit61=keyframe) + 4B size.
 * Session header: 4B flags (high bit set) + 4B width + 4B height.
 */
public class ScrcpyClient {
    static final String T = "SKILLED";
    public interface Listener { void onVideoSize(int w, int h); void onClosed(String why); }

    final String host; final int port; volatile Surface surface; final Listener l;
    Socket video, control; DataOutputStream ctl; MediaCodec codec;
    volatile boolean running; volatile int vw, vh;
    volatile long frames, rendered, dropped, decodeErrors, firstRenderAt; volatile boolean needKey = true, dropLogged; volatile byte[] lastConfig; volatile int pendingW, pendingH;
    volatile int skippedDelta; volatile long firstFrameAt; volatile String lastError = "";
    final ExecutorService tx = Executors.newSingleThreadExecutor(); // all control messages are written to the socket on the sender thread

    public ScrcpyClient(String host, int port, Surface s, Listener l) { this.host = host; this.port = port; this.surface = s; this.l = l; }

    public void start() {
        running = true;
        new Thread(this::run, "scrcpy-video").start();
    }

    public void stop() {
        running = false; tx.shutdownNow();
        try { if (video != null) video.close(); } catch (Throwable e) {}
        try { if (control != null) control.close(); } catch (Throwable e) {}
        stopCodec();
    }

    void run() {
        try {
            video = new Socket(host, port); video.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(video.getInputStream(), 1 << 16));
            // control must connect after video (server accepts in order)
            control = new Socket(host, port); control.setTcpNoDelay(true);
            ctl = new DataOutputStream(new BufferedOutputStream(control.getOutputStream()));
            new Thread(this::drainControl, "scrcpy-ctl-in").start();

            in.readUnsignedByte();                       // dummy byte
            byte[] name = new byte[64]; in.readFully(name);
            Log.i(T, "device=" + new String(name).trim());
            int codecId = in.readInt();                  // 'h264' = 0x68323634
            Log.i(T, "codec=0x" + Integer.toHexString(codecId));

            byte[] hdr = new byte[12];
            while (running) {
                in.readFully(hdr);
                ByteBuffer h = ByteBuffer.wrap(hdr);
                long pf = h.getLong(); int size = h.getInt();
                if ((pf & (1L << 63)) != 0) {            // session: hdr holds flags,w,h here
                    int w = ByteBuffer.wrap(hdr, 4, 4).getInt(), hh = ByteBuffer.wrap(hdr, 8, 4).getInt();
                    Log.i(T, "session " + w + "x" + hh);
                    vw = w; vh = hh;
                    restartCodec(w, hh, false);
                    if (l != null) l.onVideoSize(w, hh);
                    continue;
                }
                byte[] data = new byte[size]; in.readFully(data);
                boolean config = (pf & (1L << 62)) != 0, key = (pf & (1L << 61)) != 0;
                long pts = pf & ((1L << 61) - 1);
                if (config) lastConfig = data;
                if (!config) { frames++; if (firstFrameAt == 0) firstFrameAt = System.currentTimeMillis(); }
                feed(data, pts, config, key);
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            Log.e(T, "video loop end: " + e);
            if (l != null) l.onClosed(String.valueOf(e));
        }
    }

    void drainControl() { try { InputStream i = control.getInputStream(); byte[] b = new byte[4096]; while (i.read(b) >= 0) {} } catch (Throwable e) {} }

    /** Decoder (re)start. Never runs without a valid Surface: the size is remembered and the codec is started by setSurface(). */
    synchronized void restartCodec(int w, int h, boolean replay) throws IOException {
        pendingW = w; pendingH = h;
        Surface s = surface;
        if (s == null || !s.isValid()) { Log.w(T, "session " + w + "x" + h + " before a valid Surface: decoder start deferred"); stopCodec(); return; }
        stopCodec();
        MediaCodec c = MediaCodec.createDecoderByType("video/avc");
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        c.configure(f, s, null, 0);
        c.start();
        codec = c; needKey = true;
        Thread d = new Thread(() -> drain(c), "scrcpy-drain"); d.setDaemon(true); d.start();
        // A normal session packet is followed by config + keyframe from the server. When the decoder was deferred (no Surface yet),
        // that data was already consumed: replay the stored config and ask the server for a fresh keyframe (RESET_VIDEO).
        if (replay) { byte[] cfg = lastConfig; if (cfg != null) feed(cfg, 0, true, false); requestKeyframe(); }
    }

    synchronized void stopCodec() {
        MediaCodec c = codec; codec = null;
        if (c != null) { try { c.stop(); } catch (Throwable e) {} try { c.release(); } catch (Throwable e) {} }
    }

    /** The Surface was (re)created after the client started (or a session arrived before it existed): start decoding on it. */
    public synchronized void setSurface(Surface s) {
        surface = s;
        if (s != null && s.isValid() && codec == null && pendingW > 0 && running) {
            try { restartCodec(pendingW, pendingH, true); } catch (Throwable e) { lastError = "setSurface: " + e; Log.e(T, "setSurface restart failed: " + e); }
        }
    }

    /** The Surface is going away: drop the decoder (it would render into a dead Surface); setSurface() restarts it. */
    public synchronized void detachSurface() { surface = null; stopCodec(); rendered = 0; }

    /** Output side: runs on its own thread so a static picture (decoder holding back its last frame) still gets rendered and counted. */
    void drain(MediaCodec c) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (running && codec == c) {
            try {
                int o = c.dequeueOutputBuffer(bi, 20000);
                if (o >= 0) {
                    boolean draw = bi.size > 0;
                    c.releaseOutputBuffer(o, draw);
                    if (draw) { rendered++; if (firstRenderAt == 0) firstRenderAt = System.currentTimeMillis(); }
                }
            } catch (Throwable e) {
                if (codec == c && running) { decodeErrors++; lastError = "drain: " + e; Log.e(T, "decoder drain error: " + e); }
                return;
            }
        }
    }

    /** Queue one packet. Config/key packets are never dropped silently: retry up to ~500 ms, then ask the server for a new keyframe. */
    void feed(byte[] data, long pts, boolean config, boolean key) {
        MediaCodec c = codec;
        if (c == null) return;
        if (!config && needKey && !key) { if (++skippedDelta % 30 == 0) requestKeyframe(); return; }   // deltas before the first keyframe only produce garbage
        try {
            boolean must = config || key;
            long deadline = System.currentTimeMillis() + (must ? 500 : 0);
            int idx;
            do { idx = c.dequeueInputBuffer(20000); } while (idx < 0 && System.currentTimeMillis() < deadline && running && codec == c);
            if (idx >= 0) {
                ByteBuffer b = c.getInputBuffer(idx); b.clear(); b.put(data);
                c.queueInputBuffer(idx, 0, data.length, pts, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
                if (key) { needKey = false; skippedDelta = 0; }
            } else {
                dropped++;
                if (!dropLogged || must) { dropLogged = true; Log.w(T, "decoder input stalled: dropped " + (config ? "config" : key ? "key" : "delta") + " packet (total dropped=" + dropped + ")"); }
                if (must) requestKeyframe();
            }
        } catch (Throwable e) {
            decodeErrors++; lastError = "decode: " + e;
            Log.e(T, "decode error (config=" + config + " key=" + key + " size=" + data.length + "): " + e);
            if (config || key) requestKeyframe();
        }
    }

    /** scrcpy RESET_VIDEO (type 17): the server restarts its encoder so the next packets are config + a keyframe. */
    public void requestKeyframe() { try { tx.execute(this::reset0); } catch (Throwable e) {} }
    synchronized void reset0() {
        try { if (ctl == null) return; ctl.writeByte(17); ctl.flush(); Log.i(T, "requested keyframe (RESET_VIDEO)"); }
        catch (Throwable e) { Log.e(T, "reset video send " + e); }
    }

    // ---- control messages (big-endian) ----
    public void touch(int a, long p, int x, int y, int sw, int sh, float pr) { tx.execute(() -> touch0(a, p, x, y, sw, sh, pr)); }
    synchronized void touch0(int action, long pointerId, int x, int y, int sw, int sh, float pressure) {
        try {
            ctl.writeByte(2);                            // INJECT_TOUCH_EVENT
            ctl.writeByte(action);
            ctl.writeLong(pointerId);
            ctl.writeInt(x); ctl.writeInt(y); ctl.writeShort(sw); ctl.writeShort(sh);
            ctl.writeShort((int) (Math.min(1f, pressure) * 0xffff));
            ctl.writeInt(action == 1 ? 0 : 1);           // actionButton
            ctl.writeInt(action == 1 ? 0 : 1);           // buttons (PRIMARY while pressed)
            ctl.flush();
        } catch (Throwable e) { Log.e(T, "touch send " + e); }
    }

    public void key(int a, int k) { tx.execute(() -> key0(a, k)); }
    synchronized void key0(int action, int keycode) {
        try { ctl.writeByte(0); ctl.writeByte(action); ctl.writeInt(keycode); ctl.writeInt(0); ctl.writeInt(0); ctl.flush(); }
        catch (Throwable e) { Log.e(T, "key send " + e); }
    }

    public void startApp(String p) { tx.execute(() -> startApp0(p)); }
    synchronized void startApp0(String pkg) {
        try { byte[] b = pkg.getBytes("UTF-8"); ctl.writeByte(16); ctl.writeByte(b.length); ctl.write(b); ctl.flush(); }
        catch (Throwable e) { Log.e(T, "startApp send " + e); }
    }

    /** ASCII uses INJECT_TEXT; non-ASCII (e.g. CJK) uses SET_CLIPBOARD + paste, since INJECT_TEXT can only inject keyboard characters. */
    public void text(String t) {
        boolean ascii = true; for (int i = 0; i < t.length(); i++) if (t.charAt(i) > 126 || t.charAt(i) < 32) { ascii = false; break; }
        final boolean a = ascii;
        tx.execute(() -> { if (a) text0(t); else paste0(t); });
    }
    synchronized void paste0(String s) {
        try { byte[] b = s.getBytes("UTF-8"); ctl.writeByte(9); ctl.writeLong(0L); ctl.writeByte(1); ctl.writeInt(b.length); ctl.write(b); ctl.flush(); }
        catch (Throwable e) { Log.e(T, "paste send " + e); }
    }
    synchronized void text0(String s) {
        try { byte[] b = s.getBytes("UTF-8"); ctl.writeByte(1); ctl.writeInt(b.length); ctl.write(b); ctl.flush(); }
        catch (Throwable e) { Log.e(T, "text send " + e); }
    }
}
