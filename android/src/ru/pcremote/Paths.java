package ru.pcremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which addresses to try for the PC, best first. Plain Java (tested on a desktop JVM).
 *
 * The PC reports every address it has (status.addrs). If the phone itself has an address in
 * the same /24 — USB tethering (192.168.42.x), the phone's own hotspot (192.168.43.x), the home
 * LAN — that address is one hop away and goes first. Then the PC's LAN address when we are on
 * Wi-Fi, then the public one. The service re-checks this every few seconds and reconnects when
 * a better path appears (cable plugged in, back home), so the link follows the fastest road.
 */
public final class Paths {
    /** The PC's addresses as last reported by it (comma separated), kept by the service/activity. */
    public static volatile String pcAddrs = "";
    /** What the last successful connection used, for diagnostics ("direct 192.168.42.129" …). */
    public static volatile String lastPath = "";
    /** The address that worked last: tried first next time (null = the mobile fallback did). */
    public static volatile String lastGoodHost = null;

    public static final int DIRECT = 0, LAN = 1, PUBLIC = 2;

    public static final class Candidate {
        public final String host; public final int rank;
        Candidate(String h, int r) { host = h; rank = r; }
        public String kind() { return rank == DIRECT ? "direct" : rank == LAN ? "lan" : "public"; }
    }

    public static List<Candidate> candidates(String pcAddrsCsv, String lan, boolean onWifi, String host) {
        return candidates(pcAddrsCsv, lan, onWifi, host, phoneAddrs());
    }

    public static List<Candidate> candidates(String pcAddrsCsv, String lan, boolean onWifi, String host, List<String> phoneAddrs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();
        if (pcAddrsCsv != null) {
            for (String a : pcAddrsCsv.split(",")) {
                a = a.trim();
                if (a.isEmpty() || a.equals(host) || !isV4(a)) continue;
                for (String mine : phoneAddrs) {
                    if (sameSubnet(a, mine) && !a.equals(mine) && seen.add(a)) { out.add(new Candidate(a, DIRECT)); break; }
                }
            }
        }
        if (onWifi && lan != null && !lan.isEmpty() && !lan.equals(host) && seen.add(lan)) out.add(new Candidate(lan, LAN));
        if (host != null && !host.isEmpty() && seen.add(host)) out.add(new Candidate(host, PUBLIC));
        return out;
    }

    static boolean sameSubnet(String a, String b) {
        int i = a.lastIndexOf('.'), j = b.lastIndexOf('.');
        return i > 0 && j > 0 && a.substring(0, i).equals(b.substring(0, j));
    }

    static boolean isV4(String s) { return s.matches("\\d{1,3}(\\.\\d{1,3}){3}"); }

    /** IPv4 addresses of this phone's interfaces that are up (not loopback). */
    public static List<String> phoneAddrs() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return out.isEmpty() ? Collections.<String>emptyList() : out;
    }
}
