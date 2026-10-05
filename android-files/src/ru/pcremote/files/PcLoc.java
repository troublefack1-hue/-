package ru.pcremote.files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** A folder on the PC, through PcClient. "" is the list of shared roots (every drive, by design). */
public final class PcLoc implements Loc {
    public static File cacheDir = new File(System.getProperty("java.io.tmpdir"), "pccache");
    public final PcClient c; public final String path;
    public PcLoc(PcClient c, String path) { this.c = c; this.path = path == null ? "" : path; }
    @Override public String kind() { return "pc"; }
    @Override public String title() { if (path.isEmpty()) return "ПК"; String t = path.replace('\\', '/'); while (t.endsWith("/")) t = t.substring(0, t.length() - 1); int i = t.lastIndexOf('/'); return i < 0 ? t : t.substring(i + 1).isEmpty() ? t : t.substring(i + 1); }
    @Override public String path() { return "ПК:" + path; }
    @Override public Loc parent() {
        if (path.isEmpty()) return null;
        if (c.roots.contains(path)) return new PcLoc(c, "");
        String t = path.replace('\\', '/'); while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        int i = t.lastIndexOf('/');
        if (i <= 0 || (i == 2 && t.charAt(1) == ':')) return new PcLoc(c, "");     // "C:/x" -> roots
        return new PcLoc(c, path.substring(0, i + (path.charAt(i) == '\\' || path.charAt(i) == '/' ? 0 : 0)));
    }
    @Override public List<Fs.Entry> list(boolean hidden, Fs.Sort sort, boolean desc) throws IOException {
        List<Fs.Entry> l = c.list(path);
        if (!hidden) { java.util.Iterator<Fs.Entry> it = l.iterator(); while (it.hasNext()) if (it.next().name.startsWith(".")) it.remove(); }
        Fs.sort(l, sort, desc); return l;
    }
    @Override public Loc child(Fs.Entry folder) { return new PcLoc(c, folder.ref); }
    @Override public File materialize(Fs.Entry e, Fs.Progress p) throws IOException {
        File out = new File(new File(cacheDir, Integer.toHexString(e.ref.hashCode())), e.name);
        if (out.isFile() && out.length() == e.size && out.lastModified() >= e.mtime) return out;
        c.download(e.ref, e.size, out, p);
        out.setLastModified(Math.max(e.mtime, 1));
        return out;
    }
    @Override public boolean writable() { return !path.isEmpty(); }
    @Override public String id() { return "pc:" + path; }
}
