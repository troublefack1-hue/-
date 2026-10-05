package ru.pcremote.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A tiny SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel talks to; every CONNECT becomes a
 * NetMux stream to the PC, every UDP ASSOCIATE a NetMux flow. No authentication: only
 * processes on this phone can reach it, and the real credentials sit in the mux's TLS link.
 */
public final class Socks5Server implements Runnable {

    private final ServerSocket server;
    private final NetMux mux;
    private volatile boolean running = true;
    private static final InetAddress LOOPBACK4 = loopback4();

    private static InetAddress loopback4() {
        try { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
        catch (java.net.UnknownHostException e) { throw new AssertionError(e); }
    }

    public Socks5Server(NetMux mux) throws IOException {
        this.mux = mux;
        // IPv4 explicitly: hev-socks5-tunnel is configured with 127.0.0.1, getLoopbackAddress() is ::1 on Android.
        server = new ServerSocket(0, 128, LOOPBACK4);
    }

    public int port() { return server.getLocalPort(); }

    public void start() { Thread t = new Thread(this, "socks5-accept"); t.setDaemon(true); t.start(); }

    public void stop() { running = false; try { server.close(); } catch (IOException ignored) {} }

    @Override public void run() {
        while (running) {
            try {
                Socket c = server.accept();
                c.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(c), "socks5-client"); t.setDaemon(true); t.start();
            } catch (IOException e) {
                if (running) try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void serve(Socket c) {
        try (Socket client = c) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            int n = in.readUnsignedByte(); in.skipBytes(n);
            out.write(new byte[]{5, 0}); out.flush();
            if (in.readUnsignedByte() != 5) return;
            int cmd = in.readUnsignedByte(); in.readUnsignedByte();
            String host = readAddr(in);
            int port = in.readUnsignedShort();
            if (host == null) { reply(out, 8); return; }
            if (cmd == 1) connect(client, in, out, host, port);
            else if (cmd == 3) udpAssociate(client, in, out);
            else reply(out, 7);
        } catch (IOException ignored) {
        }
    }

    /** ATYP + address: 1 = IPv4, 3 = name, 4 = IPv6; null if unknown. */
    static String readAddr(DataInputStream in) throws IOException {
        int atyp = in.readUnsignedByte();
        if (atyp == 1) { byte[] a = new byte[4]; in.readFully(a); return InetAddress.getByAddress(a).getHostAddress(); }
        if (atyp == 4) { byte[] a = new byte[16]; in.readFully(a); return InetAddress.getByAddress(a).getHostAddress(); }
        if (atyp == 3) { int n = in.readUnsignedByte(); byte[] a = new byte[n]; in.readFully(a); return new String(a, StandardCharsets.UTF_8); }
        return null;
    }

    private static void reply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
    }

    private static void replyBound(OutputStream out, int port) throws IOException {
        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port}); out.flush();
    }

    // ---- CONNECT ----
    private void connect(Socket client, InputStream in, OutputStream out, String host, int port) throws IOException {
        if (!mux.isUp()) { reply(out, 1); return; }
        final CountDownLatch opened = new CountDownLatch(1);
        final boolean[] ok = {false};
        final int[] idRef = {0};
        NetMux.Stream s = new NetMux.Stream() {
            public void onOpened() { ok[0] = true; opened.countDown(); }
            public void onData(byte[] d, int off, int len) {
                try { out.write(d, off, len); out.flush(); } catch (IOException e) { mux.close(idRef[0]); try { client.close(); } catch (IOException ignored) {} }
            }
            public void onClose() { opened.countDown(); try { client.close(); } catch (IOException ignored) {} }
        };
        idRef[0] = mux.open(host, port, s);
        try { if (!opened.await(20, TimeUnit.SECONDS)) { mux.close(idRef[0]); reply(out, 4); return; } } catch (InterruptedException e) { return; }
        if (!ok[0]) { reply(out, 5); return; }
        replyBound(out, 0);
        byte[] buf = new byte[32 * 1024];
        try {
            int r;
            while ((r = in.read(buf)) > 0) mux.send(idRef[0], buf, 0, r);
        } finally {
            mux.close(idRef[0]);
        }
    }

    // ---- UDP ASSOCIATE ----
    private void udpAssociate(Socket control, InputStream in, OutputStream out) throws IOException {
        if (!mux.isUp()) { reply(out, 1); return; }
        final DatagramSocket ds = new DatagramSocket(new InetSocketAddress(LOOPBACK4, 0));
        final SocketAddress[] peer = {null};
        final int fid = mux.udpFlow((host, port, data, off, len) -> {
            if (peer[0] == null) return;
            byte[] h = host.getBytes(StandardCharsets.UTF_8);
            boolean v4 = host.indexOf(':') < 0 && host.matches("[0-9.]+");
            byte[] pkt;
            if (v4) {
                byte[] a = ipv4(host);
                pkt = new byte[10 + len]; pkt[3] = 1; System.arraycopy(a, 0, pkt, 4, 4); pkt[8] = (byte) (port >> 8); pkt[9] = (byte) port;
                System.arraycopy(data, off, pkt, 10, len);
            } else {
                pkt = new byte[7 + h.length + len]; pkt[3] = 3; pkt[4] = (byte) h.length; System.arraycopy(h, 0, pkt, 5, h.length);
                pkt[5 + h.length] = (byte) (port >> 8); pkt[6 + h.length] = (byte) port; System.arraycopy(data, off, pkt, 7 + h.length, len);
            }
            try { ds.send(new DatagramPacket(pkt, pkt.length, peer[0])); } catch (IOException ignored) {}
        });
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[65536];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                while (!ds.isClosed()) {
                    ds.receive(p);
                    peer[0] = p.getSocketAddress();
                    int off = p.getOffset(), len = p.getLength();
                    if (len < 10 || buf[off + 2] != 0) continue;        // fragments unsupported
                    int atyp = buf[off + 3] & 0xFF, hdr;
                    String host;
                    if (atyp == 1) { host = (buf[off + 4] & 0xFF) + "." + (buf[off + 5] & 0xFF) + "." + (buf[off + 6] & 0xFF) + "." + (buf[off + 7] & 0xFF); hdr = 8; }
                    else if (atyp == 3) { int n = buf[off + 4] & 0xFF; host = new String(buf, off + 5, n, StandardCharsets.UTF_8); hdr = 5 + n; }
                    else if (atyp == 4) { byte[] a = new byte[16]; System.arraycopy(buf, off + 4, a, 0, 16); host = InetAddress.getByAddress(a).getHostAddress(); hdr = 20; }
                    else continue;
                    int port = ((buf[off + hdr] & 0xFF) << 8) | (buf[off + hdr + 1] & 0xFF);
                    mux.udpSend(fid, host, port, buf, off + hdr + 2, len - hdr - 2);
                }
            } catch (IOException ignored) {
            }
        }, "socks5-udp");
        reader.setDaemon(true); reader.start();
        replyBound(out, ds.getLocalPort());
        try { while (in.read() >= 0) { /* the control connection stays open for the association's lifetime */ } }
        finally { mux.udpClose(fid); ds.close(); }
    }

    private static byte[] ipv4(String s) {
        String[] p = s.split("\\."); byte[] a = new byte[4];
        for (int i = 0; i < 4 && i < p.length; i++) a[i] = (byte) Integer.parseInt(p[i]);
        return a;
    }
}
