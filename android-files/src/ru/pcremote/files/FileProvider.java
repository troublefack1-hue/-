package ru.pcremote.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * content://ru.pcremote.files.provider/<absolute path> for "share" and "open with": other apps get
 * a temporary grant to exactly that file, nothing else (Android 7+ forbids file:// across apps).
 */
public class FileProvider extends ContentProvider {
    public static final String AUTHORITY = "ru.pcremote.files.provider";

    public static Uri uriFor(File f) { return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build(); }
    static File fileFor(Uri u) { return new File(u.getPath()); }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.MODE_READ_WRITE);
    }

    @Override public String getType(Uri uri) { return Fs.mime(fileFor(uri).getName()); }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f = fileFor(uri);
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(projection[i]) ? f.getName() : OpenableColumns.SIZE.equals(projection[i]) ? f.length() : null;
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
