package com.martin.persimringtone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks RingtoneFactory.getRingtone(...) for per-SIM ringtones.
 *
 * TYPE-SAFE STRATEGY: we do NOT guess the method's return type. Instead the
 * ORIGINAL method runs first; in the AFTER hook we inspect the real returned
 * object and rebuild an instance of exactly that shape:
 *   - android.util.Pair   → new Pair(ourRingtone, original.second)   (Motorola)
 *   - android.media.Ringtone → our Ringtone
 *   - android.net.Uri     → our Uri
 * This can never cause a ClassCastException in system_server.
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "PerSimRingtone";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        boolean isSystemServer = "android".equals(lpp.packageName);
        boolean isTelecomProc = "com.android.server.telecom".equals(lpp.packageName);
        if (!isSystemServer && !isTelecomProc) return;

        Log.i(TAG, "loading, host=" + lpp.packageName);

        final ClassLoader cl = lpp.classLoader;
        Thread finder = new Thread(() -> {
            Class<?> factory = null;
            for (int i = 0; i < 60; i++) {
                factory = XposedHelpers.findClassIfExists(
                        "com.android.server.telecom.RingtoneFactory", cl);
                if (factory != null) break;
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
            if (factory == null) {
                Log.e(TAG, "RingtoneFactory NOT found in host " + lpp.packageName);
                return;
            }
            installHooks(factory);

            // Motorola/some ROMs delegate ringing to the dialer app
            // (letDialerHandleRinging=1), bypassing RingtoneFactory entirely.
            // Force that flag off so telecom plays the ringtone itself and
            // our per-SIM substitution takes effect.
            try {
                Class<?> builderCls = XposedHelpers.findClass(
                        "com.android.server.telecom.RingerAttributes$Builder",
                        factory.getClassLoader());
                for (java.lang.reflect.Method bm : builderCls.getDeclaredMethods()) {
                    if (!bm.getName().equals("build")) continue;
                    if (bm.getParameterCount() != 0) continue;
                    XposedBridge.hookMethod(bm, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object attrs = param.getResult();
                                if (attrs == null) return;
                                XposedHelpers.setBooleanField(
                                        attrs, "mLetDialerHandleRinging", false);
                                Log.i(TAG, "forced mLetDialerHandleRinging=false");
                            } catch (Throwable t) {
                                Log.e(TAG, "force flag failed", t);
                            }
                        }
                    });
                    Log.i(TAG, "hooked RingerAttributes$Builder.build");
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "RingerAttributes not found: " + t);
            }
        }, TAG + "-classfinder");
        finder.setDaemon(true);
        finder.start();
    }

    private static void installHooks(Class<?> factory) {
        int hooked = 0;
        for (java.lang.reflect.Method m : factory.getDeclaredMethods()) {
            if (!m.getName().equals("getRingtone")) continue;
            if (m.getParameterCount() < 1) continue;
            final String paramType = m.getParameterTypes()[0].getName();
            try { m.setAccessible(true); } catch (Throwable ignored) {}

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object call = findCallArg(param.args);
                        if (call == null) return;
                        Context ctx = (Context) XposedHelpers.getObjectField(
                                param.thisObject, "mContext");
                        int subId = extractSubId(call);
                        String v = PrefsReader.getRingtoneForSubId(ctx, subId);
                        if (v == null) return;               // 未配置 → 保持系统结果

                        Object orig = param.getResult();     // 原方法真实返回对象
                        Log.i(TAG, "subId=" + subId + " custom=" + v
                                + " origType="
                                + (orig == null ? "null" : orig.getClass().getName()));

                        if ("silent".equals(v)) {            // 该卡静音
                            param.setResult(null);
                            return;
                        }

                        // Prefer direct file path so playback does not depend
                        // on our (possibly frozen) app process.
                        java.io.File rf = new java.io.File(
                                "/data/user/0/" + Store.PKG + "/files/ringtone_" + subId);
                        Uri playUri = rf.canRead()
                                ? Uri.parse("file://" + rf.getAbsolutePath())
                                : Uri.parse(v);
                        Log.i(TAG, "playUri=" + playUri + " canRead=" + rf.canRead());

                        Ringtone ours = makeRingtone(ctx, playUri);
                        if (ours == null) return;            // 创建失败 → 保持系统结果

                        if (orig instanceof android.util.Pair) {
                            // Motorola: Pair<Uri, Ringtone> or similar. The RINGTONE
                            // object is what actually plays; swap whichever element
                            // is a Ringtone with ours, keep the other as-is.
                            Object first = ((android.util.Pair<?, ?>) orig).first;
                            Object second = ((android.util.Pair<?, ?>) orig).second;
                            Object newFirst = first, newSecond = second;
                            if (second instanceof Ringtone) {
                                newSecond = ours;
                            } else if (first instanceof Ringtone) {
                                newFirst = ours;
                            } else {
                                Log.w(TAG, "no Ringtone in Pair(first="
                                        + (first == null ? "null" : first.getClass().getName())
                                        + ", second="
                                        + (second == null ? "null" : second.getClass().getName())
                                        + "), keeping system default");
                                return;
                            }
                            param.setResult(new android.util.Pair<>(newFirst, newSecond));
                            Log.i(TAG, "returning Pair("
                                    + nameOf(newFirst) + ", " + nameOf(newSecond) + ")");
                        } else if (orig instanceof Ringtone) {
                            param.setResult(ours);
                            Log.i(TAG, "returning Ringtone");
                        } else if (orig instanceof Uri) {
                            param.setResult(Uri.parse(v));
                            Log.i(TAG, "returning Uri");
                        } else {
                            // 未知的返回形状：保守起见不动，避免崩溃
                            Log.w(TAG, "unknown result shape, keeping system default");
                        }
                    } catch (Throwable t) {
                        // 绝不让异常逃出 system_server
                        Log.e(TAG, "hook error, keeping system default", t);
                    }
                }
            });
            hooked++;
            Log.i(TAG, "hooked getRingtone(" + paramType + ")");
        }
        Log.i(TAG, "total hooked=" + hooked);
    }

    private static String nameOf(Object o) {
        if (o == null) return "null";
        if (o instanceof Ringtone) return "Ringtone(ours)";
        return o.getClass().getSimpleName();
    }

    private static Ringtone makeRingtone(Context ctx, Uri uri) {
        try {
            Ringtone r = RingtoneManager.getRingtone(ctx, uri);
            if (r != null) {
                r.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "makeRingtone failed", t);
            return null;
        }
    }

    private static Object findCallArg(Object[] args) {
        for (Object a : args) {
            if (a == null) continue;
            if (a.getClass().getName().endsWith(".Call")) return a;
        }
        return null;
    }

    static int extractSubId(Object call) {
        Object handle = tryCall(call, "getTargetPhoneAccount");
        if (handle == null) {
            Object details = tryCall(call, "getDetails");
            handle = details != null ? tryCall(details, "getAccountHandle") : null;
        }
        if (handle != null) {
            String id = (String) tryCall(handle, "getId");
            if (id != null) {
                Log.d(TAG, "account handle id = " + id);
                try { return Integer.parseInt(id.trim()); } catch (NumberFormatException ignored) {}
                java.util.regex.Matcher mm =
                        java.util.regex.Pattern.compile("\\d+").matcher(id);
                if (mm.find()) return Integer.parseInt(mm.group());
            }
        }
        Log.w(TAG, "could not determine subId");
        return -1;
    }

    private static Object tryCall(Object obj, String method) {
        try { return XposedHelpers.callMethod(obj, method); }
        catch (Throwable t) { return null; }
    }
}
