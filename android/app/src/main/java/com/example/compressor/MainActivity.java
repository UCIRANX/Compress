package com.example.compressor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

    private static final int C_BG = 0xFF0E1015;
    private static final int C_CARD = 0xFF181B23;
    private static final int C_CHIP = 0xFF252B38;
    private static final int C_ACCENT = 0xFF6C8CFF;
    private static final int C_TEXT = 0xFFEEF0F5;
    private static final int C_MUTED = 0xFF8A93A6;
    private static final int C_GLASS = 0x33FFFFFF;
    private static final int C_GLASS_EDGE = 0x55FFFFFF;
    private static final int C_GLASS_SEL = 0x446C8CFF;

    /** تگ‌های EXIF که از عکس اصلی به خروجی منتقل می‌شوند (چرخش عمداً نیست) */
    private static final String[] EXIF_COPY_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH
    };

    /** یک انتخاب تک‌گزینه‌ای: کدها، برچسب‌ها، مقدار فعلی و نمایشگرش */
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
    private Choice openChoice = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile boolean inNative = false;
    private long selectedSize = -1;
    private String libDir;

    private SeekBar seek;
    private TextView outputSizeText;
    private TextView sizeHint;
    private TextView selectedText;
    private TextView statusText;
    private TextView progressText;
    private ProgressBar bar;
    private TextView pickBtn;
    private TextView startBtn;
    private LinearLayout optionPanel;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                return;
            }
            int pm = inNative ? NativeCore.progress() : 0;
            bar.setProgress(pm);
            progressText.setText(getString(R.string.progress_line, String.valueOf(pm / 10)));
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

        // ---- حجم خروجی: یک خط متن + نوار + حجم تقریبی
        LinearLayout c1 = card();
        outputSizeText = text("", 16, C_TEXT, true);
        c1.addView(outputSizeText);

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

        // ---- سه دکمه در یک ردیف؛ با کلیک، گزینه‌ها زیر همین ردیف باز می‌شوند
        LinearLayout cButtons = card();
        LinearLayout btnRow = row();
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

        optionPanel = new LinearLayout(this);
        optionPanel.setOrientation(LinearLayout.VERTICAL);
        optionPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        optionPanel.setBackground(glass(18));
        optionPanel.setVisibility(View.GONE);
        cButtons.addView(optionPanel, matchTop(dp(12)));
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

        // ---- پیشرفت: یک خط متن + نوار + وضعیت
        LinearLayout c4 = card();
        progressText = text(getString(R.string.progress_line, "0"), 16, C_TEXT, true);
        c4.addView(progressText);

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
        if (outputSizeText == null) {
            return;
        }
        outputSizeText.setText(getString(R.string.output_size_line, String.valueOf(percent())));
        updateSizeHint();
    }

    private void updateSizeHint() {
        if (sizeHint == null) {
            return;
        }
        if (selectedSize > 0) {
            sizeHint.setText(getString(R.string.approx_output_size,
                    formatSize(selectedSize * percent() / 100)));
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

    /** دکمه‌ی شیشه‌ای که عنوان و مقدار فعلی را نشان می‌دهد */
    private LinearLayout optionButton(String title, Choice c, String[] values, String[] labels, String initial) {
        c.values = values;
        c.labels = labels;
        c.selected = initial;

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setBackground(glass(14));
        b.setClickable(true);

        TextView t1 = text(title, 12, C_MUTED, false);
        t1.setGravity(Gravity.CENTER);
        c.valueView = text("", 14, C_ACCENT, true);
        c.valueView.setGravity(Gravity.CENTER);
        refreshValue(c);

        b.addView(t1);
        b.addView(c.valueView);
        b.setOnClickListener(v -> togglePanel(c));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** باز یا بسته کردن پنل گزینه‌های زیر همان ردیف */
    private void togglePanel(Choice c) {
        if (busy) {
            return;
        }
        if (openChoice == c) {
            closePanel();
            return;
        }
        openChoice = c;
        renderPanel(c);
        optionPanel.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        openChoice = null;
        optionPanel.setVisibility(View.GONE);
        optionPanel.removeAllViews();
    }

    private void renderPanel(final Choice c) {
        optionPanel.removeAllViews();
        for (int i = 0; i < c.values.length; i++) {
            final int idx = i;
            boolean on = c.values[i].equals(c.selected);
            TextView row = text(c.labels[i], 15, on ? C_ACCENT : C_TEXT, on);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(glassFill(on ? C_GLASS_SEL : C_GLASS, 12));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                c.selected = c.values[idx];
                refreshValue(c);
                closePanel();
            });
            optionPanel.addView(row, matchTop(i == 0 ? 0 : dp(8)));
        }
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

    /** انتخاب فایل از گالری (عکس و ویدیو) با امکان چند انتخاب */
    private void pickFiles() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
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
        closePanel();
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
        progressText.setText(getString(R.string.progress_line, "0"));
        statusText.setText(getString(R.string.status_preparing));
        handler.post(poll);

        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            List<Uri> okUris = new ArrayList<>();
            List<Integer> okOrient = new ArrayList<>();
            int failedCopy = 0;
            for (Uri u : uris) {
                try {
                    paths.add(copyToCache(u));
                    okUris.add(u);
                    okOrient.add(isImageUri(u) ? exifOrientation(u) : 0);
                } catch (IOException e) {
                    failedCopy++;
                }
            }
            int[] orient = new int[okOrient.size()];
            for (int i = 0; i < orient.length; i++) {
                orient[i] = okOrient.get(i);
            }

            String text;
            String pairBlock = "";
            try {
                runOnUiThread(() -> statusText.setText(getString(R.string.status_processing)));
                inNative = true;
                String raw = NativeCore.processFiles(paths.toArray(new String[0]), orient,
                        pct, vf, pf, dir, keep, fa);
                int sep = raw.indexOf('\u0001');
                text = sep >= 0 ? raw.substring(0, sep) : raw;
                pairBlock = sep >= 0 ? raw.substring(sep + 1) : "";
                if (failedCopy > 0) {
                    text = text + "\n" + getString(R.string.unreadable_files, failedCopy);
                }
            } catch (Throwable t) {
                text = getString(R.string.error_format, String.valueOf(t));
            } finally {
                inNative = false;
                busy = false;
            }

            // انتقال متادیتای EXIF به عکس‌های خروجی (بدون برچسب چرخش، چون پیکسل‌ها چرخیده‌اند)
            for (String line : pairBlock.split("\n")) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int idx = Integer.parseInt(parts[0]);
                    copyExif(okUris.get(idx), parts[1]);
                } catch (Exception ignored) {
                    // اگر متادیتا منتقل نشد، فایل خروجی همچنان ساخته شده است
                }
            }

            // اسکن پوشه تا فایل‌ها در گالری دیده شوند
            File outDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Compress");
            File[] outs = outDir.listFiles();
            if (outs != null && outs.length > 0) {
                String[] outPaths = new String[outs.length];
                for (int i = 0; i < outs.length; i++) {
                    outPaths[i] = outs[i].getAbsolutePath();
                }
                MediaScannerConnection.scanFile(this, outPaths, null, null);
            }

            final String msg = text;
            runOnUiThread(() -> {
                bar.setProgress(1000);
                progressText.setText(getString(R.string.progress_line, "100"));
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

    private boolean isImageUri(Uri u) {
        String type = getContentResolver().getType(u);
        return type != null && type.startsWith("image/");
    }

    /** برچسب چرخش EXIF عکس (۱ تا ۸) */
    private int exifOrientation(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) {
                return 1;
            }
            ExifInterface ex = new ExifInterface(in);
            return ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return 1;
        }
    }

    private void copyExif(Uri src, String outPath) {
        try (InputStream in = getContentResolver().openInputStream(src)) {
            if (in == null) {
                return;
            }
            ExifInterface s = new ExifInterface(in);
            ExifInterface d = new ExifInterface(outPath);
            for (String tag : EXIF_COPY_TAGS) {
                String v = s.getAttribute(tag);
                if (v != null) {
                    d.setAttribute(tag, v);
                }
            }
            d.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            d.saveAttributes();
        } catch (Exception ignored) {
            // بعضی فرمت‌ها (مثلاً برخی PNG) از نوشتن EXIF پشتیبانی نمی‌کنند
        }
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

    /** شیشه‌ای: پرکننده نیمه‌شفاف با لبه‌ی روشن */
    private GradientDrawable glass(int radiusDp) {
        return glassFill(C_GLASS, radiusDp);
    }

    private GradientDrawable glassFill(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), C_GLASS_EDGE);
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
