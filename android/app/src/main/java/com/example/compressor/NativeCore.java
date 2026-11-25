package com.example.compressor;

public final class NativeCore {
    static {
        System.loadLibrary("compressor");
    }

    private NativeCore() {}

    public static native String processFiles(String[] paths, int percent, String format, String libDir);

    /** پیشرفت کل از ۰ تا ۱۰۰۰ */
    public static native int progress();

    public static native void reset();
}
