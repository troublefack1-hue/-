package ru.pcremote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Pair once, every app knows the PC: «Интернет через ПК» reads this app's pairing from here
 * (content://ru.pcremote.pairing/pc). Guarded by a signature permission, so only our own apps,
 * signed with the same key, can read the secret.
 */
public final class PairingProvider extends ContentProvider {
    public static final String[] COLUMNS = {"host", "port", "pin", "secret", "lan"};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        SharedPreferences p = getContext().getSharedPreferences("pcremote", Context.MODE_PRIVATE);
        if (p.contains("secret") && !p.getString("host", "").isEmpty())
            c.addRow(new Object[]{p.getString("host", ""), p.getInt("port", 8443), p.getString("pin", ""), p.getString("secret", ""), p.getString("lan", "")});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
