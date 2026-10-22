// Детектор true-peak: 4× оверсэмплинг, 128 отводов (32 на фазу), Kaiser β = 8. Общий для лимитера и анализатора трека.
#pragma once
#include <cmath>
#include <vector>

#include "dspmath.hpp"

namespace sf {

constexpr int kOversample = 4;
constexpr int kTpTaps = 128;
constexpr int kTpPerPhase = kTpTaps / kOversample;  // 32
constexpr int kTpHalf = kTpPerPhase / 2;            // 16: задержка детектора

// Коэффициенты фаз 1..3: tp[ph][j], j = 0..31, применяются к x[k + 16 − j].
struct TpTable {
    double h[kOversample][kTpPerPhase];
    TpTable() {
        std::vector<double> t(kTpTaps);
        const double beta = 8.0, inv = 1.0 / sf::bessel0(beta);
        const double c = kTpTaps / 2.0;
        for (int i = 0; i < kTpTaps; i++) {
            const double x = (i - c) / kOversample;
            const double s = std::fabs(x) < 1e-12 ? 1.0 : std::sin(sf::kPi * x) / (sf::kPi * x);
            t[(size_t)i] = s * sf::kaiserWindow((i - c) / (kTpTaps / 2.0 + 0.5), beta, inv);
        }
        for (int ph = 0; ph < kOversample; ph++) {
            double sum = 0;
            for (int i = ph; i < kTpTaps; i += kOversample) sum += t[(size_t)i];
            for (int j = 0; j < kTpPerPhase; j++) h[ph][j] = t[(size_t)(ph + j * kOversample)] / sum;
        }
    }
};
inline const TpTable& tpTable() { static const TpTable t; return t; }


}  // namespace sf
