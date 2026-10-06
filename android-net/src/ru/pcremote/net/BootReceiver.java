package ru.pcremote.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** After a reboot or an update of this app: the tunnel comes back if it was on (Android keeps the VPN consent). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        PairShare.sync(ctx);
        android.content.SharedPreferences p = ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE);
        if (p.contains("secret") && p.getBoolean("wanted", false) && VpnService.prepare(ctx) == null) PcVpnService.start(ctx);
    }
}
