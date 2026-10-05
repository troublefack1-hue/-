package ru.pcremote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.net.ssl.SSLSocket;

/**
 * Local plain-HTTP door to the PC: listens on 127.0.0.1:<port> and pipes
 * every connection, byte for byte, into a pinned TLS connection to the PC.
 * The WebView talks http://127.0.0.1:port and never sees TLS at all, so
 * pages, WebSockets and downloads all just work.
 */
public final class Tunnel implements Runnable {
    private final String host;
    private final int port;
    private final String pin;
    private final ServerSocket server;
    private volatile boolean running = true;
    public volatile String lastError = "";

    public Tunnel(String host, int port, String pin) throws IOException {
        this.host = host; this.port = port; this.pin = pin;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    public int localPort() { return server.getLocalPort(); }

    public void start() { Thread t = new Thread(this, "tunnel"); t.setDaemon(true); t.start(); }

    public void stop() { running = false; try { server.close(); } catch (IOException ignored) {} }

    @Override public void run() {
        while (running) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                Thread t = new Thread(() -> handle(client), "tunnel-conn");
                t.setDaemon(true); t.start();
            } catch (IOException e) {
                if (running) lastError = e.toString();
            }
        }
    }

    private void handle(Socket client) {
        SSLSocket up = null;
        try {
            up = Pinned.connect(host, port, pin, null, 8000);
            up.setTcpNoDelay(true);
            final SSLSocket upstream = up;
            Thread t = new Thread(() -> pipe(client, upstream), "tunnel-up");
            t.setDaemon(true); t.start();
            pipe(upstream, client);
            t.join(2000);
        } catch (Pinned.Mismatch e) {
            lastError = "pin-mismatch";
        } catch (Exception e) {
            lastError = e.toString();
        } finally {
            close(client); if (up != null) close(up);
        }
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[64 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
            to.shutdownOutput();
        } catch (IOException ignored) {
        } finally {
            close(from); close(to);
        }
    }

    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) {} }
}
