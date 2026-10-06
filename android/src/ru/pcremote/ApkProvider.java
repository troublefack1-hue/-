package ru.pcremote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Hands a downloaded build to the system installer (content:// is required on Android 7+): update.apk (GitHub)
 *  and the in-app updater's apk-<app>-<sum>.apk (InAppUpdate, its fallback when an install session fails). */
public class ApkProvider extends ContentProvider {
    public static final String AUTHORITY = "ru.pcremote.apk";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File file(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null || n.contains("/") || n.contains("..")) return null;
        boolean ok = "update.apk".equals(n) || (n.startsWith("apk-") && n.endsWith(".apk"));
        return ok ? new File(getContext().getCacheDir(), n) : null;
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        // some installers ask for the name and size first
        File f = file(u);
        if (f == null || !f.exists()) return null;
        android.database.MatrixCursor c = new android.database.MatrixCursor(new String[]{"_display_name", "_size"});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
