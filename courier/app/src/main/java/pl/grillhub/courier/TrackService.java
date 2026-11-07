package pl.grillhub.courier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs while the courier is ONLINE: shares the GPS position with the server, polls for job offers every few seconds and
 * rings + notifies when a new order is offered (works with the screen off / app in the background).
 */
public class TrackService extends Service implements LocationListener {
    static volatile double lat, lng;
    static volatile long posTs;
    static volatile boolean running;

    private static final String CH_ON = "online", CH_OFFER = "offers";
    private static final int ID_ON = 1, ID_OFFER = 2;
    private HandlerThread ht;
    private Handler h;
    private final Set<Integer> notified = new HashSet<>();
    private MediaPlayer ring;
    private long ringUntil;
    private String lastText = "";
    private PowerManager.WakeLock wl;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_ON, "Online status", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel off = new NotificationChannel(CH_OFFER, "New delivery offers", NotificationManager.IMPORTANCE_HIGH);
        off.enableVibration(true);
        off.setSound(null, null);                     // the service plays its own looping ring
        nm.createNotificationChannel(off);
        ht = new HandlerThread("track");
        ht.start();
        h = new Handler(ht.getLooper());
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        if (in != null && "stop".equals(in.getAction())) { stopSelf(); return START_NOT_STICKY; }
        Notification n = ongoing("You're online - looking for orders");
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID_ON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(ID_ON, n);
        if (!running) {
            running = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "courier:track");
            wl.acquire(12 * 60 * 60 * 1000L);
            startLocation();
            h.post(loop);
        }
        return START_STICKY;
    }

    private void startLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Looper lo = ht.getLooper();
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000, 5, this, lo);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000, 20, this, lo);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) onLocationChanged(l);
        } catch (SecurityException e) { /* permission revoked: the loop still polls */ }
    }

    @Override public void onLocationChanged(Location l) { lat = l.getLatitude(); lng = l.getLongitude(); posTs = System.currentTimeMillis(); }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            try { poll(); } catch (Exception ignore) { /* keep going */ }
            if (running) h.postDelayed(this, 5000);
        }
    };

    private void poll() throws Exception {
        String q = "jobs";
        if (posTs > 0 && System.currentTimeMillis() - posTs < 120000) q += "?lat=" + lat + "&lng=" + lng;
        Api.Result r = Api.getSync(this, q);
        if (r.code == 401 || r.code == 403) { stopSelf(); return; }
        if (r.code != 200) return;
        JSONObject j = r.json;
        if (!j.optBoolean("online")) { stopSelf(); return; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        JSONObject cur = j.optJSONObject("current");
        JSONArray offers = j.optJSONArray("offers");
        String text = cur != null ? "Delivering order #" + cur.optInt("num") : "You're online - looking for orders";
        if (!text.equals(lastText)) { lastText = text; nm.notify(ID_ON, ongoing(text)); }
        if (cur == null && offers != null && offers.length() > 0) {
            boolean fresh = false;
            JSONObject first = offers.getJSONObject(0);
            for (int i = 0; i < offers.length(); i++) if (notified.add(offers.getJSONObject(i).optInt("id"))) fresh = true;
            if (fresh) {
                Intent oi = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(this, 0, oi, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = new Notification.Builder(this, CH_OFFER)
                        .setSmallIcon(android.R.drawable.ic_dialog_map)
                        .setContentTitle("New delivery order  +" + Ui.money(first.optDouble("payout")))
                        .setContentText(first.optJSONObject("dropoff").optString("addr"))
                        .setCategory(Notification.CATEGORY_CALL)
                        .setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true).build();
                nm.notify(ID_OFFER, n);
                startRing();
            }
        } else {
            nm.cancel(ID_OFFER);
            stopRing();
        }
        if (ring != null && System.currentTimeMillis() > ringUntil) stopRing();
    }

    private Notification ongoing(String text) {
        Intent oi = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, oi, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_ON).setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Grill Hub Delivery").setContentText(text).setOngoing(true).setContentIntent(pi).build();
    }

    private void startRing() {
        if (ring != null) { ringUntil = System.currentTimeMillis() + 25000; return; }
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, u);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            ring = mp;
            ringUntil = System.currentTimeMillis() + 25000;
        } catch (Exception e) { ring = null; }
    }

    private void stopRing() {
        if (ring == null) return;
        try { ring.stop(); ring.release(); } catch (Exception ignore) { /* ignore */ }
        ring = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopRing();
        try { ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this); } catch (Exception ignore) { /* ignore */ }
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ID_OFFER); } catch (Exception ignore) { /* ignore */ }
        if (wl != null && wl.isHeld()) wl.release();
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }
}
