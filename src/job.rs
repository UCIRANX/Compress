use crate::compress;
use crate::storage;
use anyhow::{anyhow, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Command;

const VIDEO_IN: &[&str] = &["mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi"];
const IMAGE_IN: &[&str] = &["jpg", "jpeg", "png", "webp"];

pub fn process(ffmpeg: &Path, ffprobe: &Path, input: &Path, percent: u32, choice: &str) -> Result<(PathBuf, f64)> {
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
        let fps = probe_fps(ffprobe, input);
        compress::compress_video(ffmpeg, input, &out, dur, fps, percent)
    } else {
        compress::compress_image(ffmpeg, input, &out, percent)
    };
    if result.is_err() {
        let _ = std::fs::remove_file(&out);
    }
    result?;
    let out_fps = if is_video { probe_fps(ffprobe, &out) } else { 0.0 };
    Ok((out, out_fps))
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

/// فریم‌ریت ویدیو را می‌خواند؛ اگر خوانده نشود ۰ برمی‌گرداند (یعنی تغییری داده نمی‌شود).
pub fn probe_fps(ffprobe: &Path, input: &Path) -> f64 {
    let out = match Command::new(ffprobe)
        .args(["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate", "-of", "csv=p=0"])
        .arg(input)
        .output()
    {
        Ok(o) => o,
        Err(_) => return 0.0,
    };
    let text = String::from_utf8_lossy(&out.stdout);
    let s = text.trim();
    match s.split_once('/') {
        Some((n, d)) => match (n.trim().parse::<f64>(), d.trim().parse::<f64>()) {
            (Ok(n), Ok(d)) if d > 0.0 => n / d,
            _ => 0.0,
        },
        None => s.parse::<f64>().unwrap_or(0.0),
    }
}
