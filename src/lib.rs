pub mod compress;
pub mod job;
pub mod storage;

use anyhow::Result;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};

// وضعیت پیشرفت: تعداد کل فایل‌ها، فایل‌های تمام‌شده، و درصد (هزارم) فایل جاری
static TOTAL: AtomicU32 = AtomicU32::new(0);
static DONE: AtomicU32 = AtomicU32::new(0);
static CUR_PM: AtomicU32 = AtomicU32::new(0);

pub fn set_cur(pm: u32) {
    CUR_PM.store(pm.min(1000), Ordering::Relaxed);
}

fn run(
    env: &mut JNIEnv,
    files: &JObjectArray,
    percent: jint,
    video_fmt: &str,
    photo_fmt: &str,
    lib_dir: &str,
    keep: bool,
) -> Result<String> {
    let count = env.get_array_length(files)?;
    let mut paths = Vec::with_capacity(count as usize);
    for i in 0..count {
        let obj = env.get_object_array_element(files, i)?;
        let js = JString::from(obj);
        let s: String = env.get_string(&js)?.into();
        paths.push(s);
    }

    let percent = (percent as u32).clamp(compress::MIN_PERCENT, compress::MAX_PERCENT);
    let ffmpeg = PathBuf::from(format!("{lib_dir}/libffmpeg.so"));
    let ffprobe = PathBuf::from(format!("{lib_dir}/libffprobe.so"));

    let total = paths.len();
    TOTAL.store(total as u32, Ordering::Relaxed);
    DONE.store(0, Ordering::Relaxed);
    CUR_PM.store(0, Ordering::Relaxed);

    let mut ok = 0usize;
    let mut errs: Vec<String> = Vec::new();
    let mut reports: Vec<String> = Vec::new();
    for p in paths {
        let input = PathBuf::from(&p);
        let name = input
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        match job::process(&ffmpeg, &ffprobe, &input, percent, video_fmt, photo_fmt, keep) {
            Ok((_, in_fps, out_fps, note)) => {
                ok += 1;
                if !note.is_empty() {
                    errs.push(format!("هشدار {name}: {note}"));
                }
                // فقط برای ویدیو (فریم‌ریت ورودی > ۰) گزارش و بررسی فریم‌ریت انجام می‌شود
                if in_fps > 0.0 {
                    reports.push(format!("{name}: {in_fps:.0} → {out_fps:.0}"));
                    if out_fps <= 0.0 {
                        errs.push(format!("هشدار {name}: فریم‌ریت خروجی خوانده نشد"));
                    } else if out_fps > 30.5 {
                        errs.push(format!("هشدار {name}: فریم‌ریت خروجی {out_fps:.0} است (انتظار ۳۰)"));
                    }
                }
            }
            Err(e) => errs.push(format!("{name}: {e:#}")),
        }
        let _ = std::fs::remove_file(&input);
        DONE.fetch_add(1, Ordering::Relaxed);
        CUR_PM.store(0, Ordering::Relaxed);
    }

    let mut msg = format!("{ok} از {total} فایل در DCIM/Compress ذخیره شد.");
    if !errs.is_empty() {
        msg.push_str("\nهشدارها و خطاها:\n");
        msg.push_str(&errs.join("\n"));
    }
    if !reports.is_empty() {
        msg.push_str("\nفریم‌ریت (ورودی → خروجی):\n");
        msg.push_str(&reports.join("\n"));
    }
    Ok(msg)
}

#[no_mangle]
pub extern "system" fn Java_com_example_compressor_NativeCore_processFiles(
    mut env: JNIEnv,
    _class: JClass,
    files: JObjectArray,
    percent: jint,
    video_format: JString,
    photo_format: JString,
    lib_dir: JString,
    keep: jboolean,
) -> jstring {
    let result = (|| -> Result<String> {
        let vf: String = env.get_string(&video_format)?.into();
        let pf: String = env.get_string(&photo_format)?.into();
        let dir: String = env.get_string(&lib_dir)?.into();
        run(&mut env, &files, percent, &vf, &pf, &dir, keep != 0)
    })();
    let msg = result.unwrap_or_else(|e| format!("خطا: {e:#}"));
    match env.new_string(msg) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// درصد کل پیشرفت به‌صورت هزارم (۰ تا ۱۰۰۰)
#[no_mangle]
pub extern "system" fn Java_com_example_compressor_NativeCore_progress(_env: JNIEnv, _class: JClass) -> jint {
    let total = TOTAL.load(Ordering::Relaxed);
    if total == 0 {
        return 0;
    }
    let done = DONE.load(Ordering::Relaxed).min(total);
    let cur = CUR_PM.load(Ordering::Relaxed).min(1000);
    let pm = ((done * 1000 + cur) / total).min(1000);
    pm as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_compressor_NativeCore_reset(_env: JNIEnv, _class: JClass) {
    TOTAL.store(0, Ordering::Relaxed);
    DONE.store(0, Ordering::Relaxed);
    CUR_PM.store(0, Ordering::Relaxed);
}
