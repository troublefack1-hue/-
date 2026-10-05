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

    public static final class Info {
        public final String version, url, sha256;
        Info(String v, String u, String h) { version = v; url = u; sha256 = h; }
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
    public static Info check(String current) throws IOException {
        String json = get(API);
        String tag = Pairing.jsonString(json, "tag_name");
        if (tag == null) return null;
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compare(version, current) <= 0) return null;
        int i = json.indexOf("\"name\":\"" + ASSET + "\"");
        if (i < 0) i = json.indexOf("\"name\": \"" + ASSET + "\"");
        if (i < 0) return null;
        int u = json.indexOf("browser_download_url", i);
        String url = u < 0 ? null : Pairing.jsonString(json.substring(u - 1), "browser_download_url");
        return url == null ? null : new Info(version, url, publishedSha256(json, ASSET));
    }

    public static File download(String url, File dir) throws IOException { return download(url, dir, null); }

    public static File download(String url, File dir, String sha256) throws IOException {
        File out = new File(dir, "update.apk");
        HttpURLConnection c = open(url);
        java.security.MessageDigest md;
        try { md = java.security.MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        try (InputStream in = c.getInputStream(); FileOutputStream f = new FileOutputStream(out)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { f.write(buf, 0, n); md.update(buf, 0, n); }
        }
        if (out.length() < 20_000) { out.delete(); throw new IOException("bad apk"); }
        if (sha256 != null) {
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equals(sha256)) { out.delete(); throw new IOException("checksum mismatch"); }
        }
        return out;
    }

    static int compare(String a, String b) {
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
