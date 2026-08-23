package com.martin.persimringtone;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JSON store: {"sub_<subId>": "<uri>|silent"}.
 * Two copies:
 *  - files/persim.json        (private, settings UI)
 *  - files/persim_public.json (world-readable, read directly by the hooked
 *    system process even when our app process is dead/frozen)
 */
public final class Store {
    private static final String FILE = "persim.json";
    public static final String PUBLIC_FILE = "persim_public.json";
    public static final String PKG = "com.martin.persimringtone";

    private Store() {}

    public static JSONObject load(Context ctx) {
        return loadFrom(new File(ctx.getFilesDir(), FILE));
    }

    public static JSONObject loadPublic() {
        return loadFrom(new File("/data/user/0/" + PKG + "/files/" + PUBLIC_FILE));
    }

    private static JSONObject loadFrom(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static synchronized void save(Context ctx, JSONObject obj) {
        String s = obj.toString();
        try (FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE)) {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        try {
            File pub = new File(ctx.getFilesDir(), PUBLIC_FILE);
            try (FileOutputStream fos = new FileOutputStream(pub)) {
                fos.write(s.getBytes(StandardCharsets.UTF_8));
            }
            pub.setReadable(true, false);
            ctx.getFilesDir().setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    /** Make a ringtone file world-readable so system_server can play it directly. */
    public static void makeRingtoneWorldReadable(Context ctx, int subId) {
        try {
            File f = new File(ctx.getFilesDir(), "ringtone_" + subId);
            f.setReadable(true, false);
            ctx.getFilesDir().setExecutable(true, false);
        } catch (Exception ignored) {}
    }
}
