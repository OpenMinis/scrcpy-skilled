import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class Srv {
    static Object im; static Method inject, setDisp; static long downTime;
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        im = c.getDeclaredMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisp = InputEvent.class.getMethod("setDisplayId", int.class);
        ServerSocket ss = new ServerSocket(37911, 5, InetAddress.getByName("127.0.0.1"));
        System.out.println("srv ready uid=" + android.os.Process.myUid());
        while (true) { final Socket s = ss.accept(); new Thread(() -> handle(s)).start(); }
    }
    static void handle(Socket s) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) {
                try {
                    System.out.println("cmd " + l);
                    String[] p = l.split(" ", 2);
                    if (p[0].equals("T")) { String[] f = p[1].split(" "); touch(Integer.parseInt(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Integer.parseInt(f[3])); }
                    else if (p[0].equals("K")) { String[] f = p[1].split(" "); key(Integer.parseInt(f[0]), Integer.parseInt(f[1])); }
                    else if (p[0].equals("S")) { Runtime.getRuntime().exec(new String[]{"sh", "-c", p[1]}); }
                } catch (Throwable e) { e.printStackTrace(); }
            }
        } catch (IOException e) {}
    }
    static void touch(int action, float x, float y, int disp) throws Exception {
        long now = android.os.SystemClock.uptimeMillis();
        if (action == 0) downTime = now;
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDisp.invoke(ev, disp);
        inject.invoke(im, ev, 0);
        ev.recycle();
    }
    static void key(int code, int disp) throws Exception {
        long now = android.os.SystemClock.uptimeMillis();
        for (int act = 0; act < 2; act++) {
            KeyEvent e = new KeyEvent(now, now, act, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            setDisp.invoke(e, disp);
            inject.invoke(im, e, 0);
        }
    }
}
