#!/bin/sh
# Проверка типов всего iOS-кода без Xcode: Command Line Tools + Mac Catalyst SDK (UIKit, AVAudioSession доступны).
# Запуск из корня репозитория: sh tools/ios_typecheck.sh  → «IOS TYPECHECK OK» или список ошибок, код возврата 1.
# Известная особенность окружения (не дефект кода): UnsafeMutableAudioBufferListPointer не виден под Catalyst в CLT —
# эта ошибка отфильтровывается. Компоновка и запуск этим скриптом не проверяются.
cd "$(dirname "$0")/.." || exit 1
SDK=$(xcrun --show-sdk-path --sdk macosx) || exit 1
OUT=$(find ios/SoundField -name "*.swift" -print0 | xargs -0 swiftc -typecheck \
    -target arm64-apple-ios16.0-macabi -sdk "$SDK" \
    -F "$SDK/System/iOSSupport/System/Library/Frameworks" -I "$SDK/System/iOSSupport/usr/lib/swift" 2>&1)
ERR=$(printf '%s\n' "$OUT" | grep "error:" | grep -v "UnsafeMutableAudioBufferListPointer")
if [ -n "$ERR" ]; then
    printf '%s\n' "$ERR"
    echo "IOS TYPECHECK FAILED"
    exit 1
fi
echo "IOS TYPECHECK OK"
