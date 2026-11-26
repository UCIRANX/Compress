package com.example.compressor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 42;

    // جهت‌گیری متن‌ها با زبان گوشی تنظیم می‌شود؛ این مارک‌ها برای عدد و درصد است
    private static final String LRM = "\u200E";

    private static final int C_BG = 0xFF0E1015;
    private static final int C_CARD = 0xFF181B23;
    private static final int C_CHIP = 0xFF252B38;
    private static final int C_ACCENT = 0xFF6C8CFF;
    private static final int C_TEXT = 0xFFEEF0F5;
    private static final int C_MUTED = 0xFF8A93A6;

    /** یک انتخاب تک‌گزینه‌ای: مقدارها (کد) و برچسب‌های نمایشی و نمایشگر فعلی */
    private static class Choice {
        String[] values;
        String[] labels;
        String selected;
        TextView valueView;
    }

    private final List<Uri> selected = new ArrayList<>();
    private final Choice sizeChoice = new Choice();
    private final Choice photoChoice = new Choice();
    private final Choice videoChoice = new Choice();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile boolean inNative = false;
    private long selectedSize = -1;
    private String libDir;

    private SeekBar seek;
    private TextView percentValue;
    private TextView sizeHint;
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
            barText.setText(LRM + (pm / 10) + "%");
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
        // جهت از زبان گوشی می‌آید: فارسی راست‌به‌چپ، انگلیسی چپ‌به‌راست
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);

        // ---- حجم خروجی: برچسب + درصد + نوار + حجم تقریبی
        LinearLayout c1 = card();
        LinearLayout head1 = row();
        head1.addView(text(getString(R.string.label_output_size), 16, C_TEXT, true), weightLp());
        percentValue = text("", 16, C_ACCENT, true);
        head1.addView(percentValue);
        c1.addView(head1);

        seek = new SeekBar(this);
        seek.setMax(17);          // 0..17 => 5..90 با گام ۵
        seek.setProgress(6);      // 5 + 5*6 = 35 (پیش‌فرض)
        seek.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(C_CHIP));
        seek.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updatePercentText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c1.addView(seek);

        sizeHint = text("", 14, C_MUTED, false);
        c1.addView(sizeHint);
        addCard(root, c1);
        updatePercentText();

        // ---- سه دکمه در یک ردیف: تغییر سایزبندی، فرمت عکس، فرمت فیلم
        LinearLayout cButtons = card();
        LinearLayout btnRow = row();
        btnRow.setGravity(Gravity.CENTER);
        btnRow.addView(optionButton(getString(R.string.btn_resize), sizeChoice,
                new String[]{"auto", "original"},
                new String[]{getString(R.string.opt_auto), getString(R.string.opt_original)}, "auto"));
        btnRow.addView(optionButton(getString(R.string.btn_photo_format), photoChoice,
                new String[]{"Original", "jpg", "png"},
                new String[]{getString(R.string.opt_original), "jpg", "png"}, "Original"));
        btnRow.addView(optionButton(getString(R.string.btn_video_format), videoChoice,
                new String[]{"Original", "mp4", "mkv"},
                new String[]{getString(R.string.opt_original), "mp4", "mkv"}, "Original"));
        cButtons.addView(btnRow);
        addCard(root, cButtons);

        // ---- فایل‌ها و دکمه‌ها
        LinearLayout c3 = card();
        c3.addView(text(getString(R.string.files_title), 16, C_TEXT, true));
        selectedText = text(getString(R.string.no_file_selected), 14, C_MUTED, false);
        c3.addView(selectedText);

        pickBtn = pill(getString(R.string.btn_pick), false);
        pickBtn.setOnClickListener(v -> pickFiles());
        c3.addView(pickBtn);

        startBtn = pill(getString(R.string.btn_start), true);
        startBtn.setOnClickListener(v -> startProcessing());
        c3.addView(startBtn);
        addCard(root, c3);

        // ---- پیشرفت: برچسب + درصد
        LinearLayout c4 = card();
        LinearLayout head4 = row();
        head4.addView(text(getString(R.string.label_progress), 16, C_TEXT, true), weightLp());
        barText = text(LRM + "0%", 16, C_ACCENT, true);
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

    private void updatePercentText() {
        if (percentValue == null) {
            return;
        }
        percentValue.setText(getString(R.string.percent_of_original, LRM + percent()));
        updateSizeHint();
    }

    private void updateSizeHint() {
        if (sizeHint == null) {
            return;
        }
        if (selectedSize > 0) {
            sizeHint.setText(getString(R.string.approx_output_size,
                    LRM + formatSize(selectedSize * percent() / 100)));
        } else {
            sizeHint.setText("");
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%d KB", bytes / 1024L);
    }

    /** دکمه‌ای که عنوان و مقدار فعلی را نشان می‌دهد و با کلیک پاپ‌آپ گزینه‌ها را باز می‌کند */
    private LinearLayout optionButton(String title, Choice c, String[] values, String[] labels, String initial) {
        c.values = values;
        c.labels = labels;
        c.selected = initial;

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setBackground(rounded(C_CHIP, 14));
        b.setClickable(true);

        TextView t1 = text(title, 12, C_MUTED, false);
        t1.setGravity(Gravity.CENTER);
        c.valueView = text("", 14, C_ACCENT, true);
        c.valueView.setGravity(Gravity.CENTER);
        refreshValue(c);

        b.addView(t1);
        b.addView(c.valueView);
        b.setOnClickListener(v -> showPopup(title, c));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void showPopup(String title, final Choice c) {
        if (busy) {
            return;
        }
        int checked = indexOf(c.values, c.selected);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(title)
                .setSingleChoiceItems(c.labels, checked, (d, which) -> {
                    c.selected = c.values[which];
                    refreshValue(c);
                    d.dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void refreshValue(Choice c) {
        int i = indexOf(c.values, c.selected);
        c.valueView.setText(i >= 0 ? c.labels[i] : "");
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) {
                return i;
            }
        }
        return -1;
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
        selectedSize = selected.size() == 1 ? fileSize(selected.get(0)) : -1;
        selectedText.setText(selected.isEmpty()
                ? getString(R.string.no_file_selected)
                : getString(R.string.files_selected, selected.size()));
        statusText.setText("");
        updateSizeHint();
        updateStartState();
    }

    private long fileSize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
            // اگر اندازه خوانده نشد، حجم تقریبی نشان داده نمی‌شود
        }
        return -1;
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
        final String vf = videoChoice.selected;
        final String pf = photoChoice.selected;
        final boolean keep = "original".equals(sizeChoice.selected);
        final boolean fa = Locale.getDefault().getLanguage().equals("fa");
        final String dir = libDir;

        NativeCore.reset();
        bar.setProgress(0);
        barText.setText(LRM + "0%");
        statusText.setText(getString(R.string.status_preparing));
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
                runOnUiThread(() -> statusText.setText(getString(R.string.status_processing)));
                inNative = true;
                String result = NativeCore.processFiles(paths.toArray(new String[0]),
                        pct, vf, pf, dir, keep, fa);
                if (failedCopy > 0) {
                    result = result + "\n" + getString(R.string.unreadable_files, failedCopy);
                }
                tmp = result;
            } catch (Throwable t) {
                tmp = getString(R.string.error_format, String.valueOf(t));
            } finally {
                inNative = false;
                busy = false;
            }
            final String msg = tmp;
            runOnUiThread(() -> {
                bar.setProgress(1000);
                barText.setText(LRM + "100%");
                statusText.setText(msg);
                selected.clear();
                selectedSize = -1;
                selectedText.setText(getString(R.string.no_file_selected));
                sizeHint.setText("");
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
