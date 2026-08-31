package com.termux.app.mcp.a11y;

import android.view.accessibility.AccessibilityNodeInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Builds a stable digest of query matches without refs, revisions, or bounds. */
final class QueryResultFingerprint {

    private final MessageDigest digest;

    QueryResultFingerprint() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    void add(AccessibilityNodeInfo node) {
        add(
            node.getViewIdResourceName(),
            value(node.getText()),
            value(node.getContentDescription()),
            value(node.getClassName()),
            node.isClickable(),
            node.isEditable(),
            node.isScrollable(),
            node.isFocused(),
            node.isEnabled()
        );
    }

    void add(String resourceId, String text, String contentDescription, String className,
             boolean clickable, boolean editable, boolean scrollable,
             boolean focused, boolean enabled) {
        field(resourceId);
        field(text);
        field(contentDescription);
        field(className);
        digest.update((byte) (clickable ? 1 : 0));
        digest.update((byte) (editable ? 1 : 0));
        digest.update((byte) (scrollable ? 1 : 0));
        digest.update((byte) (focused ? 1 : 0));
        digest.update((byte) (enabled ? 1 : 0));
    }

    String finish() {
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private void field(String value) {
        byte[] bytes = normalize(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
