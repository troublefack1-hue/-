import java.io.IOException;
import ru.pcremote.net.NetMux;
import ru.pcremote.net.Socks5Server;

/** Desktop harness: NetMux + Socks5Server against a real relay (see tests/test_netmux_jvm.py). */
public class SocksTest {
    public static void main(String[] a) throws Exception {
        String host = a[0]; int port = Integer.parseInt(a[1]); String secret = a[2];
        NetMux mux = new NetMux(host, port, null, secret, "", false, new NetMux.Listener() {
            public void onDown(String r) { System.err.println("mux down: " + r); }
            public void onText(String j) { }
        });
        mux.connect();
        Socks5Server s = new Socks5Server(mux);
        s.start();
        System.out.println("SOCKS " + s.port());
        System.out.flush();
        Thread.sleep(Long.parseLong(a.length > 3 ? a[3] : "20000"));
        s.stop(); mux.close();
    }
}
