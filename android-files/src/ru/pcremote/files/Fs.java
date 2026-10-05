package ru.pcremote.files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything the file manager does with files, in plain Java: listing, sorting, kinds, sizes,
 * copy/move/delete with progress, a trash with restore, zip in both directions, search.
 * No Android here, so it is tested on a desktop JVM (test/FsTest.java).
 */
public final class Fs {

    public enum Kind { FOLDER, IMAGE, VIDEO, AUDIO, APK, ARCHIVE, DOC, TEXT, OTHER }

    public static class Entry {
        public final File file; public final String name; public final boolean dir; public final long size, mtime; public final Kind kind;
        /** For zip / PC entries: an id the location understands (entry name, remote path); null for local files. */
        public final String ref;
        public Entry(File f) {
            file = f; name = f.getName(); dir = f.isDirectory(); size = dir ? 0 : f.length(); mtime = f.lastModified();
            kind = dir ? Kind.FOLDER : kindOf(name); ref = null;
        }
        public Entry(String name, boolean dir, long size, long mtime, String ref) {
            this.file = new File(ref == null ? name : ref); this.name = name; this.dir = dir; this.size = size; this.mtime = mtime;
            this.kind = dir ? Kind.FOLDER : kindOf(name); this.ref = ref;
        }
        public boolean local() { return ref == null; }
    }

    public enum Sort { NAME, DATE, SIZE, TYPE }

    public interface Progress { /** @return false to cancel */ boolean onProgress(String current, long done, long total); }

    public static final class Cancelled extends IOException { Cancelled() { super("cancelled"); } }

    // ---- kinds -------------------------------------------------------
    public static String ext(String name) { int i = name.lastIndexOf('.'); return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT); }

    public static Kind kindOf(String name) {
        switch (ext(name)) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic": case "heif": case "svg": case "avif": return Kind.IMAGE;
            case "mp4": case "mkv": case "webm": case "avi": case "mov": case "3gp": case "m4v": case "ts": return Kind.VIDEO;
            case "mp3": case "m4a": case "aac": case "ogg": case "opus": case "flac": case "wav": case "wma": case "amr": return Kind.AUDIO;
            case "apk": case "apks": case "xapk": return Kind.APK;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "tgz": case "bz2": case "xz": return Kind.ARCHIVE;
            case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "odt": case "ods": case "epub": case "fb2": case "djvu": return Kind.DOC;
            case "txt": case "md": case "log": case "json": case "xml": case "csv": case "ini": case "cfg": case "yml": case "yaml": case "html": case "htm": case "js": case "py": case "java": case "c": case "cpp": case "h": case "sh": case "bat": return Kind.TEXT;
            default: return Kind.OTHER;
        }
    }

    public static String mime(String name) {
        String e = ext(name);
        switch (e) {
            case "jpg": case "jpeg": return "image/jpeg"; case "png": return "image/png"; case "gif": return "image/gif"; case "webp": return "image/webp"; case "bmp": return "image/bmp"; case "svg": return "image/svg+xml"; case "heic": return "image/heic";
            case "mp4": case "m4v": return "video/mp4"; case "mkv": return "video/x-matroska"; case "webm": return "video/webm"; case "avi": return "video/x-msvideo"; case "mov": return "video/quicktime"; case "3gp": return "video/3gpp";
            case "mp3": return "audio/mpeg"; case "m4a": return "audio/mp4"; case "aac": return "audio/aac"; case "ogg": case "opus": return "audio/ogg"; case "flac": return "audio/flac"; case "wav": return "audio/wav"; case "amr": return "audio/amr";
            case "apk": return "application/vnd.android.package-archive"; case "zip": return "application/zip"; case "rar": return "application/vnd.rar"; case "7z": return "application/x-7z-compressed"; case "gz": case "tgz": return "application/gzip"; case "tar": return "application/x-tar";
            case "pdf": return "application/pdf"; case "doc": return "application/msword"; case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel"; case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; case "ppt": return "application/vnd.ms-powerpoint"; case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "epub": return "application/epub+zip"; case "txt": case "log": case "ini": case "cfg": return "text/plain"; case "md": return "text/markdown"; case "json": return "application/json"; case "xml": return "text/xml"; case "csv": return "text/csv"; case "html": case "htm": return "text/html";
            default: return e.isEmpty() ? "application/octet-stream" : "application/" + e;
        }
    }

    // ---- listing ----------------------------------------------------
    public static List<Entry> list(File dir, boolean hidden, Sort sort, boolean desc) {
        File[] fs = dir.listFiles();
        List<Entry> out = new ArrayList<>();
        if (fs != null) for (File f : fs) if (hidden || !f.getName().startsWith(".")) out.add(new Entry(f));
        sort(out, sort, desc);
        return out;
    }

    public static void sort(List<Entry> items, Sort sort, boolean desc) {
        Comparator<Entry> byName = (a, b) -> natural(a.name, b.name);
        Comparator<Entry> c;
        switch (sort) {
            case DATE: c = (a, b) -> Long.compare(b.mtime, a.mtime); break;
            case SIZE: c = (a, b) -> Long.compare(b.size, a.size); break;
            case TYPE: c = (a, b) -> { int k = ext(a.name).compareTo(ext(b.name)); return k != 0 ? k : natural(a.name, b.name); }; break;
            default: c = byName;
        }
        final Comparator<Entry> inner = desc ? c.reversed() : c;
        Collections.sort(items, (a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : inner.compare(a, b));   // folders always first
    }

    /** "img2" before "img10", case-insensitive. */
    public static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int k = na.compareTo(nb); if (k != 0) return k;
            } else {
                int k = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (k != 0) return k;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    public static String size(long n) {
        if (n < 1024) return n + " Б";
        double v = n / 1024.0; if (v < 1024) return fmt(v) + " КБ";
        v /= 1024; if (v < 1024) return fmt(v) + " МБ";
        v /= 1024; return fmt(v) + " ГБ";
    }
    private static String fmt(double v) { return v < 10 ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf(Math.round(v)); }

    public static String date(long t) { return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(t)); }

    /** Children count for a folder's subtitle (quick, non-recursive). */
    public static int count(File dir) { String[] n = dir.list(); return n == null ? 0 : n.length; }

    /** Total bytes under a tree (for properties / copy progress). */
    public static long treeSize(File f) {
        if (!f.isDirectory()) return f.length();
        long s = 0; File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) s += treeSize(c);
        return s;
    }

    // ---- operations ---------------------------------------------------
    /** A name that does not exist yet in dir: "x.txt" -> "x (1).txt". */
    public static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, e = "";
        int i = name.lastIndexOf('.');
        if (i > 0) { base = name.substring(0, i); e = name.substring(i); }
        for (int n = 1; n < 10000; n++) { f = new File(dir, base + " (" + n + ")" + e); if (!f.exists()) return f; }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + e);
    }

    public static void copy(File src, File dstDir, Progress p) throws IOException { copyTree(src, unique(dstDir, src.getName()), p, new long[]{0}, treeSize(src)); }

    private static void copyTree(File src, File dst, Progress p, long[] done, long total) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("не удалось создать " + dst);
            File[] fs = src.listFiles();
            if (fs != null) for (File c : fs) copyTree(c, new File(dst, c.getName()), p, done, total);
            dst.setLastModified(src.lastModified());
            return;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024]; int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r); done[0] += r;
                if (p != null && !p.onProgress(src.getName(), done[0], total)) { out.close(); dst.delete(); throw new Cancelled(); }
            }
        }
        dst.setLastModified(src.lastModified());
    }

    /** Rename when on the same volume, else copy + delete. */
    public static void move(File src, File dstDir, Progress p) throws IOException {
        File dst = unique(dstDir, src.getName());
        if (src.renameTo(dst)) return;
        copyTree(src, dst, p, new long[]{0}, treeSize(src));
        deleteTree(src);
    }

    public static boolean deleteTree(File f) {
        if (f.isDirectory() && !isSymlink(f)) { File[] fs = f.listFiles(); if (fs != null) for (File c : fs) deleteTree(c); }
        return f.delete();
    }

    static boolean isSymlink(File f) { try { return !f.getCanonicalFile().equals(f.getAbsoluteFile()); } catch (IOException e) { return false; } }

    public static File rename(File f, String newName) throws IOException {
        if (newName.isEmpty() || newName.contains("/")) throw new IOException("недопустимое имя");
        File dst = new File(f.getParentFile(), newName);
        if (dst.exists()) throw new IOException("такое имя уже есть");
        if (!f.renameTo(dst)) throw new IOException("не удалось переименовать");
        return dst;
    }

    public static File mkdir(File dir, String name) throws IOException {
        if (name.isEmpty() || name.contains("/")) throw new IOException("недопустимое имя");
        File d = new File(dir, name);
        if (d.exists()) throw new IOException("такое имя уже есть");
        if (!d.mkdir()) throw new IOException("не удалось создать папку");
        return d;
    }

    // ---- trash ---------------------------------------------------------
    /** Trash lives in <root>/.Проводник/Корзина; each item keeps its origin in a sidecar ".origin" line. */
    public static File trashDir(File root) { File d = new File(root, ".Проводник/Корзина"); d.mkdirs(); return d; }

    public static void toTrash(File f, File root) throws IOException {
        File t = trashDir(root);
        File dst = unique(t, f.getName());
        if (!f.renameTo(dst)) { copyTree(f, dst, null, new long[]{0}, treeSize(f)); deleteTree(f); }
        File meta = new File(t, "." + dst.getName() + ".origin");
        try (OutputStream o = new FileOutputStream(meta)) { o.write((f.getAbsolutePath() + "\n" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)); }
    }

    public static String trashOrigin(File trashed) {
        File meta = new File(trashed.getParentFile(), "." + trashed.getName() + ".origin");
        try (InputStream in = new FileInputStream(meta)) {
            byte[] b = new byte[4096]; int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, StandardCharsets.UTF_8).split("\n")[0];
        } catch (IOException e) { return null; }
    }

    public static File restore(File trashed) throws IOException {
        String origin = trashOrigin(trashed);
        File target = origin == null ? null : new File(origin);
        if (target == null || target.getParentFile() == null) throw new IOException("неизвестно, откуда файл");
        target.getParentFile().mkdirs();
        if (target.exists()) target = unique(target.getParentFile(), target.getName());
        if (!trashed.renameTo(target)) { copyTree(trashed, target, null, new long[]{0}, treeSize(trashed)); deleteTree(trashed); }
        new File(trashed.getParentFile(), "." + trashed.getName() + ".origin").delete();
        return target;
    }

    public static void purge(File trashed) { new File(trashed.getParentFile(), "." + trashed.getName() + ".origin").delete(); deleteTree(trashed); }

    // ---- zip ------------------------------------------------------------
    public static File unzip(File zip, File dstParent, Progress p) throws IOException {
        String base = zip.getName(); int i = base.lastIndexOf('.'); if (i > 0) base = base.substring(0, i);
        File dst = unique(dstParent, base);
        dst.mkdirs();
        long total = zip.length(), done = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e; byte[] buf = new byte[128 * 1024];
            while ((e = in.getNextEntry()) != null) {
                File out = new File(dst, e.getName());
                if (!out.getCanonicalPath().startsWith(dst.getCanonicalPath() + File.separator) && !out.getCanonicalPath().equals(dst.getCanonicalPath())) continue;   // zip slip
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) { int r; while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); } }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
                done += Math.max(0, e.getCompressedSize());
                if (p != null && !p.onProgress(e.getName(), Math.min(done, total), total)) throw new Cancelled();
            }
        }
        return dst;
    }

    public static File zip(List<File> items, File dstDir, String name, Progress p) throws IOException {
        File dst = unique(dstDir, name.endsWith(".zip") ? name : name + ".zip");
        long total = 0; for (File f : items) total += treeSize(f);
        long[] done = {0};
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(dst))) {
            for (File f : items) zipAdd(out, f, f.getName(), p, done, total);
        } catch (IOException e) { dst.delete(); throw e; }
        return dst;
    }

    private static void zipAdd(ZipOutputStream out, File f, String path, Progress p, long[] done, long total) throws IOException {
        if (f.isDirectory()) {
            out.putNextEntry(new ZipEntry(path + "/")); out.closeEntry();
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) zipAdd(out, c, path + "/" + c.getName(), p, done, total);
            return;
        }
        ZipEntry e = new ZipEntry(path); e.setTime(f.lastModified()); out.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[128 * 1024]; int r;
            while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); done[0] += r; if (p != null && !p.onProgress(f.getName(), done[0], total)) throw new Cancelled(); }
        }
        out.closeEntry();
    }

    // ---- search -----------------------------------------------------------
    public interface Found { /** @return false to stop */ boolean onFound(Entry e); }

    /** What to look for: a name (plain text or a mask with * and ?), kinds, size/date limits, text inside files. */
    public static final class Query {
        public String text = "";                 // "" = any name
        public java.util.Set<Kind> kinds = null; // null = any
        public long minSize = -1, maxSize = -1;  // bytes, -1 = any
        public long since = -1;                  // mtime >= since (ms), -1 = any
        public String content = "";              // "" = don't look inside; else case-insensitive substring in text-like files
        public boolean folders = true;

        boolean nameOk(String name) {
            if (text.isEmpty()) return true;
            String n = name.toLowerCase(Locale.ROOT), t = text.toLowerCase(Locale.ROOT);
            if (t.indexOf('*') >= 0 || t.indexOf('?') >= 0) return glob(t, n);
            return n.contains(t);
        }
    }

    /** Simple glob: * = anything, ? = one char; the whole name must match. */
    public static boolean glob(String pat, String s) {
        int p = 0, i = 0, star = -1, mark = 0;
        while (i < s.length()) {
            if (p < pat.length() && (pat.charAt(p) == '?' || pat.charAt(p) == s.charAt(i))) { p++; i++; }
            else if (p < pat.length() && pat.charAt(p) == '*') { star = p++; mark = i; }
            else if (star >= 0) { p = star + 1; i = ++mark; }
            else return false;
        }
        while (p < pat.length() && pat.charAt(p) == '*') p++;
        return p == pat.length();
    }

    public static void search(File root, String query, boolean hidden, Found cb) { Query q = new Query(); q.text = query; search(root, q, hidden, cb); }

    public static void search(File root, Query q, boolean hidden, Found cb) {
        ArrayList<File> stack = new ArrayList<>(); stack.add(root);
        int scanned = 0;
        byte[] buf = q.content.isEmpty() ? null : new byte[256 * 1024];
        String needle = q.content.toLowerCase(Locale.ROOT);
        while (!stack.isEmpty()) {
            File d = stack.remove(stack.size() - 1);
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!hidden && f.getName().startsWith(".")) continue;
                boolean dir = f.isDirectory();
                if (dir && !isSymlink(f)) stack.add(f);
                if (++scanned > 400000) return;
                if (dir && (!q.folders || !q.content.isEmpty())) continue;
                if (!q.nameOk(f.getName())) continue;
                Entry e = new Entry(f);
                if (q.kinds != null && !q.kinds.contains(e.kind)) continue;
                if (!dir && q.minSize >= 0 && e.size < q.minSize) continue;
                if (!dir && q.maxSize >= 0 && e.size > q.maxSize) continue;
                if (q.since >= 0 && e.mtime < q.since) continue;
                if (buf != null) { if (dir || e.size > 20L * 1024 * 1024 || !(e.kind == Kind.TEXT || e.kind == Kind.OTHER || e.kind == Kind.DOC) || !contains(f, needle, buf)) continue; }
                if (!cb.onFound(e)) return;
            }
        }
    }

    /** Case-insensitive substring search in a file read as UTF-8/Latin-1 (binary files just never match). */
    static boolean contains(File f, String needle, byte[] buf) {
        try (InputStream in = new FileInputStream(f)) {
            String carry = "";
            int r;
            while ((r = in.read(buf)) > 0) {
                String chunk = carry + new String(buf, 0, r, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (chunk.contains(needle)) return true;
                carry = chunk.length() > needle.length() ? chunk.substring(chunk.length() - needle.length()) : chunk;
            }
        } catch (IOException ignored) {}
        return false;
    }

    // ---- batch rename ---------------------------------------------------------
    /** New names for files: pattern with {name} {ext} {n} {n3} {date}; then find→replace; "" pattern = keep name. */
    public static List<String[]> renamePlan(List<File> files, String pattern, String find, String replace, int start) {
        List<String[]> out = new ArrayList<>();
        int n = start;
        for (File f : files) {
            String name = f.getName(), ext = ext(name), base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
            if (!ext.isEmpty()) ext = name.substring(name.length() - ext.length());   // keep the original case (.JPG stays .JPG)
            String nn = pattern == null || pattern.isEmpty() ? name
                    : pattern.replace("{name}", base).replace("{ext}", ext).replace("{n3}", String.format(Locale.ROOT, "%03d", n)).replace("{n}", String.valueOf(n))
                      .replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(f.lastModified())));
            if (!pattern.isEmpty() && !pattern.contains("{ext}") && !ext.isEmpty() && !nn.endsWith("." + ext)) nn += "." + ext;
            if (find != null && !find.isEmpty()) nn = nn.replace(find, replace == null ? "" : replace);
            out.add(new String[]{f.getAbsolutePath(), nn});
            n++;
        }
        return out;
    }

    /** Applies a plan; refuses collisions up front so nothing is half-renamed. Returns how many changed. */
    public static int renameApply(List<String[]> plan) throws IOException {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (String[] p : plan) {
            File f = new File(p[0]); String nn = p[1];
            if (nn.isEmpty() || nn.contains("/") || nn.equals(".") || nn.equals("..")) throw new IOException("недопустимое имя: " + nn);
            File t = new File(f.getParentFile(), nn);
            if (!targets.add(t.getAbsolutePath())) throw new IOException("два файла получают имя " + nn);
            if (!t.equals(f) && t.exists()) { boolean inPlan = false; for (String[] q : plan) if (q[0].equals(t.getAbsolutePath())) inPlan = true; if (!inPlan) throw new IOException("уже есть: " + nn); }
        }
        // two passes through temporary names so that swaps (a->b, b->a) work
        List<File[]> tmp = new ArrayList<>();
        int changed = 0;
        for (String[] p : plan) {
            File f = new File(p[0]); if (f.getName().equals(p[1])) continue;
            File t = new File(f.getParentFile(), ".rename-" + System.nanoTime() + "-" + tmp.size());
            if (!f.renameTo(t)) throw new IOException("не удалось переименовать " + f.getName());
            tmp.add(new File[]{t, new File(f.getParentFile(), p[1])}); changed++;
        }
        for (File[] m : tmp) if (!m[0].renameTo(m[1])) throw new IOException("не удалось переименовать в " + m[1].getName());
        return changed;
    }
}
