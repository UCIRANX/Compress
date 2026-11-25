slint::include_modules!();

pub mod android;
pub mod compress;
pub mod job;
pub mod storage;

use std::path::{Path, PathBuf};
use std::time::Duration;

/// ترتیب باید با ComboBox در ui/app.slint یکی باشد
const FORMATS: [&str; 6] = ["Original", "mp4", "webm", "jpg", "png", "webp"];

fn set_status(weak: &slint::Weak<MainWindow>, msg: String) {
    let w = weak.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = w.upgrade() {
            ui.set_status(msg.into());
        }
    });
}

fn start_worker(weak: slint::Weak<MainWindow>, files: Vec<String>, percent: u32, choice: String, lib_dir: String) {
    std::thread::spawn(move || {
        let ffmpeg = PathBuf::from(format!("{lib_dir}/libffmpeg.so"));
        let ffprobe = PathBuf::from(format!("{lib_dir}/libffprobe.so"));
        let total = files.len();
        let mut ok = 0usize;
        let mut errs: Vec<String> = Vec::new();

        for (i, f) in files.iter().enumerate() {
            let input = PathBuf::from(f);
            let name = input
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            set_status(&weak, format!("در حال پردازش {}/{}: {}", i + 1, total, name));

            match job::process(&ffmpeg, &ffprobe, &input, percent, &choice) {
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
        set_status(&weak, msg);
    });
}

#[no_mangle]
fn android_main(app: slint::android::AndroidApp) {
    android::init(&app);
    slint::android::init(app).expect("android init");

    let ui = MainWindow::new().expect("ui");
    let weak = ui.as_weak();

    ui.on_pick_and_compress({
        let weak = weak.clone();
        move || match android::start_pick() {
            Ok(()) => set_status(&weak, "یک یا چند فایل را انتخاب کنید".into()),
            Err(e) => set_status(&weak, format!("خطا در باز کردن انتخابگر: {e:#}")),
        }
    });

    // هر ۳۰۰ میلی‌ثانیه بررسی می‌کنیم که کاربر فایل‌ها را انتخاب کرده یا نه
    let timer = slint::Timer::default();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(300), {
        let weak = weak.clone();
        move || {
            let picked = match android::take_picked() {
                Ok(Some(files)) => files,
                Ok(None) => return,
                Err(e) => {
                    set_status(&weak, format!("خطا: {e:#}"));
                    return;
                }
            };
            let Some(ui) = weak.upgrade() else { return };
            if picked.is_empty() {
                ui.set_status("فایلی انتخاب نشد".into());
                return;
            }
            let percent = (ui.get_percent() as u32).clamp(compress::MIN_PERCENT, compress::MAX_PERCENT);
            let choice = FORMATS[(ui.get_format_index().max(0) as usize).min(FORMATS.len() - 1)].to_string();
            match android::native_lib_dir() {
                Ok(dir) => start_worker(weak.clone(), picked, percent, choice, dir),
                Err(e) => ui.set_status(format!("خطا: {e:#}").into()),
            }
        }
    });

    ui.run().expect("run");
}
