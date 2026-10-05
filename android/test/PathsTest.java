import java.util.Arrays;
import java.util.List;
import ru.pcremote.Paths;

/** Path ordering on a desktop JVM: USB tethering / hotspot / LAN first, public last. */
public class PathsTest {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) { System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : ": " + detail)); if (!ok) fails++; }
    static String hosts(List<Paths.Candidate> l) { StringBuilder b = new StringBuilder(); for (Paths.Candidate c : l) b.append(c.kind()).append(':').append(c.host).append(' '); return b.toString().trim(); }

    public static void main(String[] a) throws Exception {
        // phone on USB tethering (192.168.42.129) + Wi-Fi (192.168.1.20); PC reports LAN + USB addresses
        List<String> phone = Arrays.asList("192.168.42.129", "192.168.1.20");
        List<Paths.Candidate> c = Paths.candidates("192.168.1.10,192.168.42.57", "192.168.1.10", true, "93.100.1.2", phone);
        check("usb and lan direct, then public", hosts(c).equals("direct:192.168.1.10 direct:192.168.42.57 public:93.100.1.2"), hosts(c));
        // the PC's lan address as a plain "lan" fallback when not in any phone subnet, only on Wi-Fi
        c = Paths.candidates("10.0.0.5", "10.0.0.5", true, "93.100.1.2", Arrays.asList("100.64.3.3"));
        check("lan when on wifi", hosts(c).equals("lan:10.0.0.5 public:93.100.1.2"), hosts(c));
        c = Paths.candidates("10.0.0.5", "10.0.0.5", false, "93.100.1.2", Arrays.asList("100.64.3.3"));
        check("no lan on mobile data", hosts(c).equals("public:93.100.1.2"), hosts(c));
        // junk and duplicates
        c = Paths.candidates("garbage, ,93.100.1.2,192.168.42.57,192.168.42.57", "", false, "93.100.1.2", Arrays.asList("192.168.42.129"));
        check("dedupe and ignore junk", hosts(c).equals("direct:192.168.42.57 public:93.100.1.2"), hosts(c));
        // the phone's own address is never a candidate
        c = Paths.candidates("192.168.42.129", "", false, "93.100.1.2", Arrays.asList("192.168.42.129"));
        check("own address skipped", hosts(c).equals("public:93.100.1.2"), hosts(c));
        check("phoneAddrs runs", Paths.phoneAddrs() != null, "");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
