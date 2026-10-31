package pl.grillhub.kitchen;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity implements Store.Listener {

    // palette
    static final int BG = 0xFF0E1014, SURFACE = 0xFF191C23, SURFACE2 = 0xFF242834, LINE = 0xFF2C313D,
            TEXT = 0xFFF3F4F6, DIM = 0xFF9AA3B2,
            ORANGE = 0xFFFF6A1A, ORANGE_DIM = 0x33FF6A1A,
            GREEN = 0xFF22C55E, RED = 0xFFEF4444, RED_DIM = 0x33EF4444,
            AMBER = 0xFFF59E0B, AMBER_DIM = 0x26F59E0B,
            BLUE = 0xFF60A5FA, BLUE_DIM = 0x2660A5FA,
            PURPLE = 0xFFC084FC, PURPLE_DIM = 0x26C084FC;

    private static final int RC_NOTIF = 1, RC_BATT = 2, RC_FSI = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private ScrollView sv;
    private String tab = "orders";

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 30000);
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        prefs = getSharedPreferences("kitchen_ui", MODE_PRIVATE);
        tab = prefs.getString("tab", "orders");

        // show over the lock screen and wake the tablet when a new order arrives
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        startListener();
        setupNext();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Store.addListener(this);
        startListener();
        render();
        ui.removeCallbacks(clock);
        ui.postDelayed(clock, 30000);
    }

    @Override
    protected void onStop() {
        Store.removeListener(this);
        ui.removeCallbacks(clock);
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        render();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // keep running, never close
    }

    @Override
    public void onChange() { render(); }

    private void startListener() {
        try { startForegroundService(new Intent(this, OrderService.class)); } catch (Exception e) { /* ignore */ }
    }

    // ------------------------------------------------------------ one-time setup (permissions)

    private void setupNext() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("a_notif", false)) {
            prefs.edit().putBoolean("a_notif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, RC_NOTIF);
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("a_batt", false)) {
            prefs.edit().putBoolean("a_batt", true).apply();
            try {
                startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())), RC_BATT);
                return;
            } catch (Exception e) { /* try next */ }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.canUseFullScreenIntent() && !prefs.getBoolean("a_fsi", false)) {
                prefs.edit().putBoolean("a_fsi", true).apply();
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName())), RC_FSI);
                } catch (Exception e) { /* ignore */ }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        startListener();
        setupNext();
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        setupNext();
    }

    // ------------------------------------------------------------ small view helpers

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Drawable ripple(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape(color, radiusDp), shape(Color.WHITE, radiusDp));
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(String s, int bg, int fg) {
        TextView t = tv(s, 12, fg, true);
        t.setBackground(shape(bg, 20));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setSingleLine(true);
        return t;
    }

    private TextView button(String s, int bg, float sp, View.OnClickListener click) {
        TextView t = tv(s, sp, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(bg, 14));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView banner(String s, int bg, int fg, View.OnClickListener click) {
        TextView t = tv(s, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(bg);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (click != null) { t.setClickable(true); t.setOnClickListener(click); }
        return t;
    }

    // ------------------------------------------------------------ screen

    private void render() {
        if (root == null) return;
        final int scrollY = sv != null ? sv.getScrollY() : 0;

        List<JSONObject> act = new ArrayList<>(), sch = new ArrayList<>(), his = new ArrayList<>();
        for (JSONObject o : Store.orders) {
            if (o.optBoolean("done")) his.add(o);
            else if (Store.isSched(o)) sch.add(o);
            else act.add(o);
        }
        Collections.reverse(act);
        Collections.reverse(his);
        Collections.sort(sch, (a, b) -> Long.compare(Store.etaMs(a), Store.etaMs(b)));
        List<JSONObject> ring = Store.ringing();

        root.removeAllViews();

        // ---- banners
        if (!ring.isEmpty()) {
            StringBuilder nums = new StringBuilder();
            for (JSONObject o : ring) { if (nums.length() > 0) nums.append(", "); nums.append("#").append(o.optInt("num")); }
            TextView b = banner("🔔  NOWE ZAMÓWIENIE " + nums + "  —  PRZYJMIJ", ORANGE, Color.WHITE, v -> Store.acceptAll());
            b.setTextSize(18);
            AlphaAnimation a = new AlphaAnimation(1f, 0.6f);
            a.setDuration(550);
            a.setRepeatMode(Animation.REVERSE);
            a.setRepeatCount(Animation.INFINITE);
            b.startAnimation(a);
            root.addView(b);
        }
        if (Store.authError) root.addView(banner("⛔ Serwer odrzucił PIN — zmień PIN w ustawieniach wtyczki albo w aplikacji", RED, Color.WHITE, null));
        else if (Store.offline) root.addView(banner("⚠ Brak połączenia z serwerem — nowe zamówienia mogą nie dochodzić!", RED, Color.WHITE, null));

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) {
            root.addView(banner("Powiadomienia są wyłączone — dotknij, aby włączyć", AMBER, Color.BLACK, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (Exception e) { /* ignore */ }
            }));
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            root.addView(banner("Aby działać w tle, wyłącz optymalizację baterii — dotknij", AMBER, Color.BLACK, v -> {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())), RC_BATT);
                } catch (Exception e) { /* ignore */ }
            }));
        }

        // ---- header
        int cnt = 0;
        double sum = 0;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        for (JSONObject o : Store.orders) if (Store.ts(o) >= c.getTimeInMillis()) { cnt++; sum += o.optDouble("total"); }

        LinearLayout head = hbox();
        head.setPadding(dp(16), dp(12), dp(16), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, lp(dp(44), dp(44), 0f, 0, 0, 12, 0));
        LinearLayout titles = vbox();
        titles.addView(tv("Grill Hub · Kuchnia", 20, TEXT, true));
        titles.addView(tv("Dzisiaj: " + cnt + " zamówień · " + Store.money(sum) + " zł", 13, DIM, false));
        head.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        boolean bad = Store.offline || Store.authError;
        head.addView(chip(bad ? "● OFFLINE" : "● ONLINE", bad ? RED_DIM : 0x2622C55E, bad ? RED : GREEN));
        root.addView(head);

        // ---- tabs
        LinearLayout tabs = hbox();
        tabs.setPadding(dp(12), 0, dp(12), dp(8));
        String[][] T = {
                {"orders", "Zamówienia", String.valueOf(act.size())},
                {"sched", "Zaplanowane", String.valueOf(sch.size())},
                {"history", "Historia", "0"}};
        for (final String[] t : T) {
            boolean on = tab.equals(t[0]);
            String label = t[1] + (!t[2].equals("0") ? "  " + t[2] : "");
            TextView tb = tv(label, 15, on ? Color.WHITE : DIM, true);
            tb.setGravity(Gravity.CENTER);
            tb.setBackground(ripple(on ? ORANGE : SURFACE2, 22));
            tb.setClickable(true);
            tb.setOnClickListener(v -> {
                tab = t[0];
                prefs.edit().putString("tab", tab).apply();
                sv = null;
                render();
            });
            tabs.addView(tb, lp(0, dp(46), 1f, 4, 0, 4, 0));
        }
        root.addView(tabs);

        // ---- list (grid of cards; more columns on a tablet)
        List<JSONObject> list = tab.equals("orders") ? act : tab.equals("sched") ? sch : his;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int cols = Math.max(1, Math.min(3, widthDp / 360));

        LinearLayout box = vbox();
        box.setPadding(dp(8), dp(4), dp(8), dp(24));
        if (list.isEmpty()) {
            LinearLayout empty = vbox();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(90), 0, 0);
            TextView icon = tv(tab.equals("orders") ? "🍽" : tab.equals("sched") ? "🕒" : "📋", 54, TEXT, false);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView msg = tv(tab.equals("orders") ? "Czekamy na zamówienia…" : tab.equals("sched") ? "Brak zaplanowanych zamówień" : "Brak historii", 18, DIM, false);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(10), 0, 0);
            empty.addView(msg);
            box.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (int i = 0; i < list.size(); i += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                for (int k = 0; k < cols; k++) {
                    int idx = i + k;
                    LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 6, 6, 6, 6);
                    if (idx < list.size()) row.addView(card(list.get(idx), tab.equals("sched")), p);
                    else row.addView(new View(this), p);
                }
                box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(box);
        root.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sv = s;
        s.post(() -> s.scrollTo(0, scrollY));
    }

    // ------------------------------------------------------------ order card

    private View card(final JSONObject o, boolean scheduled) {
        final int id = o.optInt("id");
        final boolean done = o.optBoolean("done");
        final boolean dine = "dine".equals(o.optString("mode"));
        final boolean ringing = Store.isRinging(id);

        LinearLayout c = vbox();
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = shape(SURFACE, 18);
        bg.setStroke(dp(ringing ? 3 : 1), ringing ? ORANGE : LINE);
        c.setBackground(bg);
        if (done) c.setAlpha(0.65f);

        // number + time
        LinearLayout head = hbox();
        head.addView(tv("#" + o.optInt("num"), 32, ORANGE, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        LinearLayout when = vbox();
        TextView t1 = tv(Store.hhmm(o), 18, TEXT, true);
        t1.setGravity(Gravity.END);
        TextView t2 = tv(Store.ago(o), 12, DIM, false);
        t2.setGravity(Gravity.END);
        when.addView(t1);
        when.addView(t2);
        head.addView(when);
        c.addView(head);

        // type chips
        LinearLayout chips = hbox();
        chips.addView(dine ? chip("🍽  NA MIEJSCU" + (o.optString("table").isEmpty() ? "" : " · STOLIK " + o.optString("table")), BLUE_DIM, BLUE)
                : chip("🛍  ODBIÓR", PURPLE_DIM, PURPLE));
        String src = o.optString("src", "");
        if (!src.isEmpty() && !src.equals("direct")) chips.addView(chip(src, SURFACE2, DIM), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 8, 0, 0, 0));
        c.addView(chips, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));

        if (scheduled) {
            c.addView(tv("🕒  Odbiór za " + Store.inMin(Store.etaMs(o)) + " (" + o.optString("eta") + ")", 15, ORANGE, true),
                    lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
        }
        if (!dine) {
            String who = o.optString("name") + (o.optString("phone").isEmpty() ? "" : "  ·  " + o.optString("phone"));
            c.addView(tv(who, 16, TEXT, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
            if (!o.optString("eta").isEmpty() && !scheduled)
                c.addView(tv("Godzina odbioru: " + o.optString("eta"), 14, AMBER, true));
        }

        addDivider(c);

        // items
        JSONArray lines = o.optJSONArray("lines");
        if (lines != null) for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView q = tv(l.optInt("qty") + "×", 16, ORANGE, true);
            q.setGravity(Gravity.CENTER);
            q.setBackground(shape(ORANGE_DIM, 8));
            q.setPadding(dp(8), dp(2), dp(8), dp(2));
            row.addView(q, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 2, 10, 0));
            LinearLayout col = vbox();
            col.addView(tv(l.optString("name"), 17, TEXT, true));
            String d = l.optString("desc", "");
            if (!d.isEmpty()) col.addView(tv(d, 13, DIM, false));
            row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            c.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 5, 0, 5));
        }

        String notes = o.optString("notes", "");
        if (!notes.isEmpty()) {
            TextView n = tv("📝  " + notes, 15, AMBER, true);
            n.setBackground(shape(AMBER_DIM, 10));
            n.setPadding(dp(12), dp(8), dp(12), dp(8));
            c.addView(n, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
        }

        addDivider(c);

        // fees + total
        LinearLayout foot = hbox();
        StringBuilder fee = new StringBuilder();
        if (o.optDouble("deposit", 0) > 0) fee.append("Kaucja +").append(Store.money(o.optDouble("deposit"))).append(" zł\n");
        if (o.optDouble("pack", 0) > 0) fee.append("Opakowanie +").append(Store.money(o.optDouble("pack"))).append(" zł\n");
        if (o.optDouble("combo", 0) > 0) fee.append("Zestaw −").append(Store.money(o.optDouble("combo"))).append(" zł\n");
        if (o.optDouble("coupon", 0) > 0) fee.append("Kod ").append(o.optString("code")).append(" −").append(Store.money(o.optDouble("coupon"))).append(" zł\n");
        TextView f = tv(fee.toString().trim(), 12, DIM, false);
        foot.addView(f, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        foot.addView(tv(Store.money(o.optDouble("total")) + " zł", 24, TEXT, true));
        c.addView(foot);

        // actions
        if (done) {
            c.addView(button("↩  Przywróć", SURFACE2, 16, v -> Store.setDone(id, false, null)),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0f, 0, 12, 0, 0));
        } else if (ringing) {
            TextView acc = button("✓  PRZYJMIJ ZAMÓWIENIE", ORANGE, 19, v -> Store.accept(id));
            c.addView(acc, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), 0f, 0, 12, 0, 0));
        } else {
            long readyAt = o.optLong("ready_at");
            boolean hasEta = !dine && !o.optString("eta").isEmpty();
            if (readyAt > 0 && !hasEta) {
                long m = (long) Math.ceil((readyAt * 1000 - Store.serverNow()) / 60000.0);
                c.addView(tv(m > 0 ? "⏱  Obiecane: za " + m + " min" : "⏱  Obiecany czas minął", 14, m > 0 ? TEXT : RED, true),
                        lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 10, 0, 0));
                LinearLayout pr = hbox();
                for (final int mins : new int[]{5, 10, 15, 20, 30}) {
                    pr.addView(button("+" + mins + "′", SURFACE2, 15, v -> Store.setPrep(id, mins, r -> {
                        if (r == null) Toast.makeText(this, "Błąd sieci", Toast.LENGTH_SHORT).show();
                    })), lp(0, dp(44), 1f, 2, 6, 2, 0));
                }
                c.addView(pr);
            }
            c.addView(button("✓  GOTOWE", GREEN, 18, v -> Store.setDone(id, true, r -> {
                if (r == null) Toast.makeText(this, "Błąd sieci", Toast.LENGTH_SHORT).show();
            })), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), 0f, 0, 12, 0, 0));
        }
        return c;
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        parent.addView(d, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, 10, 0, 6));
    }
}
