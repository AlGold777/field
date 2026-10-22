// Сцепленный true-peak лимитер с упреждением (ТЗ A1.4).
//
// Структура (все индексы — кадры входного потока):
//  1. Детектор true-peak: 4× оверсэмплинг, 128 отводов (32 на фазу, Kaiser β = 8), задержка детектора 16 кадров.
//     Пик кадра k = max(|x[k]|, три промежуточных значения между k и k+1) по всем каналам (сцепка: общий пик).
//  2. Требуемое усиление g_req[k] = min(1, потолок / пик[k]).
//  3. Минимум g_req по окну W = lookahead кадров вперёд (монотонная очередь, O(1)), затем свёртка с окном Ханна (сумма 1):
//     усиление в кадре k — взвешенное среднее минимумов окон, каждое из которых покрывает k, поэтому g[k] ≤ g_req[k].
//  4. Восстановление: два односторонних сглаживателя (быстрый 50 мс и медленный 400 мс), оба ≤ g[k]. Итог — их смесь с весом
//     c ∈ [0,1]: доля времени с ослаблением > 0,1 dB за последнюю секунду (скользящее среднее τ = 1 с), с мёртвой зоной
//     0,2 и шириной 0,5. Единичный пик (ослабление ≈ 180 мс из секунды) даёт c = 0 → восстановление 50 мс; частые пики → 400 мс.
//  Задержка по входу: 16 + W − 1 кадров (latency_frames).
#include <algorithm>
#include <cmath>
#include <new>
#include <vector>

#include "dspmath.hpp"
#include "rt.hpp"
#include "sfdsp.h"

namespace {

constexpr int kOversample = 4;
constexpr int kTpTaps = 128;
constexpr int kTpPerPhase = kTpTaps / kOversample;  // 32
constexpr int kTpHalf = kTpPerPhase / 2;            // 16: задержка детектора
constexpr double kFastReleaseS = 0.050, kSlowReleaseS = 0.400, kDensityS = 1.000;
constexpr double kDensityThreshold = 0.98855;       // −0,1 dB: ослабление «держится»
constexpr double kDensityDeadZone = 0.20, kDensitySpan = 0.50;  // c = clamp((доля времени с ослаблением − 0,2) / 0,5)
constexpr double kGuardDb = 0.02;                    // запас потолка на изменение усиления внутри окна детектора

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
const TpTable& tpTable() { static const TpTable t; return t; }

}  // namespace

struct sf_limiter {
    int channels, lookahead, latency, ringSize, ringMask;
    double ceilLin, relFast, relSlow, relDensity;
    std::vector<float> xring;       // channels × ringSize
    std::vector<double> window;     // Ханн, сумма 1, длина W
    std::vector<double> mring;      // последние W минимумов
    // монотонная очередь минимумов: кольцо (индекс кадра, значение)
    std::vector<int64_t> qIdx; std::vector<double> qVal;
    int qHead = 0, qCount = 0, qCap = 0;
    int mpos = 0;                   // слот последнего записанного минимума
    int64_t n = 0;                  // число принятых входных кадров
    double gFast = 1, gSlow = 1, density = 0, gOut = 1;
    // статистика
    int64_t blocks = 0, activeBlocks = 0;
    double maxReductionDb = 0;
};

extern "C" {

sf_limiter* sf_limiter_create(int sample_rate, int channels, double ceiling_dbtp) {
    if (channels < 1 || channels > 2 || sample_rate < 8000) return nullptr;
    sf_limiter* l = new (std::nothrow) sf_limiter;
    if (!l) return nullptr;
    l->channels = channels;
    l->lookahead = std::max(8, (int)std::lround(SF_LIMITER_LOOKAHEAD_S * sample_rate));
    l->latency = kTpHalf + l->lookahead - 1;
    int rs = 1; while (rs < l->latency + kTpTaps + 8) rs <<= 1;
    l->ringSize = rs; l->ringMask = rs - 1;
    l->ceilLin = std::pow(10.0, (ceiling_dbtp - kGuardDb) / 20.0);
    l->relFast = 1.0 - std::exp(-1.0 / (kFastReleaseS * sample_rate));
    l->relSlow = 1.0 - std::exp(-1.0 / (kSlowReleaseS * sample_rate));
    l->relDensity = 1.0 - std::exp(-1.0 / (kDensityS * sample_rate));
    l->xring.assign((size_t)channels * rs, 0.0f);
    l->window.assign((size_t)l->lookahead, 0.0);
    double ws = 0;
    for (int i = 0; i < l->lookahead; i++) { l->window[(size_t)i] = 0.5 - 0.5 * std::cos(2 * sf::kPi * (i + 1) / (l->lookahead + 1)); ws += l->window[(size_t)i]; }
    for (auto& w : l->window) w /= ws;
    l->mring.assign((size_t)l->lookahead, 1.0);
    l->qCap = l->lookahead + 2;
    l->qIdx.assign((size_t)l->qCap, 0); l->qVal.assign((size_t)l->qCap, 1.0);
    sf_limiter_reset(l);
    return l;
}

void sf_limiter_destroy(sf_limiter* l) { delete l; }

void sf_limiter_reset(sf_limiter* l) {
    std::fill(l->xring.begin(), l->xring.end(), 0.0f);
    std::fill(l->mring.begin(), l->mring.end(), 1.0);
    l->qHead = 0; l->qCount = 0; l->mpos = 0;
    l->n = 0; l->gFast = 1; l->gSlow = 1; l->density = 0; l->gOut = 1;
}

int sf_limiter_latency_frames(const sf_limiter* l) { return l->latency; }

void sf_limiter_process(sf_limiter* l, float* io, int frames) {
    sf::FtzGuard ftz;
    const int ch = l->channels, W = l->lookahead, mask = l->ringMask, rs = l->ringSize;
    const auto& tp = tpTable();
    double blockMinGain = 1.0;
    for (int f = 0; f < frames; f++) {
        const int64_t n = l->n++;
        for (int c = 0; c < ch; c++) l->xring[(size_t)c * rs + (size_t)(n & mask)] = io[f * ch + c];
        // детектор: пик кадра k = n − 16 (нужны x[k−15 .. k+16] = x[n−31 .. n]); до начала потока отсчёты — нули
        const int64_t k = n - kTpHalf;
        double peak = 0;
        if (k >= 0) {
            for (int c = 0; c < ch; c++) {
                const float* x = &l->xring[(size_t)c * rs];
                peak = std::max(peak, (double)std::fabs(x[k & mask]));
                for (int ph = 1; ph < kOversample; ph++) {
                    double s = 0;
                    for (int j = 0; j < kTpPerPhase; j++) {
                        const int64_t idx = k + kTpHalf - j;
                        if (idx >= 0) s += tp.h[ph][j] * (double)x[idx & mask];
                    }
                    peak = std::max(peak, std::fabs(s));
                }
            }
        }
        const double req = peak > l->ceilLin ? l->ceilLin / peak : 1.0;
        // скользящий минимум окна [k − W + 1, k]
        const int qc = l->qCap;
        while (l->qCount > 0 && l->qVal[(size_t)((l->qHead + l->qCount - 1) % qc)] >= req) l->qCount--;
        {
            const int tail = (l->qHead + l->qCount) % qc;
            l->qIdx[(size_t)tail] = k; l->qVal[(size_t)tail] = req; l->qCount++;
        }
        while (l->qIdx[(size_t)l->qHead] <= k - W) { l->qHead = (l->qHead + 1) % qc; l->qCount--; }
        if (++l->mpos == W) l->mpos = 0;
        l->mring[(size_t)l->mpos] = l->qVal[(size_t)l->qHead];
        const int64_t kk = k - W + 1;  // выходной кадр: g = Σ w[i]·m[kk − i]
        double gs = 0;
        {
            const double* w = l->window.data();
            const double* m = l->mring.data();
            int i = 0;
            for (int slot = l->mpos; slot >= 0; slot--, i++) gs += w[i] * m[slot];
            for (int slot = W - 1; i < W; slot--, i++) gs += w[i] * m[slot];
        }
        // восстановление
        l->gFast = std::min(gs, l->gFast + (1.0 - l->gFast) * l->relFast);
        l->gSlow = std::min(gs, l->gSlow + (1.0 - l->gSlow) * l->relSlow);
        const double ind = l->gOut < kDensityThreshold ? 1.0 : 0.0;  // по итоговому усилению предыдущего кадра
        l->density += (ind - l->density) * l->relDensity;
        const double mix = std::min(1.0, std::max(0.0, (l->density - kDensityDeadZone) / kDensitySpan));
        const double g = l->gFast + mix * (l->gSlow - l->gFast);
        l->gOut = g;
        for (int c = 0; c < ch; c++) {
            const float x = kk >= 0 ? l->xring[(size_t)c * rs + (size_t)(kk & mask)] : 0.0f;
            float y = x * (float)g;
            io[f * ch + c] = std::min(1.0f, std::max(-1.0f, y));
        }
        blockMinGain = std::min(blockMinGain, g);
    }
    l->blocks++;
    const double red = blockMinGain < 1.0 ? -20.0 * std::log10(blockMinGain) : 0.0;
    if (red > 0.5) l->activeBlocks++;
    l->maxReductionDb = std::max(l->maxReductionDb, red);
}

void sf_limiter_take_stats(sf_limiter* l, int64_t* blocks, int64_t* active_blocks, double* max_reduction_db) {
    if (blocks) *blocks = l->blocks;
    if (active_blocks) *active_blocks = l->activeBlocks;
    if (max_reduction_db) *max_reduction_db = l->maxReductionDb;
    l->blocks = 0; l->activeBlocks = 0; l->maxReductionDb = 0;
}

}
