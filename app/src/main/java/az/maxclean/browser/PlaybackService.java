package az.maxclean.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps sound from a page playing while Maxclean is in the background ("Фоновая активность
 * вкладок"). Without a foreground service Android slows the app down after a while in the
 * background and the sound stops. Runs only while something is playing; the notification says so
 * and has a Stop button. It stops itself when the sound ends or the browser comes back.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final String ACTION_STOP = "az.maxclean.browser.STOP_PLAYBACK";
    private static final int ID = 7;
    private static final long CHECK_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int silentChecks;

    /** Called when the browser goes to the background. Does nothing unless sound is playing. */
    static void startIfPlaying(Context c) {
        if (!Prefs.on(c, Prefs.KEEP_VISIBLE)) return;
        AudioManager am = (AudioManager) c.getSystemService(AUDIO_SERVICE);
        if (am == null || !am.isMusicActive()) return;
        try {
            c.startForegroundService(new Intent(c, PlaybackService.class));
        } catch (RuntimeException e) {
            // Android may refuse a start from the background; then it simply plays as before.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Stop: bring nothing back, just let Android pause the app as usual.
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Звук в фоне", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // No page title or address here: the notification shade is visible on the lock screen.
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Maxclean")
                .setContentText("Звук играет в фоне")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        silentChecks = 0;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::check, CHECK_MS);
        return START_NOT_STICKY;
    }

    /** Stops once the sound has been off for two checks in a row (a pause between tracks is fine). */
    private void check() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        silentChecks = (am != null && am.isMusicActive()) ? 0 : silentChecks + 1;
        if (silentChecks >= 2) {
            stopSelf();
            return;
        }
        handler.postDelayed(this::check, CHECK_MS);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
