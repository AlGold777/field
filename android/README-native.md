# Нативная сборка Android (ядро sfdsp)

Общее C++20-ядро лежит в `dsp/` (корень репозитория), в APK попадает как `lib/arm64-v8a/libsfdsp.so` (ядро + JNI-обёртка `dsp/jni/sfdsp_jni.cpp`, Kotlin-сторона — `app.soundfield.dsp.SfDsp`).

## Инструменты (установлены через sdkmanager с dl.google.com)

| Компонент | Версия | Пакет sdkmanager |
|---|---|---|
| Android SDK command-line tools | 19.0 (`commandlinetools-mac-13114758_latest.zip`) | — |
| NDK | r27d, 27.3.13750724 (LTS r27) | `ndk;27.3.13750724` |
| CMake | 3.22.1 | `cmake;3.22.1` |
| JDK для Gradle и хост-сборки JNI | OpenJDK 17 (`/opt/homebrew/opt/openjdk@17`) | — |

Установка с нуля:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
SDK=~/Library/Android/sdk
mkdir -p $SDK/cmdline-tools && curl -O https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip
unzip commandlinetools-mac-13114758_latest.zip && mv cmdline-tools $SDK/cmdline-tools/latest
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK "ndk;27.3.13750724" "cmake;3.22.1"
```

В `android/app/build.gradle.kts`: `ndkVersion = "27.3.13750724"`, `externalNativeBuild.cmake { path = ../../dsp/CMakeLists.txt; version = "3.22.1" }`.

## ABI

Только `arm64-v8a`: все тестовые телефоны (Xiaomi 14 Ultra, Redmi 14C) — arm64, minSdk 26. На 32-битном устройстве `SfDsp.load()` вернёт `false`, приложение продолжит работать (пока блоки ядра не подключены к звуку — этап A0 звук не меняет). Нужен `armeabi-v7a` — добавить в `abiFilters` (сборка проходит без изменений кода).

## Хост-сборка для JUnit

`tools/build_dsp.sh` собирает `dsp/build/host/libsfdsp.dylib` (с JNI, заголовки берутся из `$JAVA_HOME/include`), `sflab` и `dsp_tests` одним `clang++` (без CMake). Gradle-задача `buildHostDsp` запускает его перед `testDebugUnitTest`; тесты `SfDspNativeTest` получают путь через системное свойство `sfdsp.host.lib` и пропускаются (Assume), если библиотеки нет.

## Правила арифметики

`-ffp-contract=off -fno-fast-math` везде (CMake, `build_dsp.sh`, Xcode `OTHER_CPLUSPLUSFLAGS`): без FMA-слияния результаты Kotlin/Android/iOS/хоста совпадают.

## Лаборатория на Android-тракте

`sh tools/dsp_check.sh --android` прогоняет сигналы `sflab` через настоящий `ServerRenderer` (Gradle-задача `labRender`, `LabRenderMain`) в двух вариантах — кэш PCM16 (как в приложении) и float — и сверяет с `dsp/lab/baseline.json`.
