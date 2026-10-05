package ru.pcremote.net;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The quick-settings tile: one tap on/off, like any VPN app's. */
public class NetTile extends TileService {
    private static volatile NetTile live;

    @Override public void onStartListening() { live = this; update(); }
    @Override public void onStopListening() { live = null; }

    @Override public void onClick() {
        PairShare.sync(this);
        if (!getSharedPreferences("pcnet", MODE_PRIVATE).contains("secret") || VpnService.prepare(this) != null) {
            // not paired yet, or Android still has to ask for VPN consent: the activity handles both
            Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_TOGGLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(i);
            return;
        }
        if (PcVpnService.isActive()) PcVpnService.stop(this); else PcVpnService.start(this);
        update();
    }

    static void refresh(Context ctx) {
        NetTile t = live;
        if (t != null) t.update();
        requestListeningState(ctx, new android.content.ComponentName(ctx, NetTile.class));
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean active = PcVpnService.isActive();
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile));
        t.setLabel("Через ПК");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(PcVpnService.isOn() ? (PcVpnService.dnsOnly(this) ? "только DNS" : "весь трафик") : "waiting".equals(PcVpnService.state) ? "ПК недоступен" : "connecting".equals(PcVpnService.state) ? "подключаюсь" : "выкл");
        t.updateTile();
    }
}
