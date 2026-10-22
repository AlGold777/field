// Одноходовой анализатор трека при импорте (A1.2-расширение): true-peak 4× (тот же детектор, что у лимитера).
// Структура рассчитана на добавление интегральной громкости BS.1770 (A3.1) в тот же проход.
#include <algorithm>
#include <cmath>
#include <new>
#include <vector>

#include "sfdsp.h"
#include "truepeak.hpp"

struct sf_meter {
    int channels;
    std::vector<float> ring;  // channels × 64
    int64_t n = 0;
    double peak = 0;
    bool finished = false;
};

namespace {
constexpr int kRing = 64, kMask = kRing - 1;

void step(sf_meter* m, int64_t k) {
    const auto& tp = sf::tpTable();
    for (int c = 0; c < m->channels; c++) {
        const float* x = &m->ring[(size_t)c * kRing];
        m->peak = std::max(m->peak, (double)std::fabs(x[k & kMask]));
        for (int ph = 1; ph < sf::kOversample; ph++) {
            double s = 0;
            for (int j = 0; j < sf::kTpPerPhase; j++) {
                const int64_t idx = k + sf::kTpHalf - j;
                if (idx >= 0) s += tp.h[ph][j] * (double)x[idx & kMask];
            }
            m->peak = std::max(m->peak, std::fabs(s));
        }
    }
}

void push(sf_meter* m, const float* frame) {
    const int64_t n = m->n++;
    for (int c = 0; c < m->channels; c++) m->ring[(size_t)c * kRing + (size_t)(n & kMask)] = frame ? frame[c] : 0.0f;
    const int64_t k = n - sf::kTpHalf;
    if (k >= 0) step(m, k);
}
}  // namespace

extern "C" {

sf_meter* sf_meter_create(int channels) {
    if (channels < 1 || channels > 8) return nullptr;
    sf_meter* m = new (std::nothrow) sf_meter;
    if (!m) return nullptr;
    m->channels = channels;
    m->ring.assign((size_t)channels * kRing, 0.0f);
    return m;
}

void sf_meter_destroy(sf_meter* m) { delete m; }

void sf_meter_process(sf_meter* m, const float* interleaved, int64_t frames) {
    for (int64_t i = 0; i < frames; i++) push(m, interleaved + i * m->channels);
}

double sf_meter_true_peak_dbtp(const sf_meter* m) {
    // Хвост (последние 16 кадров детектора) дописывается нулями на копии состояния: можно вызывать в любой момент.
    sf_meter c = *m;
    for (int i = 0; i < sf::kTpHalf; i++) push(&c, nullptr);
    return c.peak < 1e-10 ? -200.0 : 20.0 * std::log10(c.peak);
}

}
