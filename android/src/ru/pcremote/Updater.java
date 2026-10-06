package ru.pcremote;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Self-update: GitHub builds a release on every push; the app compares its
 * versionName with the latest release tag and downloads pcremote.apk.
 * Plain Java; the install itself is an Android intent (see MainActivity).
 */
public final class Updater {
    public static final String REPO = "troublefack1-hue/-";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String ASSET = "pcremote.apk";

    /** The paired PC, if any: updates come from it first (same signing key every time, no GitHub secrets). */
    public static final class Pc { public final String host, pin, secret, lan; public final int port; public Pc(String h, int p, String pin, String s, String lan) { host = h; port = p; this.pin = pin; secret = s; this.lan = lan == null ? "" : lan; } }
    public static volatile Pc pc;

    /** GET from the paired PC over pinned TLS; returns the body (Content-Length or chunked both handled). */
    public static byte[] pcGet(String path, java.io.File saveTo, String sha256) throws IOException {
        Pc p = pc; if (p == null) throw new IOException("ПК не привязан");
        try (javax.net.ssl.SSLSocket s = Pinned.connectPreferLan(p.lan, p.host, p.port, p.pin, null, 10000, true)) {
            s.setSoTimeout(60000);
            java.io.OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + p.host + "\r\nAuthorization: Bearer " + p.secret + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
            java.io.InputStream in = s.getInputStream();
            String status = readLine(in); if (status == null || !status.contains(" 200 ")) throw new IOException("ПК ответил: " + status);
            String l; long len = -1; boolean chunked = false;
            while ((l = readLine(in)) != null && !l.isEmpty()) { String k = l.toLowerCase(); if (k.startsWith("content-length:")) len = Long.parseLong(l.substring(15).trim()); if (k.startsWith("transfer-encoding:") && k.contains("chunked")) chunked = true; }
            java.io.ByteArrayOutputStream mem = saveTo == null ? new java.io.ByteArrayOutputStream() : null;
            java.io.OutputStream dst = saveTo == null ? mem : new java.io.FileOutputStream(saveTo);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try {
                byte[] buf = new byte[65536]; long got = 0;
                if (chunked) {
                    while (true) {
                        String hl = readLine(in); while (hl != null && hl.isEmpty()) hl = readLine(in); if (hl == null) break;
                        int semi = hl.indexOf(';'); long left = Long.parseLong((semi >= 0 ? hl.substring(0, semi) : hl).trim(), 16); if (left == 0) break;
                        while (left > 0) { int r = in.read(buf, 0, (int) Math.min(buf.length, left)); if (r < 0) throw new IOException("обрыв"); dst.write(buf, 0, r); md.update(buf, 0, r); left -= r; }
                        readLine(in);
                    }
                } else {
                    int r; while ((len < 0 || got < len) && (r = in.read(buf)) > 0) { dst.write(buf, 0, r); md.update(buf, 0, r); got += r; }
                }
            } finally { dst.close(); }
            if (sha256 != null && !sha256.isEmpty()) { StringBuilder h = new StringBuilder(); for (byte b : md.digest()) h.append(String.format("%02x", b)); if (!h.toString().equalsIgnoreCase(sha256)) { if (saveTo != null) saveTo.delete(); throw new IOException("файл с ПК повреждён (сумма не совпала)"); } }
            return mem == null ? null : mem.toByteArray();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') sb.append((char) c); }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    /** The PC's copy of {@code asset} if newer than {@code current}: version + sha256 from /api/apk (index). */
    public static Info checkPc(String current, String asset) throws IOException {
        String json = new String(pcGet("/api/apk", null, null), StandardCharsets.UTF_8);
        int i = json.indexOf("\"" + asset + "\""); if (i < 0) return null;
        String version = Pairing.jsonString(json, "version"); String sha = Pairing.jsonString(json.substring(i), "sha256");
        if (version == null || compare(version, current) <= 0) return null;
        return new Info(version, "/api/apk?name=" + asset, sha);
    }

    /** One line of an app's trace to the PC (POST /api/log), best effort, a few seconds at most. */
    public static void pcLog(String app, String line) {
        Pc p = pc; if (p == null) return;
        try (javax.net.ssl.SSLSocket s = Pinned.connectPreferLan(p.lan, p.host, p.port, p.pin, null, 5000, true)) {
            s.setSoTimeout(5000);
            byte[] body = line.getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream out = s.getOutputStream();
            out.write(("POST /api/log?app=" + app + " HTTP/1.1\r\nHost: " + p.host + "\r\nAuthorization: Bearer " + p.secret
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
            readLine(s.getInputStream());
        } catch (Exception ignored) {}
    }

    /** Download {@code asset} from the PC into dir/update.apk, checking the published sum. */
    /** Like downloadPc, into a file of its own (upd-*.apk): two installs at once used to share update.apk and Android
     *  got half of one file ("INSTALL_PARSE_FAILED_NOT_APK", 163840 bytes instead of 70515). Delete it after use. */
    /** Mobile data or a metered hotspot: nothing optional goes over it. */
    public static boolean metered(android.content.Context ctx) {
        try {
            return ((android.net.ConnectivityManager) ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)).isActiveNetworkMetered();
        } catch (Exception e) { return true; }
    }

    /** One copy per build, kept until a newer one: a retry (Android wanted a tap, the phone refused, the owner
     *  pressed the button) reuses it — 06.10.2026 the same 330 KB came down five times in a day over mobile data. */
    public static File downloadPcCached(String asset, File dir, String sha256) throws IOException {
        String tag = sha256 == null || sha256.length() < 16 ? "nosha" : sha256.substring(0, 16).toLowerCase();
        String prefix = "apk-" + asset.replace(".apk", "") + "-";
        File f = new File(dir, prefix + tag + ".apk");
        File[] all = dir.listFiles();
        if (all != null) for (File o : all) if (o.getName().startsWith(prefix) && !o.getName().equals(f.getName())) o.delete();
        if (f.length() > 0 && sha256 != null && sha256.equalsIgnoreCase(sha256Of(f))) return f;
        File tmp = File.createTempFile("dl-", ".apk", dir);
        try { pcGet("/api/apk?name=" + asset, tmp, sha256); }
        catch (IOException e) { tmp.delete(); throw e; }
        f.delete();
        if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("не удалось сохранить " + f.getName()); }
        return f;
    }

    static String sha256Of(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static File downloadPcUnique(String asset, File dir, String sha256) throws IOException {
        File f = File.createTempFile("upd-", ".apk", dir);
        try { pcGet("/api/apk?name=" + asset, f, sha256); return f; } catch (IOException e) { f.delete(); throw e; }
    }

    /** From this version «Мой ПК» and «Интернет через ПК» update each other (silent: Android asks only for an app
     *  updating ITSELF, not for one updating an app it installed). */
    public static final String CROSS_SINCE = "1.147";

    public static File downloadPc(String asset, File dir, String sha256) throws IOException {
        File f = new File(dir, "update.apk"); pcGet("/api/apk?name=" + asset, f, sha256); return f;
    }

    public static final class Info {
        public final String version, url, sha256; public String notes = "";
        Info(String v, String u, String h) { version = v; url = u; sha256 = h; }
    }
    public interface Progress { void on(long done, long total); }

    /** The release's description ("body"), with JSON escapes undone, trimmed to a readable length. */
    static String notesOf(String json) {
        String b = Pairing.jsonString(json, "body");
        if (b == null) return "";
        b = b.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\\"", "\"").replace("\\t", " ").trim();
        return b.length() > 1500 ? b.substring(0, 1500) + "…" : b;
    }

    /** Published checksum of {@code asset} from the release's SHA256SUMS, or null. */
    static String publishedSha256(String json, String asset) {
        try {
            int i = json.indexOf("\"name\":\"SHA256SUMS\"");
            if (i < 0) i = json.indexOf("\"name\": \"SHA256SUMS\"");
            if (i < 0) return null;
            int u = json.indexOf("browser_download_url", i);
            String url = u < 0 ? null : Pairing.jsonString(json.substring(u - 1), "browser_download_url");
            if (url == null) return null;
            for (String line : get(url).split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].replace("*", "").equals(asset)) return parts[0].toLowerCase();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Newer release than {@code current} (e.g. "1.7"), or null. */
    public static Info check(String current) throws IOException { return check(current, ASSET); }

    /** The same for another app built from this repository (its APK is a different release asset). */
    public static Info check(String current, String asset) throws IOException {
        String json = get(API);
        String tag = Pairing.jsonString(json, "tag_name");
        if (tag == null) return null;
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compare(version, current) <= 0) return null;
        int i = json.indexOf("\"name\":\"" + asset + "\"");
        if (i < 0) i = json.indexOf("\"name\": \"" + asset + "\"");
        if (i < 0) return null;
        int u = json.indexOf("browser_download_url", i);
        String url = u < 0 ? null : Pairing.jsonString(json.substring(u - 1), "browser_download_url");
        if (url == null) return null;
        Info info = new Info(version, url, publishedSha256(json, asset)); info.notes = notesOf(json); return info;
    }

    public static File download(String url, File dir) throws IOException { return download(url, dir, null); }
    public static File download(String url, File dir, String sha256) throws IOException { return download(url, dir, sha256, null); }

    public static File download(String url, File dir, String sha256, Progress progress) throws IOException {
        File out = new File(dir, "update.apk");
        HttpURLConnection c = open(url);
        java.security.MessageDigest md;
        try { md = java.security.MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        long total = c.getContentLengthLong(), done = 0;
        try (InputStream in = c.getInputStream(); FileOutputStream f = new FileOutputStream(out)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { f.write(buf, 0, n); md.update(buf, 0, n); done += n; if (progress != null) progress.on(done, total); }
        }
        if (out.length() < 20_000) { out.delete(); throw new IOException("bad apk"); }
        if (sha256 != null) {
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equals(sha256)) { out.delete(); throw new IOException("checksum mismatch"); }
        }
        return out;
    }

    public static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p - q;
        }
        return 0;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "pc-remote");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
