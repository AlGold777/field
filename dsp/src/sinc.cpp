#include "sinc.hpp"

#include <mutex>
#include <vector>

namespace sf {
namespace {

double bessel0(double x) {
    const double y = x * x / 4.0;
    double term = 1.0, sum = 1.0;
    for (int k = 1; k <= 24; k++) {
        term *= y / ((double)k * k);
        sum += term;
        if (term < sum * 1e-16) break;
    }
    return sum;
}

constexpr double kBeta = 8.6;
constexpr double kPi = 3.14159265358979323846;

float g_table[kPhases * kTaps];
std::once_flag g_once;

void build() {
    for (int phase = 0; phase < kPhases; phase++) {
        const double fraction = (double)phase / kPhases;
        double row[kTaps];
        double total = 0.0;
        for (int tap = 0; tap < kTaps; tap++) {
            const double x = tap - kHalf + 1 - fraction;
            const double wp = x / kHalf;
            const double window = std::fabs(wp) >= 1.0 ? 0.0 : bessel0(kBeta * std::sqrt(1.0 - wp * wp)) / bessel0(kBeta);
            const double sinc = std::fabs(x) < 1e-12 ? 1.0 : std::sin(kPi * x) / (kPi * x);
            row[tap] = sinc * window;
            total += row[tap];
        }
        for (int tap = 0; tap < kTaps; tap++) g_table[phase * kTaps + tap] = (float)(row[tap] / total);
    }
}

}  // namespace

const float* sincTable() {
    std::call_once(g_once, build);
    return g_table;
}

}  // namespace sf

extern "C" {

const char* sfdsp_version(void) { return SFDSP_VERSION; }
int sfdsp_abi_version(void) { return SFDSP_ABI_VERSION; }
void sfdsp_init(void) { sf::sincTable(); }

float sf_sinc_coefficient(int phase, int tap) {
    if (phase < 0 || phase >= SF_SINC_PHASES || tap < 0 || tap >= SF_SINC_TAPS) return NAN;
    return sf::sincTable()[phase * SF_SINC_TAPS + tap];
}

float sf_sinc_interpolate(const float* src, int64_t src_base, int64_t src_len, double position) {
    return sf::sincInterpolate(position, [&](int64_t frame) -> float {
        const int64_t i = frame - src_base;
        return (i < 0 || i >= src_len) ? NAN : src[i];
    });
}

}
