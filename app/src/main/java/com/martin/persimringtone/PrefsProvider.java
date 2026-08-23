package com.martin.persimringtone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/** Serves the stored config and ringtone audio files to the system process. */
public class PrefsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Log.i(MainHook.TAG, "PrefsProvider.onCreate");
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("get".equals(method)) {
            Bundle out = new Bundle();
            out.putString("json", Store.load(getContext()).toString());
            Log.d(MainHook.TAG, "provider.call(get) -> " + Store.load(getContext()));
            return out;
        }
        return null;
    }

    /** content://<authority>/ringtone/<subId> → audio file descriptor */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        Log.i(MainHook.TAG, "openFile uri=" + uri + " mode=" + mode);
        if (!"r".equals(mode)) return null;
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "ringtone".equals(seg.get(0))) {
            File f = new File(getContext().getFilesDir(), "ringtone_" + seg.get(1));
            Log.i(MainHook.TAG, "opening file " + f.getAbsolutePath()
                    + " exists=" + f.exists() + " size=" + f.length());
            if (f.exists()) {
                try {
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f,
                            ParcelFileDescriptor.MODE_READ_ONLY);
                    Log.i(MainHook.TAG, "openFile OK");
                    return pfd;
                } catch (Throwable t) {
                    Log.e(MainHook.TAG, "openFile failed", t);
                }
            }
        }
        Log.w(MainHook.TAG, "openFile returning null");
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "ringtone".equals(seg.get(0))) {
            // MediaPlayer/MediaMetadataRetriever may query metadata; provide a
            // MediaStore-like row so openInputStream/getTitle succeed.
            File f = new File(getContext().getFilesDir(), "ringtone_" + seg.get(1));
            MatrixCursor c = new MatrixCursor(new String[]{
                    "_id", "_display_name", "_size", "mime_type", "_data"});
            c.addRow(new Object[]{
                    (long) seg.get(1).hashCode() & 0x7fffffffL,
                    "persim_ringtone_" + seg.get(1),
                    f.exists() ? f.length() : 0L,
                    guessMime(f.getName()),
                    f.getAbsolutePath()});
            return c;
        }
        MatrixCursor c = new MatrixCursor(new String[]{"json"});
        c.addRow(new Object[]{Store.load(getContext()).toString()});
        return c;
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/x-wav";
        if (n.endsWith(".amr")) return "audio/amr";
        if (n.endsWith(".m4a")) return "audio/mp4";
        return "audio/mpeg";
    }

    @Override public String getType(Uri uri) { return "audio/*"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
