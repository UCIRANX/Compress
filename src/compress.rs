use anyhow::{bail, Context, Result};
use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::process::{Command, Stdio};

pub const MIN_PERCENT: u32 = 5;
pub const MAX_PERCENT: u32 = 90;
const AUDIO_KBPS: u32 = 96;
const MAX_FPS: f64 = 30.0;

/// درصد هدف را به بیت‌ریت ویدیو (kbps) تبدیل می‌کند.
pub fn video_bitrate_kbps(duration_s: f64, src_bytes: u64, percent: u32) -> u32 {
    let target_bits = src_bytes as f64 * 8.0 * percent as f64 / 100.0;
    let total_kbps = target_bits / duration_s.max(0.1) / 1000.0;
    (total_kbps as u32).saturating_sub(AUDIO_KBPS).max(40)
}

pub fn compress_video(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    duration_s: f64,
    fps: f64,
    percent: u32,
) -> Result<()> {
    let src = std::fs::metadata(input)?.len();
    let kbps = video_bitrate_kbps(duration_s, src, percent);
    encode_video(ffmpeg, input, output, kbps, duration_s, fps)
}

/// کدک‌ها ثابت‌اند: H.264 (libx264) برای mp4/mov/mkv و VP9 (libvpx-vp9) برای webm. x265 استفاده نمی‌شود.
/// فریم‌ریت بیشتر از ۳۰ به ۳۰ کاهش می‌یابد؛ فریم‌ریت کمتر دست‌نخورده می‌ماند.
pub fn encode_video(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    kbps: u32,
    duration_s: f64,
    fps: f64,
) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let (vcodec, acodec, extra): (&str, &str, Vec<&str>) = match ext {
        "mp4" | "mov" | "mkv" => ("libx264", "aac", vec!["-preset", "slow", "-pix_fmt", "yuv420p"]),
        "webm" => (
            "libvpx-vp9",
            "vorbis",
            vec!["-row-mt", "1", "-deadline", "good", "-cpu-used", "2", "-strict", "-2"],
        ),
        other => bail!("فرمت ویدیوی خروجی پشتیبانی نمی‌شود: {other}"),
    };

    let kb = format!("{kbps}k");
    let buf = format!("{}k", kbps * 2);
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-loglevel", "error", "-nostats", "-progress", "pipe:1", "-y", "-i"])
        .arg(input)
        .args(["-c:v", vcodec, "-b:v", kb.as_str(), "-maxrate", kb.as_str(), "-bufsize", buf.as_str()]);
    if fps > MAX_FPS {
        cmd.args(["-r", "30"]);
    }
    cmd.args(extra)
        .args(["-c:a", acodec, "-b:a", "96k"])
        .arg(output);
    run_with_progress(cmd, duration_s)
}

pub fn compress_image(ffmpeg: &Path, input: &Path, output: &Path, percent: u32) -> Result<()> {
    let src = std::fs::metadata(input)?.len();
    let target = src * percent as u64 / 100;
    let tmp = output.with_extension("tmp");
    let q = best_quality_under(target, |q| {
        encode_image(ffmpeg, input, &tmp, q)?;
        Ok(std::fs::metadata(&tmp)?.len())
    })?;
    encode_image(ffmpeg, input, output, q)?;
    let _ = std::fs::remove_file(&tmp);
    Ok(())
}

/// بزرگ‌ترین کیفیت (۱ تا ۱۰۰) را پیدا می‌کند که حجم خروجی از هدف بیشتر نشود.
pub fn best_quality_under(target_bytes: u64, mut size_at: impl FnMut(u32) -> Result<u64>) -> Result<u32> {
    let (mut lo, mut hi, mut best) = (1u32, 100u32, 1u32);
    while lo <= hi {
        let mid = (lo + hi) / 2;
        if size_at(mid)? <= target_bytes {
            best = mid;
            lo = mid + 1;
        } else {
            if mid == 1 {
                break;
            }
            hi = mid - 1;
        }
    }
    Ok(best)
}

fn encode_image(ffmpeg: &Path, input: &Path, output: &Path, q: u32) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-y", "-i"]).arg(input).args(["-frames:v", "1"]);
    match ext {
        "jpg" | "jpeg" => {
            let qv = (31 - q * 29 / 100).to_string();
            cmd.args(["-q:v", qv.as_str()]);
        }
        "webp" => {
            let s = q.to_string();
            cmd.args(["-quality", s.as_str()]);
        }
        "png" => {
            cmd.args(["-compression_level", "100"]);
        }
        other => bail!("فرمت تصویری خروجی پشتیبانی نمی‌شود: {other}"),
    }
    cmd.arg(output);
    run(cmd)
}

pub fn run(mut cmd: Command) -> Result<()> {
    let out = cmd.output().context("ffmpeg اجرا نشد")?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        bail!("خطای ffmpeg: {}", err.lines().last().unwrap_or("نامشخص"));
    }
    Ok(())
}

/// ffmpeg را اجرا می‌کند و از خروجی `-progress` درصد پیشرفت فایل جاری را به لایهٔ کل گزارش می‌دهد.
fn run_with_progress(mut cmd: Command, duration_s: f64) -> Result<()> {
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = cmd.spawn().context("ffmpeg اجرا نشد")?;
    let stdout = child.stdout.take().context("خروجی ffmpeg در دسترس نیست")?;
    for line in BufReader::new(stdout).lines() {
        let line = line?;
        if let Some(v) = line.strip_prefix("out_time_us=") {
            if let Ok(us) = v.trim().parse::<f64>() {
                if duration_s > 0.0 {
                    let pm = (us / 1e6 / duration_s * 1000.0).clamp(0.0, 1000.0) as u32;
                    crate::set_cur(pm);
                }
            }
        }
    }
    let mut err = String::new();
    if let Some(mut e) = child.stderr.take() {
        let _ = e.read_to_string(&mut err);
    }
    let status = child.wait()?;
    if !status.success() {
        bail!("خطای ffmpeg: {}", err.lines().last().unwrap_or("نامشخص"));
    }
    Ok(())
}
