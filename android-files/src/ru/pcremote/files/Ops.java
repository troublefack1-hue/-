package ru.pcremote.files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Copy / move / delete across places (local, zip, PC). Each pair that makes sense is here; others throw. */
public final class Ops {
    /** Where the local trash lives (the phone's storage root); set by the app, a temp dir in tests. */
    public static File trashRoot = new File(System.getProperty("java.io.tmpdir"));
    public static void copy(Loc from, List<Fs.Entry> items, Loc to, boolean move, Fs.Progress p) throws IOException {
        if (!to.writable()) throw new IOException(to.kind().equals("zip") ? "в архив так не положить: распакуйте его" : "сюда нельзя записывать");
        if (from instanceof LocalLoc && to instanceof LocalLoc) {
            for (Fs.Entry e : items) { if (move) Fs.move(e.file, ((LocalLoc) to).dir, p); else Fs.copy(e.file, ((LocalLoc) to).dir, p); }
        } else if (from instanceof ZipLoc && to instanceof LocalLoc) {
            ZipLoc z = (ZipLoc) from; File dst = ((LocalLoc) to).dir;
            for (Fs.Entry e : items) {
                if (e.dir) new ZipLoc(z.zip, e.ref).extractAll(Fs.unique(dst, e.name), p);
                else { File tmp = z.materialize(e, p); Fs.copy(tmp, dst, p); }
            }
        } else if (from instanceof PcLoc && to instanceof LocalLoc) {
            PcLoc pc = (PcLoc) from; File dst = ((LocalLoc) to).dir;
            for (Fs.Entry e : items) {
                if (e.dir) downloadTree(pc.c, e, Fs.unique(dst, e.name), p);
                else pc.c.download(e.ref, e.size, Fs.unique(dst, e.name), p);
                if (move) pc.c.op("delete", e.ref, null);
            }
        } else if (from instanceof LocalLoc && to instanceof PcLoc) {
            PcLoc pc = (PcLoc) to;
            for (Fs.Entry e : items) { uploadTree(pc.c, e.file, pc.path, p); if (move) Fs.toTrash(e.file, trashRoot); }
        } else if (from instanceof PcLoc && to instanceof PcLoc) {
            PcLoc pc = (PcLoc) to;
            for (Fs.Entry e : items) { if (p != null) p.onProgress(e.name, 0, 1); pc.c.op(move ? "move" : "copy", e.ref, pc.path); }
        } else if (from instanceof ZipLoc && to instanceof PcLoc) {
            ZipLoc z = (ZipLoc) from; PcLoc pc = (PcLoc) to;
            for (Fs.Entry e : items) { if (e.dir) throw new IOException("папку из архива сначала распакуйте"); File tmp = z.materialize(e, p); pc.c.upload(tmp, pc.path, p); }
        } else throw new IOException("между этими местами копировать нельзя");
    }

    static void downloadTree(PcClient c, Fs.Entry dirEntry, File dst, Fs.Progress p) throws IOException {
        dst.mkdirs();
        for (Fs.Entry e : c.list(dirEntry.ref)) {
            if (e.dir) downloadTree(c, e, new File(dst, e.name), p);
            else c.download(e.ref, e.size, new File(dst, e.name), p);
        }
    }

    static void uploadTree(PcClient c, File f, String remoteDir, Fs.Progress p) throws IOException {
        if (f.isDirectory()) {
            c.op("mkdir", remoteDir, f.getName());
            String sub = joinRemote(remoteDir, f.getName());
            File[] fs = f.listFiles(); if (fs != null) for (File x : fs) uploadTree(c, x, sub, p);
        } else c.upload(f, remoteDir, p);
    }

    static String joinRemote(String dir, String name) { char sep = dir.contains("\\") || (dir.length() >= 2 && dir.charAt(1) == ':') ? '\\' : '/'; return dir.endsWith("/") || dir.endsWith("\\") ? dir + name : dir + sep + name; }

    public static void delete(Loc loc, List<Fs.Entry> items, Fs.Progress p) throws IOException {
        if (loc instanceof LocalLoc) { File rt = trashRoot; for (Fs.Entry e : items) { if (p != null) p.onProgress(e.name, 0, 1); Fs.toTrash(e.file, rt); } }
        else if (loc instanceof PcLoc) { PcClient c = ((PcLoc) loc).c; for (Fs.Entry e : items) { if (p != null) p.onProgress(e.name, 0, 1); c.op("delete", e.ref, null); } }
        else throw new IOException("из архива не удалить: распакуйте его");
    }

    public static void mkdir(Loc loc, String name) throws IOException {
        if (loc instanceof LocalLoc) Fs.mkdir(((LocalLoc) loc).dir, name);
        else if (loc instanceof PcLoc) ((PcLoc) loc).c.op("mkdir", ((PcLoc) loc).path, name);
        else throw new IOException("здесь нельзя создавать папки");
    }

    public static void rename(Loc loc, Fs.Entry e, String name) throws IOException {
        if (loc instanceof LocalLoc) Fs.rename(e.file, name);
        else if (loc instanceof PcLoc) ((PcLoc) loc).c.op("rename", e.ref, name);
        else throw new IOException("в архиве не переименовать");
    }
}
