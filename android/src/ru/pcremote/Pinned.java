package ru.pcremote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS sockets to the PC that trust exactly one certificate: the one whose
 * SHA-256 fingerprint we learned when pairing. No CA, no system store.
 * Plain Java (no Android classes) so it can be unit-tested on a desktop JVM.
 */
public final class Pinned {

    /** Thrown when the PC presents a certificate other than the pinned one. */
    public static final class Mismatch extends IOException {
        public final String seen;
        Mismatch(String seen) { super("certificate fingerprint mismatch: " + seen); this.seen = seen; }
    }

    static String sha256(X509Certificate c) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new CertificateException(e);
        }
    }

    /**
     * Opens a TLS connection to host:port.
     * @param pin  expected fingerprint, or null = trust on first use (pairing);
     *             the fingerprint actually seen is returned via {@code seen[0]}.
     */
    /** At home on Wi-Fi the PC is one hop away: try its LAN address first (short timeout),
     *  then the public one. Same certificate, same pin either way. */
    /** The phone's mobile data as a road of its own (Android: Network.getSocketFactory()), or null when there is none.
     *  A guest Wi-Fi refuses the router's own public address and cannot see the home LAN: then nothing on Wi-Fi
     *  reaches the PC, while mobile data does — without making the owner switch Wi-Fi off. */
    public interface Cellular { javax.net.SocketFactory get(int waitMs); }
    public static volatile Cellular cellular;

    public static SSLSocket connectPreferLan(String lan, String host, int port, String pin, String[] seen, int timeoutMs, boolean tryLan)
            throws IOException {
        java.util.List<Paths.Candidate> cands = Paths.candidates(Paths.pcAddrs, lan, tryLan, host);
        IOException last = null;
        for (int i = 0; i < cands.size(); i++) {
            Paths.Candidate c = cands.get(i);
            boolean lastOne = i == cands.size() - 1;
            try {
                SSLSocket s = connect(c.host, port, pin, seen, lastOne ? timeoutMs : 1500);   // near paths get a short try
                Paths.lastPath = c.kind() + " " + c.host;
                return s;
            } catch (Mismatch m) {
                throw m;                                   // a different certificate is never "try the next address"
            } catch (IOException e) {
                last = e;
            }
        }
        Cellular cell = cellular;
        javax.net.SocketFactory sf = cell != null && host != null && !host.isEmpty() ? cell.get(5000) : null;
        if (sf != null) {
            try {
                SSLSocket s = connect(sf, host, port, pin, seen, timeoutMs);
                Paths.lastPath = "mobile " + host;
                return s;
            } catch (Mismatch m) {
                throw m;
            } catch (IOException e) {
                if (last == null) last = e;   // the spare road failing must not hide why the real ones did
            }
        }
        throw last != null ? last : new IOException("no address for the PC");
    }

    public static SSLSocket connect(String host, int port, final String pin, final String[] seen, int timeoutMs)
            throws IOException {
        return connect(null, host, port, pin, seen, timeoutMs);
    }

    /** @param net the network to go through (its socket factory), or null for the default one */
    public static SSLSocket connect(javax.net.SocketFactory net, String host, int port, final String pin, final String[] seen, int timeoutMs)
            throws IOException {
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                String fp = sha256(chain[0]);
                if (seen != null) seen[0] = fp;
                if (pin != null && !pin.equalsIgnoreCase(fp)) throw new CertificateException("PIN:" + fp);
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            SSLSocketFactory f = ctx.getSocketFactory();
            Socket raw = net != null ? net.createSocket() : new Socket();
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
            s.setUseClientMode(true);
            try {
                s.startHandshake();
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                int i = m.indexOf("PIN:");
                if (i >= 0) throw new Mismatch(m.substring(i + 4, Math.min(m.length(), i + 68)));
                throw e;
            }
            s.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
