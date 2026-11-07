package pl.grillhub.courier;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class LoginActivity extends Activity {
    private EditText login, pass;
    private Button go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.token(this).isEmpty()) { startActivity(new Intent(this, MainActivity.class)); finish(); return; }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 24);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        TextView t = Ui.text(this, "GRILL HUB", 30, Ui.ORANGE, true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        root.addView(Ui.text(this, "Delivery - courier app", 16, Ui.MUTED, false));
        root.addView(Ui.space(this, 28));
        login = Ui.input(this, "Phone or e-mail", Ui.MAIL);
        root.addView(login, Ui.lp(this, 0));
        pass = Ui.input(this, "Password", Ui.PW);
        root.addView(pass, Ui.lp(this, 10));
        go = Ui.btn(this, "Log in", Ui.ORANGE, 0xFF000000, v -> doLogin());
        root.addView(go, Ui.lp(this, 18));
        Button reg = Ui.btn(this, "Become a courier - create account", Ui.SURF2, Ui.TEXT, v -> startActivity(new Intent(this, RegisterActivity.class)));
        root.addView(reg, Ui.lp(this, 10));
        setContentView(sv);
    }

    private void doLogin() {
        String l = login.getText().toString().trim(), pw = pass.getText().toString();
        if (l.isEmpty() || pw.isEmpty()) { Toast.makeText(this, "Enter your phone / e-mail and password.", Toast.LENGTH_SHORT).show(); return; }
        go.setEnabled(false);
        go.setText("Logging in...");
        try {
            JSONObject o = new JSONObject();
            o.put("login", l);
            o.put("password", pw);
            Api.post(this, "login", o, (code, j) -> {
                go.setEnabled(true);
                go.setText("Log in");
                if (code == 200 && j.optBoolean("ok")) {
                    Api.saveLogin(this, j.optString("token"));
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                } else {
                    Toast.makeText(this, Api.err(code, j), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) { go.setEnabled(true); }
    }
}
