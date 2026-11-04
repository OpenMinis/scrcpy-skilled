import android.graphics.*;
import android.view.*;
import java.lang.reflect.*;

public class Overlay {
    public static void main(String[] a) throws Exception {
        int secs = a.length > 0 ? Integer.parseInt(a[0]) : 20;
        int w = 600, h = 300;
        SurfaceControl sc = new SurfaceControl.Builder()
                .setName("scrcpy-overlay").setBufferSize(w, h)
                .setFormat(PixelFormat.RGBA_8888).build();
        System.out.println("created layer: " + sc);
        Surface s = new Surface(sc);
        Canvas c = s.lockCanvas(null);
        c.drawColor(Color.argb(220, 255, 0, 0));
        Paint p = new Paint(); p.setColor(Color.WHITE); c.drawRect(40,40,300,140,p); p.setColor(Color.BLUE); c.drawRect(320,120,560,260,p);
        s.unlockCanvasAndPost(c);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        String[] steps = {"setLayerStack:0", "setLayer:2147483647", "setPosition:100,200", "show"};
        Class<?> T = t.getClass();
        try { T.getMethod("setLayerStack", SurfaceControl.class, int.class).invoke(t, sc, 0); System.out.println("layerStack ok"); }
        catch (Throwable e) { System.out.println("layerStack fail " + e); }
        try { T.getMethod("setLayer", SurfaceControl.class, int.class).invoke(t, sc, Integer.MAX_VALUE); System.out.println("layer ok"); }
        catch (Throwable e) { System.out.println("layer fail " + e); }
        try{T.getMethod("setPosition",SurfaceControl.class,float.class,float.class).invoke(t,sc,100f,200f);}catch(Throwable e){System.out.println("pos fail "+e);}
        try{T.getMethod("show",SurfaceControl.class).invoke(t,sc);}catch(Throwable e){System.out.println("show fail "+e);}
        t.apply();
        System.out.println("applied, sleeping " + secs);
        Thread.sleep(secs * 1000L);
        try{T.getMethod("remove",SurfaceControl.class).invoke(t,sc);}catch(Throwable e){System.out.println("rm fail "+e);} t.apply();
    }
}
