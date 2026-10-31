package pl.grillhub.kitchen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared state for the app. Lives in the process, used by both the foreground
 * service (polling + alarm) and the activity (UI). All state is touched on the
 * main thread only; network runs on a small thread pool.
 */
final class Store {

    static final String BASE = "https://grillhub.pl/wp-json/ghm/v1/";
    static final String PIN = "1111";                    // built in: no login screen
    static final long MAX_RING_AGE = 6 * 3600 * 1000L;   // don't ring for orders older than this

    interface Listener { void onChange(); }
    interface Result { void done(String body); }          // body == null -> failed

    private static class AuthException extends Exception {}

    static final List<JSONObject> orders = new ArrayList<>();
    static boolean offline = false, authError = false, loaded = false;
    static String printError = null;
    static long srvOff = 0;

    private static final Set<Integer> acked = new HashSet<>();
    private static final Set<Integer> printed = new HashSet<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService net = Executors.newFixedThreadPool(3);
    private static SharedPreferences prefs;
    private static int lastId = 0, pollN = 0, fails = 0;
    private static boolean busy = false, inited = false;

    private Store() {}

    // ------------------------------------------------------------ setup

    static void init(Context c) {
        if (inited) return;
        inited = true;
        prefs = c.getApplicationContext().getSharedPreferences("kitchen", Context.MODE_PRIVATE);
        load("acked", acked);
        load("printed", printed);
    }

    private static void load(String key, Set<Integer> into) {
        for (String s : prefs.getString(key, "").split(",")) {
            try { if (!s.isEmpty()) into.add(Integer.parseInt(s)); } catch (Exception e) { /* ignore */ }
        }
    }

    static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    private static void notifyChange() {
        for (Listener l : new ArrayList<>(listeners)) l.onChange();
    }

    private static void persistAcked() { persist("acked", acked); }

    private static void persist(String key, Set<Integer> set) {
        if (set.size() > 500) {
            List<Integer> all = new ArrayList<>(set);
            Collections.sort(all);
            for (int i = 0; i < all.size() - 400; i++) set.remove(all.get(i));
        }
        StringBuilder sb = new StringBuilder();
        for (int id : set) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    // ------------------------------------------------------------ polling

    static void poll() {
        if (busy) return;
        busy = true;
        final boolean full = !loaded || (++pollN % 5 == 0);
        final int after = full ? 0 : lastId;
        net.execute(() -> {
            JSONObject res = null;
            String err = null;
            try {
                res = new JSONObject(request("orders?after=" + after, "GET"));
            } catch (AuthException e) {
                err = "auth";
            } catch (Exception e) {
                err = "net";
            }
            final JSONObject r = res;
            final String er = err;
            main.post(() -> { busy = false; onPoll(r, er); });
        });
    }

    private static void onPoll(JSONObject r, String err) {
        boolean changed = false;
        if ("auth".equals(err)) {
            if (!authError) { authError = true; changed = true; }
        } else if (err != null) {
            fails++;
            if (fails >= 3 && !offline) { offline = true; changed = true; }
        } else {
            fails = 0;
            if (offline || authError) { offline = false; authError = false; changed = true; }
            long now = r.optLong("now", 0);
            if (now > 0) srvOff = now * 1000 - System.currentTimeMillis();
            if (merge(r.optJSONArray("orders"))) changed = true;
            if (!loaded) { loaded = true; changed = true; }
        }
        if (changed) notifyChange();
    }

    private static boolean merge(JSONArray arr) {
        if (arr == null) return false;
        boolean first = !loaded && !prefs.getBoolean("init", false);
        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int id = o.optInt("id");
            JSONObject ex = find(id);
            if (ex != null) {
                try {
                    if (ex.optBoolean("done") != o.optBoolean("done")) { ex.put("done", o.optBoolean("done")); changed = true; }
                    if (ex.optLong("ready_at") != o.optLong("ready_at")) { ex.put("ready_at", o.optLong("ready_at")); changed = true; }
                } catch (Exception e) { /* ignore */ }
                continue;
            }
            orders.add(o);
            changed = true;
            lastId = Math.max(lastId, id);
            if (first) { acked.add(id); printed.add(id); }   // very first run: old orders must not ring or print
        }
        if (first) { prefs.edit().putBoolean("init", true).apply(); persistAcked(); persist("printed", printed); }
        Collections.sort(orders, (a, b) -> a.optInt("id") - b.optInt("id"));
        return changed;
    }

    static JSONObject find(int id) {
        for (JSONObject o : orders) if (o.optInt("id") == id) return o;
        return null;
    }

    // ------------------------------------------------------------ ringing = not done, not accepted yet

    static List<JSONObject> ringing() {
        List<JSONObject> out = new ArrayList<>();
        long now = serverNow();
        for (JSONObject o : orders) {
            if (o.optBoolean("done")) continue;
            if (acked.contains(o.optInt("id"))) continue;
            if (now - ts(o) > MAX_RING_AGE) continue;
            out.add(o);
        }
        return out;
    }

    static boolean isRinging(int id) {
        for (JSONObject o : ringing()) if (o.optInt("id") == id) return true;
        return false;
    }

    static void accept(int id) {
        acked.add(id);
        persistAcked();
        notifyChange();
    }

    static void acceptAll() {
        for (JSONObject o : ringing()) acked.add(o.optInt("id"));
        persistAcked();
        notifyChange();
    }

    // ------------------------------------------------------------ printing state

    static List<JSONObject> unprinted() {
        List<JSONObject> out = new ArrayList<>();
        long now = serverNow();
        for (JSONObject o : orders) {
            if (o.optBoolean("done")) continue;
            if (printed.contains(o.optInt("id"))) continue;
            if (now - ts(o) > MAX_RING_AGE) continue;
            out.add(o);
        }
        return out;
    }

    static void markPrinted(int id) {
        if (printed.add(id)) persist("printed", printed);
    }

    /** e.g. right after the first printer is chosen: don't print the backlog */
    static void markAllPrinted() {
        for (JSONObject o : orders) printed.add(o.optInt("id"));
        persist("printed", printed);
    }

    static void setPrintError(String e) {
        boolean changed = (e == null) != (printError == null) || (e != null && !e.equals(printError));
        printError = e;
        if (changed) notifyChange();
    }

    // ------------------------------------------------------------ actions

    static void setDone(final int id, final boolean done, final Result cb) {
        act("orders/" + id + "/done" + (done ? "" : "?undo=1"), body -> {
            if (body != null) {
                JSONObject o = find(id);
                try { if (o != null) o.put("done", done); } catch (Exception e) { /* ignore */ }
                if (done) { acked.add(id); persistAcked(); }
                notifyChange();
            }
            if (cb != null) cb.done(body);
        });
    }

    static void setPrep(final int id, int minutes, final Result cb) {
        act("orders/" + id + "/prep?min=" + minutes, body -> {
            if (body != null) {
                JSONObject o = find(id);
                try {
                    long ra = new JSONObject(body).optLong("ready_at");
                    if (o != null && ra > 0) o.put("ready_at", ra);
                } catch (Exception e) { /* ignore */ }
                acked.add(id);
                persistAcked();
                notifyChange();
            }
            if (cb != null) cb.done(body);
        });
    }

    private static void act(final String path, final Result cb) {
        net.execute(() -> {
            String body = null;
            boolean auth = false;
            try {
                body = request(path, "POST");
            } catch (AuthException e) {
                auth = true;
            } catch (Exception e) { /* network */ }
            final String b = body;
            final boolean a = auth;
            main.post(() -> {
                if (a && !authError) { authError = true; notifyChange(); }
                cb.done(b);
            });
        });
    }

    private static String request(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("X-GHM-PIN", PIN);
            c.setRequestProperty("Cache-Control", "no-store");
            c.setRequestProperty("User-Agent", "GrillHubKitchen/2.0 (Android)");
            if (method.equals("POST")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new AuthException();
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ order helpers

    static long serverNow() { return System.currentTimeMillis() + srvOff; }

    static long ts(JSONObject o) { return o.optLong("ts") * 1000; }

    static long etaMs(JSONObject o) {
        if (!"pick".equals(o.optString("mode"))) return 0;
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(o.optString("eta", ""));
        if (!m.find()) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts(o));
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
        c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() < ts(o) - 3600000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static boolean isSched(JSONObject o) {
        long e = etaMs(o);
        return !o.optBoolean("done") && e > 0 && e - System.currentTimeMillis() > 30 * 60000L;
    }

    static String inMin(long ms) {
        long m = Math.round((ms - System.currentTimeMillis()) / 60000.0);
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    static String ago(JSONObject o) {
        long m = Math.max(0, (serverNow() - ts(o)) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        return (m / 60) + " h " + (m % 60) + " min ago";
    }

    static String hhmm(JSONObject o) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(ts(o)));
    }

    static String money(double n) {
        n = Math.round(n * 100) / 100.0;
        if (n == Math.floor(n)) return String.valueOf((long) n);
        return String.format(Locale.US, "%.2f", n);
    }
}
