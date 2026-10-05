package ru.pcremote;

import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The phone's storage for the PC: list / stat / read / write / delete / mkdir / move / find.
 * Requests come from the PC's relay as {"t":"pfs","id":..,"op":..,"path":..}; answers go back as
 * {"t":"pfs_r",...}. File bytes travel as binary frames:
 *   phone -> PC   0x06 + id (8 bytes, space padded) + data      (reads)
 *   PC -> phone   0x07 + id (8 bytes) + data                    (writes, between write_begin and write_end)
 * Paths are POSIX: "/sdcard/DCIM/Camera". Only external storage (and what the user lets us see) is reachable.
 */
public final class PhoneFs {
    public interface Sender {
        void text(String json) throws IOException;
        void binary(byte[] frame) throws IOException;
    }

    private static final int CHUNK = 256 * 1024;
    private final Sender out;
    private final Map<String, RandomAccessFile> writes = new HashMap<>();
    private final Map<String, File> writeTargets = new HashMap<>();

    public PhoneFs(Sender out) { this.out = out; }

    private static File root() { return Environment.getExternalStorageDirectory(); }

    /** "/sdcard/x" or "/storage/emulated/0/x" or "x" -> real file under external storage. */
    static File resolve(String p) throws IOException {
        if (p == null) p = "/sdcard";
        p = p.replace('\\', '/').trim();
        if (p.startsWith("/sdcard")) p = p.substring(7);
        else if (p.startsWith("/storage/emulated/0")) p = p.substring(19);
        else if (p.startsWith("~")) p = p.substring(1);
        File f = new File(root(), p.startsWith("/") ? p.substring(1) : p).getCanonicalFile();
        if (!(f.getPath() + "/").startsWith(root().getCanonicalPath() + "/") && !f.equals(root().getCanonicalFile()))
            throw new IOException("outside storage");
        return f;
    }

    static String show(File f) {
        try {
            String r = root().getCanonicalPath(), p = f.getCanonicalPath();
            return p.startsWith(r) ? "/sdcard" + p.substring(r.length()) : p;
        } catch (IOException e) { return f.getPath(); }
    }

    private static JSONObject item(File f) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName()); o.put("path", show(f)); o.put("dir", f.isDirectory());
        o.put("size", f.isDirectory() ? 0 : f.length()); o.put("mtime", f.lastModified() / 1000);
        return o;
    }

    private void reply(String id, JSONObject o) throws Exception {
        // "t" and "id" first: JSONObject keeps insertion order, and the relay recognises the answer by its type
        JSONObject r = new JSONObject().put("t", "pfs_r").put("id", id);
        for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) { String key = k.next(); r.put(key, o.get(key)); }
        out.text(r.toString());
    }

    private void error(String id, String msg) {
        try { reply(id, new JSONObject().put("ok", false).put("error", msg)); } catch (Exception ignored) {}
    }

    /** Called on a worker thread. */
    public void handle(JSONObject ev) {
        String id = ev.optString("id", "?"), op = ev.optString("op", "");
        try {
            switch (op) {
                case "roots": {
                    JSONArray a = new JSONArray();
                    String[][] roots = {{"Память телефона", "/sdcard"}, {"Камера", "/sdcard/DCIM/Camera"}, {"Скриншоты", "/sdcard/Pictures/Screenshots"},
                            {"Загрузки", "/sdcard/Download"}, {"Картинки", "/sdcard/Pictures"}, {"Документы", "/sdcard/Documents"},
                            {"Видео", "/sdcard/Movies"}, {"Музыка", "/sdcard/Music"}, {"WhatsApp", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"},
                            {"Telegram", "/sdcard/Pictures/Telegram"}};
                    for (String[] r : roots) { File f = resolve(r[1]); if (f.exists()) a.put(new JSONObject().put("name", r[0]).put("path", r[1])); }
                    JSONObject o = new JSONObject().put("ok", true).put("items", a);
                    o.put("free", root().getFreeSpace()); o.put("total", root().getTotalSpace());
                    reply(id, o); break;
                }
                case "list": {
                    File d = resolve(ev.optString("path", "/sdcard"));
                    if (!d.isDirectory()) { error(id, "нет такой папки: " + show(d)); return; }
                    File[] kids = d.listFiles();
                    List<File> list = new ArrayList<>();
                    if (kids != null) for (File k : kids) list.add(k);
                    java.util.Collections.sort(list, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
                    JSONArray a = new JSONArray();
                    for (File k : list) a.put(item(k));
                    reply(id, new JSONObject().put("ok", true).put("path", show(d)).put("items", a)); break;
                }
                case "stat": {
                    File f = resolve(ev.optString("path"));
                    if (!f.exists()) { error(id, "нет файла: " + show(f)); return; }
                    reply(id, new JSONObject().put("ok", true).put("item", item(f))); break;
                }
                case "find": {
                    File d = resolve(ev.optString("path", "/sdcard"));
                    String q = ev.optString("q", "*");
                    Pattern re = Pattern.compile("^" + Pattern.quote(q).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$", Pattern.CASE_INSENSITIVE);
                    JSONArray a = new JSONArray();
                    walk(d, re, a, 0, ev.optInt("limit", 500));
                    reply(id, new JSONObject().put("ok", true).put("items", a)); break;
                }
                case "read": {
                    File f = resolve(ev.optString("path"));
                    if (!f.isFile()) { error(id, "нет файла: " + show(f)); return; }
                    long offset = ev.optLong("offset", 0), size = ev.optLong("size", -1);
                    reply(id, new JSONObject().put("ok", true).put("item", item(f)).put("begin", true));
                    byte[] hdr = idBytes(id);
                    try (FileInputStream in = new FileInputStream(f)) {
                        in.skip(offset);
                        long left = size < 0 ? Long.MAX_VALUE : size;
                        byte[] buf = new byte[CHUNK + 9];
                        buf[0] = 0x06; System.arraycopy(hdr, 0, buf, 1, 8);
                        int n;
                        while (left > 0 && (n = in.read(buf, 9, (int) Math.min(CHUNK, left))) > 0) {
                            out.binary(n == CHUNK ? buf : java.util.Arrays.copyOf(buf, n + 9));
                            left -= n;
                        }
                    }
                    reply(id, new JSONObject().put("ok", true).put("done", true)); break;
                }
                case "write_begin": {
                    File f = resolve(ev.optString("path"));
                    File parent = f.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) { error(id, "нет папки: " + show(parent)); return; }
                    File part = new File(f.getPath() + ".part");
                    RandomAccessFile raf = new RandomAccessFile(part, "rw"); raf.setLength(0);
                    synchronized (writes) { writes.put(id, raf); writeTargets.put(id, f); }
                    reply(id, new JSONObject().put("ok", true).put("ready", true)); break;
                }
                case "write_end": {
                    RandomAccessFile raf; File f;
                    synchronized (writes) { raf = writes.remove(id); f = writeTargets.remove(id); }
                    if (raf == null) { error(id, "нет открытой записи"); return; }
                    raf.close();
                    File part = new File(f.getPath() + ".part");
                    if (f.exists() && !f.delete()) { error(id, "не удалось заменить " + show(f)); return; }
                    if (!part.renameTo(f)) { error(id, "не удалось сохранить " + show(f)); return; }
                    reply(id, new JSONObject().put("ok", true).put("item", item(f))); break;
                }
                case "delete": {
                    File f = resolve(ev.optString("path"));
                    if (f.equals(root().getCanonicalFile())) { error(id, "нельзя удалить корень"); return; }
                    if (!f.exists()) { error(id, "нет файла: " + show(f)); return; }
                    reply(id, new JSONObject().put("ok", rmTree(f))); break;
                }
                case "mkdir": {
                    File f = resolve(ev.optString("path"));
                    reply(id, new JSONObject().put("ok", f.isDirectory() || f.mkdirs()).put("item", item(f))); break;
                }
                case "move": {
                    File a = resolve(ev.optString("path")), b = resolve(ev.optString("to"));
                    if (!a.exists()) { error(id, "нет файла: " + show(a)); return; }
                    File bp = b.getParentFile(); if (bp != null && !bp.isDirectory()) bp.mkdirs();
                    reply(id, new JSONObject().put("ok", a.renameTo(b)).put("item", item(b))); break;
                }
                default: error(id, "unknown op " + op);
            }
        } catch (Exception e) {
            error(id, String.valueOf(e.getMessage()));
        }
    }

    /** Binary 0x07 frame from the PC: append to the open write. */
    public void writeChunk(byte[] frame) {
        if (frame.length < 9) return;
        String id = new String(frame, 1, 8).trim();
        RandomAccessFile raf;
        synchronized (writes) { raf = writes.get(id); }
        if (raf == null) return;
        try { raf.write(frame, 9, frame.length - 9); } catch (IOException ignored) {}
    }

    public static byte[] idBytes(String id) {
        byte[] b = new byte[8];
        java.util.Arrays.fill(b, (byte) ' ');
        byte[] s = id.getBytes();
        System.arraycopy(s, 0, b, 0, Math.min(8, s.length));
        return b;
    }

    private static void walk(File d, Pattern re, JSONArray out, int depth, int limit) throws Exception {
        if (depth > 12 || out.length() >= limit) return;
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (out.length() >= limit) return;
            if (re.matcher(k.getName()).matches()) out.put(item(k));
            if (k.isDirectory() && !k.getName().startsWith(".")) walk(k, re, out, depth + 1, limit);
        }
    }

    private static boolean rmTree(File f) {
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) rmTree(k); }
        return f.delete();
    }
}
