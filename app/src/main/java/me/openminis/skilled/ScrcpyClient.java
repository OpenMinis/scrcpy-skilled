package me.openminis.skilled;

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
 * scrcpy 5.0.1 协议客户端（video + control 两路，经本机 TCP 桥）。
 * 视频流：[dummy 1B][设备名 64B][codec id 4B] 之后循环 [session(12B) 或 packet(12B header + payload)]。
 * 帧头 12B：8B ptsAndFlags（bit63=session，bit62=config，bit61=keyframe）+ 4B size。
 * session 头：4B flags(最高位 1) + 4B width + 4B height。
 */
public class ScrcpyClient {
    static final String T = "SKILLED";
    public interface Listener { void onVideoSize(int w, int h); void onClosed(String why); }

    final String host; final int port; final Surface surface; final Listener l;
    Socket video, control; DataOutputStream ctl; MediaCodec codec;
    volatile boolean running; volatile int vw, vh;
    volatile long frames; volatile long firstFrameAt; volatile String lastError = "";
    final ExecutorService tx = Executors.newSingleThreadExecutor(); // 控制消息统一在发送线程写 socket

    public ScrcpyClient(String host, int port, Surface s, Listener l) { this.host = host; this.port = port; this.surface = s; this.l = l; }

    public void start() {
        running = true;
        new Thread(this::run, "scrcpy-video").start();
    }

    public void stop() {
        running = false; tx.shutdownNow();
        try { if (video != null) video.close(); } catch (Throwable e) {}
        try { if (control != null) control.close(); } catch (Throwable e) {}
        try { if (codec != null) codec.stop(); } catch (Throwable e) {}
        try { if (codec != null) codec.release(); } catch (Throwable e) {}
    }

    void run() {
        try {
            video = new Socket(host, port); video.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(video.getInputStream(), 1 << 16));
            // control 必须在 video 之后连（server 顺序 accept）
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
                if ((pf & (1L << 63)) != 0) {            // session：此时 hdr 是 flags,w,h
                    int w = ByteBuffer.wrap(hdr, 4, 4).getInt(), hh = ByteBuffer.wrap(hdr, 8, 4).getInt();
                    Log.i(T, "session " + w + "x" + hh);
                    vw = w; vh = hh;
                    restartCodec(w, hh);
                    if (l != null) l.onVideoSize(w, hh);
                    continue;
                }
                byte[] data = new byte[size]; in.readFully(data);
                boolean config = (pf & (1L << 62)) != 0, key = (pf & (1L << 61)) != 0;
                long pts = pf & ((1L << 61) - 1);
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

    void restartCodec(int w, int h) throws IOException {
        if (codec != null) { try { codec.stop(); codec.release(); } catch (Throwable e) {} }
        codec = MediaCodec.createDecoderByType("video/avc");
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        codec.configure(f, surface, null, 0);
        codec.start();
    }

    void feed(byte[] data, long pts, boolean config, boolean key) {
        if (codec == null) return;
        try {
            int idx = codec.dequeueInputBuffer(20000);
            if (idx >= 0) {
                ByteBuffer b = codec.getInputBuffer(idx); b.clear(); b.put(data);
                codec.queueInputBuffer(idx, 0, data.length, pts, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int o;
            while ((o = codec.dequeueOutputBuffer(bi, 0)) >= 0) codec.releaseOutputBuffer(o, true);
        } catch (Throwable e) { Log.e(T, "decode err " + e); }
    }

    // ---- 控制消息（大端）----
    public void touch(int a, long p, int x, int y, int sw, int sh, float pr) { tx.execute(() -> touch0(a, p, x, y, sw, sh, pr)); }
    synchronized void touch0(int action, long pointerId, int x, int y, int sw, int sh, float pressure) {
        try {
            ctl.writeByte(2);                            // INJECT_TOUCH_EVENT
            ctl.writeByte(action);
            ctl.writeLong(pointerId);
            ctl.writeInt(x); ctl.writeInt(y); ctl.writeShort(sw); ctl.writeShort(sh);
            ctl.writeShort((int) (Math.min(1f, pressure) * 0xffff));
            ctl.writeInt(action == 1 ? 0 : 1);           // actionButton
            ctl.writeInt(action == 1 ? 0 : 1);           // buttons（按下时 PRIMARY）
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

    /** ASCII 走 INJECT_TEXT；含非 ASCII（中文等）走 SET_CLIPBOARD + paste，因为 INJECT_TEXT 只能注入键盘字符。 */
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
