package com.example.compressor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 42;
    private static final String[] FORMATS = {"Original", "mp4", "webm", "jpg", "png", "webp"};

    private static final int C_BG = 0xFF0E1015;
    private static final int C_CARD = 0xFF181B23;
    private static final int C_CHIP = 0xFF252B38;
    private static final int C_ACCENT = 0xFF6C8CFF;
    private static final int C_TEXT = 0xFFEEF0F5;
    private static final int C_MUTED = 0xFF8A93A6;

    private final List<Uri> selected = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String selectedFormat = "Original";
    private volatile boolean busy = false;
    private volatile boolean inNative = false;
    private String libDir;

    private SeekBar seek;
    private TextView percentValue;
    private TextView selectedText;
    private TextView statusText;
    private TextView barText;
    private ProgressBar bar;
    private TextView pickBtn;
    private TextView startBtn;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            int pm = inNative ? NativeCore.progress() : 0;
            bar.setProgress(pm);
            barText.setText((pm / 10) + "%");
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        libDir = getApplicationInfo().nativeLibraryDir;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(20), dp(36), dp(20), dp(28));
        scroll.addView(root);

        root.addView(text("فشرده‌ساز", 28, C_TEXT, true));
        root.addView(text("عکس و ویدیو با حجم دلخواه", 14, C_MUTED, false));

        // ---- کارت حجم خروجی: یک نوار لغزنده از ۵٪ تا ۹۰٪ با پیش‌فرض ۲۵٪
        LinearLayout c1 = card();
        LinearLayout head1 = row();
        head1.addView(text("حجم خروجی", 16, C_TEXT, true), weightLp());
        percentValue = text("25%", 18, C_ACCENT, true);
        head1.addView(percentValue);
        c1.addView(head1);

        seek = new SeekBar(this);
        seek.setMax(17);          // 0..17 => 5..90 با گام ۵
        seek.setProgress(4);      // 5 + 5*4 = 25
        seek.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(C_CHIP));
        seek.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                percentValue.setText(percent() + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c1.addView(seek);
        c1.addView(text("از ۵٪ تا ۹۰٪ — پیش‌فرض ۲۵٪", 13, C_MUTED, false));
        addCard(root, c1);

        // ---- کارت فرمت خروجی: چیپ‌های انتخابی
        LinearLayout c2 = card();
        c2.addView(text("فرمت خروجی", 16, C_TEXT, true));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = row();
        for (String f : FORMATS) {
            chipRow.addView(chip(f));
        }
        hs.addView(chipRow);
        c2.addView(hs);
        addCard(root, c2);
        refreshChips();

        // ---- کارت فایل‌ها و دکمه‌های اصلی
        LinearLayout c3 = card();
        c3.addView(text("فایل‌ها", 16, C_TEXT, true));
        selectedText = text("هنوز فایلی انتخاب نشده", 14, C_MUTED, false);
        c3.addView(selectedText);

        pickBtn = pill("انتخاب فایل‌ها", false);
        pickBtn.setOnClickListener(v -> pickFiles());
        c3.addView(pickBtn);

        startBtn = pill("شروع فشرده‌سازی", true);
        startBtn.setOnClickListener(v -> startProcessing());
        c3.addView(startBtn);
        addCard(root, c3);

        // ---- کارت پیشرفت
        LinearLayout c4 = card();
        LinearLayout head4 = row();
        head4.addView(text("پیشرفت", 16, C_TEXT, true), weightLp());
        barText = text("0%", 16, C_ACCENT, true);
        head4.addView(barText);
        c4.addView(head4);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(C_CHIP));
        bar.setLayoutParams(matchTop(dp(12)));
        c4.addView(bar);

        statusText = text("", 14, C_MUTED, false);
        c4.addView(statusText);
        addCard(root, c4);

        updateStartState();
        setContentView(scroll);
    }

    private int percent() {
        return 5 + 5 * seek.getProgress();
    }

    private void pickFiles() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) {
            return;
        }
        selected.clear();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                selected.add(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        selectedText.setText(selected.isEmpty()
                ? "هنوز فایلی انتخاب نشده"
                : selected.size() + " فایل انتخاب شده");
        statusText.setText("");
        updateStartState();
    }

    private void startProcessing() {
        if (busy || selected.isEmpty()) {
            return;
        }
        busy = true;
        inNative = false;
        updateStartState();
        pickBtn.setAlpha(0.4f);

        final List<Uri> uris = new ArrayList<>(selected);
        final int pct = percent();
        final String fmt = selectedFormat;
        final String dir = libDir;

        NativeCore.reset();
        bar.setProgress(0);
        barText.setText("0%");
        statusText.setText("در حال آماده‌سازی...");
        handler.post(poll);

        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            int failedCopy = 0;
            for (Uri u : uris) {
                try {
                    paths.add(copyToCache(u));
                } catch (IOException e) {
                    failedCopy++;
                }
            }

            String tmp;
            try {
                runOnUiThread(() -> statusText.setText("در حال پردازش..."));
                inNative = true;
                String result = NativeCore.processFiles(paths.toArray(new String[0]), pct, fmt, dir);
                if (failedCopy > 0) {
                    result = result + "\nفایل نخوانده: " + failedCopy;
                }
                tmp = result;
            } catch (Throwable t) {
                tmp = "خطا: " + t;
            } finally {
                inNative = false;
                busy = false;
            }
            final String msg = tmp;
            runOnUiThread(() -> {
                bar.setProgress(1000);
                barText.setText("100%");
                statusText.setText(msg);
                selected.clear();
                selectedText.setText("هنوز فایلی انتخاب نشده");
                pickBtn.setAlpha(1f);
                updateStartState();
            });
        }).start();
    }

    private void updateStartState() {
        boolean can = !busy && !selected.isEmpty();
        startBtn.setEnabled(can);
        startBtn.setClickable(can);
        startBtn.setAlpha(can ? 1f : 0.4f);
    }

    private TextView chip(String value) {
        String label = value.equals("Original") ? "اصلی" : value;
        TextView t = text(label, 14, C_MUTED, false);
        t.setTag(value);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), dp(8), 0);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            selectedFormat = value;
            refreshChips();
        });
        chips.add(t);
        return t;
    }

    private void refreshChips() {
        for (TextView t : chips) {
            boolean on = t.getTag().equals(selectedFormat);
            t.setTextColor(on ? 0xFFFFFFFF : C_MUTED);
            t.setBackground(rounded(on ? C_ACCENT : C_CHIP, 20));
        }
    }

    private TextView pill(String label, boolean filled) {
        TextView t = text(label, 16, filled ? 0xFFFFFFFF : C_ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(rounded(filled ? C_ACCENT : C_CHIP, 14));
        t.setClickable(true);
        t.setLayoutParams(matchTop(dp(14)));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(null, Typeface.BOLD);
        }
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(rounded(C_CARD, 20));
        return c;
    }

    private void addCard(LinearLayout root, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, 0);
        root.addView(card, lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, top, 0, 0);
        return lp;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ffmpeg مسیر content:// را نمی‌خواند؛ فایل را با نام اصلی در کش کپی می‌کنیم
    private String copyToCache(Uri uri) throws IOException {
        String name = "input_" + System.nanoTime();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) {
                    name = c.getString(idx);
                }
            }
        }
        File dir = new File(getCacheDir(), "in_" + System.nanoTime());
        dir.mkdirs();
        File dst = new File(dir, name.replace('/', '_'));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return dst.getAbsolutePath();
    }
}
