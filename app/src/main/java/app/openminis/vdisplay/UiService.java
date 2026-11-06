package app.openminis.vdisplay;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Read-only accessibility service. It never reacts to events and never stores anything; it only lets
 * AgentServer call getWindowsOnAllDisplays() (the only public API that sees the virtual display's windows)
 * while answering a local GET /ui on 127.0.0.1.
 */
public class UiService extends AccessibilityService {
    static volatile UiService instance;
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
