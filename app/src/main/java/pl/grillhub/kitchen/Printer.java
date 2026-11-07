package pl.grillhub.kitchen;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ESC/POS receipt printing: builds the receipt (same layout as the web kitchen page)
 * and sends it over Bluetooth (SPP), the network (port 9100) or USB.
 */
final class Printer {

    static final String SPP = "00001101-0000-1000-8000-00805F9B34FB";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();
    private static final Set<Integer> inflight = new HashSet<>();
    private static final Map<Integer, Integer> tries = new HashMap<>();
    private static final Map<Integer, Long> lastTry = new HashMap<>();

    private Printer() {}

    // ------------------------------------------------------------ settings

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("printer", Context.MODE_PRIVATE);
    }

    static String get(Context c, String k) {
        String def = "";
        if (k.equals("paper")) def = "80";
        else if (k.equals("copies")) def = "1";
        else if (k.equals("header")) def = "Grill Hub";
        else if (k.equals("footer")) def = "Grill Hub · Katowice";
        return sp(c).getString(k, def);
    }

    static void set(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); tries.clear(); }

    /** boolean options, all on by default */
    static boolean on(Context c, String k) { return sp(c).getBoolean(k, true); }

    static void setOn(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    static boolean configured(Context c) { return !get(c, "type").isEmpty() && !get(c, "addr").isEmpty(); }

    static String typeLabel(String t) {
        return t.equals("bt") ? "Bluetooth" : t.equals("net") ? "Network (Wi-Fi/LAN)" : t.equals("usb") ? "USB" : "";
    }

    static void select(Context c, String type, String addr, String name) {
        sp(c).edit().putString("type", type).putString("addr", addr).putString("name", name).apply();
        tries.clear();
    }

    // ------------------------------------------------------------ receipt layout

    private static final class Blk {
        final String t; final char a; final boolean b; final int s; final boolean img;
        Blk(String t, char a, boolean b, int s) { this(t, a, b, s, false); }
        Blk(String t, char a, boolean b, int s, boolean img) { this.t = t; this.a = a; this.b = b; this.s = s; this.img = img; }
    }

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0x20 && ch <= 0x7E) || ch == '\n') { sb.append(ch); continue; }
            switch (ch) {
                case 'ą': sb.append('a'); break; case 'ć': sb.append('c'); break; case 'ę': sb.append('e'); break;
                case 'ł': sb.append('l'); break; case 'ń': sb.append('n'); break; case 'ó': sb.append('o'); break;
                case 'ś': sb.append('s'); break; case 'ź': case 'ż': sb.append('z'); break;
                case 'Ą': sb.append('A'); break; case 'Ć': sb.append('C'); break; case 'Ę': sb.append('E'); break;
                case 'Ł': sb.append('L'); break; case 'Ń': sb.append('N'); break; case 'Ó': sb.append('O'); break;
                case 'Ś': sb.append('S'); break; case 'Ź': case 'Ż': sb.append('Z'); break;
                case '×': sb.append('x'); break; case '—': case '–': case '·': sb.append('-'); break;
                case '↳': sb.append('>'); break; case '’': sb.append('\''); break;
                case '„': case '”': sb.append('"'); break;
                default: sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : ascii(s).split(" ", -1)) {
            while (word.length() > w) {
                if (!line.isEmpty()) { out.add(line); line = ""; }
                out.add(word.substring(0, w));
                word = word.substring(w);
            }
            if ((line.isEmpty() ? 0 : line.length() + 1) + word.length() > w) { out.add(line); line = word; }
            else line = line.isEmpty() ? word : line + " " + word;
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private static String two(String l, String r, int w) {
        l = ascii(l); r = ascii(r);
        int sp = w - l.length() - r.length();
        return sp >= 1 ? l + rep(' ', sp) + r : l + "\n" + rep(' ', Math.max(0, w - r.length())) + r;
    }

    private static List<Blk> blocks(Context c, JSONObject o, int cols) {
        List<Blk> B = new ArrayList<>();
        String dash = rep('-', cols);
        boolean prices = on(c, "show_prices"), big = on(c, "big_number");
        String header = get(c, "header"), footer = get(c, "footer");

        if (!header.isEmpty()) B.add(new Blk(header, 'c', true, 2));
        B.add(new Blk(new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new java.util.Date(o.optLong("ts") * 1000)), 'c', false, 1));
        B.add(new Blk(dash, 'l', false, 1));
        B.add(new Blk((big ? "ORDER #" : "Order #") + o.optInt("num"), 'c', true, big ? 2 : 1));
        if ("dine".equals(o.optString("mode"))) {
            B.add(new Blk("DINE IN" + (o.optString("table").isEmpty() ? "" : "  TABLE " + o.optString("table")), 'c', true, 2));
        } else if ("del".equals(o.optString("mode"))) {
            B.add(new Blk("DELIVERY", 'c', true, 2));
            if (!o.optString("name").isEmpty()) B.add(new Blk("Name: " + o.optString("name"), 'l', true, 1));
            if (!o.optString("phone").isEmpty()) B.add(new Blk("Phone: " + o.optString("phone"), 'l', false, 1));
            if (!o.optString("addr").isEmpty()) B.add(new Blk("Address: " + o.optString("addr") + (o.optString("addr2").isEmpty() ? "" : " (" + o.optString("addr2") + ")"), 'l', true, 1));
            if (o.optDouble("del_fee") > 0) B.add(new Blk("Delivery fee: " + Store.money(o.optDouble("del_fee")), 'l', false, 1));
            if (!o.optString("cour_name").isEmpty()) B.add(new Blk("Courier: " + o.optString("cour_name"), 'l', false, 1));
        } else {
            B.add(new Blk("PICKUP", 'c', true, 2));
            if (!o.optString("name").isEmpty()) B.add(new Blk("Name: " + o.optString("name"), 'l', true, 1));
            if (!o.optString("phone").isEmpty() && on(c, "show_phone")) B.add(new Blk("Phone: " + o.optString("phone"), 'l', false, 1));
            if (o.has("dist_km")) B.add(new Blk("Distance: " + Store.km(o.optDouble("dist_km")) + " (at order time)", 'l', false, 1));
            if (!o.optString("eta").isEmpty()) B.add(new Blk("Pickup time: " + o.optString("eta"), 'l', true, 1));
        }
        B.add(new Blk(dash, 'l', false, 1));

        JSONArray lines = o.optJSONArray("lines");
        if (lines != null) for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) continue;
            int qty = l.optInt("qty");
            String left = qty + " x " + l.optString("name");
            String right = prices ? Store.money(l.optDouble("price") * qty) : "";
            int room = cols - right.length() - 1;
            if (!right.isEmpty()) {
                B.add(new Blk(two(left.length() > room ? left.substring(0, room) : left, right, cols), 'l', true, 1));
                if (left.length() > room) for (String x : wrap("  " + left.substring(room), cols)) B.add(new Blk(x, 'l', false, 1));
            } else {
                for (String x : wrap(left, cols)) B.add(new Blk(x, 'l', true, 1));
            }
            if (!l.optString("desc").isEmpty()) for (String x : wrap("  > " + l.optString("desc"), cols)) B.add(new Blk(x, 'l', false, 1));
        }
        B.add(new Blk(dash, 'l', false, 1));

        if (prices) {
            if (o.optDouble("deposit", 0) > 0) B.add(new Blk(two("Drink deposit", "+" + Store.money(o.optDouble("deposit")), cols), 'l', false, 1));
            if (o.optDouble("pack", 0) > 0) B.add(new Blk(two("Takeaway packaging", "+" + Store.money(o.optDouble("pack")), cols), 'l', false, 1));
            if (o.optDouble("combo", 0) > 0) B.add(new Blk(two("Wrap + drink combo", "-" + Store.money(o.optDouble("combo")), cols), 'l', false, 1));
            if (o.optDouble("coupon", 0) > 0) B.add(new Blk(two("Code " + o.optString("code"), "-" + Store.money(o.optDouble("coupon")), cols), 'l', false, 1));
            B.add(new Blk(two("TOTAL:", Store.money(o.optDouble("total")) + " PLN", cols / 2), 'r', true, 2));
        }
        B.add(new Blk("", 'l', false, 1));
        B.add(new Blk(payLine(o), 'c', true, 1, true));        // Arabic + English, printed as a picture (the printer has no Arabic font)
        if (!o.optString("notes").isEmpty()) {
            B.add(new Blk("", 'l', false, 1));
            B.add(new Blk("NOTES:", 'l', true, 1));
            for (String x : wrap(o.optString("notes"), cols)) B.add(new Blk(x, 'l', false, 1));
        }
        if (!footer.isEmpty()) {
            B.add(new Blk("", 'l', false, 1));
            B.add(new Blk(footer, 'c', false, 1));
        }
        return B;
    }

    static byte[] escpos(Context c, JSONObject o) {
        int cols = get(c, "paper").equals("58") ? 32 : 48;
        int copies = 1;
        try { copies = Math.max(1, Math.min(5, Integer.parseInt(get(c, "copies")))); } catch (Exception e) { /* default */ }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int n = 0; n < copies; n++) {
            b.write(0x1B); b.write(0x40);                                   // init
            for (Blk x : blocks(c, o, cols)) {
                if (x.img) {
                    try { b.write(raster(x.t, cols == 32 ? 384 : 576)); continue; } catch (Exception e) { x = new Blk("Payment: CASH", 'c', true, 1); }
                }
                b.write(0x1B); b.write(0x61); b.write(x.a == 'c' ? 1 : x.a == 'r' ? 2 : 0);   // align
                b.write(0x1B); b.write(0x45); b.write(x.b ? 1 : 0);                           // bold
                b.write(0x1D); b.write(0x21); b.write(x.s == 2 ? 0x11 : 0);                   // size
                int w = x.s == 2 ? cols / 2 : cols;
                String t = ascii(x.t);
                if (x.s == 2 && t.length() > w && t.indexOf('\n') < 0) {
                    for (String ln : wrap(t, w)) { write(b, ln); b.write(0x0A); }
                    continue;
                }
                write(b, t);
                b.write(0x0A);
            }
            b.write(0x1B); b.write(0x45); b.write(0);
            b.write(0x1D); b.write(0x21); b.write(0);
            b.write(0x1B); b.write(0x61); b.write(0);
            for (int i = 0; i < 4; i++) b.write(0x0A);
            if (on(c, "cut")) { b.write(0x1D); b.write(0x56); b.write(0x42); b.write(0x00); }
        }
        return b.toByteArray();
    }

    static final String CASH_LINE = "الدفع كاش - Cash";

    /**
     * Payment method of an order. The website sends no payment field today (every order is paid in cash), so the default is CASH.
     * When the site starts sending "paid": true and/or "pay" / "payment" / "payment_method": "online" | "card" | "blik" | "p24" ...,
     * receipts and cards switch to PAID automatically.
     */
    static boolean isPaid(JSONObject o) {
        if (o.optBoolean("paid", false)) return true;
        String m = payMethod(o).toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return false;
        return !(m.contains("cash") || m.contains("gotówk") || m.contains("gotowk") || m.contains("كاش") || m.equals("cod") || m.equals("pickup") || m.equals("on_pickup") || m.equals("unpaid"));
    }

    static String payMethod(JSONObject o) {
        String[] keys = {"pay", "payment", "payment_method", "pay_method"};
        for (String k : keys) { String v = o.optString(k, "").trim(); if (!v.isEmpty() && !v.equals("null")) return v; }
        return "";
    }

    static String payLine(JSONObject o) {
        if (!isPaid(o)) return CASH_LINE;
        String m = payMethod(o);
        boolean generic = m.isEmpty() || m.equalsIgnoreCase("paid") || m.equalsIgnoreCase("online") || m.equals("1") || m.equalsIgnoreCase("true");
        return "تم الدفع - PAID" + (generic ? "" : " (" + ascii(m).toUpperCase(Locale.ROOT) + ")");
    }

    /** Draws one line of text (any language) as a 1-bit picture: ESC/POS "GS v 0" raster. widthDots: 384 (58 mm) or 576 (80 mm). */
    private static byte[] raster(String text, int widthDots) throws Exception {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float size = 46f;
        p.setTextSize(size);
        while (p.measureText(text) > widthDots - 16 && size > 18f) { size -= 2f; p.setTextSize(size); }
        int h = (int) Math.ceil(size * 1.4f);
        Bitmap bmp = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.WHITE);
        cv.drawText(text, (widthDots - p.measureText(text)) / 2f, size * 1.05f, p);
        int bpr = widthDots / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x1B); o.write(0x61); o.write(0);                          // left-align (the picture is already full width)
        o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00);
        o.write(bpr & 0xFF); o.write((bpr >> 8) & 0xFF); o.write(h & 0xFF); o.write((h >> 8) & 0xFF);
        int[] px = new int[widthDots];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(px, 0, widthDots, 0, y, widthDots, 1);
            for (int xb = 0; xb < bpr; xb++) {
                int v = 0;
                for (int k = 0; k < 8; k++) {
                    int c = px[xb * 8 + k];
                    int lum = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                    v = (v << 1) | (lum < 140 ? 1 : 0);
                }
                o.write(v);
            }
        }
        bmp.recycle();
        return o.toByteArray();
    }

    private static void write(ByteArrayOutputStream b, String s) {
        for (int i = 0; i < s.length(); i++) b.write(s.charAt(i) & 0xFF);
    }

    static JSONObject testOrder() {
        try {
            JSONObject o = new JSONObject();
            o.put("num", 0);
            o.put("ts", System.currentTimeMillis() / 1000);
            o.put("mode", "dine");
            o.put("table", "TEST");
            JSONArray lines = new JSONArray();
            lines.put(new JSONObject().put("name", "Test print").put("qty", 1).put("price", 10).put("desc", ""));
            o.put("lines", lines);
            o.put("total", 10);
            o.put("notes", "Test");
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------ queue

    /** Prints one order in the background. cb gets "ok" or null (error text in Store.printError). */
    static void print(Context ctx, JSONObject order, final Store.Result cb) {
        final Context c = ctx.getApplicationContext();
        final String json = order.toString();
        queue.execute(() -> {
            String err = null;
            try {
                send(c, escpos(c, new JSONObject(json)));
            } catch (Exception e) {
                err = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String er = err;
            main.post(() -> {
                Store.setPrintError(er);
                if (cb != null) cb.done(er == null ? "ok" : null);
            });
        });
    }

    static void printManual(Context ctx, JSONObject order, Store.Result cb) {
        tries.remove(order.optInt("id"));
        final int id = order.optInt("id");
        print(ctx, order, r -> {
            if (r != null && id > 0) Store.markPrinted(id);
            if (cb != null) cb.done(r);
        });
    }

    /** Called regularly by the service: prints new orders that have not been printed yet. */
    static void autoPrint(Context ctx) {
        if (!configured(ctx) || !on(ctx, "auto")) return;
        long now = System.currentTimeMillis();
        for (JSONObject o : Store.unprinted()) {
            final int id = o.optInt("id");
            if (inflight.contains(id)) continue;
            int n = tries.containsKey(id) ? tries.get(id) : 0;
            if (n >= 6) continue;
            Long last = lastTry.get(id);
            if (last != null && now - last < 15000) continue;
            inflight.add(id);
            tries.put(id, n + 1);
            lastTry.put(id, now);
            print(ctx, o, r -> {
                inflight.remove(id);
                if (r != null) Store.markPrinted(id);
            });
        }
    }

    // ------------------------------------------------------------ transports

    static String usbKey(UsbDevice d) { return d.getVendorId() + ":" + d.getProductId(); }

    private static void send(Context c, byte[] data) throws Exception {
        String type = get(c, "type"), addr = get(c, "addr");
        if (type.equals("bt")) sendBt(addr, data);
        else if (type.equals("net")) sendNet(addr, data);
        else if (type.equals("usb")) sendUsb(c, addr, data);
        else throw new IOException("No printer selected");
    }

    private static void chunked(OutputStream os, byte[] data, int size, int pauseMs) throws Exception {
        for (int i = 0; i < data.length; i += size) {
            os.write(data, i, Math.min(size, data.length - i));
            os.flush();
            if (pauseMs > 0) Thread.sleep(pauseMs);
        }
    }

    @SuppressWarnings("deprecation")
    private static void sendBt(String mac, byte[] data) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new IOException("This device has no Bluetooth");
        if (!ad.isEnabled()) throw new IOException("Bluetooth is turned off");
        try { ad.cancelDiscovery(); } catch (SecurityException e) { throw new IOException("Bluetooth permission denied"); }
        BluetoothDevice d = ad.getRemoteDevice(mac);
        BluetoothSocket s = null;
        try {
            try {
                s = d.createRfcommSocketToServiceRecord(UUID.fromString(SPP));
                s.connect();
            } catch (IOException first) {
                try { if (s != null) s.close(); } catch (Exception ignore) { /* ignore */ }
                s = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                s.connect();
            }
            OutputStream os = s.getOutputStream();
            chunked(os, data, 512, 40);
            Thread.sleep(900);   // let the printer finish before the link is closed
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission denied");
        } catch (IOException e) {
            throw new IOException("Can't connect to the Bluetooth printer (is it on and paired?)");
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static void sendNet(String ip, byte[] data) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, 9100), 4000);
            s.setSoTimeout(5000);
            chunked(s.getOutputStream(), data, 1024, 10);
            Thread.sleep(400);
        } catch (IOException e) {
            throw new IOException("Can't connect to printer " + ip);
        } finally {
            try { s.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static void sendUsb(Context c, String key, byte[] data) throws Exception {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        UsbDevice dev = null;
        for (UsbDevice d : um.getDeviceList().values()) if (usbKey(d).equals(key)) dev = d;
        if (dev == null) throw new IOException("USB printer is not connected");
        if (!um.hasPermission(dev)) throw new IOException("No USB permission — select the printer again in Settings");
        UsbInterface intf = null;
        UsbEndpoint out = null;
        for (int i = 0; i < dev.getInterfaceCount() && out == null; i++) {
            UsbInterface it = dev.getInterface(i);
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    intf = it; out = ep; break;
                }
            }
        }
        if (out == null) throw new IOException("USB printer output not found");
        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) throw new IOException("Can't open the USB printer");
        try {
            conn.claimInterface(intf, true);
            for (int off = 0; off < data.length; off += 4096) {
                int len = Math.min(4096, data.length - off);
                byte[] part = new byte[len];
                System.arraycopy(data, off, part, 0, len);
                if (conn.bulkTransfer(out, part, len, 5000) < 0) throw new IOException("Error sending data to the USB printer");
            }
            conn.releaseInterface(intf);
        } finally {
            conn.close();
        }
    }
}
