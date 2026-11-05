import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * Test helper (shell uid): inject a two-finger pinch on display 0 via InputManager.injectInputEvent.
 * Usage: app_process / Pinch <cx> <cy> <startHalfGap> <endHalfGap> [steps] [holdMsBeforeMove]
 * Both fingers go down, optionally stay still for holdMs, then spread/close horizontally, then lift.
 */
public class Pinch {
    static Object im; static Method inject;

    public static void main(String[] a) throws Exception {
        float cx = Float.parseFloat(a[0]), cy = Float.parseFloat(a[1]);
        float g0 = Float.parseFloat(a[2]), g1 = Float.parseFloat(a[3]);
        int steps = a.length > 4 ? Integer.parseInt(a[4]) : 12;
        long hold = a.length > 5 ? Long.parseLong(a[5]) : 0;
        // Android 14+: InputManagerGlobal; older: InputManager.getInstance()
        Class<?> c;
        try { c = Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch (ClassNotFoundException e) { c = Class.forName("android.hardware.input.InputManager"); }
        im = c.getDeclaredMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);

        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, 1, cx, cy, g0);
        send(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, cx, cy, g0);
        if (hold > 0) {                                   // keep both fingers still, sending MOVEs like a real touchscreen
            long end = SystemClock.uptimeMillis() + hold;
            while (SystemClock.uptimeMillis() < end) { send(down, MotionEvent.ACTION_MOVE, 2, cx, cy, g0); Thread.sleep(16); }
        }
        for (int i = 1; i <= steps; i++) {
            float g = g0 + (g1 - g0) * i / steps;
            send(down, MotionEvent.ACTION_MOVE, 2, cx, cy, g);
            Thread.sleep(16);
        }
        send(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, cx, cy, g1);
        send(down, MotionEvent.ACTION_UP, 1, cx, cy, g1);
        System.out.println("pinch done");
    }

    static void send(long downTime, int action, int count, float cx, float cy, float gap) throws Exception {
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            pp[i] = new MotionEvent.PointerProperties(); pp[i].id = i; pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords(); pc[i].x = i == 0 ? cx - gap : cx + gap; pc[i].y = cy; pc[i].pressure = 1f; pc[i].size = 1f;
        }
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject.invoke(im, e, 2 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */);
        e.recycle();
    }
}
