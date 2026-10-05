package ru.pcremote.files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A place the pane can show: a local folder, the inside of a zip, a folder on the PC.
 * Listing, going up/down, and getting a real local file for an entry (download / extract) is all a
 * pane needs; copying between places is Ops' business.
 */
public interface Loc {
    String kind();                               // "local" | "zip" | "pc"
    String title();                              // last path element for the toolbar
    String path();                               // full display path
    Loc parent();                                // null at the top of this place
    List<Fs.Entry> list(boolean hidden, Fs.Sort sort, boolean desc) throws IOException;
    Loc child(Fs.Entry folder);
    /** A local file with this entry's bytes (the file itself, or a copy in cache); dirs unsupported. */
    File materialize(Fs.Entry e, Fs.Progress p) throws IOException;
    boolean writable();
    /** Same place? (for "drop onto itself" and history) */
    String id();
}
