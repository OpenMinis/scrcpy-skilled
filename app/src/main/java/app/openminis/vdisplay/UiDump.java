package app.openminis.vdisplay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;

/** Builds the /ui answer from UiService.getWindowsOnAllDisplays(). Nothing is cached or logged: node text only leaves via the HTTP reply. */
class UiDump {
    static final int MAX_NODES = 6000;

    static class Node {
        int idx, depth, parent; String cls, text, desc, id; Rect b = new Rect();
        boolean container; boolean click, edit, scroll, enabled, checked, selected, focusable; int cx, cy;
    }
    static class Win { int type, layer; String title, pkg; boolean active, focused; List<Node> nodes = new ArrayList<>(); int total; }

    /** Display id of our virtual display: the one named "scrcpy" (created by the scrcpy server); -1 if none. */
    static int ownDisplay(android.content.Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(android.content.Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) if ("scrcpy".equals(d.getName())) return d.getDisplayId();
        } catch (Throwable e) { }
        return -1;
    }

    static boolean serviceEnabled(android.content.Context c) {
        try {
            String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
            return s != null && (s.contains(c.getPackageName() + "/" + UiService.class.getName()) || s.contains(c.getPackageName() + "/.UiService"));
        } catch (Throwable e) { return false; }
    }

    /** Windows of the given display (ordered top layer first). */
    static List<Win> windows(UiService s, int display, int[] size, boolean all) {
        SparseArray<List<AccessibilityWindowInfo>> map = s.getWindowsOnAllDisplays();
        List<Win> res = new ArrayList<>();
        for (int k = 0; k < map.size(); k++) {
            int did = map.keyAt(k);
            if (did != display) continue;
            List<AccessibilityWindowInfo> ws = new ArrayList<>(map.valueAt(k));
            Collections.sort(ws, (a, b) -> b.getLayer() - a.getLayer());
            for (AccessibilityWindowInfo w : ws) {
                Win o = new Win(); o.type = w.getType(); o.layer = w.getLayer(); o.active = w.isActive(); o.focused = w.isFocused();
                o.title = w.getTitle() == null ? "" : w.getTitle().toString();
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    o.pkg = String.valueOf(root.getPackageName());
                    int[] cnt = {0};
                    walk(root, 0, -1, o, cnt, all);
                    o.total = cnt[0];
                } else o.pkg = "";
                res.add(o);
            }
        }
        return res;
    }

    static void walk(AccessibilityNodeInfo n, int depth, int parentIdx, Win w, int[] cnt, boolean all) {
        if (n == null || cnt[0] >= MAX_NODES) return;
        cnt[0]++;
        n.refresh();   // window/child nodes can come from a stale client-side cache: ask the app for current text/state
        Node o = new Node(); o.depth = depth; o.parent = parentIdx;
        n.getBoundsInScreen(o.b);
        o.cls = n.getClassName() == null ? "" : n.getClassName().toString();
        int dot = o.cls.lastIndexOf('.'); if (dot >= 0) o.cls = o.cls.substring(dot + 1);
        o.text = n.getText() == null ? "" : n.getText().toString();
        o.desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        String id = n.getViewIdResourceName(); o.id = id == null ? "" : id.substring(id.indexOf('/') + 1);
        o.click = n.isClickable(); o.edit = n.isEditable(); o.scroll = n.isScrollable(); o.enabled = n.isEnabled();
        o.checked = n.isChecked(); o.selected = n.isSelected(); o.focusable = n.isFocusable();
        boolean vis = n.isVisibleToUser() && o.b.width() > 0 && o.b.height() > 0;
        boolean useful = !o.text.isEmpty() || !o.desc.isEmpty() || !o.id.isEmpty() || o.click || o.edit || o.scroll;
        // layout-only nodes that merely carry an id are hidden from the compact list (still found by find= and shown with all=1)
        o.container = o.text.isEmpty() && o.desc.isEmpty() && !o.click && !o.edit && !o.scroll;
        int myIdx = parentIdx;
        if (all || (vis && useful)) { o.idx = w.nodes.size(); w.nodes.add(o); myIdx = o.idx; }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, myIdx, w, cnt, all);
    }

    /** Fill centre points (reference space); a non-clickable node under a clickable ancestor taps the ancestor's centre. */
    static void centres(Win w, float sc) {
        for (Node n : w.nodes) {
            Rect r = n.b;
            if (!n.click) {
                int p = n.parent;
                while (p >= 0) { Node a = w.nodes.get(p); if (a.click) { r = a.b; break; } p = a.parent; }
            }
            n.cx = Math.round(r.centerX() * sc); n.cy = Math.round(r.centerY() * sc);
        }
    }

    static boolean match(Node n, String f) {
        return n.text.toLowerCase().contains(f) || n.desc.toLowerCase().contains(f) || n.id.toLowerCase().contains(f);
    }

    static String clip(String s) { s = s.replace('\n', ' ').replace('\r', ' '); return s.length() > 80 ? s.substring(0, 80) + "…" : s; }

    static String lineOf(Node n, float sc) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(n.idx).append("] ").append(n.cls);
        if (!n.text.isEmpty()) b.append(" \"").append(clip(n.text).replace("\"", "'")).append('"');
        if (!n.desc.isEmpty() && !n.desc.equals(n.text)) b.append(" desc=\"").append(clip(n.desc).replace("\"", "'")).append('"');
        if (!n.id.isEmpty()) b.append(" id=").append(n.id);
        if (n.click) b.append(" click"); if (n.edit) b.append(" edit"); if (n.scroll) b.append(" scroll");
        if (!n.enabled) b.append(" disabled"); if (n.checked) b.append(" checked"); if (n.selected) b.append(" selected");
        b.append(" (").append(Math.round(n.b.left * sc)).append(',').append(Math.round(n.b.top * sc)).append(")-(")
         .append(Math.round(n.b.right * sc)).append(',').append(Math.round(n.b.bottom * sc)).append(") c=(").append(n.cx).append(',').append(n.cy).append(')');
        return b.toString();
    }

    static String jsonOf(Node n, float sc) {
        StringBuilder b = new StringBuilder("{\"i\":" + n.idx + ",\"d\":" + n.depth + ",\"p\":" + n.parent + ",\"class\":" + AgentServer.quote(n.cls));
        if (!n.text.isEmpty()) b.append(",\"text\":").append(AgentServer.quote(n.text));
        if (!n.desc.isEmpty()) b.append(",\"desc\":").append(AgentServer.quote(n.desc));
        if (!n.id.isEmpty()) b.append(",\"id\":").append(AgentServer.quote(n.id));
        b.append(",\"bounds\":[").append(Math.round(n.b.left * sc)).append(',').append(Math.round(n.b.top * sc)).append(',')
         .append(Math.round(n.b.right * sc)).append(',').append(Math.round(n.b.bottom * sc)).append("],\"center\":[").append(n.cx).append(',').append(n.cy).append(']');
        if (n.click) b.append(",\"clickable\":true"); if (n.edit) b.append(",\"editable\":true"); if (n.scroll) b.append(",\"scrollable\":true");
        if (!n.enabled) b.append(",\"enabled\":false"); if (n.checked) b.append(",\"checked\":true"); if (n.selected) b.append(",\"selected\":true"); if (n.focusable) b.append(",\"focusable\":true");
        return b.append('}').toString();
    }
}
