package ru.pcremote.files;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;

import ru.pcremote.Pinned;

/**
 * The PC's files through PC Remote's relay (/api/files, /api/file, /api/upload, /api/fs), over the
 * same pinned TLS the other apps use. Plain Java with a tiny HTTP/1.1 client: Content-Length and
 * chunked bodies, nothing else is needed. Tested on a desktop JVM against the real relay.
 */
public final class PcClient {
    public final String host, lan, pin, secret; public final int port; public boolean tryLan;
    public PcClient(String host, int port, String pin, String secret, String lan, boolean tryLan) { this.host = host; this.port = port; this.pin = pin; this.secret = secret; this.lan = lan; this.tryLan = tryLan; }

    /** Paths of the shared roots, remembered from the last list(""): a root's parent is the roots screen. */
    public final java.util.Set<String> roots = new java.util.HashSet<>();

    public static final class Resp { public int status; public String type = ""; public long length = -1; public boolean chunked; public InputStream body; public SSLSocket sock; public String name = ""; }

    private SSLSocket open(int timeout) throws IOException { return Pinned.connectPreferLan(lan, host, port, pin, null, timeout, tryLan); }

    private Resp request(String method, String path, String[] headers, InputStream body, long bodyLen, int timeout) throws IOException {
        SSLSocket s = open(timeout);
        s.setSoTimeout(timeout);
        OutputStream out = s.getOutputStream();
        StringBuilder h = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: Bearer " + secret + "\r\nConnection: close\r\n");
        if (headers != null) for (int i = 0; i + 1 < headers.length; i += 2) h.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
        if (body != null) h.append("Content-Length: ").append(bodyLen).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) { byte[] buf = new byte[128 * 1024]; int r; while ((r = body.read(buf)) > 0) out.write(buf, 0, r); }
        out.flush();
        InputStream in = s.getInputStream();
        Resp resp = new Resp(); resp.sock = s;
        String status = line(in);
        if (status == null || !status.startsWith("HTTP/")) throw new IOException("PC answered nothing");
        try { resp.status = Integer.parseInt(status.split(" ")[1]); } catch (Exception e) { throw new IOException("bad status: " + status); }
        String l;
        while ((l = line(in)) != null && !l.isEmpty()) {
            int c = l.indexOf(':'); if (c < 0) continue;
            String k = l.substring(0, c).trim().toLowerCase(), v = l.substring(c + 1).trim();
            if (k.equals("content-length")) resp.length = Long.parseLong(v);
            else if (k.equals("transfer-encoding") && v.toLowerCase().contains("chunked")) resp.chunked = true;
            else if (k.equals("content-type")) resp.type = v;
            else if (k.equals("content-disposition")) { int i = v.indexOf("filename=\""); if (i >= 0) { int j = v.indexOf('"', i + 10); if (j > 0) resp.name = v.substring(i + 10, j); } }
        }
        resp.body = resp.chunked ? new Chunked(in) : in;
        return resp;
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.write(c); }
        return c < 0 && b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Decodes HTTP chunked transfer encoding on the fly. */
    static final class Chunked extends InputStream {
        final InputStream in; long left = 0; boolean eof;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; int r = read(b, 0, 1); return r < 0 ? -1 : b[0] & 0xFF; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (left == 0) {
                String l = line(in); while (l != null && l.isEmpty()) l = line(in);
                if (l == null) { eof = true; return -1; }
                int semi = l.indexOf(';'); left = Long.parseLong((semi >= 0 ? l.substring(0, semi) : l).trim(), 16);
                if (left == 0) { eof = true; line(in); return -1; }
            }
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) { eof = true; return -1; }
            left -= r;
            if (left == 0) line(in);   // CRLF after the chunk
            return r;
        }
    }

    private String text(Resp r) throws IOException {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n; long got = 0; while ((n = r.body.read(buf)) > 0) { b.write(buf, 0, n); got += n; if (r.length >= 0 && got >= r.length) break; } return new String(b.toByteArray(), StandardCharsets.UTF_8); }
        finally { r.sock.close(); }
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; } }

    // ---- API ----
    /** "" lists the shared roots; else a folder's items as entries with ref = remote path. */
    public List<Fs.Entry> list(String path) throws IOException {
        Resp r = request("GET", "/api/files?path=" + enc(path), null, null, 0, 15000);
        String j = text(r);
        if (r.status != 200) throw new IOException(err(j, r.status));
        List<Fs.Entry> out = new ArrayList<>();
        int i = j.indexOf("\"items\"");
        if (i < 0) return out;
        int pos = j.indexOf('[', i);
        while (true) {
            int a = j.indexOf('{', pos); if (a < 0) break;
            int b = matchBrace(j, a); if (b < 0) break;
            String o = j.substring(a, b + 1); pos = b + 1;
            String name = ru.pcremote.Pairing.jsonString(o, "name"), p = ru.pcremote.Pairing.jsonString(o, "path");
            if (name == null || p == null) continue;
            boolean dir = o.contains("\"dir\": true") || o.contains("\"dir\":true");
            long size = num(ru.pcremote.Pairing.jsonNumber(o, "size")), mtime = (long) (dnum(ru.pcremote.Pairing.jsonNumber(o, "mtime")) * 1000);
            out.add(new Fs.Entry(unescape(name), dir, size, mtime, unescape(p)));
            if (path.isEmpty()) roots.add(unescape(p));
            if (j.charAt(skipWs(j, pos)) == ']') break;
        }
        return out;
    }

    static int matchBrace(String s, int from) { int d = 0; boolean q = false; for (int i = from; i < s.length(); i++) { char c = s.charAt(i); if (q) { if (c == '\\') i++; else if (c == '"') q = false; continue; } if (c == '"') q = true; else if (c == '{') d++; else if (c == '}') { d--; if (d == 0) return i; } } return -1; }
    static int skipWs(String s, int i) { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; return Math.min(i, s.length() - 1); }
    static long num(String s) { try { return s == null ? 0 : (long) Double.parseDouble(s); } catch (Exception e) { return 0; } }
    static double dnum(String s) { try { return s == null ? 0 : Double.parseDouble(s); } catch (Exception e) { return 0; } }
    static String unescape(String s) { return s.replace("\\\\", "\\").replace("\\/", "/").replace("\\\"", "\""); }
    static String err(String j, int status) { String e = ru.pcremote.Pairing.jsonString(j, "error"); return e != null ? e : status == 403 ? "ПК отказал (проверьте привязку)" : "ПК ответил " + status; }

    /** Download a remote file into dst (streamed, with progress). */
    public void download(String remotePath, long size, File dst, Fs.Progress p) throws IOException {
        Resp r = request("GET", "/api/file?path=" + enc(remotePath), null, null, 0, 30000);
        try {
            if (r.status != 200) throw new IOException(err(text(r), r.status));
            long total = r.length >= 0 ? r.length : size;
            dst.getParentFile().mkdirs();
            File part = new File(dst.getPath() + ".part");
            try (OutputStream o = new FileOutputStream(part)) {
                byte[] buf = new byte[128 * 1024]; int n; long done = 0;
                while ((n = r.body.read(buf)) > 0) { o.write(buf, 0, n); done += n; if (p != null && !p.onProgress(dst.getName(), done, total)) throw new Fs.Cancelled(); if (r.length >= 0 && done >= r.length) break; }
            }
            if (!part.renameTo(dst)) { dst.delete(); if (!part.renameTo(dst)) throw new IOException("не удалось сохранить " + dst.getName()); }
        } finally { r.sock.close(); }
    }

    /** Upload a local file into the remote folder. */
    public void upload(File src, String remoteDir, Fs.Progress p) throws IOException {
        final long total = src.length();
        try (InputStream in = new FileInputStream(src)) {
            InputStream counted = new InputStream() {
                long done = 0;
                @Override public int read() throws IOException { int c = in.read(); if (c >= 0) tick(1); return c; }
                @Override public int read(byte[] b, int off, int len) throws IOException { int r = in.read(b, off, len); if (r > 0) tick(r); return r; }
                void tick(int n) throws IOException { done += n; if (p != null && !p.onProgress(src.getName(), done, total)) throw new Fs.Cancelled(); }
            };
            Resp r = request("POST", "/api/upload", new String[]{"X-Filename", enc(src.getName()), "X-Dir", enc(remoteDir), "Content-Type", "application/octet-stream"}, counted, total, 120000);
            String j = text(r);
            if (r.status != 200 || !j.contains("\"ok\": true") && !j.contains("\"ok\":true")) throw new IOException(err(j, r.status));
        }
    }

    /** mkdir / rename / delete / copy / move on the PC. */
    public void op(String op, String path, String nameOrTo) throws IOException {
        String body = "{\"op\":\"" + op + "\",\"path\":\"" + jsonEsc(path) + "\"" + (op.equals("mkdir") || op.equals("rename") ? ",\"name\":\"" + jsonEsc(nameOrTo) + "\"" : op.equals("copy") || op.equals("move") ? ",\"to\":\"" + jsonEsc(nameOrTo) + "\"" : "") + "}";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Resp r = request("POST", "/api/fs", new String[]{"Content-Type", "application/json"}, new java.io.ByteArrayInputStream(b), b.length, 120000);
        String j = text(r);
        if (r.status != 200 || !(j.contains("\"ok\": true") || j.contains("\"ok\":true"))) throw new IOException(err(j, r.status));
    }

    static String jsonEsc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
