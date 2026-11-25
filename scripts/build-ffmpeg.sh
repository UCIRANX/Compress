#!/usr/bin/env bash
# ساخت ffmpeg و ffprobe برای اندروید با libx264 و libvpx (بدون x265)
set -euo pipefail

NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME تنظیم نشده}"
API=26
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/src"
OUT="$ROOT/third_party/out"
BUILD_ROOT="$ROOT/third_party/build"
JOBS="$(nproc)"
mkdir -p "$SRC" "$OUT" "$BUILD_ROOT"

fetch() { # url dir [ref]
  [ -d "$2" ] && return 0
  if [ -n "${3:-}" ]; then
    git clone --depth 1 --branch "$3" "$1" "$2" 2>/dev/null || { git clone "$1" "$2" && git -C "$2" checkout "$3"; }
  else
    git clone --depth 1 "$1" "$2"
  fi
}

fetch https://code.videolan.org/videolan/x264.git "$SRC/x264"
fetch https://github.com/webmproject/libvpx.git "$SRC/libvpx" v1.14.1
fetch https://github.com/FFmpeg/FFmpeg.git "$SRC/ffmpeg" n7.1

build_abi() {
  local ABI=$1 TRIPLE=$2 ARCH=$3 CPU=$4 VPX_TGT=$5 X264_HOST=$6 X264_EXTRA=$7 VPX_EXTRA=${8:-}
  local CC="$TC/bin/${TRIPLE}${API}-clang"
  local CXX="$TC/bin/${TRIPLE}${API}-clang++"
  local AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib"
  local STRIP="$TC/bin/llvm-strip" NM="$TC/bin/llvm-nm"
  local PREFIX="$BUILD_ROOT/$ABI/deps"
  local B="$BUILD_ROOT/$ABI"
  mkdir -p "$PREFIX" "$B"

  echo "== x264 ($ABI)"
  rm -rf "$B/x264" && cp -r "$SRC/x264" "$B/x264" && cd "$B/x264"
  # متغیرهای کامپایلر باید env باشند، نه آرگومان configure
  CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
    ./configure --host="$X264_HOST" --enable-static --enable-pic --disable-cli \
    --prefix="$PREFIX" $X264_EXTRA
  make -j"$JOBS"
  make install

  echo "== libvpx ($ABI)"
  rm -rf "$B/libvpx" "$B/libvpx-build"
  cp -r "$SRC/libvpx" "$B/libvpx" && mkdir -p "$B/libvpx-build" && cd "$B/libvpx-build"
  # libvpx 1.14 گزینهٔ --sdk-path ندارد؛ کامپایلر باید از طریق env داده شود
    CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" NM="$NM" \
    "$B/libvpx/configure" --target="$VPX_TGT" --prefix="$PREFIX" $VPX_EXTRA \
    --enable-vp9 --enable-pic --enable-static --disable-shared \
    --disable-examples --disable-tools --disable-docs --disable-unit-tests
  make -j"$JOBS"
  make install

  echo "== ffmpeg ($ABI)"
  rm -rf "$B/ffmpeg" && mkdir -p "$B/ffmpeg" && cd "$B/ffmpeg"
  # ffmpeg کتابخانه‌ها را با pkg-config پیدا می‌کند؛ مسیر .pc ها را می‌دهیم
  PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
  "$SRC/ffmpeg/configure" \
    --prefix="$PREFIX" --enable-cross-compile --target-os=android \
    --arch="$ARCH" --cpu="$CPU" --sysroot="$TC/sysroot" \
    --cc="$CC" --cxx="$CXX" --ld="$CC" --ar="$AR" --ranlib="$RANLIB" \
    --strip="$STRIP" --nm="$NM" \
    --enable-gpl --enable-static --disable-shared --enable-pic --enable-small \
    --enable-libx264 --enable-libvpx \
    --disable-doc --disable-debug --disable-ffplay --disable-network --disable-avdevice \
    --extra-cflags="-I$PREFIX/include -fPIC -O3" \
    --extra-ldflags="-L$PREFIX/lib" --extra-libs="-lm"
  make -j"$JOBS" ffmpeg ffprobe

  mkdir -p "$OUT/$ABI/bin"
  cp ffmpeg ffprobe "$OUT/$ABI/bin/"
  "$STRIP" "$OUT/$ABI/bin/ffmpeg" "$OUT/$ABI/bin/ffprobe" || true
  cd "$ROOT"
}

build_abi arm64-v8a aarch64-linux-android aarch64 armv8-a arm64-android-gcc aarch64-linux-android ""
build_abi armeabi-v7a armv7a-linux-androideabi arm armv7-a armv7-android-gcc arm-linux-androideabi "--disable-asm" "--disable-neon-asm"

echo "ffmpeg و ffprobe در third_party/out ساخته شدند"
