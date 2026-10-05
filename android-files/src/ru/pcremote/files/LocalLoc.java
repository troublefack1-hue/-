package ru.pcremote.files;

import java.io.File;
import java.util.List;

public final class LocalLoc implements Loc {
    public final File dir;
    public LocalLoc(File dir) { this.dir = dir; }
    @Override public String kind() { return "local"; }
    @Override public String title() { return dir.getName().isEmpty() ? "/" : dir.getName(); }
    @Override public String path() { return dir.getAbsolutePath(); }
    @Override public Loc parent() { File p = dir.getParentFile(); return p == null ? null : new LocalLoc(p); }
    @Override public List<Fs.Entry> list(boolean hidden, Fs.Sort sort, boolean desc) { return Fs.list(dir, hidden, sort, desc); }
    @Override public Loc child(Fs.Entry f) { return Fs.ext(f.name).equals("zip") && !f.dir ? new ZipLoc(f.file, "") : new LocalLoc(f.file); }
    @Override public File materialize(Fs.Entry e, Fs.Progress p) { return e.file; }
    @Override public boolean writable() { return true; }
    @Override public String id() { return "local:" + dir.getAbsolutePath(); }
    @Override public boolean equals(Object o) { return o instanceof LocalLoc && ((LocalLoc) o).dir.equals(dir); }
    @Override public int hashCode() { return dir.hashCode(); }
}
