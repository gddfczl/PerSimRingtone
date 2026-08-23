package com.martin.persimringtone;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

/**
 * Reads config from the hooked system process. Order:
 * 1. direct file read (works even if the app process is dead)
 * 2. our ContentProvider (fallback)
 */
public final class PrefsReader {

    public static String getRingtoneForSubId(Context ctx, int subId) {
        if (subId < 0 || ctx == null) return null;

        // 1) direct read of the world-readable copy
        JSONObject o = Store.loadPublic();
        String v = o.optString("sub_" + subId, "");
        if (!v.isEmpty()) return sanitize(v);

        // 2) provider fallback
        try {
            Bundle b = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + Store.PKG + ".prefs/config"),
                    "get", null, null);
            if (b != null) {
                v = new JSONObject(b.getString("json")).optString("sub_" + subId, "");
                if (!v.isEmpty()) return sanitize(v);
            }
        } catch (Throwable t) {
            Log.d(MainHook.TAG, "provider fallback failed: " + t);
        }
        return null;
    }

    /** Only accept URIs pointing at our own ringtone files, or "silent". */
    private static String sanitize(String v) {
        if ("silent".equals(v)) return "silent";
        if (v.startsWith("content://" + Store.PKG + ".prefs/ringtone/")
                || v.startsWith("file:///data/user/0/" + Store.PKG + "/files/ringtone_")) {
            return v;
        }
        return null;
    }
}
