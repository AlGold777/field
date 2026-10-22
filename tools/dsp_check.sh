#!/bin/sh
# Проверка ядра без телефона и Xcode: сборка на Mac, тесты ядра, sflab, сравнение с базовой линией dsp/lab/baseline.json.
#   sh tools/dsp_check.sh            полная проверка; без скачанного корпуса — ошибка (python3 tools/audio-corpus/fetch.py)
#   sh tools/dsp_check.sh --no-corpus  без метрик корпуса (только для быстрых локальных прогонов; не для доставки)
#   sh tools/dsp_check.sh --android  дополнительно прогоняет Kotlin-тракт Android (Gradle labRender) и сравнивает его
#   sh tools/dsp_check.sh --update   перезаписать базовую линию текущими значениями (только осознанно, с таблицей «до/после»)
set -e
cd "$(dirname "$0")/.."
ANDROID=0; UPDATE=0; NOCORPUS=0
for a in "$@"; do case "$a" in --android) ANDROID=1 ;; --update) UPDATE=1 ;; --no-corpus) NOCORPUS=1 ;; esac; done
B=dsp/build/host
sh tools/build_dsp.sh "$B"
"$B/dsp_tests"

CORPUS=tools/audio-corpus/wav
ARGS=""
EXPECTED=$(grep -c '"id"' tools/audio-corpus/manifest.json)
HAVE=$(ls "$CORPUS"/*.wav 2>/dev/null | wc -l | tr -d ' ')
if [ "$NOCORPUS" = 1 ]; then
    echo "ВНИМАНИЕ: --no-corpus — метрики корпуса не проверяются"
elif [ "$HAVE" -ne "$EXPECTED" ]; then
    echo "ОШИБКА: корпус не полон ($HAVE из $EXPECTED фрагментов в $CORPUS)." >&2
    echo "Скачайте: python3 tools/audio-corpus/fetch.py   (или --no-corpus для локального прогона без корпуса)" >&2
    exit 2
else
    ARGS="--corpus $CORPUS"
fi

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
    [ "$NOCORPUS" = 1 ] && { echo "--update без корпуса запрещён" >&2; exit 2; }
    cp "$B/current.json" dsp/lab/baseline.json
    echo "базовая линия обновлена: dsp/lab/baseline.json"
    exit 0
fi
SKIP=""
[ "$ANDROID" = 1 ] || SKIP="--skip android_kotlin"
[ "$NOCORPUS" = 1 ] && SKIP="$SKIP --skip .corpus."
"$B/sflab" check dsp/lab/baseline.json "$B/current.json" $SKIP
echo "DSP CHECK OK"
