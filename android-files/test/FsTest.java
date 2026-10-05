import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ru.pcremote.files.Fs;

/** Desktop JVM checks for the file manager's core (no Android needed). */
public class FsTest {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + (ok ? "" : ": " + d)); if (!ok) fails++; }
    static void write(File f, String s) throws Exception { f.getParentFile().mkdirs(); try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); } }
    static String names(List<Fs.Entry> l) { StringBuilder b = new StringBuilder(); for (Fs.Entry e : l) b.append(e.name).append(' '); return b.toString().trim(); }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("fs").toFile();
        File d = new File(root, "Папка"); d.mkdir();
        write(new File(root, "img10.jpg"), "x"); write(new File(root, "img2.jpg"), "xxxx"); write(new File(root, "Doc.pdf"), "xx"); write(new File(root, ".hidden"), "h"); write(new File(root, "b.txt"), "xxx");
        List<Fs.Entry> l = Fs.list(root, false, Fs.Sort.NAME, false);
        check("natural sort, folders first, hidden off", names(l).equals("Папка b.txt Doc.pdf img2.jpg img10.jpg"), names(l));
        l = Fs.list(root, true, Fs.Sort.SIZE, false);
        check("by size desc, hidden on", names(l).equals("Папка img2.jpg b.txt Doc.pdf .hidden img10.jpg") || names(l).equals("Папка img2.jpg b.txt Doc.pdf img10.jpg .hidden"), names(l));
        l = Fs.list(root, false, Fs.Sort.TYPE, false);
        check("by type", names(l).equals("Папка img2.jpg img10.jpg Doc.pdf b.txt"), names(l));
        check("kinds", Fs.kindOf("a.JPG") == Fs.Kind.IMAGE && Fs.kindOf("v.mkv") == Fs.Kind.VIDEO && Fs.kindOf("x.apk") == Fs.Kind.APK && Fs.kindOf("x.tar.gz") == Fs.Kind.ARCHIVE && Fs.kindOf("noext") == Fs.Kind.OTHER, "");
        check("mime", Fs.mime("a.jpg").equals("image/jpeg") && Fs.mime("a.APK").equals("application/vnd.android.package-archive") && Fs.mime("weird.qqq").equals("application/qqq"), Fs.mime("a.jpg"));
        check("sizes", Fs.size(900).equals("900 Б") && Fs.size(1536).equals("1.5 КБ") && Fs.size(5L * 1024 * 1024 * 1024).equals("5.0 ГБ") && Fs.size(123L * 1024 * 1024).equals("123 МБ"), Fs.size(1536) + " " + Fs.size(123L * 1024 * 1024));
        // copy with unique names and progress
        File dst = new File(root, "dst"); dst.mkdir();
        final long[] seen = {0};
        Fs.copy(new File(root, "img2.jpg"), dst, (cur, done, total) -> { seen[0] = total; return true; });
        Fs.copy(new File(root, "img2.jpg"), dst, null);
        check("copy + unique name", new File(dst, "img2.jpg").length() == 4 && new File(dst, "img2 (1).jpg").exists() && seen[0] == 4, "");
        // copy a tree, move, rename, mkdir
        write(new File(d, "sub/deep.txt"), "deep");
        Fs.copy(d, dst, null);
        check("copy tree", new File(dst, "Папка/sub/deep.txt").length() == 4, "");
        Fs.move(new File(dst, "Папка"), root.getParentFile() == null ? root : new File(root, "moved" + File.separator), null);
        File moved = new File(root, "moved/Папка/sub/deep.txt");
        check("move tree", moved.exists() && !new File(dst, "Папка").exists(), moved.getPath());
        File rn = Fs.rename(new File(root, "b.txt"), "c.txt");
        check("rename", rn.exists() && !new File(root, "b.txt").exists(), "");
        boolean bad = false; try { Fs.rename(rn, "x/y"); } catch (Exception e) { bad = true; }
        check("rename rejects slash", bad, "");
        Fs.mkdir(root, "Новая"); check("mkdir", new File(root, "Новая").isDirectory(), "");
        // trash: move, origin, restore, purge
        File victim = new File(root, "Doc.pdf");
        Fs.toTrash(victim, root);
        File trash = Fs.trashDir(root);
        File trashed = new File(trash, "Doc.pdf");
        check("to trash", trashed.exists() && !victim.exists() && victim.getAbsolutePath().equals(Fs.trashOrigin(trashed)), String.valueOf(Fs.trashOrigin(trashed)));
        File back = Fs.restore(trashed);
        check("restore", back.equals(victim) && victim.exists() && !trashed.exists(), back.getPath());
        Fs.toTrash(victim, root); Fs.purge(trashed);
        check("purge", !trashed.exists() && !new File(trash, ".Doc.pdf.origin").exists(), "");
        // zip / unzip, zip-slip refused
        File z = Fs.zip(Arrays.asList(new File(root, "img2.jpg"), new File(root, "moved")), root, "arch", null);
        check("zip created", z.getName().equals("arch.zip") && z.length() > 50, z.getName());
        File un = Fs.unzip(z, dst, null);
        check("unzip", new File(un, "img2.jpg").length() == 4 && new File(un, "moved/Папка/sub/deep.txt").length() == 4, un.getPath());
        File evil = new File(root, "evil.zip");
        try (java.util.zip.ZipOutputStream o = new java.util.zip.ZipOutputStream(new FileOutputStream(evil))) { o.putNextEntry(new java.util.zip.ZipEntry("../../escape.txt")); o.write(1); o.closeEntry(); }
        Fs.unzip(evil, dst, null);
        check("zip slip blocked", !new File(root, "escape.txt").exists() && !new File(root.getParentFile(), "escape.txt").exists(), "");
        // search
        List<String> found = new ArrayList<>();
        Fs.search(root, "DEEP", false, e -> { found.add(e.file.getAbsolutePath()); return true; });
        check("search recursive, case-insensitive", found.size() >= 2 && found.get(0).endsWith("deep.txt"), found.toString());
        // delete tree
        check("delete tree", Fs.deleteTree(root) && !root.exists(), "");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
