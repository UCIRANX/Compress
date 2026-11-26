use crate::{compress, storage, tr};
use anyhow::{anyhow, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Command;

const VIDEO_IN: &[&str] = &["mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi"];
const IMAGE_IN: &[&str] = &["jpg", "jpeg", "png", "webp"];

/// خروجی: مسیر فایل، فریم‌ریت ورودی، فریم‌ریت خروجی، و هشدار کیفیت (اگر باشد)
pub fn process(
    ffmpeg: &Path,
    ffprobe: &Path,
    input: &Path,
    percent: u32,
    video_choice: &str,
    photo_choice: &str,
    keep: bool,
    orientation: i32,
) -> Result<(PathBuf, f64, f64, String)> {
    let name = input
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| anyhow!("{}", tr("نام فایل نامعتبر است", "Invalid file name")))?;
    let (stem, in_ext) = match name.rsplit_once('.') {
        Some((s, e)) => (s, e.to_lowercase()),
        None => (name, String::new()),
    };
    let is_video = VIDEO_IN.contains(&in_ext.as_str());
    let is_image = IMAGE_IN.contains(&in_ext.as_str());
    if !is_video && !is_image {
        return Err(anyhow!(
            "{}{}{}",
            tr("فرمت ", "Format "),
            in_ext,
            tr(" پشتیبانی نمی‌شود", " is not supported")
        ));
    }

    let dims = probe_dims_ffmpeg(ffmpeg, input)
        .ok_or_else(|| anyhow!("{}", tr("ابعاد تصویر خوانده نشد", "Could not read image dimensions")))?;
    let in_fps = if is_video { detect_fps(ffmpeg, ffprobe, input) } else { 0.0 };

    let choice = if is_video { video_choice } else { photo_choice };
    let out_ext = pick_ext(is_video, &in_ext, choice);
    let out = storage::unique_path(&format!("{stem}.{out_ext}"))?;

    let result = if is_video {
        let dur = probe_duration(ffprobe, input)?;
        let location = probe_location(ffmpeg, input);
        compress::compress_video(ffmpeg, input, &out, dur, in_fps, dims, percent, keep, location.as_deref())
    } else {
        compress::compress_image(ffmpeg, input, &out, percent, dims, keep, orientation)
    };
    if result.is_err() {
        let _ = std::fs::remove_file(&out);
    }
    let note = result?.unwrap_or_default();
    let out_fps = if is_video { detect_fps(ffmpeg, ffprobe, &out) } else { 0.0 };
    Ok((out, in_fps, out_fps, note))
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
    if is_video {
        "mp4".into()
    } else {
        "jpg".into()
    }
}

pub fn probe_duration(ffprobe: &Path, input: &Path) -> Result<f64> {
    let out = Command::new(ffprobe)
        .args(["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0"])
        .arg(input)
        .output()
        .context(tr("ffprobe اجرا نشد", "Could not run ffprobe"))?;
    let text = String::from_utf8_lossy(&out.stdout);
    text.trim()
        .parse::<f64>()
        .map_err(|_| anyhow!("{}", tr("مدت ویدیو خوانده نشد", "Could not read video duration")))
}

/// ابعاد (عرض، ارتفاع) را از خروجی `ffmpeg -i` می‌خواند.
/// فقط ضلع کوچک و حاصل‌ضرب استفاده می‌شوند، پس جابه‌جایی ابعاد روی نتیجه اثری ندارد.
pub fn probe_dims_ffmpeg(ffmpeg: &Path, input: &Path) -> Option<(u32, u32)> {
    let out = Command::new(ffmpeg).args(["-hide_banner", "-i"]).arg(input).output().ok()?;
    let text = String::from_utf8_lossy(&out.stderr);
    for line in text.lines().filter(|l| l.contains("Video:")) {
        for part in line.split(',') {
            let word = part.trim().split_whitespace().next().unwrap_or("");
            if let Some((a, b)) = word.split_once('x') {
                if let (Ok(w), Ok(h)) = (a.parse::<u32>(), b.parse::<u32>()) {
                    if w > 0 && h > 0 {
                        return Some((w, h));
                    }
                }
            }
        }
    }
    None
}

/// اول از خود ffmpeg (خط Video) فریم‌ریت را می‌خواند، و اگر نشد از ffprobe.
pub fn detect_fps(ffmpeg: &Path, ffprobe: &Path, input: &Path) -> f64 {
    let v = probe_fps_ffmpeg(ffmpeg, input);
    if v > 0.0 {
        v
    } else {
        probe_fps(ffprobe, input)
    }
}

/// `ffmpeg -i file` اطلاعات را در stderr چاپ می‌کند؛ از خط Video عدد قبل از «fps» یا «tbr» را می‌خوانیم.
pub fn probe_fps_ffmpeg(ffmpeg: &Path, input: &Path) -> f64 {
    let out = match Command::new(ffmpeg).args(["-hide_banner", "-i"]).arg(input).output() {
        Ok(o) => o,
        Err(_) => return 0.0,
    };
    let text = String::from_utf8_lossy(&out.stderr);
    for line in text.lines() {
        if !line.contains("Video:") {
            continue;
        }
        for key in [" fps", " tbr"] {
            if let Some(pos) = line.find(key) {
                let before = &line[..pos];
                let tok = before
                    .rsplit(|c: char| c == ',' || c == ' ')
                    .find(|t| !t.is_empty());
                if let Some(tok) = tok {
                    if let Ok(v) = tok.parse::<f64>() {
                        if v > 0.0 {
                            return v;
                        }
                    }
                }
            }
        }
    }
    0.0
}

/// فریم‌ریت از ffprobe (r_frame_rate، سپس avg_frame_rate). اگر نشد ۰.
pub fn probe_fps(ffprobe: &Path, input: &Path) -> f64 {
    for entry in ["r_frame_rate", "avg_frame_rate"] {
        let v = probe_stream_rate(ffprobe, input, entry);
        if v > 0.0 {
            return v;
        }
    }
    0.0
}

fn probe_stream_rate(ffprobe: &Path, input: &Path, entry: &str) -> f64 {
    let show = format!("stream={entry}");
    let out = match Command::new(ffprobe)
        .args(["-v", "error", "-select_streams", "v:0", "-show_entries", show.as_str(), "-of", "csv=p=0"])
        .arg(input)
        .output()
    {
        Ok(o) => o,
        Err(_) => return 0.0,
    };
    let text = String::from_utf8_lossy(&out.stdout);
    let s = text.lines().next().unwrap_or("").trim();
    match s.split_once('/') {
        Some((n, d)) => match (n.trim().parse::<f64>(), d.trim().parse::<f64>()) {
            (Ok(n), Ok(d)) if d > 0.0 => n / d,
            _ => 0.0,
        },
        None => s.parse::<f64>().unwrap_or(0.0),
    }
}

/// موقعیت مکانی ویدیو را از متادیتای ffmpeg (خط location) می‌خواند؛ اگر نبود None.
pub fn probe_location(ffmpeg: &Path, input: &Path) -> Option<String> {
    let out = Command::new(ffmpeg).args(["-hide_banner", "-i"]).arg(input).output().ok()?;
    let text = String::from_utf8_lossy(&out.stderr);
    for line in text.lines() {
        let t = line.trim();
        if t.starts_with("location") || t.starts_with("com.apple.quicktime.location") {
            if let Some((_, v)) = t.split_once(':') {
                let v = v.trim();
                if !v.is_empty() {
                    return Some(v.to_string());
                }
            }
        }
    }
    None
}
