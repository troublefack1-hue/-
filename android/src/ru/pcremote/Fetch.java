package ru.pcremote;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A file from the PC with resume («докачка»): what already came stays in dest.part, the next try asks only for the
 * rest (HTTP Range — the PC's aiohttp FileResponse answers 206). On mobile data a 330 KB app used to start over after
 * every drop. Pure Java (no android.*): tested on the desktop JVM against the real relay (tests/test_fetch_jvm.py).
 */
public final class Fetch {
    public interface Progress { void on(long done, long total); }

    private Fetch() {}

    /** dest complete and checked against sha256 (hex, may be null), or an IOException — dest.part then keeps what came. */
    public static File get(String lan, String host, int port, String pin, String secret, String path, File dest,
                           String sha256, Progress progress) throws IOException {
        File part = new File(dest.getPath() + ".part");
        if (dest.length() > 0 && (sha256 == null || sha256.equalsIgnoreCase(sha256Of(dest)))) return dest;
        if (part.length() > 0 && sha256 != null && sha256.equalsIgnoreCase(sha256Of(part))) return finish(part, dest);
        long have = part.length();
        long left = -1, total = -1;
        try (javax.net.ssl.SSLSocket s = Pinned.connectPreferLan(lan, host, port, pin, null, 10000, true)) {
            s.setSoTimeout(30000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: Bearer " + secret + "\r\n"
                    + (have > 0 ? "Range: bytes=" + have + "-\r\n" : "") + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            String status = line(in);
            if (status == null) throw new IOException("ПК не ответил");
            if (status.contains(" 416 ")) {   // the part is as long as the file or longer: it is not this file, start over
                part.delete();
                throw new IOException("файл на ПК сменился, начну заново");
            }
            boolean partial = status.contains(" 206 ");
            if (!partial && !status.contains(" 200 ")) throw new IOException("ПК ответил: " + status);
            String l;
            while ((l = line(in)) != null && !l.isEmpty()) {
                String k = l.toLowerCase();
                if (k.startsWith("content-length:")) left = Long.parseLong(l.substring(15).trim());
                if (k.startsWith("content-range:")) {
                    int slash = l.lastIndexOf('/');
                    if (slash > 0) try { total = Long.parseLong(l.substring(slash + 1).trim()); } catch (NumberFormatException ignored) {}
                }
            }
            if (!partial) { have = 0; total = left; }
            long got = have;
            try (OutputStream f = new FileOutputStream(part, partial)) {
                byte[] buf = new byte[16384];
                while (left != 0) {
                    int n = in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    f.write(buf, 0, n);
                    got += n;
                    if (left > 0) left -= n;
                    if (progress != null) progress.on(got, total);
                }
            }
        }
        if (left > 0) throw new IOException("связь оборвалась на " + part.length() + " из " + total + " байт, докачаю");
        if (sha256 != null && !sha256.equalsIgnoreCase(sha256Of(part))) {
            part.delete();   // the whole length came and the sum is wrong: a changed file, not a resumable one
            throw new IOException("сумма не сошлась, начну заново");
        }
        return finish(part, dest);
    }

    private static File finish(File part, File dest) throws IOException {
        dest.delete();
        if (!part.renameTo(dest)) throw new IOException("не удалось сохранить " + dest.getName());
        return dest;
    }

    public static String sha256Of(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }
}
