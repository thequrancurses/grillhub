package pl.grillhub.kitchen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.List;

/**
 * Foreground service: keeps polling the kitchen API while the app is closed
 * (like WhatsApp) and rings an alarm until every new order is accepted.
 */
public class OrderService extends Service implements Store.Listener {

    static final String ACTION_ACCEPT_ALL = "pl.grillhub.kitchen.ACCEPT_ALL";

    private static final String CH_SERVICE = "service_en";
    private static final String CH_ALERT = "alert_en";
    private static final int ID_FG = 1, ID_ALERT = 2;
    private static final long POLL_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private MediaPlayer player;
    private String lastFg = "", lastAlert = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Store.poll();
            Printer.autoPrint(OrderService.this);
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();

        Notification n = buildForeground("Starting…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_FG, n);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kitchen:poll");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Exception e) { /* ignore */ }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kitchen:wifi");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } catch (Exception e) { /* ignore */ }

        Store.addListener(this);
        handler.post(tick);
        sync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ACCEPT_ALL.equals(intent.getAction())) Store.acceptAll();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Store.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        stopAlarm();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) { /* ignore */ }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) { /* ignore */ }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onChange() { sync(); }

    // ------------------------------------------------------------ state -> alarm + notifications

    private void sync() {
        Printer.autoPrint(this);
        List<JSONObject> ring = Store.ringing();
        if (ring.isEmpty()) {
            stopAlarm();
            if (!lastAlert.isEmpty()) { nm.cancel(ID_ALERT); lastAlert = ""; }
        } else {
            startAlarm();
            StringBuilder nums = new StringBuilder();
            for (JSONObject o : ring) {
                if (nums.length() > 0) nums.append(", ");
                nums.append("#").append(o.optInt("num"));
            }
            String key = nums.toString();
            if (!key.equals(lastAlert)) {
                lastAlert = key;
                nm.notify(ID_ALERT, buildAlert(key));
            }
        }

        int active = 0;
        for (JSONObject o : Store.orders) if (!o.optBoolean("done")) active++;
        String text = Store.authError ? "Server rejected the PIN"
                : Store.offline ? "⚠ No connection to the server"
                : !Store.loaded ? "Connecting…"
                : "Listening for orders · active: " + active;
        if (!text.equals(lastFg)) {
            lastFg = text;
            nm.notify(ID_FG, buildForeground(text));
        }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildForeground(String text) {
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Grill Hub · Kitchen")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(0xFFFF6A1A)
                .setContentIntent(openApp())
                .build();
    }

    private Notification buildAlert(String nums) {
        Intent acc = new Intent(this, OrderService.class).setAction(ACTION_ACCEPT_ALL);
        PendingIntent accPi = PendingIntent.getService(this, 1, acc, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "ACCEPT", accPi).build();
        PendingIntent open = openApp();
        return new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("🔔 NEW ORDER " + nums)
                .setContentText("Tap to open and accept")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setColor(0xFFFF6A1A)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .addAction(action)
                .build();
    }

    private void createChannels() {
        nm.deleteNotificationChannel("service");     // old channels with Polish names
        nm.deleteNotificationChannel("alert_v1");
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Background service", NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);

        NotificationChannel a = new NotificationChannel(CH_ALERT, "New orders", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(null, null);          // the alarm is played by the service itself, in a loop
        a.enableVibration(false);
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
    }

    // ------------------------------------------------------------ looping alarm

    private void startAlarm() {
        if (player != null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception e) { /* ignore */ }

        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.setVolume(1f, 1f);
            mp.setOnErrorListener((m, what, extra) -> { stopAlarm(); return true; });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            player = null;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 500}, 0));
        } catch (Exception e) { /* ignore */ }
    }

    private void stopAlarm() {
        if (player != null) {
            try { player.stop(); } catch (Exception e) { /* ignore */ }
            try { player.release(); } catch (Exception e) { /* ignore */ }
            player = null;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception e) { /* ignore */ }
    }
}
