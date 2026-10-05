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
        public final String secret, fingerprint;
        Result(String s, String f) { secret = s; fingerprint = f; }
    }

    public static Result pair(String host, int port, String code) throws IOException {
        String[] seen = new String[1];
        try (SSLSocket s = Pinned.connect(host, port, null, seen, 8000)) {
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/pair?code=" + code + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = in.readLine();
            if (status == null) throw new IOException("no response");
            if (!status.contains(" 200 ")) {
                if (status.contains(" 403 ")) throw new IOException("Неверный код или код истёк");
                throw new IOException("PC answered: " + status);
            }
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* headers */ }
            StringBuilder body = new StringBuilder();
            while ((line = in.readLine()) != null) body.append(line);
            String secret = jsonString(body.toString(), "secret");
            if (secret == null) throw new IOException("bad pairing response");
            return new Result(secret, seen[0]);
        }
    }

    /** Minimal extraction of a string field from a flat JSON object. */
    static String jsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int q1 = json.indexOf('"', json.indexOf(':', k) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 < 0 || q2 < 0) ? null : json.substring(q1 + 1, q2);
    }
}
