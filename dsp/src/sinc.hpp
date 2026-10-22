#pragma once
#include <cmath>
#include <cstdint>

#include "sfdsp.h"

namespace sf {

constexpr int kTaps = SF_SINC_TAPS;
constexpr int kHalf = kTaps / 2;
constexpr int kPhases = SF_SINC_PHASES;

const float* sincTable();  // kPhases * kTaps, строка = фаза

// Чтение кадра: NaN, если кадра нет. Reader: float(int64_t).
template <class Reader>
inline float sincInterpolate(double position, Reader&& read) {
    if (!std::isfinite(position)) return NAN;
    int64_t center = (int64_t)std::floor(position);
    int phase = (int)std::lround((position - (double)center) * kPhases);  // round-half-away, как Kotlin roundToInt для x ≥ 0
    if (phase == kPhases) { center++; phase = 0; }
    const float* row = sincTable() + phase * kTaps;
    float sum = 0.0f;
    const int64_t first = center - kHalf + 1;
    for (int tap = 0; tap < kTaps; tap++) {
        float s = read(first + tap);
        if (std::isnan(s)) return NAN;
        sum += s * row[tap];
    }
    return sum;
}

}  // namespace sf
