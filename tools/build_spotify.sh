#!/bin/sh
# Сборка libsfspotify.so (Rust, librespot) для Android arm64 без cargo-ndk.
# Результат: android/app/src/main/jniLibs/arm64-v8a/libsfspotify.so
# Нет cargo или NDK: сообщение и выход 0, приложение собирается без Spotify.
# Пересборка пропускается, если .so новее всех исходников crate и Cargo.lock.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
CRATE="$ROOT/spotify"
OUT_DIR="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libsfspotify.so"
TARGET=aarch64-linux-android
API=26

export PATH="$HOME/.cargo/bin:$PATH"
if ! command -v cargo >/dev/null 2>&1; then
    echo "build_spotify: cargo не найден, libsfspotify.so не собирается (приложение без Spotify)" >&2
    exit 0
fi

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
NDK=${ANDROID_NDK_HOME:-}
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    NDK=$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -n 1 | sed 's:/$::')
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "build_spotify: Android NDK не найден ($SDK/ndk), libsfspotify.so не собирается (приложение без Spotify)" >&2
    exit 0
fi
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo "build_spotify: неизвестная ОС, пропуск" >&2; exit 0 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [ ! -x "$BIN/${TARGET}${API}-clang" ]; then
    echo "build_spotify: нет $BIN/${TARGET}${API}-clang, пропуск" >&2
    exit 0
fi

if [ -f "$OUT" ] && [ -z "$(find "$CRATE/src" "$CRATE/vendor" "$CRATE/Cargo.toml" "$CRATE/Cargo.lock" -type f -newer "$OUT" 2>/dev/null | head -n 1)" ]; then
    echo "build_spotify: libsfspotify.so актуален ($(wc -c < "$OUT" | tr -d ' ') байт), пересборка не нужна"
    exit 0
fi

if ! rustup target list --installed 2>/dev/null | grep -q "^$TARGET\$"; then
    rustup target add "$TARGET" >&2 || { echo "build_spotify: не удалось добавить target $TARGET, пропуск" >&2; exit 0; }
fi

CLANG="$BIN/${TARGET}${API}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export CXX_aarch64_linux_android="$BIN/${TARGET}${API}-clang++"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export RANLIB_aarch64_linux_android="$BIN/llvm-ranlib"
# 16 КБ-совместимые страницы (Android 15+) и без лишних зависимостей от версии libc++.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--build-id=none"

echo "build_spotify: сборка $TARGET (NDK $(basename "$NDK"))"
(cd "$CRATE" && cargo build --release --lib --target "$TARGET" --locked)

SRC="$CRATE/target/$TARGET/release/libsfspotify.so"
mkdir -p "$OUT_DIR"
cp "$SRC" "$OUT"
[ -x "$BIN/llvm-strip" ] && "$BIN/llvm-strip" --strip-unneeded "$OUT"
echo "build_spotify: $OUT, $(wc -c < "$OUT" | tr -d ' ') байт"
