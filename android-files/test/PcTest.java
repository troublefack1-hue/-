import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ru.pcremote.files.*;

/** PcClient / PcLoc / ZipLoc / Ops / search filters / batch rename against a real relay (tests/test_pcclient_jvm.py). */
public class PcTest {
    static int fails = 0;
    static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + (ok ? "" : ": " + d)); if (!ok) fails++; }
    static void write(File f, String s) throws Exception { f.getParentFile().mkdirs(); try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); } }
    static String names(List<Fs.Entry> l) { StringBuilder b = new StringBuilder(); for (Fs.Entry e : l) b.append(e.name).append(' '); return b.toString().trim(); }
    static Fs.Entry find(List<Fs.Entry> l, String n) { for (Fs.Entry e : l) if (e.name.equals(n)) return e; return null; }

    public static void main(String[] a) throws Exception {
        String host = a[0]; int port = Integer.parseInt(a[1]); String secret = a[2]; File share = new File(a[3]);
        File local = Files.createTempDirectory("local").toFile(); Ops.trashRoot = local; ZipLoc.cacheDir = new File(local, ".zipcache"); PcLoc.cacheDir = new File(local, ".pccache");
        PcClient c = new PcClient(host, port, null, secret, "", false);
        // roots and listing
        List<Fs.Entry> roots = c.list("");
        check("PC roots", roots.size() == 1 && roots.get(0).dir && roots.get(0).ref.equals(share.getAbsolutePath()), names(roots));
        PcLoc pc = new PcLoc(c, share.getAbsolutePath());
        List<Fs.Entry> l = pc.list(false, Fs.Sort.NAME, false);
        check("PC folder listing", names(l).equals("docs hello.txt photo.jpg") && find(l, "hello.txt").size == 12, names(l));
        check("PC parent/child", pc.parent().path().equals("ПК:") && pc.child(find(l, "docs")).path().endsWith("docs"), pc.child(find(l, "docs")).path());
        // download (materialize) and upload
        File got = pc.materialize(find(l, "hello.txt"), null);
        check("download", got.isFile() && new String(Files.readAllBytes(got.toPath()), "UTF-8").equals("привет"), got.getPath());
        File up = new File(local, "up.txt"); write(up, "uploaded-content");
        c.upload(up, share.getAbsolutePath(), (n, d, t) -> true);
        check("upload", new File(share, "up.txt").length() == 16, "");
        // ops on the PC
        c.op("mkdir", share.getAbsolutePath(), "newdir");
        c.op("rename", new File(share, "up.txt").getAbsolutePath(), "renamed.txt");
        c.op("copy", new File(share, "renamed.txt").getAbsolutePath(), new File(share, "newdir").getAbsolutePath());
        c.op("move", new File(share, "renamed.txt").getAbsolutePath(), new File(share, "docs").getAbsolutePath());
        check("mkdir/rename/copy/move on the PC", new File(share, "newdir/renamed.txt").exists() && new File(share, "docs/renamed.txt").exists() && !new File(share, "renamed.txt").exists(), "");
        c.op("delete", new File(share, "newdir/renamed.txt").getAbsolutePath(), null);
        check("delete on the PC", !new File(share, "newdir/renamed.txt").exists(), "");
        boolean refused = false; try { c.op("delete", "/etc/passwd", null); } catch (Exception e) { refused = true; }
        check("outside share refused", refused, "");
        // Ops: PC -> local tree, local -> PC tree
        LocalLoc dst = new LocalLoc(new File(local, "dl")); dst.dir.mkdirs();
        List<Fs.Entry> sel = new ArrayList<>(); sel.add(find(pc.list(false, Fs.Sort.NAME, false), "docs"));
        Ops.copy(pc, sel, dst, false, null);
        check("Ops PC->local tree", new File(dst.dir, "docs/renamed.txt").length() == 16 && new File(dst.dir, "docs/inner/deep.md").exists(), "");
        write(new File(local, "tree/a/b.txt"), "b"); write(new File(local, "tree/c.txt"), "c");
        List<Fs.Entry> sel2 = new ArrayList<>(); sel2.add(new Fs.Entry(new File(local, "tree")));
        Ops.copy(new LocalLoc(local), sel2, pc, false, (n, d, t) -> true);
        check("Ops local->PC tree", new File(share, "tree/a/b.txt").exists() && new File(share, "tree/c.txt").exists(), "");
        // zip as a folder
        File z = Fs.zip(Arrays.asList(new File(local, "tree")), local, "tree", null);
        ZipLoc zl = new ZipLoc(z, "");
        List<Fs.Entry> zi = zl.list(false, Fs.Sort.NAME, false);
        check("zip root lists its folder", names(zi).equals("tree"), names(zi));
        Loc inner = zl.child(zi.get(0)); List<Fs.Entry> zi2 = inner.list(false, Fs.Sort.NAME, false);
        check("zip folder listing", names(zi2).equals("a c.txt") && inner.parent().id().equals(zl.id()), names(zi2));
        File mat = inner.materialize(find(zi2, "c.txt"), null);
        check("zip materialize", mat.isFile() && mat.length() == 1, mat.getPath());
        List<Fs.Entry> zsel = new ArrayList<>(); zsel.add(find(zi2, "a"));
        Ops.copy(inner, zsel, dst, false, null);
        check("Ops zip folder -> local", new File(dst.dir, "a/b.txt").exists(), "");
        boolean ro = false; try { Ops.copy(dst, zsel, zl, false, null); } catch (Exception e) { ro = true; }
        check("zip is read-only", ro, "");
        // search filters
        write(new File(local, "s/big.bin"), new String(new char[3000]).replace('\0', 'x')); write(new File(local, "s/note.txt"), "Секретное слово внутри"); write(new File(local, "s/IMG_1.JPG"), "j");
        Fs.Query q = new Fs.Query(); q.text = "*.jpg"; List<String> f1 = new ArrayList<>(); Fs.search(new File(local, "s"), q, false, e -> { f1.add(e.name); return true; });
        check("glob search", f1.equals(Arrays.asList("IMG_1.JPG")), f1.toString());
        q = new Fs.Query(); q.minSize = 2000; List<String> f2 = new ArrayList<>(); Fs.search(new File(local, "s"), q, false, e -> { f2.add(e.name); return true; });
        check("size filter", f2.equals(Arrays.asList("big.bin")), f2.toString());
        q = new Fs.Query(); q.content = "секретное"; List<String> f3 = new ArrayList<>(); Fs.search(new File(local, "s"), q, false, e -> { f3.add(e.name); return true; });
        check("content search", f3.equals(Arrays.asList("note.txt")), f3.toString());
        q = new Fs.Query(); q.kinds = java.util.EnumSet.of(Fs.Kind.IMAGE); List<String> f4 = new ArrayList<>(); Fs.search(local, q, false, e -> { f4.add(e.name); return true; });
        check("kind filter", f4.contains("IMG_1.JPG") && !f4.contains("note.txt"), f4.toString());
        // batch rename
        File r = new File(local, "r"); write(new File(r, "IMG_1.JPG"), "1"); write(new File(r, "IMG_2.JPG"), "2"); write(new File(r, "note.txt"), "n");
        List<String[]> plan = Fs.renamePlan(Arrays.asList(new File(r, "IMG_1.JPG"), new File(r, "IMG_2.JPG")), "Отпуск {n3}", "", "", 1);
        check("rename plan", plan.get(0)[1].equals("Отпуск 001.JPG") && plan.get(1)[1].equals("Отпуск 002.JPG"), plan.get(0)[1]);
        Fs.renameApply(plan);
        check("rename apply", new File(r, "Отпуск 001.JPG").exists() && new File(r, "Отпуск 002.JPG").exists(), "");
        List<String[]> swap = Fs.renamePlan(Arrays.asList(new File(r, "Отпуск 001.JPG"), new File(r, "Отпуск 002.JPG")), "", "001", "TMP", 1);
        swap.set(0, new String[]{swap.get(0)[0], "Отпуск 002.JPG"}); swap.set(1, new String[]{swap.get(1)[0], "Отпуск 001.JPG"});
        Fs.renameApply(swap);
        check("rename swap a<->b", new String(Files.readAllBytes(new File(r, "Отпуск 002.JPG").toPath())).equals("1"), "");
        boolean coll = false; try { Fs.renameApply(Fs.renamePlan(Arrays.asList(new File(r, "note.txt")), "", "note.txt", "Отпуск 002.JPG", 1)); } catch (Exception e) { coll = true; }
        check("rename collision refused", coll && new File(r, "note.txt").exists(), "");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
