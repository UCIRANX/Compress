use crate::compress;
use crate::storage;
use anyhow::{anyhow, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Command;

const VIDEO_IN: &[&str] = &["mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi"];
const IMAGE_IN: &[&str] = &["jpg", "jpeg", "png", "webp"];

pub fn process(ffmpeg: &Path, ffprobe: &Path, input: &Path, percent: u32, choice: &str) -> Result<PathBuf> {
    let name = input
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| anyhow!("نام فایل نامعتبر است"))?;
    let (stem, in_ext) = match name.rsplit_once('.') {
        Some((s, e)) => (s, e.to_lowercase()),
        None => (name, String::new()),
    };
    let is_video = VIDEO_IN.contains(&in_ext.as_str());
    let is_image = IMAGE_IN.contains(&in_ext.as_str());
    if !is_video && !is_image {
        return Err(anyhow!("فرمت {in_ext} پشتیبانی نمی‌شود"));
    }

    let out_ext = pick_ext(is_video, &in_ext, choice);
    let out = storage::unique_path(&format!("{stem}.{out_ext}"))?;

    let result = if is_video {
        let dur = probe_duration(ffprobe, input)?;
        compress::compress_video(ffmpeg, input, &out, dur, percent)
    } else {
        compress::compress_image(ffmpeg, input, &out, percent)
    };
    if result.is_err() {
        let _ = std::fs::remove_file(&out);
    }
    result?;
    Ok(out)
}

/// فرمت خروجی: اگر انتخاب کاربر برای این نوع فایل معتبر باشد همان، وگرنه فرمت اصلی.
fn pick_ext(is_video: bool, in_ext: &str, choice: &str) -> String {
    let norm = if in_ext == "jpeg" { "jpg" } else { in_ext };
    let writable: &[&str] = if is_video {
        &["mp4", "mov", "mkv", "webm"]
    } else {
        &["jpg", "png", "webp"]
    };
    if choice != "Original" && writable.contains(&choice) {
        return choice.to_string();
    }
    if writable.contains(&norm) {
        return norm.to_string();
    }
    if is_video { "mp4".into() } else { "jpg".into() }
}

pub fn probe_duration(ffprobe: &Path, input: &Path) -> Result<f64> {
    let out = Command::new(ffprobe)
        .args(["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0"])
        .arg(input)
        .output()
        .context("ffprobe اجرا نشد")?;
    let text = String::from_utf8_lossy(&out.stdout);
    text.trim()
        .parse::<f64>()
        .map_err(|_| anyhow!("مدت ویدیو خوانده نشد"))
}
