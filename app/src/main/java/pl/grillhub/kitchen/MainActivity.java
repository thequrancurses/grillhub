package pl.grillhub.kitchen;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

public class MainActivity extends Activity {

    static final String BASE = "https://grillhub.pl/wp-json/ghm/v1/";

    static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, FG = 0xFFFFFFFF, DIM = 0xFFAAAAAA,
            ORANGE = 0xFFFF6A00, GREEN = 0xFF2E9E4F, RED = 0xFFD32F2F, GRAY = 0xFF3A3A3A;

    static class AuthException extends Exception {}

    interface Cb { void ok(String body); }

    private SharedPreferences sp;
    private String pin = "";
    private String tab = "orders";

    private final List<JSONObject> orders = new ArrayList<>();
    private final Set<Integer> ring = new HashSet<>();
    private int lastId = 0, pollN = 0, fails = 0;
    private boolean offline = false, busy = false, running = false;
    private long srvOff = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private ToneGenerator tg;

    private LinearLayout root;
    private ScrollView sv;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("kitchen", MODE_PRIVATE);
        pin = sp.getString("pin", "");
        tab = sp.getString("tab", "orders");

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        if (pin.isEmpty()) showLogin(""); else startPolling();
    }

    @Override
    protected void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        if (tg != null) tg.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ login

    private void showLogin(String err) {
        running = false;
        ui.removeCallbacksAndMessages(null);
        root.removeAllViews();
        root.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(32), 0, dp(32), 0);

        TextView title = tv("Grill Hub · Kuchnia", 24, FG, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        final EditText in = new EditText(this);
        in.setHint("PIN");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setSingleLine(true);
        in.setImeOptions(EditorInfo.IME_ACTION_DONE);
        in.setTextColor(FG);
        in.setHintTextColor(DIM);
        box.addView(in);

        final Button go = new Button(this);
        go.setText("Wejdź");
        go.setAllCaps(false);
        go.setOnClickListener(v -> {
            String p = in.getText().toString().trim();
            if (p.isEmpty()) return;
            pin = p;
            sp.edit().putString("pin", p).apply();
            startPolling();
        });
        in.setOnEditorActionListener((v, actionId, event) -> {
            go.performClick();
            return true;
        });
        box.addView(go);

        TextView e = tv(err, 14, 0xFFFF6B6B, false);
        e.setGravity(Gravity.CENTER);
        box.addView(e);

        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void logout(String msg) {
        pin = "";
        sp.edit().putString("pin", "").apply();
        orders.clear();
        ring.clear();
        showLogin(msg);
    }

    // ------------------------------------------------------------ polling

    private void startPolling() {
        orders.clear();
        ring.clear();
        lastId = 0; pollN = 0; fails = 0; offline = false; busy = false;
        running = true;
        ui.removeCallbacksAndMessages(null);
        render();
        poll(true);
        ui.postDelayed(tick, 4000);
        ui.postDelayed(refresh, 30000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            poll(false);
            ui.postDelayed(this, 4000);
        }
    };

    // re-draw every 30s so the "promised time" counters stay current
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            render();
            ui.postDelayed(this, 30000);
        }
    };

    private final Runnable ringTask = new Runnable() {
        @Override public void run() {
            for (JSONObject o : orders) if (o.optBoolean("done")) ring.remove(o.optInt("id"));
            if (ring.isEmpty()) { render(); return; }
            beep();
            ui.postDelayed(this, 6000);
        }
    };

    private void poll(final boolean initial) {
        if (busy) return;
        busy = true;
        final boolean full = initial || (++pollN % 5 == 0);
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
            ui.post(() -> { busy = false; onPoll(r, er, initial); });
        });
    }

    private void onPoll(JSONObject r, String err, boolean initial) {
        if (!running) return;
        if ("auth".equals(err)) { logout("Zły PIN."); return; }
        if (err != null) {
            fails++;
            if (fails >= 3 && !offline) { offline = true; render(); beep(); }
            return;
        }
        fails = 0;
        boolean wasOff = offline;
        offline = false;
        long now = r.optLong("now", 0);
        if (now > 0) srvOff = now * 1000 - System.currentTimeMillis();
        boolean changed = merge(r.optJSONArray("orders"), initial);
        if (initial || changed || wasOff) render();
    }

    private JSONObject find(int id) {
        for (JSONObject o : orders) if (o.optInt("id") == id) return o;
        return null;
    }

    private boolean merge(JSONArray arr, boolean initial) {
        boolean changed = false;
        if (arr == null) return false;
        boolean newOne = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int id = o.optInt("id");
            JSONObject ex = find(id);
            try {
                if (ex != null) {
                    if (ex.optBoolean("done") != o.optBoolean("done")) {
                        ex.put("done", o.optBoolean("done"));
                        ring.remove(id);
                        changed = true;
                    }
                    if (ex.optLong("ready_at") != o.optLong("ready_at")) {
                        ex.put("ready_at", o.optLong("ready_at"));
                        changed = true;
                    }
                    continue;
                }
            } catch (Exception e) { /* ignore */ }
            orders.add(o);
            changed = true;
            lastId = Math.max(lastId, id);
            if (!initial) { ring.add(id); newOne = true; }
        }
        Collections.sort(orders, (a, b) -> a.optInt("id") - b.optInt("id"));
        if (newOne) {
            beep();
            ui.removeCallbacks(ringTask);
            ui.postDelayed(ringTask, 6000);
        }
        return changed;
    }

    // ------------------------------------------------------------ network

    private String request(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("X-GHM-PIN", pin);
            c.setRequestProperty("Cache-Control", "no-store");
            c.setRequestProperty("User-Agent", "GrillHubKitchen/1.0 (Android)");
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

    private void act(final String path, final Cb cb) {
        net.execute(() -> {
            try {
                final String body = request(path, "POST");
                ui.post(() -> { cb.ok(body); render(); });
            } catch (AuthException e) {
                ui.post(() -> logout("Zły PIN."));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(MainActivity.this, "Błąd sieci", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ------------------------------------------------------------ sound

    private void beep() {
        try {
            if (tg == null) tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
        } catch (Exception e) { /* ignore */ }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) { /* ignore */ }
    }

    // ------------------------------------------------------------ order helpers

    private long ts(JSONObject o) { return o.optLong("ts") * 1000; }

    private long etaMs(JSONObject o) {
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

    private boolean isSched(JSONObject o) {
        long e = etaMs(o);
        return !o.optBoolean("done") && e > 0 && e - System.currentTimeMillis() > 30 * 60000L;
    }

    private String inMin(long ms) {
        long m = Math.round((ms - System.currentTimeMillis()) / 60000.0);
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    private String money(double n) {
        n = Math.round(n * 100) / 100.0;
        if (n == Math.floor(n)) return String.valueOf((long) n);
        return String.format(Locale.US, "%.2f", n);
    }

    private String dt(JSONObject o) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new java.util.Date(ts(o)));
    }

    // ------------------------------------------------------------ UI

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView banner(String text, int color, View.OnClickListener click) {
        TextView t = tv(text, 16, FG, true);
        t.setBackgroundColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        if (click != null) t.setOnClickListener(click);
        return t;
    }

    private Button btn(String text, int color, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(FG);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackgroundColor(color);
        b.setOnClickListener(click);
        return b;
    }

    private void render() {
        if (!running) return;
        final int y = sv != null ? sv.getScrollY() : 0;

        List<JSONObject> act = new ArrayList<>(), sch = new ArrayList<>(), his = new ArrayList<>();
        for (JSONObject o : orders) {
            if (o.optBoolean("done")) his.add(o);
            else if (isSched(o)) sch.add(o);
            else act.add(o);
        }
        Collections.reverse(act);
        Collections.reverse(his);
        Collections.sort(sch, (a, b) -> Long.compare(etaMs(a), etaMs(b)));

        root.removeAllViews();
        root.setGravity(Gravity.TOP | Gravity.START);

        if (offline) root.addView(banner("⚠ Brak połączenia z serwerem — nowe zamówienia mogą nie dochodzić!", RED, null));

        if (!ring.isEmpty()) {
            StringBuilder nums = new StringBuilder();
            for (JSONObject o : orders) if (ring.contains(o.optInt("id"))) {
                if (nums.length() > 0) nums.append(", ");
                nums.append("#").append(o.optInt("num"));
            }
            root.addView(banner("🔔 NOWE ZAMÓWIENIE " + nums + " — dotknij, aby wyciszyć", ORANGE, v -> {
                ring.clear();
                ui.removeCallbacks(ringTask);
                render();
            }));
        }

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = tv("Grill Hub · Kuchnia", 18, FG, true);
        title.setOnLongClickListener(v -> { logout(""); return true; }); // long-press title = log out
        bar.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        bar.addView(tv("●", 18, offline ? RED : GREEN, false));
        root.addView(bar);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[][] T = {
                {"orders", "Orders", String.valueOf(act.size())},
                {"sched", "Scheduled", String.valueOf(sch.size())},
                {"history", "History", "0"}};
        for (final String[] t : T) {
            boolean on = tab.equals(t[0]);
            String label = t[1] + (!t[2].equals("0") ? " (" + t[2] + ")" : "");
            TextView tb = tv(label, 15, on ? FG : DIM, on);
            tb.setGravity(Gravity.CENTER);
            tb.setPadding(0, dp(12), 0, dp(12));
            tb.setBackgroundColor(on ? ORANGE : GRAY);
            tb.setOnClickListener(v -> {
                tab = t[0];
                sp.edit().putString("tab", tab).apply();
                render();
            });
            tabs.addView(tb, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        }
        root.addView(tabs);

        // list
        List<JSONObject> list = tab.equals("orders") ? act : tab.equals("sched") ? sch : his;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (list.isEmpty()) {
            TextView e = tv(tab.equals("orders") ? "Czekamy na zamówienia…" : tab.equals("sched") ? "Brak zamówień zaplanowanych." : "Brak historii.", 16, DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(60), 0, 0);
            box.addView(e);
        } else {
            if (tab.equals("history")) {
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                int cnt = 0; double sum = 0;
                for (JSONObject o : orders) if (ts(o) >= c.getTimeInMillis()) { cnt++; sum += o.optDouble("total"); }
                box.addView(tv("Dzisiaj: " + cnt + " zamówień · " + money(sum) + " zł", 15, FG, true));
            }
            for (JSONObject o : list) box.addView(card(o, tab.equals("sched")), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, dp(8), 0, 0));
        }

        sv = new ScrollView(this);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.post(() -> sv.scrollTo(0, y));
    }

    private View card(final JSONObject o, boolean scheduled) {
        final int id = o.optInt("id");
        boolean done = o.optBoolean("done");
        boolean dine = "dine".equals(o.optString("mode"));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(10));
        if (ring.contains(id)) bg.setStroke(dp(3), ORANGE);
        c.setBackground(bg);
        if (done) c.setAlpha(0.6f);

        // number + source + time
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String src = o.optString("src", "");
        head.addView(tv("#" + o.optInt("num") + (!src.isEmpty() && !src.equals("direct") ? "  [" + src + "]" : ""), 24, ORANGE, true),
                lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 0, 0, 0, 0));
        head.addView(tv(dt(o), 13, DIM, false));
        c.addView(head);

        if (scheduled) c.addView(tv("Odbiór za " + inMin(etaMs(o)) + " (" + o.optString("eta") + ")", 15, ORANGE, true));

        c.addView(tv(dine ? "Na miejscu · Stolik: " + o.optString("table") : "Odbiór", 14, DIM, false));
        if (!dine) {
            String who = o.optString("name") + " · " + o.optString("phone") + (!o.optString("eta").isEmpty() ? " · " + o.optString("eta") : "");
            c.addView(tv(who, 16, FG, true));
        }

        // prep time
        long readyAt = o.optLong("ready_at");
        boolean hasEta = !dine && !o.optString("eta").isEmpty();
        if (!done && readyAt > 0 && !hasEta) {
            long m = (long) Math.ceil((readyAt * 1000 - (System.currentTimeMillis() + srvOff)) / 60000.0);
            c.addView(tv(m > 0 ? "Obiecane: za " + m + " min" : "Obiecany czas minął", 14, m > 0 ? FG : 0xFFFF6B6B, true),
                    lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, dp(6), 0, 0));
            LinearLayout pr = new LinearLayout(this);
            pr.setOrientation(LinearLayout.HORIZONTAL);
            for (final int mins : new int[]{5, 10, 15, 20, 30}) {
                pr.addView(btn(mins + "′", GRAY, v -> act("orders/" + id + "/prep?min=" + mins, body -> {
                    try { o.put("ready_at", new JSONObject(body).optLong("ready_at")); } catch (Exception e) { /* ignore */ }
                })), lp(0, dp(44), 1f, dp(2), dp(4), dp(2), 0));
            }
            c.addView(pr);
        }

        // lines
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(8), 0, dp(4));
        JSONArray lines = o.optJSONArray("lines");
        if (lines != null) for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) continue;
            items.addView(tv(l.optInt("qty") + "×  " + l.optString("name"), 18, FG, true));
            String d = l.optString("desc", "");
            if (!d.isEmpty()) {
                TextView dd = tv(d, 13, DIM, false);
                dd.setPadding(dp(28), 0, 0, dp(2));
                items.addView(dd);
            }
        }
        c.addView(items);

        String notes = o.optString("notes", "");
        if (!notes.isEmpty()) {
            TextView n = tv("UWAGI: " + notes, 15, 0xFFFFD54F, true);
            n.setPadding(0, dp(4), 0, dp(4));
            c.addView(n);
        }

        StringBuilder fee = new StringBuilder();
        if (o.optDouble("deposit", 0) > 0) fee.append("Kaucja +").append(money(o.optDouble("deposit"))).append(" zł  ");
        if (o.optDouble("pack", 0) > 0) fee.append("Opakowanie +").append(money(o.optDouble("pack"))).append(" zł  ");
        if (o.optDouble("combo", 0) > 0) fee.append("Zestaw −").append(money(o.optDouble("combo"))).append(" zł  ");
        if (o.optDouble("coupon", 0) > 0) fee.append("Kod ").append(o.optString("code")).append(" −").append(money(o.optDouble("coupon"))).append(" zł");
        if (fee.length() > 0) c.addView(tv(fee.toString().trim(), 13, DIM, false));

        TextView tot = tv(money(o.optDouble("total")) + " zł", 20, FG, true);
        tot.setGravity(Gravity.END);
        c.addView(tot);

        // buttons
        if (done) {
            c.addView(btn("↩ Przywróć", GRAY, v -> act("orders/" + id + "/done?undo=1", body -> {
                try { o.put("done", false); } catch (Exception e) { /* ignore */ }
            })), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0f, 0, dp(6), 0, 0));
        } else {
            c.addView(btn("✓ Gotowe", GREEN, v -> act("orders/" + id + "/done", body -> {
                try { o.put("done", true); } catch (Exception e) { /* ignore */ }
                ring.remove(id);
            })), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 0f, 0, dp(6), 0, 0));
        }
        return c;
    }
}
