package ru.pcremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import javax.net.SocketFactory;

/**
 * Mobile data as a spare road to the PC while Wi-Fi stays on (see Pinned.Cellular). Asked for only after
 * every Wi-Fi path failed, then kept for the life of the process: a socket bound to a network dies with
 * the request, and a good Wi-Fi never wakes the mobile radio.
 */
final class CellularLink implements Pinned.Cellular {
    private final ConnectivityManager cm;
    private volatile Network net;
    private boolean requested;

    private CellularLink(Context c) { cm = c.getSystemService(ConnectivityManager.class); }

    static void install(Context c) {
        if (Pinned.cellular == null) Pinned.cellular = new CellularLink(c.getApplicationContext());
    }

    @Override public SocketFactory get(int waitMs) {
        boolean first;
        synchronized (this) {
            first = !requested;
            if (first) {
                requested = true;
                try {
                    NetworkRequest r = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                    cm.requestNetwork(r, new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(Network n) { synchronized (CellularLink.this) { net = n; CellularLink.this.notifyAll(); } }
                        @Override public void onLost(Network n) { if (n.equals(net)) net = null; }
                    });
                } catch (RuntimeException e) {   // no permission, no mobile data on this device
                    return null;
                }
            }
            // only the first ask waits for the radio; later failures (PC off, data disabled) do not stall reconnects
            long end = System.currentTimeMillis() + (first ? waitMs : 0);
            while (net == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        Network n = net;
        return n == null ? null : n.getSocketFactory();
    }
}
