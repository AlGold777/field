cmake_minimum_required(VERSION 3.22)
project(sfdsp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Одинаковая арифметика на всех платформах: без слияния в FMA и без fast-math.
add_compile_options(-ffp-contract=off -fno-fast-math -Wall -Wextra -Wno-unused-parameter)

add_library(sfdsp_core STATIC src/sinc.cpp src/playout.cpp src/pcm24.cpp src/src.cpp src/limiter.cpp src/meter.cpp src/speaker.cpp)
target_include_directories(sfdsp_core PUBLIC include)

if(ANDROID)
    # Единственная библиотека в APK: ядро + JNI-обёртка для app.soundfield.dsp.SfDsp.
    add_library(sfdsp SHARED jni/sfdsp_jni.cpp)
    target_link_libraries(sfdsp PRIVATE sfdsp_core)
    set_target_properties(sfdsp PROPERTIES CXX_VISIBILITY_PRESET hidden)
    target_link_options(sfdsp PRIVATE -Wl,-z,max-page-size=16384)  # Android 15+: 16 КБ страницы
else()
    # Хост (macOS/Linux): лаборатория и тесты. Основной путь — tools/build_dsp.sh (без CMake).
    add_executable(sflab lab/sflab.cpp)
    target_link_libraries(sflab PRIVATE sfdsp_core)
    target_include_directories(sflab PRIVATE lab)
    add_executable(dsp_tests tests/test_main.cpp)
    target_link_libraries(dsp_tests PRIVATE sfdsp_core)
    target_include_directories(dsp_tests PRIVATE lab)
    enable_testing()
    add_test(NAME dsp_tests COMMAND dsp_tests)
endif()
