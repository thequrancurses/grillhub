package pl.grillhub.courier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Courier API on grillhub.pl (WordPress plugin Grill Hub Menu, /wp-json/ghm/v1/courier/...). */
final class Api {
    private Api() {}

    static final String BASE = "https://grillhub.pl/wp-json/ghm/v1/courier/";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface CB { void done(int code, JSONObject json); }

    // ---- saved login ----
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("courier", Context.MODE_PRIVATE); }
    static String token(Context c) { return prefs(c).getString("token", ""); }
    static void saveLogin(Context c, String token) { prefs(c).edit().putString("token", token).apply(); }
    static void logout(Context c) { prefs(c).edit().clear().apply(); }

    // ---- calls (callback on the main thread; code -1 = no network) ----
    static void get(Context c, String path, CB cb) { run(c, "GET", path, null, cb); }
    static void post(Context c, String path, JSONObject body, CB cb) { run(c, "POST", path, body == null ? new JSONObject() : body, cb); }

    /** Same as get/post but blocking, for the background service. */
    static Result getSync(Context c, String path) { return call(token(c), "GET", path, null); }

    static final class Result { int code; JSONObject json; }

    private static void run(Context c, String method, String path, JSONObject body, CB cb) {
        final String tok = token(c);
        POOL.execute(() -> {
            final Result r = call(tok, method, path, body);
            if (cb != null) MAIN.post(() -> cb.done(r.code, r.json));
        });
    }

    private static Result call(String tok, String method, String path, JSONObject body) {
        Result r = new Result();
        r.json = new JSONObject();
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(BASE + path).openConnection();
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setRequestMethod(method);
            h.setRequestProperty("Accept", "application/json");
            if (!tok.isEmpty()) h.setRequestProperty("X-GHM-Courier", tok);
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                OutputStream o = h.getOutputStream();
                o.write(body.toString().getBytes("UTF-8"));
                o.close();
            }
            read(h, r);
        } catch (Exception e) {
            r.code = -1;
        } finally {
            if (h != null) h.disconnect();
        }
        return r;
    }

    static void multipart(Context c, String path, Map<String, String> fields, Map<String, File> files, CB cb) {
        POOL.execute(() -> {
            Result r = new Result();
            r.json = new JSONObject();
            HttpURLConnection h = null;
            try {
                String b = "----ghc" + System.currentTimeMillis();
                h = (HttpURLConnection) new URL(BASE + path).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(90000);
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setChunkedStreamingMode(16384);
                h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                DataOutputStream o = new DataOutputStream(h.getOutputStream());
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    o.writeBytes("--" + b + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
                    o.write(e.getValue().getBytes("UTF-8"));
                    o.writeBytes("\r\n");
                }
                for (Map.Entry<String, File> e : files.entrySet()) {
                    o.writeBytes("--" + b + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"; filename=\"" + e.getKey() + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n");
                    InputStream in = new FileInputStream(e.getValue());
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    in.close();
                    o.writeBytes("\r\n");
                }
                o.writeBytes("--" + b + "--\r\n");
                o.flush();
                o.close();
                read(h, r);
            } catch (Exception e) {
                r.code = -1;
            } finally {
                if (h != null) h.disconnect();
            }
            MAIN.post(() -> cb.done(r.code, r.json));
        });
    }

    private static void read(HttpURLConnection h, Result r) throws Exception {
        r.code = h.getResponseCode();
        InputStream in = r.code >= 400 ? h.getErrorStream() : h.getInputStream();
        if (in == null) return;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        try { r.json = new JSONObject(bo.toString("UTF-8")); } catch (Exception ignore) { r.json = new JSONObject(); }
    }

    /** Human text for an API error code. */
    static String err(int code, JSONObject j) {
        if (code == -1) return "No internet connection. Try again.";
        String e = j == null ? "" : j.optString("error");
        switch (e) {
            case "login": return "Wrong phone / e-mail or password.";
            case "blocked": return "This account is blocked. Contact the restaurant.";
            case "rate": return "Too many attempts. Wait a few minutes and try again.";
            case "fields": return "Fill in all the required fields.";
            case "email": return "Enter a valid e-mail address.";
            case "phone": return "Enter a valid phone number.";
            case "age": return "You must be at least 18 years old.";
            case "vehicle": return "Choose your vehicle.";
            case "doc": return "Choose the document type and enter its number.";
            case "password": return "Password must have at least 8 characters.";
            case "consent": return "Please accept the data processing consent.";
            case "exists": return "An account with this phone or e-mail already exists. Log in instead.";
            case "file_doc_front": return "Add a photo of the front of your ID document.";
            case "file_selfie": return "Add a selfie photo.";
            case "file_type": return "Photos must be JPG, PNG or PDF.";
            case "taken": return "Too late - another courier took this order.";
            case "busy": return "Finish your current delivery first.";
            case "offline": return "Go online first.";
            case "not_approved": return "Your account is not approved yet.";
            case "gone": return "This order is no longer available.";
            default: return "Something went wrong (" + code + "). Try again.";
        }
    }
}
