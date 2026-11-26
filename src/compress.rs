use crate::tr;
use anyhow::{bail, Context, Result};
use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::process::{Command, Stdio};

pub const MIN_PERCENT: u32 = 5;
pub const MAX_PERCENT: u32 = 90;
/// کمترین ضلع کوچک‌تر برای ویدیو
pub const MIN_SHORT_SIDE_VIDEO: u32 = 360;
/// کمترین ضلع کوچک‌تر برای عکس
pub const MIN_SHORT_SIDE_IMAGE: u32 = 480;
/// کمترین کیفیت قابل قبول برای JPEG/WebP
pub const MIN_IMAGE_Q: u32 = 40;
const AUDIO_KBPS: u32 = 96;
const MAX_FPS: f64 = 30.0;

/// عدد را به نزدیک‌ترین زوج (رو به پایین) تبدیل می‌کند؛ کدک H.264 ابعاد زوج می‌خواهد.
pub fn even(v: f64) -> u32 {
    let r = v.round() as u32;
    (r & !1).max(2)
}

/// ضریب مقیاس: مساحت ≈ درصد، ضلع کوچک زیر min_short نمی‌رود (اگر اصل بزرگ‌تر باشد)،
/// و هیچ‌وقت بزرگ‌تر از اصل نمی‌شود. فقط به ضلع کوچک وابسته است.
pub fn scale_factor(short: u32, percent: u32, keep: bool, min_short: u32) -> f64 {
    if keep {
        return 1.0;
    }
    let s = (percent as f64 / 100.0).sqrt();
    let short_f = short.max(1) as f64;
    if short_f * s < min_short as f64 {
        (min_short as f64 / short_f).min(1.0).max(s)
    } else {
        s
    }
}

/// فیلتر scale نسبت به ابعاد واقعی فریم (بعد از چرخش) کار می‌کند.
pub fn scale_filter(k: f64) -> String {
    format!("scale=trunc(iw*{k:.6}/2)*2:trunc(ih*{k:.6}/2)*2")
}

/// فیلتر چرخش بر اساس برچسب EXIF Orientation (۱ تا ۸). برای ۱ چیزی اعمال نمی‌شود.
pub fn orient_filter(o: i32) -> Option<&'static str> {
    match o {
        2 => Some("hflip"),
        3 => Some("hflip,vflip"),
        4 => Some("vflip"),
        5 => Some("transpose=0"),
        6 => Some("transpose=1"),
        7 => Some("transpose=3"),
        8 => Some("transpose=2"),
        _ => None,
    }
}

/// درصد هدف را به بیت‌ریت ویدیو (kbps) تبدیل می‌کند.
pub fn video_bitrate_kbps(duration_s: f64, src_bytes: u64, percent: u32) -> u32 {
    let target_bits = src_bytes as f64 * 8.0 * percent as f64 / 100.0;
    let total_kbps = target_bits / duration_s.max(0.1) / 1000.0;
    (total_kbps as u32).saturating_sub(AUDIO_KBPS).max(40)
}

/// بیت به ازای هر پیکسل در هر فریم
pub fn bits_per_pixel(kbps: u32, w: u32, h: u32, fps: f64) -> f64 {
    kbps as f64 * 1000.0 / (w as f64 * h as f64 * fps.max(1.0))
}

fn bpp_floor(ext: &str) -> f64 {
    if ext == "webm" {
        0.03
    } else {
        0.04
    }
}

/// برمی‌گرداند: Some(هشدار) اگر کیفیت زیر حد باشد
pub fn compress_video(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    duration_s: f64,
    fps: f64,
    dims: (u32, u32),
    percent: u32,
    keep: bool,
) -> Result<Option<String>> {
    let (w, h) = dims;
    let k = scale_factor(w.min(h), percent, keep, MIN_SHORT_SIDE_VIDEO);
    let (ow, oh) = (even(w as f64 * k), even(h as f64 * k));
    let src = std::fs::metadata(input)?.len();
    let kbps = video_bitrate_kbps(duration_s, src, percent);
    let out_fps = if fps <= 0.0 { MAX_FPS } else { fps.min(MAX_FPS) };
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let bpp = bits_per_pixel(kbps, ow, oh, out_fps);
    let warn = if bpp < bpp_floor(ext) {
        Some(format!(
            "{} (bpp {bpp:.3}){}",
            tr("کیفیت کم است", "Low quality"),
            tr(" — حجم هدف برای این ابعاد کم است", " — target size is small for this resolution")
        ))
    } else {
        None
    };
    encode_video(ffmpeg, input, output, kbps, duration_s, fps, k)?;
    Ok(warn)
}

/// کدک‌ها ثابت‌اند: H.264 (libx264) برای mp4/mov/mkv و VP9 (libvpx-vp9) برای webm. x265 استفاده نمی‌شود.
/// چرخش ویدیو توسط ffmpeg (autorotate) یک‌بار روی پیکسل‌ها اعمال می‌شود و برچسب چرخش در خروجی نمی‌ماند.
/// متادیتای کانتینر حفظ می‌شود.
pub fn encode_video(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    kbps: u32,
    duration_s: f64,
    fps: f64,
    k: f64,
) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let (vcodec, acodec, extra): (&str, &str, Vec<&str>) = match ext {
        "mp4" | "mov" | "mkv" => ("libx264", "aac", vec!["-preset", "slow", "-pix_fmt", "yuv420p"]),
        "webm" => (
            "libvpx-vp9",
            "vorbis",
            vec!["-row-mt", "1", "-deadline", "good", "-cpu-used", "2", "-strict", "-2"],
        ),
        other => bail!("{}{other}", tr("فرمت ویدیوی خروجی پشتیبانی نمی‌شود: ", "Unsupported video output format: ")),
    };

    let mut vf: Vec<String> = Vec::new();
    if fps > MAX_FPS {
        vf.push("fps=30".to_string());
    }
    vf.push(scale_filter(k));
    let vf_arg = vf.join(",");

    let kb = format!("{kbps}k");
    let buf = format!("{}k", kbps * 2);
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-loglevel", "error", "-nostats", "-progress", "pipe:1", "-y", "-i"])
        .arg(input)
        .args(["-map_metadata", "0", "-metadata:s:v:0", "rotate=0"])
        .args(["-vf", vf_arg.as_str()])
        .args(["-c:v", vcodec, "-b:v", kb.as_str(), "-maxrate", kb.as_str(), "-bufsize", buf.as_str()])
        .args(extra)
        .args(["-c:a", acodec, "-b:a", "96k"])
        .arg(output);
    run_with_progress(cmd, duration_s)
}

/// برمی‌گرداند: Some(هشدار) اگر کیفیت حتی با حداقل وضوح زیر حد باشد.
/// چرخش عکس با orientation (EXIF) اعمال می‌شود و متادیتا جدا از این مسیر منتقل می‌شود.
pub fn compress_image(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    percent: u32,
    dims: (u32, u32),
    keep: bool,
    orientation: i32,
) -> Result<Option<String>> {
    let (w, h) = dims;
    let short = w.min(h).max(1);
    let mut k = scale_factor(short, percent, keep, MIN_SHORT_SIDE_IMAGE);
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();

    // PNG کیفیت قابل تنظیم ندارد؛ فقط ابعاد و چرخش تغییر می‌کند
    if ext == "png" {
        encode_image(ffmpeg, input, output, 100, k, orientation)?;
        return Ok(None);
    }

    let src = std::fs::metadata(input)?.len();
    let target = src * percent as u64 / 100;
    // پسوند واقعی را نگه می‌داریم چون ffmpeg از روی پسوند فرمت را تشخیص می‌دهد
    let tmp = output.with_extension(format!("tmp.{ext}"));
    loop {
        let kk = k;
        let q = best_quality_under(target, |q| {
            encode_image(ffmpeg, input, &tmp, q, kk, orientation)?;
            Ok(std::fs::metadata(&tmp)?.len())
        })?;
        let cur_short = short as f64 * k;
        let can_shrink = !keep && cur_short > MIN_SHORT_SIDE_IMAGE as f64 + 0.5;
        if q >= MIN_IMAGE_Q || !can_shrink {
            encode_image(ffmpeg, input, output, q, k, orientation)?;
            let _ = std::fs::remove_file(&tmp);
            return Ok(if q < MIN_IMAGE_Q {
                Some(format!(
                    "{} (q {q}){}",
                    tr("کیفیت کم است", "Low quality"),
                    tr(" — حجم هدف برای این ابعاد کم است", " — target size is small for this resolution")
                ))
            } else {
                None
            });
        }
        // کیفیت زیر حد است و هنوز می‌شود کمی کوچک‌تر کرد؛ تا کف ۴۸۰ پیکسل
        k = (k * 0.9).max(MIN_SHORT_SIDE_IMAGE as f64 / short as f64);
    }
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

fn encode_image(ffmpeg: &Path, input: &Path, output: &Path, q: u32, k: f64, orientation: i32) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    // چرخش را خودمان اعمال می‌کنیم؛ autorotate را خاموش می‌کنیم تا دو بار چرخش ندهیم
    let mut parts: Vec<String> = Vec::new();
    if let Some(f) = orient_filter(orientation) {
        parts.push(f.to_string());
    }
    parts.push(scale_filter(k));
    let vf = parts.join(",");

    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-noautorotate", "-y", "-i"])
        .arg(input)
        .args(["-frames:v", "1", "-vf", vf.as_str()]);
    match ext {
        "jpg" | "jpeg" => {
            // mjpeg: q:v بین 2 (بهترین) و 31 (بدترین)
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
        other => bail!("{}{other}", tr("فرمت تصویری خروجی پشتیبانی نمی‌شود: ", "Unsupported image output format: ")),
    }
    cmd.arg(output);
    run(cmd)
}

pub fn run(mut cmd: Command) -> Result<()> {
    let out = cmd.output().context(tr("ffmpeg اجرا نشد", "Could not run ffmpeg"))?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        bail!("{}{}", tr("خطای ffmpeg: ", "ffmpeg error: "), err.lines().last().unwrap_or(""));
    }
    Ok(())
}

/// ffmpeg را اجرا می‌کند و از خروجی `-progress` درصد پیشرفت فایل جاری را گزارش می‌دهد.
fn run_with_progress(mut cmd: Command, duration_s: f64) -> Result<()> {
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = cmd.spawn().context(tr("ffmpeg اجرا نشد", "Could not run ffmpeg"))?;
    let stdout = child
        .stdout
        .take()
        .context(tr("خروجی ffmpeg در دسترس نیست", "ffmpeg output is not available"))?;
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
        bail!("{}{}", tr("خطای ffmpeg: ", "ffmpeg error: "), err.lines().last().unwrap_or(""));
    }
    Ok(())
}
