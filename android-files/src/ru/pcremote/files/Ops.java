package ru.pcremote.files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Copy / move / delete across places (local folders and the inside of zip archives). */
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
        } else throw new IOException("между этими местами копировать нельзя");
    }

    public static void delete(Loc loc, List<Fs.Entry> items, Fs.Progress p) throws IOException {
        if (loc instanceof LocalLoc) { for (Fs.Entry e : items) { if (p != null) p.onProgress(e.name, 0, 1); Fs.toTrash(e.file, trashRoot); } }
        else throw new IOException("из архива не удалить: распакуйте его");
    }

    public static void mkdir(Loc loc, String name) throws IOException {
        if (loc instanceof LocalLoc) Fs.mkdir(((LocalLoc) loc).dir, name);
        else throw new IOException("здесь нельзя создавать папки");
    }

    public static void rename(Loc loc, Fs.Entry e, String name) throws IOException {
        if (loc instanceof LocalLoc) Fs.rename(e.file, name);
        else throw new IOException("в архиве не переименовать");
    }
}
