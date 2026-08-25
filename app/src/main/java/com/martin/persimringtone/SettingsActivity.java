package com.martin.persimringtone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Settings UI: per-SIM ringtone picker. The chosen audio is COPIED into the
 * app's private dir and exposed through our own provider, so the hooked
 * system_server can play it without any external-storage access.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PERMS = 900;
    private static final int REQ_RINGTONE = 1000;

    private LinearLayout root;
    private JSONObject prefs;
    private List<SubscriptionInfo> subs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = Store.load(this);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 顶部加大留白，避免被系统标题栏/状态栏遮挡
        root.setPadding(48, 160, 48, 48);
        scroll.addView(root);
        setContentView(scroll);

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PERMS);
        } else {
            buildUi();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        buildUi();
    }

    private void buildUi() {
        root.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Per-SIM Ringtone 设置");
        title.setTextSize(22);
        root.addView(title);

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            TextView t = new TextView(this);
            t.setText("需要“电话”权限才能读取 SIM 卡信息。");
            t.setPadding(0, 32, 0, 0);
            root.addView(t);
            Button retry = new Button(this);
            retry.setText("授权");
            retry.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PERMS));
            root.addView(retry);
            return;
        }

        try {
            SubscriptionManager sm = (SubscriptionManager)
                    getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            subs = sm.getActiveSubscriptionInfoList();
        } catch (Throwable t) { subs = null; }

        if (subs == null || subs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("未检测到活动的 SIM 卡。\n（请确认双卡已插入并启用）");
            t.setPadding(0, 32, 0, 0);
            root.addView(t);
            return;
        }

        for (SubscriptionInfo info : subs) {
            try { addSimRow(info); } catch (Throwable ignored) {}
        }

        TextView hint = new TextView(this);
        hint.setText("\n说明：铃声文件会复制一份到本应用私有空间，修改后无需重启。\n"
                + "卸载本应用即完全还原系统行为。");
        hint.setTextSize(12);
        root.addView(hint);
    }

    private void addSimRow(SubscriptionInfo info) {
        final int subId = info.getSubscriptionId();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 40, 0, 0);

        String number = "";
        try { number = info.getNumber() == null ? "" : info.getNumber(); } catch (Throwable ignored) {}
        TextView name = new TextView(this);
        name.setText("SIM " + (info.getSimSlotIndex() + 1)
                + " — " + info.getDisplayName()
                + (number.isEmpty() ? "" : " (" + maskNumber(number) + ")")
                + "\nsubId=" + subId);
        name.setTextSize(16);
        row.addView(name);

        TextView current = new TextView(this);
        current.setTextSize(13);
        current.setText(currentText(subId));
        current.setTag("cur_" + subId);
        row.addView(current);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button pick = new Button(this);
        pick.setText("选择铃声");
        pick.setOnClickListener(v -> {
            try {
                Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
                startActivityForResult(i, REQ_RINGTONE + subId);
            } catch (Throwable ignored) {}
        });
        btns.addView(pick);

        Button clear = new Button(this);
        clear.setText("恢复默认");
        clear.setOnClickListener(v -> {
            prefs.remove("sub_" + subId);
            Store.save(this, prefs);
            new java.io.File(getFilesDir(), "ringtone_" + subId).delete();
            refreshCurrentLabels();
        });
        btns.addView(clear);

        row.addView(btns);
        root.addView(row);
    }

    private String currentText(int subId) {
        String v = prefs.optString("sub_" + subId, "");
        if (v.isEmpty()) return "当前：跟随系统默认";
        if ("silent".equals(v)) return "当前：静音";
        // 我们的 provider URI 系统解析不了标题，直接确认文件存在即可
        java.io.File f = new java.io.File(getFilesDir(), "ringtone_" + subId);
        return f.exists() && f.length() > 0
                ? "当前：已设置自定义铃声 (" + (f.length() / 1024) + " KB)"
                : "当前：已设置（铃声文件缺失，请重选）";
    }

    private static Uri providerUri(int subId) {
        return Uri.parse("content://com.martin.persimringtone.prefs/ringtone/" + subId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int subId = requestCode - REQ_RINGTONE;
        if (resultCode != RESULT_OK || data == null || subId <= 0) return;
        Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);

        try {
            if (picked == null) {                       // 用户选了"无" → 静音? 不，选无=跟随默认
                // RingtonePicker 的"无"(silent toggle off) 返回 null；
                // 我们把"静音"用 EXTRA 布尔区分不了，这里统一视为恢复默认。
                prefs.remove("sub_" + subId);
                Store.save(this, prefs);
            } else {
                copyRingtone(subId, picked);
                Store.makeRingtoneWorldReadable(this, subId);
                prefs.put("sub_" + subId, providerUri(subId).toString());
                Store.save(this, prefs);
            }
            refreshCurrentLabels();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "保存失败: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Copy the picked audio into private storage so system_server can read it. */
    private void copyRingtone(int subId, Uri src) throws Exception {
        InputStream in = getContentResolver().openInputStream(src);
        if (in == null) throw new Exception("无法读取所选铃声");
        java.io.File dst = new java.io.File(getFilesDir(), "ringtone_" + subId);
        try (OutputStream out = openFileOutput(dst.getName(), Context.MODE_PRIVATE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private void refreshCurrentLabels() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View row = root.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout ll = (LinearLayout) row;
            for (int j = 0; j < ll.getChildCount(); j++) {
                View c = ll.getChildAt(j);
                Object tag = c.getTag();
                if (tag instanceof String && ((String) tag).startsWith("cur_")) {
                    int sid = Integer.parseInt(((String) tag).substring(4));
                    ((TextView) c).setText(currentText(sid));
                }
            }
        }
    }

    private static String maskNumber(String n) {
        if (n.length() <= 4) return n;
        return n.substring(0, 3) + "****" + n.substring(n.length() - 4);
    }
}
