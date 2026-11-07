package pl.grillhub.courier;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Courier sign-up: personal data, vehicle, ID document photos (kept privately on the restaurant's server) and consent. */
public class RegisterActivity extends Activity {
    private static final int REQ = 40;
    private EditText first, last, email, phone, pass, nat, city, plate, docNum;
    private Spinner vehicle, docType;
    private Button dobBtn, submit;
    private CheckBox consent;
    private String dob = "";
    private final String[] vKeys = {"bike", "ebike", "scooter", "car", "foot"};
    private final String[] vNames = {"Bicycle", "E-bike", "Scooter / motorbike", "Car", "On foot"};
    private final String[] dKeys = {"passport", "id", "residence"};
    private final String[] dNames = {"Passport", "National ID card", "Residence card"};
    private final Map<String, File> photos = new HashMap<>();
    private final Map<String, Button> photoBtns = new HashMap<>();
    private String picking = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, p * 2);
        sv.addView(root);

        root.addView(Ui.text(this, "Become a courier", 26, Ui.TEXT, true));
        root.addView(Ui.text(this, "Fill in your details. The restaurant reviews your application and approves it before you can start.", 14, Ui.MUTED, false), Ui.lp(this, 4));

        root.addView(section("About you"), Ui.lp(this, 18));
        first = add(root, "First name", Ui.TXT);
        last = add(root, "Last name", Ui.TXT);
        phone = add(root, "Phone number (+48 ...)", Ui.PHONE);
        email = add(root, "E-mail", Ui.MAIL);
        nat = add(root, "Nationality", Ui.TXT);
        dobBtn = Ui.btn(this, "Date of birth", Ui.SURF2, Ui.MUTED, v -> pickDob());
        dobBtn.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        root.addView(dobBtn, Ui.lp(this, 10));
        city = add(root, "City", Ui.TXT);
        city.setText("Katowice");

        root.addView(section("Vehicle"), Ui.lp(this, 18));
        vehicle = new Spinner(this);
        vehicle.setAdapter(darkAdapter(vNames));
        root.addView(vehicle, Ui.lp(this, 6));
        plate = add(root, "Plate number (if any)", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        root.addView(section("Identity document"), Ui.lp(this, 18));
        docType = new Spinner(this);
        docType.setAdapter(darkAdapter(dNames));
        root.addView(docType, Ui.lp(this, 6));
        docNum = add(root, "Document number", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        photoBtn(root, "doc_front", "Photo of the document - front (required)");
        photoBtn(root, "doc_back", "Photo of the document - back (if it has one)");
        photoBtn(root, "selfie", "Selfie photo of your face (required)");

        root.addView(section("Account"), Ui.lp(this, 18));
        pass = add(root, "Password (min. 8 characters)", Ui.PW);

        consent = new CheckBox(this);
        consent.setText("I consent to Grill Hub processing my personal data and ID document to verify me as a delivery courier. I can ask for it to be deleted at any time.");
        consent.setTextColor(Ui.TEXT);
        consent.setTextSize(13);
        root.addView(consent, Ui.lp(this, 16));

        submit = Ui.btn(this, "Send application", Ui.ORANGE, 0xFF000000, v -> submit());
        root.addView(submit, Ui.lp(this, 16));
        setContentView(sv);
    }

    private TextView section(String s) { return Ui.text(this, s.toUpperCase(Locale.US), 12, Ui.ORANGE, true); }

    private EditText add(LinearLayout root, String hint, int type) {
        EditText e = Ui.input(this, hint, type);
        root.addView(e, Ui.lp(this, 10));
        return e;
    }

    private ArrayAdapter<String> darkAdapter(String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public android.view.View getView(int pos, android.view.View v, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(Ui.TEXT);
                return t;
            }
        };
    }

    private void photoBtn(LinearLayout root, String key, String label) {
        Button bt = Ui.btn(this, "+  " + label, Ui.SURF2, Ui.TEXT, v -> {
            picking = key;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, "Choose photo"), REQ);
        });
        bt.setTextSize(14);
        photoBtns.put(key, bt);
        root.addView(bt, Ui.lp(this, 10));
    }

    private void pickDob() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, -25);
        DatePickerDialog d = new DatePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, (view, y, m, day) -> {
            dob = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day);
            dobBtn.setText("Date of birth: " + dob);
            dobBtn.setTextColor(Ui.TEXT);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        Calendar max = Calendar.getInstance();
        max.add(Calendar.YEAR, -18);
        d.getDatePicker().setMaxDate(max.getTimeInMillis());
        d.show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ || res != RESULT_OK || data == null || data.getData() == null || picking.isEmpty()) return;
        try {
            File f = shrink(data.getData(), picking);
            photos.put(picking, f);
            Button bt = photoBtns.get(picking);
            bt.setText("✓  Photo added - tap to change");
            bt.setTextColor(Ui.GREEN);
        } catch (Exception e) {
            Toast.makeText(this, "Could not read that photo. Choose another.", Toast.LENGTH_LONG).show();
        }
    }

    /** Photos are scaled to at most 1600 px and saved as JPEG so the upload stays small. */
    private File shrink(Uri u, String key) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(u);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 2400) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        in = getContentResolver().openInputStream(u);
        Bitmap bm = BitmapFactory.decodeStream(in, null, o);
        in.close();
        if (bm == null) throw new Exception("decode");
        int big = Math.max(bm.getWidth(), bm.getHeight());
        if (big > 1600) {
            float k = 1600f / big;
            bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
        }
        File f = new File(getCacheDir(), key + ".jpg");
        FileOutputStream out = new FileOutputStream(f);
        bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
        out.close();
        return f;
    }

    private void submit() {
        String msg = null;
        if (first.getText().toString().trim().isEmpty() || last.getText().toString().trim().isEmpty()) msg = "Enter your first and last name.";
        else if (phone.getText().toString().trim().isEmpty()) msg = "Enter your phone number.";
        else if (email.getText().toString().trim().isEmpty()) msg = "Enter your e-mail.";
        else if (nat.getText().toString().trim().isEmpty()) msg = "Enter your nationality.";
        else if (dob.isEmpty()) msg = "Choose your date of birth.";
        else if (docNum.getText().toString().trim().isEmpty()) msg = "Enter your document number.";
        else if (!photos.containsKey("doc_front")) msg = "Add a photo of the front of your document.";
        else if (!photos.containsKey("selfie")) msg = "Add a selfie photo.";
        else if (pass.getText().toString().length() < 8) msg = "Password must have at least 8 characters.";
        else if (!consent.isChecked()) msg = "Please tick the consent box.";
        if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); return; }

        Map<String, String> f = new LinkedHashMap<>();
        f.put("first", first.getText().toString().trim());
        f.put("last", last.getText().toString().trim());
        f.put("phone", phone.getText().toString().trim());
        f.put("email", email.getText().toString().trim());
        f.put("nationality", nat.getText().toString().trim());
        f.put("dob", dob);
        f.put("city", city.getText().toString().trim());
        f.put("vehicle", vKeys[vehicle.getSelectedItemPosition()]);
        f.put("plate", plate.getText().toString().trim());
        f.put("doc_type", dKeys[docType.getSelectedItemPosition()]);
        f.put("doc_number", docNum.getText().toString().trim());
        f.put("password", pass.getText().toString());
        f.put("consent", "1");
        submit.setEnabled(false);
        submit.setText("Sending...");
        Api.multipart(this, "register", f, photos, (code, j) -> {
            submit.setEnabled(true);
            submit.setText("Send application");
            if (code == 200 && j.optBoolean("ok")) {
                Api.saveLogin(this, j.optString("token"));
                startActivity(new Intent(this, MainActivity.class));
                finishAffinity();
            } else {
                Toast.makeText(this, Api.err(code, j), Toast.LENGTH_LONG).show();
            }
        });
    }
}
