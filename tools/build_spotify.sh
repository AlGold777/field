#!/bin/sh
# Сборка libsfspotify.so (Rust, librespot) для Android arm64 без cargo-ndk.
# Результат: android/app/src/main/jniLibs/arm64-v8a/libsfspotify.so
# Нет cargo или NDK: сообщение и выход 0, приложение собирается без Spotify. Устаревший .so при этом удаляется:
# библиотека от других исходников хуже, чем её отсутствие.
# Ошибка компиляции: выход с ненулевым кодом, сборка приложения останавливается.
# Пересборка пропускается, если .so новее всех исходников crate и Cargo.lock.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
CRATE="$ROOT/spotify"
OUT_DIR="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libsfspotify.so"
TARGET=aarch64-linux-android
API=26

if [ -f "$OUT" ] && [ -z "$(find "$CRATE/src" "$CRATE/vendor" "$CRATE/Cargo.toml" "$CRATE/Cargo.lock" -type f -newer "$OUT" 2>/dev/null | head -n 1)" ]; then
    echo "build_spotify: libsfspotify.so актуален ($(wc -c < "$OUT" | tr -d ' ') байт), пересборка не нужна"
    exit 0
fi

# Собрать нечем: приложение идёт без Spotify, устаревшая библиотека в него не попадает.
skip() {
    # Строгий режим для выпуска: SF_REQUIRE_SPOTIFY=1 — без Spotify сборка не проходит, а не тихо собирается без него.
    if [ "${SF_REQUIRE_SPOTIFY:-0}" = 1 ]; then
        echo "build_spotify: $1 — SF_REQUIRE_SPOTIFY=1, сборка остановлена" >&2
        exit 1
    fi
    echo "build_spotify: $1, libsfspotify.so не собирается (приложение без Spotify)" >&2
    if [ -f "$OUT" ]; then
        rm -f "$OUT"
        echo "build_spotify: устаревший libsfspotify.so удалён" >&2
    fi
    exit 0
}

export PATH="$HOME/.cargo/bin:$PATH"
command -v cargo >/dev/null 2>&1 || skip "cargo не найден"

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
NDK=${ANDROID_NDK_HOME:-}
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    # Та же версия NDK, что закреплена в android/app/build.gradle.kts (ndkVersion); иначе — самая новая.
    PINNED=$(sed -n 's/.*ndkVersion *= *"\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -n 1)
    if [ -n "$PINNED" ] && [ -d "$SDK/ndk/$PINNED" ]; then NDK="$SDK/ndk/$PINNED"
    else NDK=$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -n 1 | sed 's:/$::'); fi
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    skip "Android NDK не найден ($SDK/ndk)"
fi
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) skip "неизвестная ОС" ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$BIN/${TARGET}${API}-clang" ] || skip "нет $BIN/${TARGET}${API}-clang"

if ! rustup target list --installed 2>/dev/null | grep -q "^$TARGET\$"; then
    rustup target add "$TARGET" >&2 || skip "не удалось добавить target $TARGET"
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
