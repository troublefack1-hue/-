package ru.pcremote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

/**
 * One-time pairing: connect to the PC (trust on first use), send the 6-digit
 * code, get the secret back. Returns the secret and the certificate
 * fingerprint to pin from now on.
 */
public final class Pairing {

    public static final class Result {
        public final String secret, fingerprint, ntfy, wake, lan;
        Result(String s, String f, String n, String w, String l) { secret = s; fingerprint = f; ntfy = n == null ? "" : n; wake = w == null ? "" : w; lan = l == null ? "" : l; }
    }

    /** What this phone is called in the request a paired phone has to allow (set by the app: maker + model). */
    public static volatile String deviceName = "";
    public interface Status { void on(String text); }
    /** Progress for the screen while a paired phone is asked (null = nobody listens). */
    public static volatile Status status;

    public static Result pair(String host, int port, String code) throws IOException {
        return pair(host, port, code, null, "", false);
    }

    /** From a QR: the fingerprint is known in advance, so the code is only ever sent to the PC that printed the QR;
     *  at home the LAN address from the QR is tried first. */
    public static Result pair(String host, int port, String code, String expectedFp, String lan, boolean tryLan) throws IOException {
        String[] seen = new String[1];
        String pin = expectedFp == null || expectedFp.isEmpty() ? null : expectedFp;
        String req = null;
        long deadline = System.currentTimeMillis() + 150_000;
        while (true) {
            try (SSLSocket s = open(lan, host, port, pin, seen, tryLan)) {
                s.setSoTimeout(8000);
                OutputStream out = s.getOutputStream();
                out.write(("GET /api/pair?code=" + code + "&model=" + java.net.URLEncoder.encode(deviceName, "UTF-8")
                        + (req != null ? "&req=" + req : "") + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String status = in.readLine();
                if (status == null) throw new IOException("no response");
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) { /* headers */ }
                StringBuilder body = new StringBuilder();
                while ((line = in.readLine()) != null) body.append(line);
                String err = jsonString(body.toString(), "error");
                if (status.contains(" 202 ")) {
                    // the permanent code: an already paired phone must say yes (owner's rule, 06.10.2026)
                    req = jsonString(body.toString(), "pending");
                    Status st = Pairing.status;
                    if (st != null) st.on("Ждём подтверждения на вашем привязанном телефоне: «Мой ПК» или «Интернет через ПК» покажет запрос «Разрешить?»");
                    if (System.currentTimeMillis() > deadline)
                        throw new IOException("Никто не подтвердил за 2 минуты. Подтвердите на привязанном телефоне или возьмите одноразовый код в окне PC Remote");
                    try { Thread.sleep(2000); } catch (InterruptedException e) { throw new IOException("прервано"); }
                    continue;
                }
                if (!status.contains(" 200 ")) {
                    if (err != null) throw new IOException(err);
                    if (status.contains(" 403 ")) throw new IOException("Неверный код (проверьте код в окне PC Remote)");
                    throw new IOException("PC answered: " + status);
                }
                String secret = jsonString(body.toString(), "secret");
                if (secret == null) throw new IOException("bad pairing response");
                return new Result(secret, seen[0], jsonString(body.toString(), "ntfy"), jsonString(body.toString(), "wake"), jsonString(body.toString(), "lan"));
            }
        }
    }

    private static SSLSocket open(String lan, String host, int port, String pin, String[] seen, boolean tryLan) throws IOException {
        try {
            return Pinned.connectPreferLan(lan, host, port, pin, seen, 8000, tryLan);
        } catch (javax.net.ssl.SSLException e) {
            if (pin != null) throw new IOException("Сертификат ПК не совпадает с QR. Отсканируйте QR из окна PC Remote ещё раз");
            throw e;
        }
    }

    /** A bare number field ("dns_blocked": 12) as text, or null. */
    public static String jsonNumber(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int c = json.indexOf(':', k) + 1;
        while (c < json.length() && json.charAt(c) == ' ') c++;
        int e = c;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-' || json.charAt(e) == '.')) e++;
        return e > c ? json.substring(c, e) : null;
    }

    /** Minimal extraction of a string field from a flat JSON object. */
    public static String jsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int q1 = json.indexOf('"', json.indexOf(':', k) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 < 0 || q2 < 0) ? null : json.substring(q1 + 1, q2);
    }
}
