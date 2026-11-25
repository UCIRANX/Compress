package com.example.compressor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
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

    private int percent = 50;
    private TextView percentText;
    private TextView statusText;
    private Spinner formatSpinner;
    private String libDir;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        libDir = getApplicationInfo().nativeLibraryDir;
        // از اندروید 6 به بالا دسترسی ذخیره‌سازی باید در زمان اجرا گرفته شود
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        percentText = new TextView(this);
        percentText.setTextSize(20);
        updatePercent();
        root.addView(percentText);

        LinearLayout row = new LinearLayout(this);
        Button minus = new Button(this);
        minus.setText("-5");
        minus.setOnClickListener((View v) -> {
            if (percent > 5) {
                percent -= 5;
                updatePercent();
            }
        });
        Button plus = new Button(this);
        plus.setText("+5");
        plus.setOnClickListener((View v) -> {
            if (percent < 90) {
                percent += 5;
                updatePercent();
            }
        });
        row.addView(minus);
        row.addView(plus);
        root.addView(row);

        formatSpinner = new Spinner(this);
        formatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, FORMATS));
        root.addView(formatSpinner);

        Button pick = new Button(this);
        pick.setText("انتخاب فایل‌ها و فشرده‌سازی");
        pick.setOnClickListener((View v) -> pickFiles());
        root.addView(pick);

        statusText = new TextView(this);
        root.addView(statusText);

        setContentView(root);
    }

    private void updatePercent() {
        percentText.setText("حجم هدف: " + percent + "%");
    }

    private void pickFiles() {
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
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                uris.add(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        final int pct = percent;
        final String fmt = String.valueOf(formatSpinner.getSelectedItem());
        statusText.setText("در حال پردازش " + uris.size() + " فایل...");

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
            String result = NativeCore.processFiles(paths.toArray(new String[0]), pct, fmt, libDir);
            if (failedCopy > 0) {
                result = result + "\nفایل نخوانده: " + failedCopy;
            }
            final String msg = result;
            runOnUiThread(() -> statusText.setText(msg));
        }).start();
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
