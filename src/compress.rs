use anyhow::{bail, Context, Result};
use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::process::{Command, Stdio};

pub const MIN_PERCENT: u32 = 5;
pub const MAX_PERCENT: u32 = 90;
/// کمترین ضلع کوچک‌تر خروجی (اگر اصلی از این کوچک‌تر نباشد)
pub const MIN_SHORT_SIDE: u32 = 480;
/// کمترین کیفیت قابل قبول برای JPEG/WebP
pub const MIN_IMAGE_Q: u32 = 40;
const AUDIO_KBPS: u32 = 96;
const MAX_FPS: f64 = 30.0;

/// عدد را به نزدیک‌ترین زوج (رو به پایین) تبدیل می‌کند؛ کدک H.264 ابعاد زوج می‌خواهد.
pub fn even(v: f64) -> u32 {
    let r = v.round() as u32;
    (r & !1).max(2)
}

/// ابعاد خروجی: مساحت متناسب با درصد (یعنی هر ضلع در sqrt(درصد) ضرب می‌شود)،
/// نسبت تصویر حفظ می‌شود، ابعاد زوج‌اند، و ضلع کوچک‌تر زیر MIN_SHORT_SIDE نمی‌رود.
/// هیچ‌وقت بزرگ‌تر از اصل نمی‌شود.
pub fn scaled_dims(w: u32, h: u32, percent: u32) -> (u32, u32) {
    let s = (percent as f64 / 100.0).sqrt();
    let short = w.min(h) as f64;
    let k = if short * s < MIN_SHORT_SIDE as f64 {
        (MIN_SHORT_SIDE as f64 / short).min(1.0).max(s)
    } else {
        s
    };
    (even(w as f64 * k), even(h as f64 * k))
}

/// اگر کاربر «اصلی» انتخاب کرده باشد ابعاد دست‌نخورده می‌ماند (فقط زوج می‌شود)،
/// وگرنه طبق درصد و قوانین scaled_dims تغییر می‌کند.
pub fn target_dims(w: u32, h: u32, percent: u32, keep: bool) -> (u32, u32) {
    if keep {
        (even(w as f64), even(h as f64))
    } else {
        scaled_dims(w, h, percent)
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
    let (ow, oh) = target_dims(w, h, percent, keep);
    let src = std::fs::metadata(input)?.len();
    let kbps = video_bitrate_kbps(duration_s, src, percent);
    let out_fps = if fps <= 0.0 {
        MAX_FPS
    } else {
        fps.min(MAX_FPS)
    };
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let bpp = bits_per_pixel(kbps, ow, oh, out_fps);
    let warn = if bpp < bpp_floor(ext) {
        Some(format!("کیفیت کم است (bpp {bpp:.3}) — حجم هدف برای این ابعاد کم است"))
    } else {
        None
    };
    encode_video(ffmpeg, input, output, kbps, duration_s, fps, ow, oh)?;
    Ok(warn)
}

/// کدک‌ها ثابت‌اند: H.264 (libx264) برای mp4/mov/mkv و VP9 (libvpx-vp9) برای webm. x265 استفاده نمی‌شود.
/// فریم‌ریت بیشتر از ۳۰ به ۳۰ کاهش می‌یابد؛ ابعاد با فیلتر scale تنظیم می‌شوند.
pub fn encode_video(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    kbps: u32,
    duration_s: f64,
    fps: f64,
    ow: u32,
    oh: u32,
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

    let mut vf: Vec<String> = Vec::new();
    if fps > MAX_FPS {
        vf.push("fps=30".to_string());
    }
    vf.push(format!("scale={ow}:{oh}"));
    let vf_arg = vf.join(",");

    let kb = format!("{kbps}k");
    let buf = format!("{}k", kbps * 2);
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-loglevel", "error", "-nostats", "-progress", "pipe:1", "-y", "-i"])
        .arg(input)
        .args(["-vf", vf_arg.as_str()])
        .args(["-c:v", vcodec, "-b:v", kb.as_str(), "-maxrate", kb.as_str(), "-bufsize", buf.as_str()])
        .args(extra)
        .args(["-c:a", acodec, "-b:a", "96k"])
        .arg(output);
    run_with_progress(cmd, duration_s)
}

/// برمی‌گرداند: Some(هشدار) اگر کیفیت حتی با حداقل وضوح زیر حد باشد
pub fn compress_image(
    ffmpeg: &Path,
    input: &Path,
    output: &Path,
    percent: u32,
    dims: (u32, u32),
    keep: bool,
) -> Result<Option<String>> {
    let (mut cw, mut ch) = target_dims(dims.0, dims.1, percent, keep);
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();

    // PNG کیفیت قابل تنظیم ندارد؛ فقط ابعاد تغییر می‌کند
    if ext == "png" {
        encode_image(ffmpeg, input, output, 100, cw, ch)?;
        return Ok(None);
    }

    let src = std::fs::metadata(input)?.len();
    let target = src * percent as u64 / 100;
    // پسوند واقعی را نگه می‌داریم چون ffmpeg از روی پسوند فرمت را تشخیص می‌دهد
    let tmp = output.with_extension(format!("tmp.{ext}"));
    loop {
        let q = best_quality_under(target, |q| {
            encode_image(ffmpeg, input, &tmp, q, cw, ch)?;
            Ok(std::fs::metadata(&tmp)?.len())
        })?;
        let can_shrink = !keep && cw.min(ch) > MIN_SHORT_SIDE;
        if q >= MIN_IMAGE_Q || !can_shrink {
            encode_image(ffmpeg, input, output, q, cw, ch)?;
            let _ = std::fs::remove_file(&tmp);
            let warn = if q < MIN_IMAGE_Q {
                Some(format!("کیفیت کم است (q {q}) — حجم هدف برای این ابعاد کم است"))
            } else {
                None
            };
            return Ok(warn);
        }
        // کیفیت زیر حد است و هنوز می‌توان وضوح را کم کرد؛ ۱۰٪ کوچک‌تر و دوباره
        cw = even(cw as f64 * 0.9);
        ch = even(ch as f64 * 0.9);
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

fn encode_image(ffmpeg: &Path, input: &Path, output: &Path, q: u32, w: u32, h: u32) -> Result<()> {
    let ext = output.extension().and_then(|e| e.to_str()).unwrap_or_default();
    let vf = format!("scale={w}:{h}");
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-y", "-i"])
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
