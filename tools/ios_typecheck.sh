#!/bin/sh
# Проверка типов всего iOS-кода без Xcode: Command Line Tools + Mac Catalyst SDK (UIKit, AVAudioSession доступны).
# Запуск из корня репозитория: sh tools/ios_typecheck.sh  → «IOS TYPECHECK OK» или список ошибок, код возврата 1.
# Проверяются: Swift-код приложения (с bridging-header на C-ядро sfdsp) и синтаксис каждого dsp/src/*.cpp.
# Известная особенность окружения (не дефект кода): UnsafeMutableAudioBufferListPointer не виден под Catalyst в CLT —
# эта ошибка отфильтровывается. Компоновка и запуск этим скриптом не проверяются.
cd "$(dirname "$0")/.." || exit 1
SDK=$(xcrun --show-sdk-path --sdk macosx) || exit 1
FAILED=0

# C++-ядро: синтаксис каждого исходника (ошибки компилятора → вывод и FAILED)
for f in dsp/src/*.cpp; do
    if ! OUT_CXX=$(xcrun clang++ -std=c++20 -fsyntax-only -Wall -Wextra -Wno-unused-parameter \
        -target arm64-apple-ios16.0-macabi -isysroot "$SDK" -Idsp/include "$f" 2>&1); then
        printf '%s\n' "$OUT_CXX"
        FAILED=1
    fi
done

# Swift-код приложения; bridging-header подключает C-ядро, поэтому sfdsp_* видны из Swift.
# -continue-building-after-errors: известная ошибка Catalyst не должна скрывать остальные ошибки типов.
OUT=$(find ios/SoundField -name "*.swift" -print0 | xargs -0 swiftc -typecheck -continue-building-after-errors \
    -target arm64-apple-ios16.0-macabi -sdk "$SDK" \
    -F "$SDK/System/iOSSupport/System/Library/Frameworks" -I "$SDK/System/iOSSupport/usr/lib/swift" \
    -import-objc-header ios/SoundField/SoundField-Bridging-Header.h -Xcc -Idsp/include 2>&1)
ERR=$(printf '%s\n' "$OUT" | grep "error:" | grep -v "UnsafeMutableAudioBufferListPointer")
if [ -n "$ERR" ]; then
    printf '%s\n' "$ERR"
    FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
    echo "IOS TYPECHECK FAILED"
    exit 1
fi
echo "IOS TYPECHECK OK"
