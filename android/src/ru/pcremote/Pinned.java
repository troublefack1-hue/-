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
    public static SSLSocket connectPreferLan(String lan, String host, int port, String pin, String[] seen, int timeoutMs, boolean tryLan)
            throws IOException {
        if (tryLan && lan != null && !lan.isEmpty() && !lan.equals(host)) {
            try { return connect(lan, port, pin, seen, 1500); } catch (IOException viaLan) { /* not at home, or the PC is elsewhere */ }
        }
        return connect(host, port, pin, seen, timeoutMs);
    }

    public static SSLSocket connect(String host, int port, final String pin, final String[] seen, int timeoutMs)
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
            Socket raw = new Socket();
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
