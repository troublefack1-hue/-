package ru.pcremote.files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A zip archive browsed like a folder; a tapped file is extracted to the cache and opened from there. */
public final class ZipLoc implements Loc {
    public static File cacheDir = new File(System.getProperty("java.io.tmpdir"), "zipcache");
    public final File zip; public final String inner;   // inner: "" or "a/b/" (always ends with /)
    private static final Map<String, List<String[]>> TABLES = new HashMap<>();   // zip path+mtime -> [name, dir, size, time]

    public ZipLoc(File zip, String inner) { this.zip = zip; this.inner = inner; }
    @Override public String kind() { return "zip"; }
    @Override public String title() { if (inner.isEmpty()) return zip.getName(); String t = inner.substring(0, inner.length() - 1); return t.substring(t.lastIndexOf('/') + 1); }
    @Override public String path() { return zip.getAbsolutePath() + "/" + inner; }
    @Override public Loc parent() {
        if (inner.isEmpty()) return new LocalLoc(zip.getParentFile());
        String t = inner.substring(0, inner.length() - 1); int i = t.lastIndexOf('/');
        return new ZipLoc(zip, i < 0 ? "" : t.substring(0, i + 1));
    }
    @Override public boolean writable() { return false; }
    @Override public String id() { return "zip:" + zip.getAbsolutePath() + "!" + inner; }

    private List<String[]> table() throws IOException {
        String key = zip.getAbsolutePath() + ":" + zip.lastModified();
        synchronized (TABLES) {
            List<String[]> t = TABLES.get(key);
            if (t != null) return t;
            t = new ArrayList<>();
            try (ZipFile z = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) { ZipEntry e = en.nextElement(); if (e.getName().contains("..")) continue; t.add(new String[]{e.getName(), e.isDirectory() ? "1" : "0", String.valueOf(e.getSize()), String.valueOf(e.getTime())}); }
            }
            if (TABLES.size() > 8) TABLES.clear();
            TABLES.put(key, t);
            return t;
        }
    }

    @Override public List<Fs.Entry> list(boolean hidden, Fs.Sort sort, boolean desc) throws IOException {
        Map<String, Fs.Entry> out = new HashMap<>();
        for (String[] r : table()) {
            String n = r[0];
            if (!n.startsWith(inner) || n.length() == inner.length()) continue;
            String rest = n.substring(inner.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {   // something deeper: show the folder once
                String folder = rest.substring(0, slash);
                if (!out.containsKey(folder)) out.put(folder, new Fs.Entry(folder, true, 0, 0, inner + folder + "/"));
            } else {
                long size = Long.parseLong(r[2]), time = Long.parseLong(r[3]);
                out.put(rest, new Fs.Entry(rest, false, Math.max(0, size), Math.max(0, time), n));
            }
        }
        List<Fs.Entry> l = new ArrayList<>();
        for (Fs.Entry e : out.values()) if (hidden || !e.name.startsWith(".")) l.add(e);
        Fs.sort(l, sort, desc);
        return l;
    }

    @Override public Loc child(Fs.Entry folder) { return new ZipLoc(zip, folder.ref); }

    @Override public File materialize(Fs.Entry e, Fs.Progress p) throws IOException {
        File dir = new File(cacheDir, Integer.toHexString((zip.getAbsolutePath() + zip.lastModified()).hashCode()));
        File out = new File(dir, e.ref.replace('/', File.separatorChar));
        if (out.isFile() && out.length() == e.size) return out;
        out.getParentFile().mkdirs();
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry ze = z.getEntry(e.ref);
            if (ze == null) throw new IOException("нет в архиве: " + e.ref);
            try (InputStream in = z.getInputStream(ze); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[128 * 1024]; int r; long done = 0;
                while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); done += r; if (p != null && !p.onProgress(e.name, done, e.size)) throw new Fs.Cancelled(); }
            }
        }
        return out;
    }

    /** Extract a whole folder of the archive (or the whole archive when inner is "") into dst. */
    public void extractAll(File dstDir, Fs.Progress p) throws IOException {
        List<String[]> t = table();
        long total = 0; for (String[] r : t) if (r[0].startsWith(inner) && r[1].equals("0")) total += Math.max(0, Long.parseLong(r[2]));
        long done = 0;
        try (ZipFile z = new ZipFile(zip)) {
            for (String[] r : t) {
                if (!r[0].startsWith(inner) || r[1].equals("1")) continue;
                File out = new File(dstDir, r[0].substring(inner.length()).replace('/', File.separatorChar));
                if (!out.getCanonicalPath().startsWith(dstDir.getCanonicalPath())) continue;
                out.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(z.getEntry(r[0])); OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[128 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n; if (p != null && !p.onProgress(out.getName(), done, total)) throw new Fs.Cancelled(); }
                }
            }
        }
    }
}
