package ru.pcremote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Find my phone": full screen, alarm at maximum volume, vibration, one big Stop button. */
public class RingActivity extends Activity {
    private static RingActivity current;
    private MediaPlayer player;
    private Vibrator vib;
    private int savedVolume = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF1D6FE0);
        TextView t = new TextView(this); t.setText("Телефон здесь!"); t.setTextSize(34); t.setTextColor(Color.WHITE);
        t.setTypeface(null, Typeface.BOLD); t.setGravity(Gravity.CENTER); root.addView(t);
        TextView s = new TextView(this); s.setText("Сигнал с вашего ПК"); s.setTextSize(18); s.setTextColor(0xCCFFFFFF);
        s.setGravity(Gravity.CENTER); s.setPadding(0, 10, 0, 60); root.addView(s);
        Button stop = new Button(this); stop.setText("Выключить"); stop.setTextSize(22); stop.setAllCaps(false);
        stop.setBackgroundColor(Color.WHITE); stop.setTextColor(0xFF1D6FE0);
        stop.setPadding(80, 40, 80, 40); stop.setOnClickListener(v -> finish());
        root.addView(stop);
        setContentView(root);

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            player.setLooping(true); player.prepare(); player.start();
        } catch (Exception ignored) { player = null; }
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null) {
            long[] pattern = {0, 700, 300};
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0)); else vib.vibrate(pattern, 0);
        }
        getWindow().getDecorView().postDelayed(this::finish, 120_000);  // give up after 2 minutes
    }

    public static void stop() { if (current != null) current.finish(); }

    @Override protected void onDestroy() {
        if (player != null) { player.stop(); player.release(); }
        if (vib != null) vib.cancel();
        if (savedVolume >= 0) ((AudioManager) getSystemService(AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
        current = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() { finish(); }
}
