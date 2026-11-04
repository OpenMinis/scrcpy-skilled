package me.openminis.skilled;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.net.*;

/**
 * shell 身份的 TCP 桥。普通 APK 无法连接 shell 进程创建的 abstract socket（SELinux 拒绝），
 * 但可以连 127.0.0.1 TCP。本桥负责：连 scrcpy server 的 @scrcpy_<scid>（tunnel_forward 模式，
 * 顺序 accept：video、control），再把这两路映射到 127.0.0.1:<port> 的第 1、第 2 个 TCP 连接。
 * 用法: Bridge <scid-hex8> <port> [video|video,control]
 * scrcpy server 本身零改动。
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
                    // server 在 tunnel_forward 下按 video、control 的顺序 accept，因此必须按相同顺序连
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
            // 一个会话结束后等下一次（scrcpy server 通常随会话退出，这里只做简单重置）
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
        });
        t.setDaemon(true);
        t.start();
    }
}
