package ru.pcremote.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** «Интернет через ПК» can allow a new phone too: «Мой ПК» on this phone may be the one just reinstalled. */
public final class NetPairApprove extends BroadcastReceiver {
    static void ask(Context ctx, String id, String model, String ip) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pairing", "Новые телефоны", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(id.hashCode(), new Notification.Builder(ctx, "pairing").setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Новый телефон просит доступ к ПК")
                .setContentText(model + " (" + ip + ") ввёл постоянный код. Разрешить?")
                .setStyle(new Notification.BigTextStyle().bigText(model + " с адреса " + ip + " ввёл постоянный код ПК. "
                        + "Если это не вы — нажмите «Отклонить». Запрос живёт 2 минуты."))
                .addAction(new Notification.Action.Builder(null, "Разрешить", answer(ctx, id, true)).build())
                .addAction(new Notification.Action.Builder(null, "Отклонить", answer(ctx, id, false)).build())
                .setAutoCancel(true).setTimeoutAfter(120_000).build());
    }

    static void done(Context ctx, String id) { ctx.getSystemService(NotificationManager.class).cancel(id.hashCode()); }

    private static PendingIntent answer(Context ctx, String id, boolean ok) {
        Intent i = new Intent(ctx, NetPairApprove.class).putExtra("id", id).putExtra("ok", ok);
        return PendingIntent.getBroadcast(ctx, id.hashCode() * 2 + (ok ? 1 : 0), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        boolean ok = intent.getBooleanExtra("ok", false);
        done(ctx, id);
        PcVpnService v = PcVpnService.instance;
        boolean sent = v != null && v.answerPair("{\"t\":\"pair_answer\",\"id\":\"" + id.replace("\"", "") + "\",\"ok\":" + ok + "}");
        android.widget.Toast.makeText(ctx, sent ? (ok ? "Разрешено" : "Отклонено") : "Нет связи с ПК: ответ не ушёл", android.widget.Toast.LENGTH_SHORT).show();
    }
}
