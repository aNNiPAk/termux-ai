package com.termux.app.mcp.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

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
    public void onAccessibilityEvent(AccessibilityEvent event) { /* PoC: snapshot is pull-based */ }

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
        return new UiSnapshot(snapshotId, SNAPSHOT_TTL_MS, nodes).toJson();
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
