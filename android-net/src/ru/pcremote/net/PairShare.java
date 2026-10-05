package ru.pcremote.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

/**
 * Takes the pairing from «Мой ПК» (its PairingProvider), so this app never asks for a code of its own.
 * Called on every start: when «Мой ПК» re-pairs, we follow. Without «Мой ПК», or with a copy signed by
 * another key (the provider is signature-guarded), nothing changes and manual pairing still works.
 */
final class PairShare {
    private static final Uri URI = Uri.parse("content://ru.pcremote.pairing/pc");

    static boolean sync(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(URI, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return false;
            String host = c.getString(c.getColumnIndexOrThrow("host")), pin = c.getString(c.getColumnIndexOrThrow("pin"));
            String secret = c.getString(c.getColumnIndexOrThrow("secret")), lan = c.getString(c.getColumnIndexOrThrow("lan"));
            int port = c.getInt(c.getColumnIndexOrThrow("port"));
            if (host == null || host.isEmpty() || secret == null || secret.isEmpty()) return false;
            SharedPreferences p = ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE);
            if (secret.equals(p.getString("secret", "")) && host.equals(p.getString("host", "")) && port == p.getInt("port", 8443)
                    && String.valueOf(pin).equals(p.getString("pin", "")) && String.valueOf(lan).equals(p.getString("lan", ""))) return true;
            p.edit().putString("hostport", host + ":" + port).putString("host", host).putInt("port", port)
                    .putString("secret", secret).putString("pin", pin == null ? "" : pin).putString("lan", lan == null ? "" : lan).apply();
            return true;
        } catch (Exception e) {   // «Мой ПК» not installed, another signing key, an old version without the provider
            return false;
        }
    }
}
