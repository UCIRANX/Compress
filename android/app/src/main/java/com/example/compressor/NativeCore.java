package com.example.compressor;

public final class NativeCore {
    static {
        System.loadLibrary("compressor");
    }

    private NativeCore() {}

    /** keepSize = true یعنی ابعاد اصلی حفظ شود؛ persian = زبان پیام‌های برنامه */
    public static native String processFiles(String[] paths, int percent, String videoFormat,
                                             String photoFormat, String libDir,
                                             boolean keepSize, boolean persian);

    /** پیشرفت کل از ۰ تا ۱۰۰۰ */
    public static native int progress();

    public static native void reset();
}
