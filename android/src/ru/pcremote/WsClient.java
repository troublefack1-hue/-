package ru.pcremote;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocket;

/**
 * Minimal RFC 6455 WebSocket client over a pinned TLS socket. Used by the
 * background service (ring, cast) where no WebView is around. Plain Java,
 * tested on a desktop JVM against the relay.
 */
public final class WsClient implements Runnable {

    public interface Listener {
        void onText(String s);
        void onBinary(byte[] b);
        void onClose(String reason);
    }

    private final String host;
    private final int port;
    private final String pin;
    private final String path;
    private final Listener listener;
    private SSLSocket sock;
    private OutputStream out;
    private volatile boolean open;
    private final SecureRandom rnd = new SecureRandom();

    private String lan = ""; private boolean tryLan = false;
    public WsClient(String host, int port, String pin, String path, Listener l) {
        this.host = host; this.port = port; this.pin = pin; this.path = path; this.listener = l;
    }
    public WsClient(String host, int port, String pin, String path, Listener l, String lan, boolean tryLan) {
        this(host, port, pin, path, l); this.lan = lan == null ? "" : lan; this.tryLan = tryLan;
    }

    public boolean isOpen() { return open; }

    /** Connects and performs the handshake; then call start() to read. */
    public void connect() throws IOException {
        sock = Pinned.connectPreferLan(lan, host, port, pin, null, 8000, tryLan);
        sock.setTcpNoDelay(true);
        out = sock.getOutputStream();
        byte[] key = new byte[16]; rnd.nextBytes(key);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII)); out.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII), 1);
        String status = readLine(sock.getInputStream());
        if (status == null || !status.contains(" 101 ")) throw new IOException("handshake failed: " + status);
        String line;
        while ((line = readLine(sock.getInputStream())) != null && !line.isEmpty()) { /* headers */ }
        open = true;
    }

    /** Reads one CRLF line byte-by-byte so no body bytes are swallowed by a buffer. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    public void start() { Thread t = new Thread(this, "ws-reader"); t.setDaemon(true); t.start(); }

    public synchronized void sendText(String s) throws IOException { frame(0x1, s.getBytes(StandardCharsets.UTF_8)); }
    public synchronized void sendBinary(byte[] b) throws IOException { frame(0x2, b); }

    public void close() {
        open = false;
        try { synchronized (this) { frame(0x8, new byte[0]); } } catch (IOException ignored) {}
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
    }

    private void frame(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        byte[] mask = new byte[4]; rnd.nextBytes(mask);
        int n = payload.length;
        byte[] head;
        if (n < 126) head = new byte[]{(byte) (0x80 | opcode), (byte) (0x80 | n)};
        else if (n < 65536) head = new byte[]{(byte) (0x80 | opcode), (byte) (0x80 | 126), (byte) (n >> 8), (byte) n};
        else head = new byte[]{(byte) (0x80 | opcode), (byte) (0x80 | 127), 0, 0, 0, 0,
                (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(head); out.write(mask); out.write(masked); out.flush();
    }

    @Override public void run() {
        String reason = "closed";
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 65536));
            java.io.ByteArrayOutputStream msg = new java.io.ByteArrayOutputStream();
            int msgOp = 0;
            while (open) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0; int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > 16 * 1024 * 1024) throw new IOException("frame too large");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] data = new byte[(int) len]; in.readFully(data);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                switch (op) {
                    case 0x9: synchronized (this) { frame(0xA, data); } continue;  // ping -> pong
                    case 0xA: continue;
                    case 0x8: reason = data.length >= 2 ? "close " + (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) : "close"; open = false; continue;
                    case 0x0: break;
                    default: msgOp = op; msg.reset();
                }
                msg.write(data);
                if (fin) {
                    byte[] whole = msg.toByteArray(); msg.reset();
                    if (msgOp == 0x1) listener.onText(new String(whole, StandardCharsets.UTF_8));
                    else if (msgOp == 0x2) listener.onBinary(whole);
                }
            }
        } catch (IOException e) {
            if (open) reason = e.toString();
        } finally {
            open = false;
            try { sock.close(); } catch (IOException ignored) {}
            listener.onClose(reason);
        }
    }
}
