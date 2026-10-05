package ru.pcremote;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The phone's own trace of what happens with nobody looking (background updates, installs): one line to
 * /sdcard/Download/PCRemote/log.txt (the PC reads it with `phone cat`) and the same line to the PC over the
 * background link, where the relay writes it into pcapp.log as "phone log: …".
 */
final class PhoneLog {
    static volatile WsClient link;   // set by RemoteService while it is connected

    private PhoneLog() {}

    static void add(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/PCRemote");
            if (dir.isDirectory() || dir.mkdirs()) {
                File f = new File(dir, "log.txt");
                if (f.length() > 512 * 1024) f.delete();   // a log, not an archive
                try (FileOutputStream out = new FileOutputStream(f, true)) { out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); }
            }
        } catch (Exception ignored) {}   // no "all files" access: the PC copy below still works
        WsClient c = link;
        if (c != null) {
            try { c.sendText("{\"t\":\"phone_log\",\"msg\":" + org.json.JSONObject.quote(line) + "}"); } catch (Exception ignored) {}
        }
    }
}
