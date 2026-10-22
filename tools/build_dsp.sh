#!/bin/sh
# Хост-сборка ядра sfdsp без CMake: libsfdsp.dylib (с JNI для JUnit), sflab и dsp_tests в $1 (по умолчанию dsp/build/host).
# Только clang++ из Command Line Tools. -ffp-contract=off: одинаковая арифметика на всех платформах (без FMA-слияния).
set -e
cd "$(dirname "$0")/.."
OUT=${1:-dsp/build/host}
mkdir -p "$OUT"
CXX=${CXX:-clang++}
FLAGS="-std=c++20 -O2 -ffp-contract=off -fno-fast-math -Wall -Wextra -Wno-unused-parameter -Idsp/include"
SRC="dsp/src/sinc.cpp dsp/src/playout.cpp"
JH=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
"$CXX" $FLAGS -fvisibility=hidden -shared -fPIC $SRC dsp/jni/sfdsp_jni.cpp -I"$JH/include" -I"$JH/include/darwin" -o "$OUT/libsfdsp.dylib" 2>"$OUT/jni.log" ||
    { echo "JNI-библиотека не собрана (нужен JDK): см. $OUT/jni.log" >&2; }
"$CXX" $FLAGS $SRC dsp/lab/sflab.cpp -o "$OUT/sflab"
"$CXX" $FLAGS $SRC dsp/tests/test_main.cpp -Idsp/lab -o "$OUT/dsp_tests"
