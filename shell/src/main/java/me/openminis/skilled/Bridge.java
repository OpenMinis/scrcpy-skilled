package me.openminis.skilled;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.net.*;

/**
 * TCP bridge running as shell. A regular APK cannot connect to abstract sockets created by shell processes (denied by SELinux),
 * but it can connect to 127.0.0.1 TCP. This bridge connects to the scrcpy server's @scrcpy_<scid> (tunnel_forward mode,
 * accepted in order: video, control), then maps those two streams to the 1st and 2nd TCP connections on 127.0.0.1:<port>.
 * Usage: Bridge <scid-hex8> <port> [video|video,control]
 * The scrcpy server itself is unmodified.
 */
public class Bridge {
    public static void main(String[] a) throws Exception {
        String name = "scrcpy_" + a[0];
        int port = Integer.parseInt(a[1]);
        int n = a.length > 2 && a[2].contains("control") ? 2 : 1;
        ServerSocket ss = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("bridge listening 127.0.0.1:" + port + " -> @" + name + " streams=" + n);
        while (true) {
            Socket[] c = new Socket[n];
            LocalSocket[] l = new LocalSocket[n];
            try {
                for (int i = 0; i < n; i++) {
                    c[i] = ss.accept();
                    c[i].setTcpNoDelay(true);
                    // in tunnel_forward mode the server accepts video then control, so we must connect in the same order
                    l[i] = new LocalSocket();
                    l[i].connect(new LocalSocketAddress(name));
                    System.out.println("stream " + i + " connected");
                    pipe(l[i].getInputStream(), c[i].getOutputStream(), "dev->app" + i);
                    pipe(c[i].getInputStream(), l[i].getOutputStream(), "app->dev" + i);
                }
            } catch (Throwable e) {
                System.out.println("session error: " + e);
                for (int i = 0; i < n; i++) { try { if (c[i] != null) c[i].close(); } catch (Throwable t) {} try { if (l[i] != null) l[i].close(); } catch (Throwable t) {} }
            }
            // after a session ends, wait for the next one (scrcpy server usually exits with the session; this is just a simple reset)
            Thread.sleep(500);
        }
    }

    static void pipe(final InputStream in, final OutputStream out, final String tag) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[65536];
            try {
                int r;
                while ((r = in.read(b)) > 0) { out.write(b, 0, r); out.flush(); }
            } catch (IOException e) {}
            System.out.println("pipe end " + tag);
            try { out.close(); } catch (IOException e) {}
            // end of video stream = end of session (user closed the secondary display / server exited). The bridge serves a single session and exits with it to avoid leftover processes.
            if ("dev->app0".equals(tag) || "app->dev0".equals(tag)) { System.out.println("session over, bridge exit"); System.exit(0); }
        });
        t.setDaemon(true);
        t.start();
    }
}
