package com.example.compressor;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends NativeActivity {
    private static final int REQ_PICK = 42;
    private static final ArrayList<String> picked = new ArrayList<>();
    private static volatile boolean pickDone = false;

    /** Rust این متد را فرامی‌خواند تا انتخابگر فایل باز شود */
    public static void startPick(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        a.startActivityForResult(i, REQ_PICK);
    }

    /** null یعنی هنوز تمام نشده؛ در غیر این صورت مسیرهای کپی‌شده در کش */
    public static String[] takePicked() {
        if (!pickDone) return null;
        pickDone = false;
        String[] r = picked.toArray(new String[0]);
        picked.clear();
        return r;
    }

    public static String nativeLibDir(Activity a) {
        return a.getApplicationInfo().nativeLibraryDir;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        if (res == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                    uris.add(data.getClipData().getItemAt(k).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) {
                try {
                    picked.add(copyToCache(u));
                } catch (IOException ignored) {
                    // فایلی که خوانده نشد نادیده گرفته می‌شود
                }
            }
        }
        pickDone = true;
    }

    /** ffmpeg مسیر content:// را نمی‌خواند؛ فایل را با نام اصلی در کش کپی می‌کنیم */
    private String copyToCache(Uri uri) throws IOException {
        String name = "input_" + System.nanoTime();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) name = c.getString(idx);
            }
        }
        File dir = new File(getCacheDir(), "in_" + System.nanoTime());
        dir.mkdirs();
        File dst = new File(dir, name.replace('/', '_'));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dst.getAbsolutePath();
    }
}
