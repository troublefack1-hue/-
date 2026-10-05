package ru.pcremote.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import ru.pcremote.WsClient;

/**
 * One WebSocket to the PC (/ws/net) carrying every TCP stream and UDP flow of the phone.
 * Plain Java: the same class is exercised on a desktop JVM against the real relay.
 * Wire format: see relay/netproxy.py.
 */
public final class NetMux implements WsClient.Listener {

    public interface Stream { void onOpened(); void onData(byte[] data, int off, int len); void onClose(); }
    public interface UdpFlow { void onDatagram(String host, int port, byte[] data, int off, int len); }
    public interface Listener { void onDown(String reason); void onText(String json); }

    static final int OPEN = 1, DATA = 2, CLOSE = 3, OPENED = 4, UDP = 5;

    private final String host, lan, secret, pin;
    private final int port;
    private final boolean tryLan;
    private final Listener listener;
    private WsClient ws;
    private final AtomicInteger next = new AtomicInteger(1);
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final Map<Integer, UdpFlow> flows = new ConcurrentHashMap<>();
    private volatile boolean up;

    public NetMux(String host, int port, String pin, String secret, String lan, boolean tryLan, Listener l) {
        this.host = host; this.port = port; this.pin = pin; this.secret = secret; this.lan = lan; this.tryLan = tryLan; this.listener = l;
    }

    public void connect() throws IOException {
        ws = new WsClient(host, port, pin, "/ws/net", this, lan, tryLan);
        ws.connect();
        ws.sendText("{\"t\":\"auth\",\"token\":\"" + secret + "\"}");
        ws.start();
        up = true;
    }

    public boolean isUp() { return up && ws != null && ws.isOpen(); }

    public void close() { up = false; if (ws != null) ws.close(); }

    public void requestStats() { try { if (isUp()) ws.sendText("{\"t\":\"stats\"}"); } catch (IOException ignored) {} }

    // ---- TCP ----
    public int open(String target, int tport, Stream s) throws IOException {
        int id = next.getAndIncrement();
        streams.put(id, s);
        byte[] h = target.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(7 + h.length);
        b.put((byte) OPEN).putInt(id).putShort((short) tport).put(h);
        ws.sendBinary(b.array());
        return id;
    }

    public void send(int id, byte[] data, int off, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5 + len);
        b.put((byte) DATA).putInt(id).put(data, off, len);
        ws.sendBinary(b.array());
    }

    public void close(int id) {
        if (streams.remove(id) == null) return;
        try { ByteBuffer b = ByteBuffer.allocate(5); b.put((byte) CLOSE).putInt(id); ws.sendBinary(b.array()); } catch (IOException ignored) {}
    }

    // ---- UDP ----
    public int udpFlow(UdpFlow f) { int id = next.getAndIncrement(); flows.put(id, f); return id; }

    public void udpClose(int id) { flows.remove(id); }

    public void udpSend(int id, String target, int tport, byte[] data, int off, int len) throws IOException {
        byte[] h = target.getBytes(StandardCharsets.UTF_8);
        if (h.length > 255) return;
        ByteBuffer b = ByteBuffer.allocate(8 + h.length + len);
        b.put((byte) UDP).putInt(id).putShort((short) tport).put((byte) h.length).put(h).put(data, off, len);
        ws.sendBinary(b.array());
    }

    // ---- from the PC ----
    @Override public void onBinary(byte[] f) {
        if (f.length < 5) return;
        ByteBuffer b = ByteBuffer.wrap(f);
        int kind = b.get() & 0xFF, id = b.getInt();
        switch (kind) {
            case OPENED: { Stream s = streams.get(id); if (s != null) s.onOpened(); break; }
            case DATA: { Stream s = streams.get(id); if (s != null) s.onData(f, 5, f.length - 5); break; }
            case CLOSE: { Stream s = streams.remove(id); if (s != null) s.onClose(); break; }
            case UDP: {
                if (f.length < 8) return;
                int port = b.getShort() & 0xFFFF, hlen = b.get() & 0xFF;
                if (f.length < 8 + hlen) return;
                String h = new String(f, 8, hlen, StandardCharsets.UTF_8);
                UdpFlow u = flows.get(id);
                if (u != null) u.onDatagram(h, port, f, 8 + hlen, f.length - 8 - hlen);
                break;
            }
            default:
        }
    }

    @Override public void onText(String s) { if (listener != null) listener.onText(s); }

    @Override public void onClose(String reason) {
        up = false;
        for (Stream s : streams.values()) { try { s.onClose(); } catch (RuntimeException ignored) {} }
        streams.clear(); flows.clear();
        if (listener != null) listener.onDown(reason);
    }

    public int streamCount() { return streams.size(); }
}
