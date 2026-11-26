package com.example.compressor;

public final class NativeCore {
    static {
        System.loadLibrary("compressor");
    }

    private NativeCore() {}

    /**
     * orientations[i] برچسب EXIF چرخش فایل i (۱ تا ۸، برای ویدیو ۰).
     * پاسخ: متن نتیجه، و بعد از کاراکتر \u0001 فهرست "اندیس\tمسیر خروجی" عکس‌ها.
     */
    public static native String processFiles(String[] paths, int[] orientations, int percent,
                                             String videoFormat, String photoFormat, String libDir,
                                             boolean keepSize, boolean persian);

    /** پیشرفت کل از ۰ تا ۱۰۰۰ */
    public static native int progress();

    public static native void reset();
}
