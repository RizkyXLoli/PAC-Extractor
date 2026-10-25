package com.rizkyxloli.pacx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PAC Extractor — membongkar firmware .pac Unisoc dan mengambil partisi pilihan.
 * Tanpa fitur flashing, tanpa bypass: hanya membaca dan mengekstrak.
 */
public class MainActivity extends Activity {

    private static final int REQ_PAC = 1001;
    private static final int REQ_DIR = 1002;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int parseGen = 0;

    private TextView tvFile;
    private TextView tvInfo;
    private TextView tvLog;
    private ProgressBar progress;
    private Button btnPickPac, btnPickDir, btnExtract, btnAll, btnNone, btnSafe;
    private LinearLayout listBox;
    private ScrollView listScroll;

    private Uri pacUri;
    private Uri dirUri;
    private String pacName = "";
    private PacParser.Pac pac;

    /** checkbox per entri berdata, urut sesuai tabel PAC */
    private final Map<PacParser.Entry, CheckBox> boxes = new LinkedHashMap<>();

    private volatile boolean cancelFlag;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        SharedPreferences sp = getSharedPreferences("pacx", MODE_PRIVATE);
        String d = sp.getString("dir", null);
        if (d != null) {
            dirUri = Uri.parse(d);
            updateButtons();
        }
        log("PAC Extractor siap. Pilih file .pac untuk mulai.");

        // dibuka dari file manager (VIEW)
        Uri fromIntent = getIntent() == null ? null : getIntent().getData();
        if (fromIntent != null) {
            pacUri = fromIntent;
            pacName = queryName(fromIntent);
            tvFile.setText("File: " + pacName);
            openPac();
        }
    }

    // ------------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12151C);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PAC Extractor");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Bongkar firmware .pac Unisoc, ambil partisi pilihan");
        sub.setTextColor(0xFF8B98AC);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        btnPickPac = mkBtn("1. Pilih file .pac", 0xFF2563EB);
        btnPickPac.setOnClickListener(v -> pickPac());
        root.addView(btnPickPac);

        tvFile = new TextView(this);
        tvFile.setTextColor(0xFFB8C4D6);
        tvFile.setTextSize(12);
        tvFile.setPadding(dp(2), dp(8), dp(2), dp(4));
        tvFile.setText("Belum ada file dipilih");
        root.addView(tvFile);

        tvInfo = new TextView(this);
        tvInfo.setTextColor(0xFF9AE6B4);
        tvInfo.setTextSize(12);
        tvInfo.setTypeface(Typeface.MONOSPACE);
        tvInfo.setPadding(dp(2), dp(4), dp(2), dp(8));
        tvInfo.setVisibility(View.GONE);
        root.addView(tvInfo);

        // tombol pintasan pilih
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        btnAll = mkBtn("Semua", 0xFF334155);
        btnNone = mkBtn("Kosongkan", 0xFF334155);
        btnSafe = mkBtn("Aman", 0xFF334155);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        quick.addView(btnAll, lp);
        quick.addView(btnNone, new LinearLayout.LayoutParams(lp));
        quick.addView(btnSafe, new LinearLayout.LayoutParams(lp));
        quick.setVisibility(View.GONE);
        root.addView(quick);

        btnAll.setOnClickListener(v -> setAll(true));
        btnNone.setOnClickListener(v -> setAll(false));
        btnSafe.setOnClickListener(v -> {
            // pilih partisi kecil saja — lewati Super/userdata yang raksasa
            for (Map.Entry<PacParser.Entry, CheckBox> e : boxes.entrySet()) {
                String id = e.getKey().label().toLowerCase(Locale.ROOT);
                boolean big = id.contains("super") || id.contains("userdata")
                        || e.getKey().size > 512L * 1024 * 1024;
                e.getValue().setChecked(!big);
            }
        });

        listScroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(0, dp(6), 0, dp(6));
        listScroll.setLayoutParams(slp);
        listScroll.setVisibility(View.GONE);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listBox);
        root.addView(listScroll);

        btnPickDir = mkBtn("2. Pilih folder tujuan", 0xFF2563EB);
        btnPickDir.setOnClickListener(v -> pickDir());
        root.addView(btnPickDir);

        btnExtract = mkBtn("3. EKSTRAK", 0xFF16A34A);
        btnExtract.setOnClickListener(v -> doExtract());
        root.addView(btnExtract);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setPadding(0, dp(8), 0, dp(4));
        progress.setVisibility(View.GONE);
        root.addView(progress);

        tvLog = new TextView(this);
        tvLog.setTextColor(0xFF7DD3FC);
        tvLog.setTextSize(10);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setBackgroundColor(0xFF0B0E14);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        llp.setMargins(0, dp(4), 0, 0);
        tvLog.setLayoutParams(llp);
        root.addView(tvLog);

        updateButtons();
        return root;
    }

    private Button mkBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void updateButtons() {
        btnExtract.setEnabled(pac != null && dirUri != null);
        btnExtract.setAlpha(pac != null && dirUri != null ? 1f : 0.45f);
    }

    // ---------------------------------------------------------------- pilih

    private void pickPac() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PAC);
    }

    private void pickDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_DIR);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();

        if (req == REQ_PAC) {
            pacUri = u;
            pacName = queryName(u);
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            tvFile.setText("File: " + pacName);
            openPac();
        } else if (req == REQ_DIR) {
            dirUri = u;
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            getSharedPreferences("pacx", MODE_PRIVATE).edit()
                    .putString("dir", u.toString()).apply();
            log("Folder tujuan: " + u.getLastPathSegment());
            updateButtons();
        }
    }

    private String queryName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) n = c.getString(idx);
            }
        } catch (Exception ignored) { }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "firmware.pac" : n;
    }

    // ----------------------------------------------------------------- parse

    /** Tutup sumber PAC yang sedang terbuka supaya tidak bocor file descriptor. */
    private void closePac() {
        if (pac != null && pac.source != null) {
            try { pac.source.close(); } catch (Exception ignored) { }
        }
        pac = null;
    }

    @Override
    protected void onDestroy() {
        closePac();
        super.onDestroy();
    }

    private void openPac() {
        log("Membaca header PAC ...");
        tvInfo.setVisibility(View.GONE);
        listScroll.setVisibility(View.GONE);
        btnAll.setVisibility(View.GONE);
        btnNone.setVisibility(View.GONE);
        btnSafe.setVisibility(View.GONE);
        boxes.clear();
        listBox.removeAllViews();
        closePac();
        updateButtons();

        final Uri u = pacUri;
        final String nm = pacName;
        final int gen = ++parseGen;
        new Thread(() -> {
            Source src = null;
            try {
                src = new UriSource(getContentResolver(), u, nm);
                long t0 = System.currentTimeMillis();
                PacParser.Pac p = PacParser.parse(src);
                long ms = System.currentTimeMillis() - t0;
                final PacParser.Pac fp = p;
                final long fms = ms;
                final Source fs = src;
                ui.post(() -> {
                    if (gen != parseGen) {          // pengguna sudah pilih file lain
                        try { fs.close(); } catch (Exception ignored) { }
                        return;
                    }
                    onParsed(fp, fs, fms);
                });
            } catch (Exception ex) {
                final String m = ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage();
                if (src != null) try { src.close(); } catch (Exception ignored) { }
                ui.post(() -> {
                    if (gen != parseGen) return;
                    log("GAGAL: " + m);
                    Toast.makeText(this, "Gagal membaca PAC:\n" + m, Toast.LENGTH_LONG).show();
                });
            }
        }, "pac-parse").start();
    }

    private void onParsed(PacParser.Pac p, Source src, long ms) {
        pac = p;
        int nData = p.dataEntries().size();
        StringBuilder sb = new StringBuilder();
        sb.append("produk   : ").append(p.productName.isEmpty() ? "-" : p.productName).append('\n');
        sb.append("versi    : ").append(p.version.isEmpty() ? "-" : p.version).append('\n');
        sb.append("mode     : ").append(p.modeStr()).append(" / ").append(p.flashTypeStr()).append('\n');
        sb.append("entri    : ").append(nData).append(" berdata dari ")
                .append(p.fileCount).append(" terdaftar\n");
        sb.append("total    : ").append(PacParser.human(p.totalDataSize())).append('\n');
        sb.append("XML      : ").append(p.xml.isEmpty() ? "tidak ada"
                : p.xml.length() + " karakter").append('\n');
        sb.append("parse    : ").append(ms).append(" ms");
        tvInfo.setText(sb.toString());
        tvInfo.setVisibility(View.VISIBLE);

        boxes.clear();
        listBox.removeAllViews();
        int autoChecked = 0;
        for (PacParser.Entry e : p.entries) {
            if (!e.hasData()) continue;
            CheckBox cb = new CheckBox(this);
            String line = e.label();
            if (!e.name.isEmpty() && !e.name.equalsIgnoreCase(e.label())) {
                line += "  (" + e.name + ")";
            }
            line += "  —  " + PacParser.human(e.size);
            if (e.isLoader()) line += "   [loader]";
            cb.setText(line);
            cb.setTextColor(e.isLoader() ? 0xFFFBBF24 : 0xFFE2E8F0);
            cb.setTextSize(12);
            // partisi raksasa tidak dicentang otomatis supaya tidak ekstrak 7 GB
            // tanpa sengaja; pengguna bisa mencentang sendiri
            boolean huge = e.size > 1024L * 1024 * 1024;
            cb.setChecked(!huge);
            if (!huge) autoChecked++;
            cb.setPadding(dp(2), dp(6), dp(2), dp(6));
            listBox.addView(cb);
            boxes.put(e, cb);
        }
        listScroll.setVisibility(View.VISIBLE);
        btnAll.setVisibility(View.VISIBLE);
        btnNone.setVisibility(View.VISIBLE);
        btnSafe.setVisibility(View.VISIBLE);
        log("Siap: " + nData + " bagian terbaca, " + autoChecked
                + " dicentang otomatis. Partisi >1 GB tidak dicentang — centang sendiri bila perlu.");
        updateButtons();
    }

    private void setAll(boolean on) {
        for (CheckBox c : boxes.values()) c.setChecked(on);
    }

    private List<PacParser.Entry> selected() {
        List<PacParser.Entry> out = new ArrayList<>();
        for (Map.Entry<PacParser.Entry, CheckBox> e : boxes.entrySet()) {
            if (e.getValue().isChecked()) out.add(e.getKey());
        }
        return out;
    }

    // ---------------------------------------------------------------- ekstrak

    private void doExtract() {
        if (pac == null) { toast("Pilih file .pac dulu"); return; }
        if (dirUri == null) { toast("Pilih folder tujuan dulu"); return; }
        List<PacParser.Entry> sel = selected();
        if (sel.isEmpty()) { toast("Belum ada partisi yang dicentang"); return; }

        long total = 0;
        for (PacParser.Entry e : sel) total += e.size;

        new AlertDialog.Builder(this)
                .setTitle("Ekstrak " + sel.size() + " partisi?")
                .setMessage("Total " + PacParser.human(total)
                        + "\n\nHasil ditulis ke folder tujuan. File dengan nama sama "
                        + "akan dibuat baru, tidak menimpa.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Ekstrak", (d, w) -> startExtract(sel))
                .show();
    }

    private void startExtract(List<PacParser.Entry> sel) {
        cancelFlag = false;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        btnExtract.setEnabled(false);
        btnPickPac.setEnabled(false);
        btnExtract.setText("3. EKSTRAK");
        log("--- mulai ekstrak " + sel.size() + " partisi ---");

        final Uri dir = dirUri;
        final ContentResolver cr = getContentResolver();
        final PacParser.Pac p = pac;
        final SafSink sink = new SafSink(cr, dir);

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Engine.Outcome o = Engine.extract(p, sel, sink, new Engine.Cb() {
                public void log(String msg) { ui.post(() -> MainActivity.this.log(msg)); }
                public boolean progress(int pct, String stage, long done, long total) {
                    ui.post(() -> {
                        progress.setProgress(pct);
                        btnExtract.setText("3. EKSTRAK  " + pct + "%");
                    });
                    return !cancelFlag;
                }
            });

            // XML peta partisi ikut diekstrak bila ada
            String xmlNote = "";
            if (!o.cancelled && !p.xml.isEmpty()) {
                String base = p.productAlias.isEmpty() ? p.productName : p.productAlias;
                String xn = Engine.safeName(base, "pac") + ".xml";
                xmlNote = sink.writeXml(xn, p.xml) ? " + " + xn : " (XML gagal ditulis)";
            }

            final Engine.Outcome fo = o;
            final String fx = xmlNote;
            final long ms = System.currentTimeMillis() - t0;
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                btnExtract.setEnabled(true);
                btnPickPac.setEnabled(true);
                btnExtract.setText("3. EKSTRAK");
                String head = fo.cancelled ? "DIBATALKAN" : "SELESAI";
                log("--- " + head + ": " + fo.ok + " OK, " + fo.failed + " gagal, "
                        + PacParser.human(fo.bytes) + " dalam " + (ms / 1000.0) + " s" + fx + " ---");
                toast(head + ": " + fo.ok + " partisi diekstrak" + fx);
            });
        }, "pac-extract").start();

    }

    private void log(String s) {
        String cur = tvLog.getText().toString();
        if (cur.length() > 6000) cur = cur.substring(cur.length() - 4000);
        tvLog.setText(cur + "\n" + s);
        tvLog.post(() -> {
            int sc = tvLog.getLayout() == null ? 0 : tvLog.getLayout().getLineTop(tvLog.getLineCount());
            tvLog.scrollTo(0, Math.max(0, sc - tvLog.getHeight()));
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (progress.getVisibility() == View.VISIBLE) {
            cancelFlag = true;
            log("Permintaan batal dikirim ...");
            return;
        }
        super.onBackPressed();
    }
}
