package ru.pcremote;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs an APK through a PackageInstaller session instead of the "Install?" screen.
 *
 * Android 12+ lets an installer skip the question (USER_ACTION_NOT_REQUIRED, with the
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION permission) when it updates itself or an app it installed
 * earlier: «Мой ПК», «Интернет через ПК» and «Проводник» then update silently from the PC. A first
 * install, or an app installed from elsewhere, still asks once: InstallResult shows Android's screen.
 */
final class SilentInstaller {
    static final String ACTION = "ru.pcremote.INSTALL_RESULT";

    private SilentInstaller() {}

    static void install(Context ctx, File apk, String label) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent result = new Intent(ctx, InstallResult.class).setAction(ACTION).putExtra("label", label);
            // mutable: the system adds the status and, when it wants the owner's consent, the screen to show
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, result, flags).getIntentSender());
            PhoneLog.add("install " + label + ": session " + id + " committed (" + apk.length() + " bytes, sdk " + Build.VERSION.SDK_INT + ")");
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (apk.getName().startsWith("upd-")) apk.delete();   // the session holds its own copy now
        }
    }
}
