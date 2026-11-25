pub mod compress;
pub mod job;
pub mod storage;

use anyhow::Result;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::path::PathBuf;

fn run(env: &mut JNIEnv, files: &JObjectArray, percent: jint, format: &str, lib_dir: &str) -> Result<String> {
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
    let mut ok = 0usize;
    let mut errs: Vec<String> = Vec::new();
    for p in paths {
        let input = PathBuf::from(&p);
        let name = input
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        match job::process(&ffmpeg, &ffprobe, &input, percent, format) {
            Ok(_) => ok += 1,
            Err(e) => errs.push(format!("{name}: {e:#}")),
        }
        let _ = std::fs::remove_file(&input);
    }

    let mut msg = format!("{ok} از {total} فایل در DCIM/Compress ذخیره شد.");
    if !errs.is_empty() {
        msg.push_str("\nخطاها:\n");
        msg.push_str(&errs.join("\n"));
    }
    Ok(msg)
}

#[no_mangle]
pub extern "system" fn Java_com_example_compressor_NativeCore_processFiles(
    mut env: JNIEnv,
    _class: JClass,
    files: JObjectArray,
    percent: jint,
    format: JString,
    lib_dir: JString,
) -> jstring {
    let result = (|| -> Result<String> {
        let fmt: String = env.get_string(&format)?.into();
        let dir: String = env.get_string(&lib_dir)?.into();
        run(&mut env, &files, percent, &fmt, &dir)
    })();
    let msg = result.unwrap_or_else(|e| format!("خطا: {e:#}"));
    match env.new_string(msg) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
