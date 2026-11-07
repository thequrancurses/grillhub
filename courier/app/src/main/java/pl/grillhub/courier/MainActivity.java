package pl.grillhub.courier;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Home (go online, offers, current delivery) / Earnings / Profile. */
public class MainActivity extends Activity {
    private static final int OFFER_SECS = 30;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private TextView[] tabs = new TextView[3];
    private int tab = 0;
    private JSONObject jobs, earn;
    private String lastSig = "";
    private final Map<Integer, Long> seen = new HashMap<>();
    private final Set<Integer> dismissed = new HashSet<>();
    private final Map<TextView, Integer> countdowns = new HashMap<>();
    private boolean resumed, pendingOnline;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Api.token(this).isEmpty()) { toLogin(); return; }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.SURF);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.SURF);
        String[] names = {"Home", "Earnings", "Profile"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = Ui.text(this, names[i], 14, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            t.setOnClickListener(v -> { tab = k; if (k == 1) loadEarnings(); renderAll(); });
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        renderAll();
    }

    private void toLogin() { Api.logout(this); startActivity(new Intent(this, LoginActivity.class)); finish(); }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (Api.token(this).isEmpty()) return;
        h.post(pollLoop);
        h.post(tick);
    }

    @Override
    protected void onPause() { super.onPause(); resumed = false; h.removeCallbacks(pollLoop); h.removeCallbacks(tick); }

    private final Runnable pollLoop = new Runnable() {
        @Override public void run() { poll(); if (resumed) h.postDelayed(this, 4000); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean expired = false;
            long now = System.currentTimeMillis();
            for (Map.Entry<TextView, Integer> e : countdowns.entrySet()) {
                long left = OFFER_SECS - (now - seen.get(e.getValue())) / 1000;
                if (left <= 0) { dismissed.add(e.getValue()); expired = true; }
                else e.getKey().setText("Respond in " + left + "s");
            }
            if (expired) renderAll();
            if (resumed) h.postDelayed(this, 1000);
        }
    };

    private void poll() {
        String q = "jobs";
        if (TrackService.posTs > 0 && System.currentTimeMillis() - TrackService.posTs < 120000) q += "?lat=" + TrackService.lat + "&lng=" + TrackService.lng;
        Api.get(this, q, (code, j) -> {
            if (code == 401 || code == 403) { toLogin(); return; }
            if (code != 200) return;
            jobs = j;
            JSONArray of = j.optJSONArray("offers");
            if (of != null) for (int i = 0; i < of.length(); i++) { int id = of.optJSONObject(i).optInt("id"); if (!seen.containsKey(id)) seen.put(id, System.currentTimeMillis()); }
            if (j.optBoolean("online") && !TrackService.running && hasLocPerm()) startService();
            if (!j.optBoolean("online") && TrackService.running) stopService();
            JSONObject c = new JSONObject();
            try { c.put("s", j.optString("status")); c.put("o", j.optBoolean("online")); c.put("c", j.opt("current")); c.put("f", of); } catch (Exception ignore) { /* ignore */ }
            String sig = c.toString() + dismissed.size();
            if (!sig.equals(lastSig)) { lastSig = sig; renderAll(); }
        });
    }

    private boolean hasLocPerm() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private void startService() { try { startForegroundService(new Intent(this, TrackService.class)); } catch (Exception ignore) { /* ignore */ } }
    private void stopService() { try { startService(new Intent(this, TrackService.class).setAction("stop")); } catch (Exception ignore) { /* ignore */ } }

    // ------------------------------------------------------------------ rendering
    private void renderAll() {
        if (content == null) return;
        for (int i = 0; i < 3; i++) tabs[i].setTextColor(i == tab ? Ui.ORANGE : Ui.MUTED);
        countdowns.clear();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        col.setPadding(p, p, p, p);
        if (tab == 0) home(col); else if (tab == 1) earnings(col); else profile(col);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        content.removeAllViews();
        content.addView(sv);
    }

    private void home(LinearLayout col) {
        if (jobs == null) { col.addView(Ui.text(this, "Loading...", 16, Ui.MUTED, false)); return; }
        String st = jobs.optString("status");
        boolean online = jobs.optBoolean("online");
        if (st.equals("pending")) {
            col.addView(Ui.text(this, "Application under review", 24, Ui.TEXT, true));
            col.addView(Ui.text(this, "The restaurant is checking your details and documents. You will be able to go online as soon as you are approved. Open the app again later.", 15, Ui.MUTED, false), Ui.lp(this, 8));
            return;
        }
        if (st.equals("rejected") || st.equals("blocked")) {
            col.addView(Ui.text(this, st.equals("rejected") ? "Application not approved" : "Account blocked", 24, Ui.RED, true));
            col.addView(Ui.text(this, "Please contact the restaurant for details.", 15, Ui.MUTED, false), Ui.lp(this, 8));
            return;
        }
        JSONObject cur = jobs.optJSONObject("current");
        if (cur != null) { job(col, cur); return; }

        col.addView(Ui.text(this, online ? "You're online" : "You're offline", 26, online ? Ui.GREEN : Ui.TEXT, true));
        col.addView(Ui.text(this, online ? "Waiting for delivery orders. Keep the app open or in the background." : "Go online to receive delivery orders.", 14, Ui.MUTED, false), Ui.lp(this, 4));
        col.addView(Ui.space(this, 14));
        if (!online) {
            col.addView(Ui.btn(this, "GO ONLINE", Ui.GREEN, 0xFF000000, v -> goOnline(true)), new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
            return;
        }
        JSONArray of = jobs.optJSONArray("offers");
        int shown = 0;
        if (of != null) for (int i = 0; i < of.length(); i++) {
            JSONObject o = of.optJSONObject(i);
            if (dismissed.contains(o.optInt("id"))) continue;
            offer(col, o);
            shown++;
        }
        if (shown == 0) col.addView(Ui.center(Ui.text(this, "Looking for orders near you...", 16, Ui.MUTED, false)), Ui.lp(this, 30));
        col.addView(Ui.btn(this, "Go offline", Ui.SURF2, Ui.TEXT, v -> goOnline(false)), Ui.lp(this, 24));
    }

    private void offer(LinearLayout col, JSONObject o) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "NEW ORDER #" + o.optInt("num"), 12, Ui.ORANGE, true));
        c.addView(Ui.text(this, "+" + Ui.money(o.optDouble("payout")), 34, Ui.GREEN, true));
        String km = o.has("to_pickup_km") ? o.optDouble("to_pickup_km") + " km to the restaurant" : "Restaurant";
        c.addView(Ui.text(this, km, 15, Ui.TEXT, true), Ui.lp(this, 6));
        c.addView(Ui.text(this, o.optJSONObject("pickup").optString("addr"), 13, Ui.MUTED, false));
        c.addView(Ui.text(this, "Deliver to", 12, Ui.MUTED, false), Ui.lp(this, 10));
        c.addView(Ui.text(this, o.optJSONObject("dropoff").optString("addr"), 15, Ui.TEXT, true));
        c.addView(Ui.text(this, o.optInt("items") + " items  ·  Cash to collect " + Ui.money(o.optDouble("collect")), 13, Ui.MUTED, false), Ui.lp(this, 8));
        TextView cd = Ui.text(this, "Respond in " + OFFER_SECS + "s", 13, Ui.ORANGE, true);
        countdowns.put(cd, o.optInt("id"));
        c.addView(cd, Ui.lp(this, 8));
        LinearLayout row = new LinearLayout(this);
        row.addView(Ui.btn(this, "Decline", Ui.SURF2, Ui.TEXT, v -> { dismissed.add(o.optInt("id")); renderAll(); }), new LinearLayout.LayoutParams(0, -2, 1f));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        row.addView(Ui.btn(this, "ACCEPT", Ui.GREEN, 0xFF000000, v -> act(o.optInt("id"), "accept")), new LinearLayout.LayoutParams(0, -2, 2f));
        c.addView(row, Ui.lp(this, 10));
        col.addView(c, Ui.lp(this, 12));
    }

    private void job(LinearLayout col, JSONObject j) {
        String state = j.optString("state");
        boolean picked = state.equals("picked");
        JSONObject pu = j.optJSONObject("pickup"), dr = j.optJSONObject("dropoff"), cu = j.optJSONObject("customer");
        col.addView(Ui.text(this, "ORDER #" + j.optInt("num"), 12, Ui.ORANGE, true));
        col.addView(Ui.text(this, picked ? "Deliver to the customer" : "Pick up at the restaurant", 24, Ui.TEXT, true));
        LinearLayout c = Ui.card(this);
        if (!picked) {
            c.addView(Ui.text(this, pu.optString("name"), 18, Ui.TEXT, true));
            c.addView(Ui.text(this, pu.optString("addr"), 14, Ui.MUTED, false));
            long left = j.optLong("ready_at") - j.optLong("now");
            String rd = j.optBoolean("ready") ? "Order is READY - pick it up" : (left > 0 ? "Food ready in about " + Math.max(1, (left + 59) / 60) + " min" : "Food is almost ready");
            c.addView(Ui.text(this, rd, 15, j.optBoolean("ready") ? Ui.GREEN : Ui.ORANGE, true), Ui.lp(this, 8));
            c.addView(Ui.text(this, j.optInt("items") + " items", 13, Ui.MUTED, false), Ui.lp(this, 4));
            c.addView(Ui.btn(this, "Navigate to restaurant", Ui.BLUE, Ui.TEXT, v -> navigate(pu.optString("addr"))), Ui.lp(this, 12));
        } else {
            c.addView(Ui.text(this, dr.optString("addr"), 18, Ui.TEXT, true));
            if (!dr.optString("addr2").isEmpty()) c.addView(Ui.text(this, dr.optString("addr2"), 14, Ui.MUTED, false));
            if (cu != null) c.addView(Ui.text(this, cu.optString("name") + "  ·  " + cu.optString("phone"), 14, Ui.MUTED, false), Ui.lp(this, 6));
            if (!j.optString("notes").isEmpty()) c.addView(Ui.text(this, "Note: " + j.optString("notes"), 13, Ui.ORANGE, false), Ui.lp(this, 6));
            c.addView(Ui.btn(this, "Navigate to customer", Ui.BLUE, Ui.TEXT, v -> navigate(dr.optString("addr"))), Ui.lp(this, 12));
            if (cu != null) c.addView(Ui.btn(this, "Call customer", Ui.SURF2, Ui.TEXT, v -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + cu.optString("phone").replaceAll("[^+0-9]", ""))))), Ui.lp(this, 8));
            c.addView(Ui.text(this, "Collect cash: " + Ui.money(j.optDouble("collect")), 20, Ui.GREEN, true), Ui.lp(this, 14));
        }
        col.addView(c, Ui.lp(this, 12));
        final int id = j.optInt("id");
        if (!picked) {
            col.addView(Ui.btn(this, "I PICKED UP THE ORDER", Ui.GREEN, 0xFF000000, v -> act(id, "pickup")), Ui.lp(this, 16));
            col.addView(Ui.btn(this, "Cancel this job", Ui.SURF2, Ui.RED, v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Give this order back? It will be offered to other couriers.").setNegativeButton("No", null)
                    .setPositiveButton("Yes, give back", (d, w) -> act(id, "release")).show()), Ui.lp(this, 10));
        } else {
            col.addView(Ui.btn(this, "DELIVERED - CASH COLLECTED", Ui.GREEN, 0xFF000000, v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Confirm the order was handed to the customer and you collected " + Ui.money(j.optDouble("collect")) + "?").setNegativeButton("Not yet", null)
                    .setPositiveButton("Confirm", (d, w) -> act(id, "deliver")).show()), Ui.lp(this, 16));
        }
    }

    private void navigate(String addr) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(addr)))); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(addr)))); }
            catch (Exception e2) { Toast.makeText(this, "No maps app found.", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void act(int id, String a) {
        Api.post(this, "jobs/" + id + "/" + a, null, (code, j) -> {
            if (code == 200 && j.optBoolean("ok")) { if (a.equals("deliver")) Toast.makeText(this, "Delivered. Nice work!", Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(this, Api.err(code, j), Toast.LENGTH_LONG).show();
            lastSig = "";
            poll();
        });
    }

    private void goOnline(boolean on) {
        if (on && !hasLocPerm()) {
            pendingOnline = true;
            requestPermissions(android.os.Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("online", on);
            if (TrackService.posTs > 0) { o.put("lat", TrackService.lat); o.put("lng", TrackService.lng); }
            Api.post(this, "online", o, (code, j) -> {
                if (code == 200 && j.optBoolean("ok")) { if (on) startService(); else stopService(); }
                else Toast.makeText(this, Api.err(code, j), Toast.LENGTH_LONG).show();
                lastSig = "";
                poll();
            });
        } catch (Exception ignore) { /* ignore */ }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 7 && pendingOnline) {
            pendingOnline = false;
            if (hasLocPerm()) goOnline(true);
            else Toast.makeText(this, "Location permission is needed to receive orders.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ earnings / profile
    private void loadEarnings() {
        Api.get(this, "earnings", (code, j) -> { if (code == 200) { earn = j; if (tab == 1) renderAll(); } });
    }

    private void earnings(LinearLayout col) {
        col.addView(Ui.text(this, "Earnings", 26, Ui.TEXT, true));
        if (earn == null) { col.addView(Ui.text(this, "Loading...", 15, Ui.MUTED, false), Ui.lp(this, 10)); return; }
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "TODAY", 12, Ui.ORANGE, true));
        c.addView(Ui.text(this, Ui.money(earn.optDouble("today")), 34, Ui.GREEN, true));
        c.addView(Ui.text(this, earn.optInt("n_today") + " deliveries  ·  cash collected " + Ui.money(earn.optDouble("cash_today")), 13, Ui.MUTED, false));
        c.addView(Ui.text(this, "This week: " + Ui.money(earn.optDouble("week")) + " (" + earn.optInt("n_week") + ")", 15, Ui.TEXT, true), Ui.lp(this, 10));
        c.addView(Ui.text(this, "Last 45 days: " + Ui.money(earn.optDouble("all")) + " (" + earn.optInt("n_all") + ")", 15, Ui.TEXT, false), Ui.lp(this, 4));
        col.addView(c, Ui.lp(this, 12));
        JSONArray l = earn.optJSONArray("list");
        if (l != null) for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            LinearLayout r = Ui.card(this);
            r.addView(Ui.text(this, "#" + o.optInt("num") + "  ·  " + o.optString("addr"), 14, Ui.TEXT, true));
            r.addView(Ui.text(this, "+" + Ui.money(o.optDouble("payout")) + "   ·   " + new java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.US).format(new java.util.Date(o.optLong("at") * 1000)), 13, Ui.MUTED, false));
            col.addView(r, Ui.lp(this, 8));
        }
    }

    private void profile(LinearLayout col) {
        col.addView(Ui.text(this, "Profile", 26, Ui.TEXT, true));
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, jobs == null ? "" : "Status: " + jobs.optString("status"), 15, Ui.TEXT, true));
        c.addView(Ui.text(this, "To change your details or delete your data, contact the restaurant.", 13, Ui.MUTED, false), Ui.lp(this, 6));
        col.addView(c, Ui.lp(this, 12));
        col.addView(Ui.btn(this, "Log out", Ui.SURF2, Ui.RED, v -> {
            if (jobs != null && jobs.optJSONObject("current") != null) { Toast.makeText(this, "Finish your current delivery first.", Toast.LENGTH_LONG).show(); return; }
            try {
                JSONObject o = new JSONObject();
                o.put("online", false);
                Api.post(this, "online", o, (code, j) -> { stopService(); toLogin(); });
            } catch (Exception e) { toLogin(); }
        }), Ui.lp(this, 18));
    }
}
