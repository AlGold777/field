// Одноходовой анализатор трека при импорте (A1.2-расширение): true-peak 4× (тот же детектор, что у лимитера).
// Структура рассчитана на добавление интегральной громкости BS.1770 (A3.1) в тот же проход.
#include <algorithm>
#include <cmath>
#include <new>
#include <vector>

#include "sfdsp.h"
#include "truepeak.hpp"

namespace {
// K-взвешивание BS.1770-4 для 48 кГц (ступень 1: предфильтр-полка, ступень 2: RLB-ФВЧ), TDF-II, double.
struct KState { double z[2][2] = {}; };
constexpr double kK1[5] = {1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585};
constexpr double kK2[5] = {1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621};
constexpr int kSubBlock = 4800;   // 100 мс при 48 кГц; блок громкости = 4 подблока (400 мс, перекрытие 75%)

inline double kStage(const double* c, double* z, double x) {
    const double y = c[0] * x + z[0];
    z[0] = c[1] * x - c[3] * y + z[1];
    z[1] = c[2] * x - c[4] * y;
    return y;
}
}  // namespace

struct sf_meter {
    int channels;
    // Громкость BS.1770-4: энергия подблоков по 100 мс, z блока = сумма последних четырёх / 19200.
    std::vector<KState> k;
    double sub = 0;
    int subN = 0;
    double last4[4] = {};
    int64_t subCount = 0;
    std::vector<double> blocks;
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

void loudnessPush(sf_meter* m, const float* frame) {
    double e = 0;
    for (int c = 0; c < m->channels; c++) {
        KState& st = m->k[(size_t)c];
        const double y = kStage(kK2, st.z[1], kStage(kK1, st.z[0], (double)frame[c]));
        e += (c < 3 ? 1.0 : 1.41) * y * y;   // канал-веса BS.1770: L, R, C = 1, окружение 1,41 (для 1–2 каналов всегда 1)
    }
    m->sub += e;
    if (++m->subN == kSubBlock) {
        m->last4[m->subCount & 3] = m->sub;
        m->subCount++;
        m->sub = 0;
        m->subN = 0;
        if (m->subCount >= 4) m->blocks.push_back((m->last4[0] + m->last4[1] + m->last4[2] + m->last4[3]) / (4.0 * kSubBlock));
    }
}

void push(sf_meter* m, const float* frame) {
    if (frame) loudnessPush(m, frame);
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
    m->k.assign((size_t)channels, KState{});
    m->ring.assign((size_t)channels * kRing, 0.0f);
    return m;
}

void sf_meter_destroy(sf_meter* m) { delete m; }

void sf_meter_process(sf_meter* m, const float* interleaved, int64_t frames) {
    for (int64_t i = 0; i < frames; i++) push(m, interleaved + i * m->channels);
}

double sf_meter_loudness_lufs(const sf_meter* m) {
    auto lufs = [](double e) { return -0.691 + 10.0 * std::log10(e); };
    double sum = 0;
    size_t cnt = 0;
    for (double e : m->blocks) if (e > 0 && lufs(e) > -70.0) { sum += e; cnt++; }
    if (!cnt) return -200.0;
    const double rel = lufs(sum / (double)cnt) - 10.0;
    sum = 0;
    cnt = 0;
    for (double e : m->blocks) if (e > 0 && lufs(e) > -70.0 && lufs(e) > rel) { sum += e; cnt++; }
    return cnt ? lufs(sum / (double)cnt) : -200.0;
}

double sf_meter_true_peak_dbtp(const sf_meter* m) {
    // Хвост (последние 16 кадров детектора) дописывается нулями на копии состояния: можно вызывать в любой момент.
    sf_meter c;
    c.channels = m->channels; c.ring = m->ring; c.n = m->n; c.peak = m->peak;
    for (int i = 0; i < sf::kTpHalf; i++) push(&c, nullptr);
    return c.peak < 1e-10 ? -200.0 : 20.0 * std::log10(c.peak);
}

}
