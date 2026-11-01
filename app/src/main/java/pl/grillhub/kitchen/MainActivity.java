package pl.grillhub.kitchen;

import android.app.Activity;
import android.app.Dialog;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.provider.OpenableColumns;
import android.view.Window;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.text.InputType;
import android.widget.EditText;
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

    private static final int RC_NOTIF = 1, RC_BATT = 2, RC_FSI = 3, RC_BT = 4;
    private final List<PrinterScan.Found> found = new ArrayList<>();
    private boolean scanning = false, scanned = false;
    private String query = "";
    private static final int RC_PICK = 5;
    private static final class Cd { final TextView t; final int id; Cd(TextView t, int id) { this.t = t; this.id = id; } }
    private final List<Cd> cds = new ArrayList<>();
    private TextView dlgCountdown = null;
    private int dlgId = 0;
    private boolean animNew = false, renderedOnce = false;
    private final Set<Integer> shown = new HashSet<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private ScrollView sv;
    private String tab = "orders";

    private final Runnable secTick = new Runnable() {
        @Override public void run() {
            for (Cd c : cds) paintCountdown(c.t, c.id);
            if (dlgCountdown != null) paintCountdown(dlgCountdown, dlgId);
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 15000);
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
        ui.postDelayed(clock, 15000);
        ui.removeCallbacks(secTick);
        ui.postDelayed(secTick, 1000);
    }

    @Override
    protected void onStop() {
        Store.removeListener(this);
        ui.removeCallbacks(clock);
        ui.removeCallbacks(secTick);
        PrinterScan.stop();
        scanning = false;
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
        if (code == RC_BT) { startScan(btGranted()); return; }
        startListener();
        setupNext();
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == RC_PICK) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { /* ignore */ }
                String name = "sound file";
                try (Cursor cur = getContentResolver().query(u, null, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0) name = cur.getString(i);
                    }
                } catch (Exception e) { /* ignore */ }
                Printer.set(this, "alarm_uri", u.toString());
                Printer.set(this, "alarm_name", name);
                Printer.set(this, "alarm", "custom");
                render();
            }
            return;
        }
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
        t.setBackground(bg == ORANGE || bg == GREEN ? gradient(bg, 14) : ripple(bg, 14));
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
        applyKeepScreen();
        animNew = renderedOnce;
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
        cds.clear();

        // ---- banners
        if (!ring.isEmpty()) {
            StringBuilder nums = new StringBuilder();
            for (JSONObject o : ring) { if (nums.length() > 0) nums.append(", "); nums.append("#").append(o.optInt("num")); }
            TextView b = banner("🔔  NEW ORDER " + nums + "  —  ACCEPT", ORANGE, Color.WHITE, v -> Store.acceptAll());
            b.setTextSize(18);
            AlphaAnimation a = new AlphaAnimation(1f, 0.6f);
            a.setDuration(550);
            a.setRepeatMode(Animation.REVERSE);
            a.setRepeatCount(Animation.INFINITE);
            b.startAnimation(a);
            root.addView(b);
        }
        if (Store.authError) root.addView(banner("⛔ Server rejected the PIN — change the PIN in the plugin settings or in the app", RED, Color.WHITE, null));
        else if (Store.offline) root.addView(banner("⚠ No connection to the server — new orders may not arrive!", RED, Color.WHITE, null));

        if (!Printer.configured(this)) {
            root.addView(banner("🖨 No printer selected — tap to choose", AMBER, Color.BLACK, v -> openSettings()));
        } else if (Store.printError != null) {
            root.addView(banner("🖨 Printer error: " + Store.printError + " — tap", RED, Color.WHITE, v -> openSettings()));
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) {
            root.addView(banner("Notifications are turned off — tap to enable", AMBER, Color.BLACK, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (Exception e) { /* ignore */ }
            }));
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            root.addView(banner("To keep running in the background, turn off battery optimization — tap", AMBER, Color.BLACK, v -> {
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

        int widthDp0 = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        LinearLayout head = hbox();
        head.setPadding(dp(16), dp(12), dp(16), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, lp(dp(46), dp(46), 0f, 0, 0, 12, 0));
        LinearLayout titles = vbox();
        titles.addView(tv("Grill Hub", 22, TEXT, true));
        titles.addView(tv("KITCHEN DISPLAY", 11, ORANGE, true));
        head.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        if (widthDp0 >= 700) {
            head.addView(tile("OPEN", String.valueOf(act.size())), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 8, 0));
            head.addView(tile("TODAY", cnt + " orders"), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 8, 0));
            head.addView(tile("REVENUE", Store.money(sum) + " PLN"), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 14, 0));
        }
        LinearLayout right = vbox();
        right.setGravity(Gravity.END);
        right.addView(tv(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date()), 24, TEXT, true));
        boolean bad = Store.offline || Store.authError;
        right.addView(chip(bad ? "● OFFLINE" : "● ONLINE", bad ? RED_DIM : 0x2622C55E, bad ? RED : GREEN));
        head.addView(right);
        root.addView(head);

        // ---- tabs
        LinearLayout tabs = hbox();
        tabs.setPadding(dp(12), 0, dp(12), dp(8));
        String[][] T = {
                {"orders", "Orders", String.valueOf(act.size())},
                {"sched", "Scheduled", String.valueOf(sch.size())},
                {"history", "History", "0"},
                {"settings", "⚙ Settings", "0"}};
        for (final String[] t : T) {
            boolean on = tab.equals(t[0]);
            String label = t[1] + (!t[2].equals("0") ? "  " + t[2] : "");
            TextView tb = tv(label, 14, on ? Color.WHITE : DIM, true);
            tb.setSingleLine(true);
            tb.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        if (tab.equals("history") && !query.isEmpty()) {
            String q = query.toLowerCase(Locale.getDefault());
            List<JSONObject> f = new ArrayList<>();
            for (JSONObject o : his) {
                String hay = (o.optInt("num") + " " + o.optString("name") + " " + o.optString("phone") + " " + o.optString("table")).toLowerCase(Locale.getDefault());
                if (hay.contains(q)) f.add(o);
            }
            his = f;
        }
        List<JSONObject> list = tab.equals("orders") ? act : tab.equals("sched") ? sch : his;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int cols = Math.max(1, Math.min(3, widthDp / 360));

        LinearLayout box = vbox();
        box.setPadding(dp(8), dp(4), dp(8), dp(24));
        if (tab.equals("orders") && act.size() >= 2) box.addView(prepList(act));
        if (tab.equals("history")) box.addView(historyHeader());
        if (tab.equals("settings")) {
            box.addView(settingsView());
        } else if (list.isEmpty()) {
            LinearLayout empty = vbox();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(90), 0, 0);
            TextView icon = tv(tab.equals("orders") ? "🍽" : tab.equals("sched") ? "🕒" : "📋", 54, TEXT, false);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView msg = tv(tab.equals("orders") ? "Waiting for orders…" : tab.equals("sched") ? "No scheduled orders" : "No history", 18, DIM, false);
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
        renderedOnce = true;
    }

    // ------------------------------------------------------------ order card

    private View card(final JSONObject o, boolean scheduled) {
        final int id = o.optInt("id");
        final boolean done = o.optBoolean("done");
        final boolean dine = "dine".equals(o.optString("mode"));
        final boolean ringing = Store.isRinging(id);

        final LinearLayout outer = vbox();
        GradientDrawable bg = shape(SURFACE, 18);
        bg.setStroke(dp(ringing ? 3 : 1), ringing ? ORANGE : LINE);
        outer.setBackground(bg);
        outer.setClipToOutline(true);
        final int ageCol = done ? LINE : ringing ? ORANGE : ageColor(o);
        View bar = new View(this);
        bar.setBackgroundColor(ageCol);
        outer.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
        if (ringing) {
            AlphaAnimation pa = new AlphaAnimation(1f, 0.25f);
            pa.setDuration(500);
            pa.setRepeatMode(Animation.REVERSE);
            pa.setRepeatCount(Animation.INFINITE);
            bar.startAnimation(pa);
        }
        LinearLayout c = vbox();
        c.setPadding(dp(16), dp(12), dp(16), dp(16));
        outer.addView(c);
        if (!done) outer.setOnClickListener(v -> showPrepDialog(id));      // tap any open order: set its preparation time
        float targetAlpha = done ? 0.65f : 1f;
        outer.setAlpha(targetAlpha);
        if (shown.add(id) && animNew) {
            outer.setAlpha(0f);
            outer.setTranslationY(dp(28));
            outer.animate().alpha(targetAlpha).translationY(0).setDuration(380).start();
        }

        // number + time
        LinearLayout head = hbox();
        head.addView(tv("#" + o.optInt("num"), 32, ORANGE, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        head.addView(button("🖨", SURFACE2, 18, v -> printOrder(o)), lp(dp(50), dp(42), 0f, 0, 0, 12, 0));
        LinearLayout when = vbox();
        TextView t1 = tv(Store.hhmm(o), 18, TEXT, true);
        t1.setGravity(Gravity.END);
        View t2 = done ? tv(Store.ago(o), 12, DIM, false)
                : chip("⏱ " + Math.max(0, (Store.serverNow() - Store.ts(o)) / 60000) + " min", ageDim(ageCol), ageCol);
        when.setGravity(Gravity.END);
        when.addView(t1);
        when.addView(t2);
        head.addView(when);
        c.addView(head);

        // type chips
        LinearLayout chips = hbox();
        chips.addView(dine ? chip("🍽  DINE IN" + (o.optString("table").isEmpty() ? "" : " · TABLE " + o.optString("table")), BLUE_DIM, BLUE)
                : chip("🛍  PICKUP", PURPLE_DIM, PURPLE));
        String src = o.optString("src", "");
        if (!src.isEmpty() && !src.equals("direct")) chips.addView(chip(src, SURFACE2, DIM), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 8, 0, 0, 0));
        c.addView(chips, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));

        if (scheduled) {
            c.addView(tv("🕒  Pickup in " + Store.inMin(Store.etaMs(o)) + " (" + o.optString("eta") + ")", 15, ORANGE, true),
                    lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
        }
        if (!dine) {
            String who = o.optString("name") + (o.optString("phone").isEmpty() ? "" : "  ·  " + o.optString("phone"));
            LinearLayout wr = hbox();
            wr.addView(tv(who, 16, TEXT, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            if (!o.optString("phone").isEmpty())
                wr.addView(button("📞 Call", SURFACE2, 14, v -> dial(o.optString("phone"))), lp(dp(92), dp(40), 0f, 8, 0, 0, 0));
            c.addView(wr, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
            if (!o.optString("eta").isEmpty() && !scheduled)
                c.addView(tv("Pickup time: " + o.optString("eta"), 14, AMBER, true));
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
        if (o.optDouble("deposit", 0) > 0) fee.append("Deposit +").append(Store.money(o.optDouble("deposit"))).append(" PLN\n");
        if (o.optDouble("pack", 0) > 0) fee.append("Packaging +").append(Store.money(o.optDouble("pack"))).append(" PLN\n");
        if (o.optDouble("combo", 0) > 0) fee.append("Combo −").append(Store.money(o.optDouble("combo"))).append(" PLN\n");
        if (o.optDouble("coupon", 0) > 0) fee.append("Code ").append(o.optString("code")).append(" −").append(Store.money(o.optDouble("coupon"))).append(" PLN\n");
        TextView f = tv(fee.toString().trim(), 12, DIM, false);
        foot.addView(f, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        foot.addView(tv(Store.money(o.optDouble("total")) + " PLN", 24, TEXT, true));
        c.addView(foot);

        // ---- preparation time: live countdown + "Set time"
        if (!done && (!ringing || o.optLong("ready_at") > 0)) {
            LinearLayout tr = hbox();
            TextView cd = tv("", 20, TEXT, true);
            tr.addView(cd, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            tr.addView(button("⏱ Set time", SURFACE2, 14, v -> showPrepDialog(id)), lp(dp(124), dp(42), 0f, 8, 0, 0, 0));
            c.addView(tr, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 12, 0, 0));
            cds.add(new Cd(cd, id));
            paintCountdown(cd, id);
        }

        // ---- actions
        if (done) {
            c.addView(button("↩  Restore", SURFACE2, 16, v -> Store.setDone(id, false, null)),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0f, 0, 12, 0, 0));
        } else if (ringing) {
            c.addView(button("✓  ACCEPT ORDER", ORANGE, 19, v -> Store.accept(id)),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), 0f, 0, 12, 0, 0));
            c.addView(tv("ACCEPT & PROMISE READY IN", 11, DIM, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 2, 10, 0, 0));
            c.addView(presetRow(id, new int[]{10, 15, 20, 30}, ORANGE_DIM));
        } else {
            c.addView(tv("SET TIME FROM NOW", 11, DIM, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 2, 10, 0, 0));
            c.addView(presetRow(id, new int[]{5, 10, 15, 20, 30}, SURFACE2));
            c.addView(button("✓  READY", GREEN, 18, v -> Store.setDone(id, true, r -> {
                if (r == null) Toast.makeText(this, "Network error", Toast.LENGTH_SHORT).show();
            })), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), 0f, 0, 12, 0, 0));
        }
        return outer;
    }

    // ------------------------------------------------------------ extras: stats, prep list, search, helpers

    private int scale(int color, float f) {
        return Color.rgb(Math.min(255, (int) (Color.red(color) * f)), Math.min(255, (int) (Color.green(color) * f)), Math.min(255, (int) (Color.blue(color) * f)));
    }

    private Drawable gradient(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{scale(color, 1.15f), scale(color, 0.82f)});
        g.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), g, shape(Color.WHITE, radiusDp));
    }

    /** green < 10 min, amber < 20 min, red after that */
    private int ageColor(JSONObject o) {
        long m = (Store.serverNow() - Store.ts(o)) / 60000;
        return m < 10 ? GREEN : m < 20 ? AMBER : RED;
    }

    private int ageDim(int c) { return (c & 0x00FFFFFF) | 0x33000000; }

    private View tile(String label, String value) {
        LinearLayout t = vbox();
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setBackground(shape(SURFACE, 12));
        t.addView(tv(label, 10, DIM, true));
        t.addView(tv(value, 17, TEXT, true));
        return t;
    }

    private void applyKeepScreen() {
        if (Printer.on(this, "keep_screen")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void dial(String phone) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replaceAll("[^0-9+]", ""))));
        } catch (Exception e) {
            Toast.makeText(this, "No phone app on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickSound() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), RC_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void testAlarm() {
        try {
            final android.media.MediaPlayer mp = Alarm.create(this);
            mp.start();
            ui.postDelayed(() -> { try { mp.stop(); mp.release(); } catch (Exception e) { /* ignore */ } }, 3600);
            Toast.makeText(this, "Playing the alarm for a few seconds…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Can't play the alarm sound", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ preparation time

    private void paintCountdown(TextView t, int id) {
        JSONObject o = Store.find(id);
        if (o == null) return;
        long ra = o.optLong("ready_at");
        if (ra <= 0) { t.setText("⏱ No time set"); t.setTextColor(DIM); return; }
        long ms = ra * 1000 - Store.serverNow();
        boolean late = ms < 0;
        long s = Math.abs(ms) / 1000;
        String mmss = String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        t.setText(late ? "⏱ LATE " + mmss : "⏱ " + mmss + " left");
        t.setTextColor(late ? RED : ms < 120000 ? AMBER : TEXT);
    }

    private LinearLayout presetRow(final int id, int[] mins, int color) {
        LinearLayout pr = hbox();
        for (final int m : mins) pr.addView(button(m + "′", color, 15, v -> applyPrep(id, m)), lp(0, dp(46), 1f, 2, 4, 2, 0));
        return pr;
    }

    private void applyPrep(int id, final int minutes) {
        Store.setPrep(id, minutes, r -> Toast.makeText(this,
                r != null ? "Ready in " + minutes + " min — the customer sees it now" : "Network error", Toast.LENGTH_SHORT).show());
    }

    /** Tap an order: ready-made buttons, your own number of minutes, +/- 5, and the live countdown. */
    private void showPrepDialog(final int id) {
        final JSONObject o = Store.find(id);
        if (o == null) return;
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar_MinWidth);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackground(shape(SURFACE, 22));

        box.addView(tv("Order #" + o.optInt("num"), 22, TEXT, true));
        box.addView(tv("Set the preparation time. The customer sees it on the website.", 13, DIM, false));

        TextView cd = tv("", 38, TEXT, true);
        cd.setGravity(Gravity.CENTER);
        cd.setPadding(0, dp(14), 0, dp(10));
        box.addView(cd);
        dlgCountdown = cd;
        dlgId = id;
        paintCountdown(cd, id);

        box.addView(tv("MINUTES FROM NOW", 11, ORANGE, true));
        int[] all = {5, 10, 15, 20, 25, 30, 45, 60};
        for (int i = 0; i < all.length; i += 4) {
            LinearLayout row = hbox();
            for (int k = i; k < i + 4; k++) {
                final int m = all[k];
                row.addView(button(String.valueOf(m), SURFACE2, 18, v -> applyPrep(id, m)), lp(0, dp(52), 1f, 3, 4, 3, 0));
            }
            box.addView(row);
        }

        box.addView(tv("OR TYPE YOUR OWN", 11, ORANGE, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 14, 0, 4));
        LinearLayout cr = hbox();
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setHint("minutes (1–180)");
        in.setTextColor(TEXT);
        in.setHintTextColor(DIM);
        in.setSingleLine(true);
        cr.addView(in, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        cr.addView(button("SET", ORANGE, 16, v -> {
            int m = -1;
            try { m = Integer.parseInt(in.getText().toString().trim()); } catch (Exception e) { /* invalid */ }
            if (m < 1 || m > 180) { Toast.makeText(this, "Enter 1 to 180 minutes", Toast.LENGTH_SHORT).show(); return; }
            applyPrep(id, m);
            in.setText("");
        }), lp(dp(100), dp(50), 0f, 0, 0, 0, 0));
        box.addView(cr);

        // +/- 5 min on top of what was promised
        LinearLayout adj = hbox();
        for (final int delta : new int[]{-5, 5}) {
            adj.addView(button(delta < 0 ? "−5 min" : "+5 min", SURFACE2, 16, v -> {
                long ra = o.optLong("ready_at");
                long left = ra > 0 ? (long) Math.ceil((ra * 1000 - Store.serverNow()) / 60000.0) : 0;
                int m = (int) Math.max(1, Math.min(180, Math.max(0, left) + delta));
                applyPrep(id, m);
            }), lp(0, dp(48), 1f, 3, 0, 3, 0));
        }
        box.addView(adj, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 12, 0, 0));

        box.addView(button("Close", SURFACE2, 16, v -> d.dismiss()), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0f, 0, 14, 0, 0));

        d.setContentView(box);
        d.setOnDismissListener(x -> { dlgCountdown = null; });
        d.show();
        if (d.getWindow() != null) d.getWindow().setLayout((int) Math.min(dp(440), getResources().getDisplayMetrics().widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Totals of every item in all open orders: what the kitchen has to prepare right now. */
    private View prepList(List<JSONObject> open) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (JSONObject o : open) {
            JSONArray lines = o.optJSONArray("lines");
            if (lines == null) continue;
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l == null) continue;
                String n = l.optString("name");
                Integer cur = m.get(n);
                m.put(n, (cur == null ? 0 : cur) + l.optInt("qty"));
            }
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        Collections.sort(es, (a, b) -> b.getValue() - a.getValue());

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Map.Entry<String, Integer> e : es) {
            if (sb.length() > 0) sb.append("     ");
            int st = sb.length();
            sb.append(e.getValue() + "×");
            sb.setSpan(new ForegroundColorSpan(ORANGE), st, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" " + e.getKey());
        }
        LinearLayout p = panel();
        p.addView(tv("PREP LIST · ALL OPEN ORDERS", 11, DIM, true));
        TextView body = tv("", 17, TEXT, true);
        body.setText(sb);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(6), 0, 0);
        p.addView(body);
        LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 6, 6, 6, 4);
        p.setLayoutParams(lp);
        return p;
    }

    private View historyHeader() {
        LinearLayout h = vbox();
        h.setPadding(dp(6), dp(6), dp(6), dp(2));
        h.addView(statsPanel());

        LinearLayout bar = hbox();
        bar.addView(button("🔍  Search", SURFACE2, 15, v -> searchDialog()), lp(dp(140), dp(44), 0f, 0, 0, 8, 0));
        if (!query.isEmpty()) {
            TextView chip = chip("“" + query + "”   ✕", ORANGE_DIM, ORANGE);
            chip.setTextSize(14);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setClickable(true);
            chip.setOnClickListener(v -> { query = ""; render(); });
            bar.addView(chip);
        }
        h.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 10, 0, 4));
        return h;
    }

    private void searchDialog() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint("Order #, name, phone or table");
        in.setText(query);
        in.setSelection(in.getText().length());
        new AlertDialog.Builder(this).setTitle("Search history").setView(in)
                .setPositiveButton("Search", (d, w) -> { query = in.getText().toString().trim(); render(); })
                .setNeutralButton("Clear", (d, w) -> { query = ""; render(); })
                .setNegativeButton("Cancel", null).show();
    }

    /** today: orders, revenue, average ticket, best sellers and orders per hour */
    private View statsPanel() {
        Calendar c0 = Calendar.getInstance();
        c0.set(Calendar.HOUR_OF_DAY, 0); c0.set(Calendar.MINUTE, 0); c0.set(Calendar.SECOND, 0); c0.set(Calendar.MILLISECOND, 0);
        int n = 0;
        double sum = 0;
        int[] perHour = new int[24];
        Map<String, Integer> items = new LinkedHashMap<>();
        Calendar tmp = Calendar.getInstance();
        for (JSONObject o : Store.orders) {
            if (Store.ts(o) < c0.getTimeInMillis()) continue;
            n++;
            sum += o.optDouble("total");
            tmp.setTimeInMillis(Store.ts(o));
            perHour[tmp.get(Calendar.HOUR_OF_DAY)]++;
            JSONArray lines = o.optJSONArray("lines");
            if (lines != null) for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l == null) continue;
                Integer cur = items.get(l.optString("name"));
                items.put(l.optString("name"), (cur == null ? 0 : cur) + l.optInt("qty"));
            }
        }

        LinearLayout p = panel();
        p.addView(tv("TODAY", 11, ORANGE, true));
        LinearLayout tiles = hbox();
        tiles.addView(tile("ORDERS", String.valueOf(n)), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 6, 4, 0));
        tiles.addView(tile("REVENUE", Store.money(sum) + " PLN"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f, 4, 6, 4, 0));
        tiles.addView(tile("AVG ORDER", n == 0 ? "—" : Store.money(sum / n) + " PLN"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f, 4, 6, 0, 0));
        p.addView(tiles);

        if (n > 0) {
            // busiest hours
            int lo = 23, hi = 0, max = 1;
            for (int h = 0; h < 24; h++) if (perHour[h] > 0) { lo = Math.min(lo, h); hi = Math.max(hi, h); max = Math.max(max, perHour[h]); }
            p.addView(tv("ORDERS PER HOUR", 11, DIM, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 14, 0, 4));
            LinearLayout bars = new LinearLayout(this);
            bars.setOrientation(LinearLayout.HORIZONTAL);
            bars.setGravity(Gravity.BOTTOM);
            for (int h = lo; h <= hi; h++) {
                LinearLayout col = vbox();
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(tv(perHour[h] > 0 ? String.valueOf(perHour[h]) : "", 11, TEXT, true));
                View b = new View(this);
                b.setBackground(shape(perHour[h] == max ? ORANGE : 0x99FF6A1A, 4));
                col.addView(b, new LinearLayout.LayoutParams(dp(18), Math.max(dp(3), dp(56) * perHour[h] / max)));
                col.addView(tv(String.valueOf(h), 10, DIM, false));
                bars.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 1, 0, 1, 0));
            }
            p.addView(bars);

            // best sellers
            List<Map.Entry<String, Integer>> es = new ArrayList<>(items.entrySet());
            Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
            p.addView(tv("BEST SELLERS", 11, DIM, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 14, 0, 4));
            for (int i = 0; i < Math.min(5, es.size()); i++) {
                LinearLayout r = hbox();
                r.addView(tv((i + 1) + ".", 14, DIM, true), lp(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 0, 0));
                r.addView(tv(es.get(i).getKey(), 15, TEXT, false), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
                r.addView(tv(es.get(i).getValue() + "×", 15, ORANGE, true));
                p.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 2, 0, 2));
            }
        }
        return p;
    }

    // ------------------------------------------------------------ printing UI

    private void openSettings() {
        tab = "settings";
        prefs.edit().putString("tab", tab).apply();
        sv = null;
        render();
    }

    private void printOrder(JSONObject o) {
        if (!Printer.configured(this)) {
            Toast.makeText(this, "Choose a printer in Settings first", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        Printer.printManual(this, o, r -> Toast.makeText(this,
                r != null ? "Sent to printer ✓" : "Printer error: " + Store.printError, Toast.LENGTH_LONG).show());
    }

    private String[] btPerms() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"}
                : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private boolean btGranted() {
        for (String p : btPerms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void scanClick() {
        if (!btGranted()) { requestPermissions(btPerms(), RC_BT); return; }
        startScan(true);
    }

    private void startScan(boolean bt) {
        found.clear();
        scanning = true;
        scanned = true;
        render();
        PrinterScan.start(this, bt, new PrinterScan.Cb() {
            @Override public void found(PrinterScan.Found f) {
                for (PrinterScan.Found x : found) if (x.type.equals(f.type) && x.addr.equals(f.addr)) return;
                found.add(f);
                render();
            }
            @Override public void done() {
                scanning = false;
                render();
            }
        });
    }

    private void choose(PrinterScan.Found f) {
        boolean first = !Printer.configured(this);
        Printer.select(this, f.type, f.addr, f.name);
        if (first) Store.markAllPrinted();      // don't print the old backlog
        Store.setPrintError(null);
        try {
            if (f.type.equals("usb")) {
                UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (Printer.usbKey(d).equals(f.addr) && !um.hasPermission(d)) {
                        um.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                                new Intent("pl.grillhub.kitchen.USB").setPackage(getPackageName()),
                                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                    }
                }
            } else if (f.type.equals("bt")) {
                BluetoothDevice d = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(f.addr);
                if (d.getBondState() == BluetoothDevice.BOND_NONE) d.createBond();   // shows the pairing prompt
            }
        } catch (Exception e) { /* ignore */ }
        Toast.makeText(this, "Selected: " + f.name, Toast.LENGTH_SHORT).show();
        render();
    }

    private TextView sectionTitle(String s) {
        TextView t = tv(s.toUpperCase(), 12, ORANGE, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(6), dp(14), 0, dp(6));
        return t;
    }

    private LinearLayout panel() {
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = shape(SURFACE, 16);
        g.setStroke(dp(1), LINE);
        p.setBackground(g);
        return p;
    }

    private LinearLayout settingRow(String label) {
        LinearLayout r = hbox();
        r.setMinimumHeight(dp(48));
        r.addView(tv(label, 16, TEXT, false), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        return r;
    }

    private void toggleRow(LinearLayout parent, String label, final String key) {
        final boolean on = Printer.on(this, key);
        LinearLayout r = settingRow(label);
        TextView pill = tv(on ? "ON" : "OFF", 13, on ? Color.WHITE : DIM, true);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(shape(on ? GREEN : SURFACE2, 20));
        pill.setPadding(dp(16), dp(7), dp(16), dp(7));
        r.addView(pill);
        r.setClickable(true);
        r.setOnClickListener(v -> { Printer.setOn(this, key, !on); render(); });
        parent.addView(r);
    }

    private void textRow(LinearLayout parent, String label, final String key, final String title) {
        LinearLayout r = settingRow(label);
        String v = Printer.get(this, key);
        TextView val = tv(v.isEmpty() ? "—" : v, 15, DIM, false);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setMaxWidth(dp(220));
        r.addView(val);
        r.addView(tv("  ›", 18, DIM, true));
        r.setClickable(true);
        r.setOnClickListener(x -> {
            final EditText in = new EditText(this);
            in.setInputType(InputType.TYPE_CLASS_TEXT);
            in.setText(Printer.get(this, key));
            in.setSelection(in.getText().length());
            new AlertDialog.Builder(this).setTitle(title).setView(in)
                    .setPositiveButton("Save", (d, w) -> { Printer.set(this, key, in.getText().toString().trim()); render(); })
                    .setNegativeButton("Cancel", null).show();
        });
        parent.addView(r);
    }

    private View settingsView() {
        LinearLayout box = vbox();
        box.setPadding(dp(6), 0, dp(6), 0);

        // ---- printer
        box.addView(sectionTitle("Printer"));
        LinearLayout p = panel();
        boolean has = Printer.configured(this);
        TextView cur = tv(has ? "✓  " + Printer.get(this, "name") : "No printer selected", 18, has ? GREEN : AMBER, true);
        p.addView(cur);
        if (has) p.addView(tv(Printer.typeLabel(Printer.get(this, "type")) + " · " + Printer.get(this, "addr"), 13, DIM, false));

        LinearLayout btns = hbox();
        btns.addView(button(scanning ? "⏳  Searching…" : "🔍  Find printers", ORANGE, 16, v -> { if (!scanning) scanClick(); }),
                lp(0, dp(52), 1f, 0, 0, 4, 0));
        btns.addView(button("🖨  Test print", SURFACE2, 16, v -> {
            if (!Printer.configured(this)) { Toast.makeText(this, "Choose a printer first", Toast.LENGTH_SHORT).show(); return; }
            Printer.print(this, Printer.testOrder(), r -> Toast.makeText(this,
                    r != null ? "Sent to printer ✓" : "Printer error: " + Store.printError, Toast.LENGTH_LONG).show());
        }), lp(0, dp(52), 1f, 4, 0, 0, 0));
        p.addView(btns, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 12, 0, 0));

        if (scanning) p.addView(tv("Searching: Bluetooth · Wi-Fi/LAN · USB…", 13, DIM, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 10, 0, 0));
        if (scanned && !scanning && found.isEmpty())
            p.addView(tv("No printers found. Make sure the printer is on and the tablet's Bluetooth and Wi-Fi are active.", 14, AMBER, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 10, 0, 0));

        for (final PrinterScan.Found f : found) {
            boolean sel = has && Printer.get(this, "type").equals(f.type) && Printer.get(this, "addr").equals(f.addr);
            LinearLayout row = hbox();
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(ripple(sel ? 0x3322C55E : SURFACE2, 12));
            row.setClickable(true);
            row.setOnClickListener(v -> choose(f));
            row.addView(tv(f.type.equals("bt") ? "🔷" : f.type.equals("net") ? "📶" : "🔌", 22, TEXT, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 12, 0));
            LinearLayout col = vbox();
            col.addView(tv(f.name, 16, TEXT, true));
            col.addView(tv(Printer.typeLabel(f.type) + " · " + f.extra, 12, DIM, false));
            row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            row.addView(tv(sel ? "✓" : "Select", sel ? 20 : 14, sel ? GREEN : ORANGE, true));
            p.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 8, 0, 0));
        }
        box.addView(p);

        // ---- receipt
        box.addView(sectionTitle("Receipt"));
        LinearLayout q = panel();
        toggleRow(q, "Print new orders automatically", "auto");

        LinearLayout paper = settingRow("Paper width");
        for (final String w : new String[]{"80", "58"}) {
            boolean on = Printer.get(this, "paper").equals(w);
            TextView t = tv(w + " mm", 14, on ? Color.WHITE : DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(shape(on ? ORANGE : SURFACE2, 20));
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            t.setClickable(true);
            t.setOnClickListener(v -> { Printer.set(this, "paper", w); render(); });
            paper.addView(t, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 4, 0, 0, 0));
        }
        q.addView(paper);

        LinearLayout copies = settingRow("Copies");
        int n = 1;
        try { n = Integer.parseInt(Printer.get(this, "copies")); } catch (Exception e) { /* default */ }
        final int cn = n;
        TextView minus = button("−", SURFACE2, 20, v -> { Printer.set(this, "copies", String.valueOf(Math.max(1, cn - 1))); render(); });
        TextView plus = button("+", SURFACE2, 20, v -> { Printer.set(this, "copies", String.valueOf(Math.min(5, cn + 1))); render(); });
        TextView num = tv(String.valueOf(n), 18, TEXT, true);
        num.setGravity(Gravity.CENTER);
        copies.addView(minus, lp(dp(44), dp(40), 0f, 0, 0, 0, 0));
        copies.addView(num, lp(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 0, 0));
        copies.addView(plus, lp(dp(44), dp(40), 0f, 0, 0, 0, 0));
        q.addView(copies);

        toggleRow(q, "Cut paper after printing", "cut");
        toggleRow(q, "Show prices and total", "show_prices");
        toggleRow(q, "Show customer phone", "show_phone");
        toggleRow(q, "Large order number", "big_number");
        textRow(q, "Header", "header", "Receipt header");
        textRow(q, "Footer", "footer", "Receipt footer");
        box.addView(q);

        box.addView(sectionTitle("App"));
        LinearLayout ap = panel();
        toggleRow(ap, "Keep screen on", "keep_screen");
        ap.addView(tv("ALARM SOUND", 11, DIM, true), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 4, 8, 0, 4));
        final String curMode = Alarm.mode(this);
        String[][] opts = {{"chime", "🔔  Chime (rising ride-request style)"}, {"loud", "📢  Loud beeps (noisy kitchen)"},
                {"system", "⏰  Tablet's alarm sound"}, {"custom", "🎵  My own sound file"}};
        for (final String[] op : opts) {
            boolean on = op[0].equals(curMode);
            LinearLayout row = hbox();
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            row.setBackground(ripple(on ? 0x33FF6A1A : SURFACE2, 12));
            row.setClickable(true);
            row.addView(tv(op[1], 15, TEXT, on), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
            if (on) row.addView(tv("✓", 18, ORANGE, true));
            row.setOnClickListener(v -> {
                if (op[0].equals("custom") && Printer.get(this, "alarm_uri").isEmpty()) { pickSound(); return; }
                Printer.set(this, "alarm", op[0]);
                render();
            });
            ap.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 3, 0, 3));
        }
        if (curMode.equals("custom")) {
            ap.addView(button("Choose another file…  (" + Printer.get(this, "alarm_name") + ")", SURFACE2, 14, v -> pickSound()),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), 0f, 0, 6, 0, 0));
        }
        ap.addView(button("🔔  Test alarm sound", SURFACE2, 16, v -> testAlarm()), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), 0f, 0, 8, 0, 0));
        box.addView(ap);

        TextView hint = tv("A Bluetooth printer must be on (the first time, you will be asked to pair it). A network printer needs port 9100 and must be on the same Wi-Fi as the tablet.", 12, DIM, false);
        hint.setPadding(dp(6), dp(12), dp(6), 0);
        box.addView(hint);
        return box;
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        parent.addView(d, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1, 0f, 0, 10, 0, 6));
    }
}
