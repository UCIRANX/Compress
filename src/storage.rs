use crate::tr;
use anyhow::Result;
use std::path::PathBuf;

/// خروجی‌ها در Pictures/Compress ذخیره می‌شوند
pub const OUT_DIR: &str = "/storage/emulated/0/Pictures/Compress";

/// مسیر خروجی با همان نام فایل اصلی؛ اگر تکراری بود پسوند عددی می‌گیرد: IMG_1 (2).jpg
pub fn unique_path(name: &str) -> Result<PathBuf> {
    let dir = PathBuf::from(OUT_DIR);
    std::fs::create_dir_all(&dir).map_err(|e| {
        anyhow::anyhow!("{}{e}", tr("ساخت پوشه خروجی ناموفق بود: ", "Could not create output folder: "))
    })?;
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) => (s.to_string(), format!(".{e}")),
        None => (name.to_string(), String::new()),
    };
    let mut path = dir.join(name);
    let mut n = 2;
    while path.exists() {
        path = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    Ok(path)
}
