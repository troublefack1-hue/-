import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

import ru.pcremote.Fetch;

/** Harness for tests/test_fetch_jvm.py: Fetch.get against the real relay over TLS, four ways (one line each). */
public class FetchTest {
    static String host; static int port; static String secret, path, sha; static File orig, dir;

    static long[] first = new long[1];

    static File get(File dest) throws IOException {
        first[0] = -1;
        return Fetch.get("", host, port, null, secret, path, dest, sha, (done, total) -> { if (first[0] < 0) first[0] = done; });
    }

    static void part(File dest, byte[] data) throws IOException {
        try (FileOutputStream f = new FileOutputStream(new File(dest.getPath() + ".part"))) { f.write(data); }
    }

    public static void main(String[] a) throws Exception {
        host = a[0]; port = Integer.parseInt(a[1]); secret = a[2]; path = a[3]; sha = a[4]; orig = new File(a[5]); dir = new File(a[6]);
        byte[] all = Files.readAllBytes(orig.toPath());

        File d1 = new File(dir, "full.apk");
        File r1 = get(d1);
        System.out.println("CASE full " + (sha.equals(Fetch.sha256Of(r1)) && first[0] < 70000 ? "ok" : "fail") + " first=" + first[0]);

        File d2 = new File(dir, "resume.apk");
        byte[] head = new byte[100000]; System.arraycopy(all, 0, head, 0, head.length); part(d2, head);
        File r2 = get(d2);
        System.out.println("CASE resume " + (sha.equals(Fetch.sha256Of(r2)) && first[0] > 100000 ? "ok" : "fail") + " first=" + first[0]
                + " part_left=" + new File(d2.getPath() + ".part").exists());

        File d3 = new File(dir, "garbage.apk");
        byte[] junk = new byte[100000]; new Random(1).nextBytes(junk); part(d3, junk);
        String e3 = "none";
        try { get(d3); } catch (IOException e) { e3 = e.getMessage(); }
        File r3 = get(d3);
        System.out.println("CASE garbage " + (!"none".equals(e3) && sha.equals(Fetch.sha256Of(r3)) ? "ok" : "fail") + " first_error=" + e3);

        File d4 = new File(dir, "toolong.apk");
        byte[] big = new byte[all.length + 5000]; part(d4, big);
        String e4 = "none";
        try { get(d4); } catch (IOException e) { e4 = e.getMessage(); }
        File r4 = get(d4);
        System.out.println("CASE toolong " + (!"none".equals(e4) && sha.equals(Fetch.sha256Of(r4)) ? "ok" : "fail") + " first_error=" + e4);
        System.out.flush();
    }
}
