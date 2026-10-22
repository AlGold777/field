#!/bin/sh
# Проверка ядра без телефона и Xcode: сборка на Mac, тесты ядра, sflab, сравнение с базовой линией dsp/lab/baseline.json.
#   sh tools/dsp_check.sh            быстрая проверка (корпус — если уже скачан)
#   sh tools/dsp_check.sh --android  дополнительно прогоняет Kotlin-тракт Android (Gradle labRender) и сравнивает его
#   sh tools/dsp_check.sh --update   перезаписать базовую линию текущими значениями (только осознанно, с таблицей «до/после»)
set -e
cd "$(dirname "$0")/.."
ANDROID=0; UPDATE=0
for a in "$@"; do case "$a" in --android) ANDROID=1 ;; --update) UPDATE=1 ;; esac; done
B=dsp/build/host
sh tools/build_dsp.sh "$B"
"$B/dsp_tests"

CORPUS=tools/audio-corpus/wav
ARGS=""
if ls "$CORPUS"/*.wav >/dev/null 2>&1; then ARGS="--corpus $CORPUS"; else echo "корпус не скачан (python3 tools/audio-corpus/fetch.py) — метрики корпуса пропущены"; fi

if [ "$ANDROID" = 1 ]; then
    WORK="$B/android_lab"
    rm -rf "$WORK"; mkdir -p "$WORK/in"
    "$B/sflab" gen-signals "$WORK/in" $ARGS
    JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17} android/gradlew -p android --offline -q labRender \
        -PlabIn="$(pwd)/$WORK/in" -PlabOut="$(pwd)/$WORK/out"
    ARGS="$ARGS --kotlin android_kotlin_pcm16=$WORK/out/pcm16 --kotlin android_kotlin_float=$WORK/out/float"
fi

"$B/sflab" run $ARGS --out "$B/current.json"
if [ "$UPDATE" = 1 ]; then
    cp "$B/current.json" dsp/lab/baseline.json
    echo "базовая линия обновлена: dsp/lab/baseline.json"
    exit 0
fi
SKIP=""
[ "$ANDROID" = 1 ] || SKIP="--skip android_kotlin"
"$B/sflab" check dsp/lab/baseline.json "$B/current.json" $SKIP
echo "DSP CHECK OK"
