package me.openminis.sockprobe;
import android.app.Service; import android.content.Intent; import android.net.*; import android.os.IBinder; import android.util.Log;
import java.io.*;
public class Probe extends Service {
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int f, int id) {
        final String name = i.getStringExtra("name");
        new Thread(() -> {
            try {
                LocalSocket s = new LocalSocket();
                s.connect(new LocalSocketAddress(name));
                InputStream in = s.getInputStream();
                byte[] b = new byte[80]; int n = in.read(b);
                StringBuilder hex = new StringBuilder(); for (int k = 0; k < Math.max(n,0); k++) hex.append(String.format("%02x", b[k]));
                Log.i("SOCKPROBE", "CONNECTED read=" + n + " hex=" + hex);
                n = in.read(b);
                Log.i("SOCKPROBE", "second read=" + n);
            } catch (Throwable e) { Log.e("SOCKPROBE", "FAIL " + e); }
        }).start();
        return START_NOT_STICKY;
    }
}
