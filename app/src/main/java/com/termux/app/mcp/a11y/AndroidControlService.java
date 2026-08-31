package com.termux.app.mcp.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code android-control} AccessibilityService: reads the active-window
 * accessibility tree, hands out TTL-bound node refs, and performs tap / set-text
 * on those refs. No root, no adb. Backs the MCP {@code android-control} tools.
 *
 * <p>All public methods return a JSON string (the MCP tool payload). Errors use
 * the typed taxonomy: SERVICE_OFF, STALE_REF, NODE_NOT_FOUND, ACTION_UNSUPPORTED,
 * GESTURE_FAILED, NO_ACTIVE_WINDOW.
 */
public final class AndroidControlService extends AccessibilityService {

    public static volatile AndroidControlService INSTANCE;

    private static final long SNAPSHOT_TTL_MS = 8000L;
    private static final int MAX_NODES = 500;

    private final Map<String, AccessibilityNodeInfo> refMap = new HashMap<>();
    private int snapshotSeq = 0;
    private long snapshotExpiresAt = 0L;
    private final AtomicLong uiRevision = new AtomicLong(0L);
    private final Object revisionLock = new Object();
    private volatile int lastUiEventType = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (INSTANCE == this) INSTANCE = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        lastUiEventType = type;
        uiRevision.incrementAndGet();
        synchronized (revisionLock) {
            revisionLock.notifyAll();
        }
    }

    @Override
    public void onInterrupt() { }

    // ---- public API used by the MCP server (returns JSON strings) ----

    public static String statusJson() {
        JSONObject o = new JSONObject();
        try { o.put("enabled", INSTANCE != null); } catch (Exception ignored) {}
        return o.toString();
    }

    public synchronized String snapshotJson() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errJson("NO_ACTIVE_WINDOW");
        clearRefs();
        snapshotSeq++;
        String snapshotId = UiSnapshot.newSnapshotId(snapshotSeq);
        snapshotExpiresAt = System.currentTimeMillis() + SNAPSHOT_TTL_MS;

        List<UiSnapshot.UiNode> nodes = new ArrayList<>();
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int i = 0;
        while (!q.isEmpty() && i < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            boolean interactable = n.isClickable() || n.isEditable() || n.isScrollable()
                || (n.getText() != null && n.getText().length() > 0);
            if (interactable) {
                String ref = "n" + i;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                nodes.add(new UiSnapshot.UiNode(
                    ref,
                    str(n.getClassName()),
                    str(n.getText()),
                    n.getViewIdResourceName(),
                    str(n.getContentDescription()),
                    "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]",
                    n.isClickable(), n.isEditable(), n.isScrollable(), n.isFocused(),
                    compactActions(n)));
                refMap.put(ref, n);
                i++;
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo child = n.getChild(c);
                if (child != null) q.add(child);
            }
        }
        return new UiSnapshot(snapshotId, SNAPSHOT_TTL_MS, uiRevision.get(), nodes).toJson();
    }

    public synchronized String queryUiJson(JSONObject filters) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errJson("NO_ACTIVE_WINDOW");
        clearRefs();
        snapshotSeq++;
        snapshotExpiresAt = System.currentTimeMillis() + SNAPSHOT_TTL_MS;

        int limit = Math.max(1, Math.min(100, filters == null ? 20 : filters.optInt("limit", 20)));
        JSONArray matches = new JSONArray();
        String ancestorResourceId = filters == null ? ""
            : filters.optString("ancestor_resource_id", "");
        int[] nextRef = new int[] {0};
        QueryResultFingerprint fingerprint = new QueryResultFingerprint();
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES && matches.length() < limit) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }

            if (matchesFilters(node, filters)) {
                String ref = "n" + nextRef[0]++;
                fingerprint.add(node);
                refMap.put(ref, node);
                JSONObject match = queryNodeJson(ref, node);
                String ancestorRef = findAndRegisterAncestor(node, ancestorResourceId, nextRef);
                if (ancestorRef != null) {
                    try { match.put("ancestor_ref", ancestorRef); } catch (Exception ignored) {}
                }
                matches.put(match);
            } else {
                try { node.recycle(); } catch (Exception ignored) {}
            }
        }
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node != null) try { node.recycle(); } catch (Exception ignored) {}
        }

        String resultHash = fingerprint.finish();
        String previousHash = filters == null ? "" : filters.optString("if_result_hash", "");
        boolean conditional = filters != null && filters.has("if_result_hash");
        JSONObject result = new JSONObject();
        try {
            result.put("revision", uiRevision.get());
            result.put("result_hash", resultHash);
            if (conditional && previousHash.equals(resultHash)) {
                result.put("changed", false);
                return result.toString();
            }
            result.put("ttl_ms", SNAPSHOT_TTL_MS);
            if (conditional) result.put("changed", true);
            result.put("nodes", matches);
        } catch (Exception ignored) {}
        return result.toString();
    }

    public String waitForChangeJson(long since, int timeoutMs) {
        if (since < 0L) return errJson("INVALID_REVISION");
        int timeout = Math.max(1, Math.min(30000, timeoutMs));
        long deadline = SystemClock.elapsedRealtime() + timeout;
        synchronized (revisionLock) {
            while (uiRevision.get() <= since) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                try {
                    revisionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return errJson("WAIT_INTERRUPTED");
                }
            }
        }
        long current = uiRevision.get();
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("changed", current > since);
            result.put("timeout", current <= since);
            result.put("revision", current);
            if (current > since) result.put("event_type", eventTypeName(lastUiEventType));
        } catch (Exception ignored) {}
        return result.toString();
    }

    public synchronized String tapJson(String ref) {
        AccessibilityNodeInfo node = resolve(ref);
        if (node == null) return refError(ref);
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return okJson();
        }
        // fallback: gesture tap at node centre (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            Path p = new Path();
            p.moveTo(b.centerX(), b.centerY());
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 50);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            boolean dispatched = dispatchGesture(gesture, null, null);
            return dispatched ? okJson() : errJson("GESTURE_FAILED");
        }
        return errJson("ACTION_UNSUPPORTED");
    }

    public synchronized String typeJson(String ref, String text) {
        AccessibilityNodeInfo node = resolve(ref);
        if (node == null) return refError(ref);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text == null ? "" : text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? okJson() : errJson("ACTION_UNSUPPORTED");
    }

    public synchronized String backJson() {
        return performGlobalAction(GLOBAL_ACTION_BACK)
            ? okJson() : errJson("BACK_ACTION_FAILED");
    }

    public synchronized String scrollJson(String ref, String direction) {
        final int action;
        if ("forward".equals(direction)) {
            action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        } else if ("backward".equals(direction)) {
            action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        } else {
            return errJson("INVALID_DIRECTION");
        }

        if (ref != null && !ref.isEmpty()) {
            AccessibilityNodeInfo node = resolve(ref);
            if (node == null) return refError(ref);
            ActionAttempt attempt = performOnNodeOrAncestor(node, action);
            if (attempt.performed) return okJson();
            return errJson(attempt.supported ? "SCROLL_ACTION_FAILED" : "SCROLL_NOT_SUPPORTED");
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errJson("NO_ACTIVE_WINDOW");
        boolean supported = false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                try {
                    ActionAttempt attempt = performOnNodeOrAncestor(focused, action);
                    if (attempt.performed) return okJson();
                    supported = attempt.supported;
                } finally {
                    try { focused.recycle(); } catch (Exception ignored) {}
                }
            }

            ActionAttempt attempt = performOnBestScrollable(root, action);
            if (attempt.performed) return okJson();
            supported |= attempt.supported;
        } finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
        return errJson(supported ? "SCROLL_ACTION_FAILED" : "SCROLL_NOT_SUPPORTED");
    }

    public synchronized String imeActionJson(String ref) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return errJson("ime_action_not_supported");
        }

        AccessibilityNodeInfo node = null;
        boolean recycle = false;
        if (ref != null && !ref.isEmpty()) {
            node = resolve(ref);
            if (node == null) return refError(ref);
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return errJson("NO_ACTIVE_WINDOW");
            try {
                node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                recycle = node != null;
            } finally {
                try { root.recycle(); } catch (Exception ignored) {}
            }
            if (node == null) return errJson("INPUT_FOCUS_NOT_FOUND");
        }

        try {
            int actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
            if (!node.isEditable() && !node.isFocusable() && !supportsAction(node, actionId)) {
                return errJson("ime_action_not_supported");
            }
            if (!node.isFocused()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (supportsAction(node, actionId)) {
                return node.performAction(actionId)
                    ? okJson() : errJson("IME_ACTION_FAILED");
            }

            // A ref may point to a cached pre-focus node. Refresh the current
            // input focus before deciding whether ACTION_IME_ENTER is absent.
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return errJson("NO_ACTIVE_WINDOW");
            AccessibilityNodeInfo focused = null;
            try {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) return errJson("INPUT_FOCUS_NOT_FOUND");
                if (ref != null && !ref.isEmpty() && !node.equals(focused)) {
                    return errJson("INPUT_FOCUS_FAILED");
                }
                if (!supportsAction(focused, actionId)) {
                    return errJson("ime_action_not_supported");
                }
                return focused.performAction(actionId)
                    ? okJson() : errJson("IME_ACTION_FAILED");
            } finally {
                if (focused != null) {
                    try { focused.recycle(); } catch (Exception ignored) {}
                }
                try { root.recycle(); } catch (Exception ignored) {}
            }
        } finally {
            if (recycle) {
                try { node.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    public synchronized String openUriJson(String uriValue, String packageName) {
        if (uriValue == null || uriValue.isEmpty()) return errJson("INVALID_URI");
        try {
            android.net.Uri uri = android.net.Uri.parse(uriValue);
            String scheme = uri.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
                return errJson("URI_SCHEME_NOT_ALLOWED");
            }
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, uri);
            if (packageName != null && !packageName.isEmpty()) intent.setPackage(packageName);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return okJson();
        } catch (android.content.ActivityNotFoundException e) {
            return errJson("URI_HANDLER_NOT_FOUND");
        } catch (Exception e) {
            return errJson("OPEN_URI_FAILED");
        }
    }

    public synchronized String launchAppJson(String pkg) {
        if (pkg == null || pkg.isEmpty()) return errJson("PACKAGE_NOT_FOUND");
        try {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return errJson("PACKAGE_NOT_FOUND");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return okJson();
        } catch (Exception e) {
            return errJson("LAUNCH_FAILED");
        }
    }

    public synchronized String wakeJson() {
        try {
            android.content.Intent i = new android.content.Intent(this, WakeActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return okJson();
        } catch (Exception e) {
            return errJson("WAKE_FAILED");
        }
    }

    // ---- helpers ----

    private static final class ActionAttempt {
        final boolean supported;
        final boolean performed;

        ActionAttempt(boolean supported, boolean performed) {
            this.supported = supported;
            this.performed = performed;
        }
    }

    private static ActionAttempt performOnNodeOrAncestor(AccessibilityNodeInfo node, int action) {
        AccessibilityNodeInfo current = node;
        boolean recycleCurrent = false;
        boolean supported = false;
        while (current != null) {
            AccessibilityNodeInfo parent = null;
            try {
                if (supportsAction(current, action)) {
                    supported = true;
                    if (current.performAction(action)) return new ActionAttempt(true, true);
                }
                parent = current.getParent();
            } finally {
                if (recycleCurrent) {
                    try { current.recycle(); } catch (Exception ignored) {}
                }
            }
            current = parent;
            recycleCurrent = true;
        }
        return new ActionAttempt(supported, false);
    }

    private static ActionAttempt performOnBestScrollable(AccessibilityNodeInfo root, int action) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = null;
        long bestScore = -1L;
        boolean supported = false;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            if (supportsAction(node, action)) {
                supported = true;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                long width = Math.max(0, bounds.width());
                long height = Math.max(0, bounds.height());
                long score = width * height + (height >= width ? width * height : 0);
                if (node.isVisibleToUser() && score > bestScore) {
                    if (best != null) try { best.recycle(); } catch (Exception ignored) {}
                    best = AccessibilityNodeInfo.obtain(node);
                    bestScore = score;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            try { node.recycle(); } catch (Exception ignored) {}
        }
        if (best == null) return new ActionAttempt(supported, false);
        try {
            return new ActionAttempt(true, best.performAction(action));
        } finally {
            try { best.recycle(); } catch (Exception ignored) {}
        }
    }

    private static boolean supportsAction(AccessibilityNodeInfo node, int actionId) {
        if (node == null) return false;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action != null && action.getId() == actionId) return true;
        }
        return false;
    }

    private static List<String> compactActions(AccessibilityNodeInfo node) {
        List<String> out = new ArrayList<>();
        addAction(out, node, AccessibilityNodeInfo.ACTION_CLICK, "click");
        addAction(out, node, AccessibilityNodeInfo.ACTION_SET_TEXT, "set_text");
        addAction(out, node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_forward");
        addAction(out, node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "scroll_backward");
        addAction(out, node, AccessibilityNodeInfo.ACTION_FOCUS, "focus");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            addAction(out, node,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId(), "ime_enter");
        }
        return out;
    }

    private static void addAction(List<String> out, AccessibilityNodeInfo node,
                                  int actionId, String name) {
        if (supportsAction(node, actionId)) out.add(name);
    }

    private static String eventTypeName(int type) {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return "window_state_changed";
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return "window_content_changed";
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) return "view_scrolled";
        return "unknown";
    }

    private static boolean matchesFilters(AccessibilityNodeInfo node, JSONObject filters) {
        if (filters == null) return true;
        if (filters.optBoolean("visible_only", false) && !node.isVisibleToUser()) {
            return false;
        }
        String text = str(node.getText());
        if (filters.has("text") && !equalsIgnoreCase(text, filters.optString("text", ""))) {
            return false;
        }
        if (filters.has("text_contains")) {
            String needle = filters.optString("text_contains", "").toLowerCase(Locale.ROOT);
            if (text == null || !text.toLowerCase(Locale.ROOT).contains(needle)) return false;
        }
        if (filters.has("resource_id")
            && !filters.optString("resource_id", "").equals(node.getViewIdResourceName())) return false;
        if (filters.has("content_desc")
            && !filters.optString("content_desc", "").equals(str(node.getContentDescription()))) return false;
        if (filters.has("class_name")
            && !filters.optString("class_name", "").equals(str(node.getClassName()))) return false;
        if (filters.has("clickable") && filters.optBoolean("clickable") != node.isClickable()) return false;
        if (filters.has("editable") && filters.optBoolean("editable") != node.isEditable()) return false;
        if (filters.has("scrollable") && filters.optBoolean("scrollable") != node.isScrollable()) return false;
        return !filters.has("focused") || filters.optBoolean("focused") == node.isFocused();
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && left.toLowerCase(Locale.ROOT).equals(
            (right == null ? "" : right).toLowerCase(Locale.ROOT));
    }

    private String findAndRegisterAncestor(AccessibilityNodeInfo node,
                                           String resourceId, int[] nextRef) {
        if (resourceId == null || resourceId.isEmpty()) return null;
        AccessibilityNodeInfo ancestor = node.getParent();
        while (ancestor != null) {
            if (resourceId.equals(ancestor.getViewIdResourceName())) {
                for (Map.Entry<String, AccessibilityNodeInfo> entry : refMap.entrySet()) {
                    if (ancestor.equals(entry.getValue())) {
                        try { ancestor.recycle(); } catch (Exception ignored) {}
                        return entry.getKey();
                    }
                }
                String ref = "n" + nextRef[0]++;
                refMap.put(ref, ancestor);
                return ref;
            }
            AccessibilityNodeInfo parent = ancestor.getParent();
            try { ancestor.recycle(); } catch (Exception ignored) {}
            ancestor = parent;
        }
        return null;
    }

    private static JSONObject queryNodeJson(String ref, AccessibilityNodeInfo node) {
        JSONObject out = new JSONObject();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        try {
            out.put("ref", ref);
            putIfPresent(out, "text", str(node.getText()));
            putIfPresent(out, "contentDescription", str(node.getContentDescription()));
            putIfPresent(out, "resourceId", node.getViewIdResourceName());
            putIfPresent(out, "className", str(node.getClassName()));
            out.put("bounds", "[" + bounds.left + "," + bounds.top + "]["
                + bounds.right + "," + bounds.bottom + "]");
            out.put("clickable", node.isClickable());
            out.put("editable", node.isEditable());
            out.put("scrollable", node.isScrollable());
            out.put("focused", node.isFocused());
            out.put("enabled", node.isEnabled());
            List<String> actions = compactActions(node);
            if (!actions.isEmpty()) {
                JSONArray array = new JSONArray();
                for (String action : actions) array.put(action);
                out.put("actions", array);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void putIfPresent(JSONObject out, String key, String value) {
        if (value == null || value.isEmpty()) return;
        try { out.put(key, value); } catch (Exception ignored) {}
    }

    private AccessibilityNodeInfo resolve(String ref) {
        if (System.currentTimeMillis() > snapshotExpiresAt) return null; // stale snapshot
        return refMap.get(ref);
    }

    private String refError(String ref) {
        if (System.currentTimeMillis() > snapshotExpiresAt) return errJson("STALE_REF");
        return errJson("NODE_NOT_FOUND");
    }

    private void clearRefs() {
        for (AccessibilityNodeInfo n : refMap.values()) {
            try { n.recycle(); } catch (Exception ignored) {}
        }
        refMap.clear();
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String okJson() {
        try { return new JSONObject().put("ok", true).toString(); }
        catch (Exception e) { return "{\"ok\":true}"; }
    }

    private static String errJson(String code) {
        try { return new JSONObject().put("ok", false).put("error", code).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"error\":\"" + code + "\"}"; }
    }
}
