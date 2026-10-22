// Полифазный ресемплер: оконный sinc с окном Кайзера (ТЗ A1.2). Выполняется при импорте, не в реальном времени.
//
// Выход m соответствует входной позиции m·fin/fout (рациональной, без накопления ошибки); отсчёты вне входа — нули.
// Фильтр линейно-фазный, задержки относительно входной позиции нет. Накопление — double.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>
#include <vector>

#include "dspmath.hpp"
#include "rt.hpp"
#include "sfdsp.h"

namespace {

constexpr int kBlock = 4096;           // максимум входных кадров за один шаг внутреннего цикла
constexpr int kExactPhases = 2048;     // до такого числа фаз таблица точная, иначе — 2048 фаз и линейная интерполяция строк
constexpr double kPassHz = 20000.0;
constexpr double kStopbandDb = SF_SRC_DESIGN_STOPBAND_DB;

double designBeta(double a) { return a > 50 ? 0.1102 * (a - 8.7) : a >= 21 ? 0.5842 * std::pow(a - 21, 0.4) + 0.07886 * (a - 21) : 0.0; }

}  // namespace

struct sf_src {
    int channels, inRate, outRate;
    int64_t step, phases;   // fin/g, fout/g
    int taps, half;
    bool fine;              // таблица на kExactPhases фаз + интерполяция
    int tablePhases;
    std::vector<double> table;  // (tablePhases + 1) × taps
    std::vector<double> rowTmp;
    // потоковое состояние
    std::vector<float> buf;     // planar: channels × cap
    int cap = 0;
    int64_t base = 0;
    int count = 0;
    int64_t outIndex = 0;
    bool ended = false;
};

namespace {

void designRow(sf_src* s, double frac, double* row, double fcNorm, double beta, double invI0) {
    double sum = 0.0;
    for (int j = 0; j < s->taps; j++) {
        const double t = (double)(j - s->half + 1) - frac;
        const double x = 2.0 * fcNorm * t;
        const double sinc = std::fabs(x) < 1e-12 ? 1.0 : std::sin(sf::kPi * x) / (sf::kPi * x);
        row[j] = 2.0 * fcNorm * sinc * sf::kaiserWindow(t / s->half, beta, invI0);
        sum += row[j];
    }
    for (int j = 0; j < s->taps; j++) row[j] /= sum;  // единичное усиление на постоянной составляющей в каждой фазе
}

int64_t gcd64(int64_t a, int64_t b) { while (b) { const int64_t t = a % b; a = b; b = t; } return a; }

}  // namespace

extern "C" {

sf_src* sf_src_create(int channels, int in_rate, int out_rate) {
    if (channels < 1 || channels > 8 || in_rate < 8000 || out_rate < 8000 || in_rate > 768000 || out_rate > 768000) return nullptr;
    sf_src* s = new (std::nothrow) sf_src;
    if (!s) return nullptr;
    s->channels = channels; s->inRate = in_rate; s->outRate = out_rate;
    const int64_t g = gcd64(in_rate, out_rate);
    s->step = in_rate / g; s->phases = out_rate / g;
    const double stop = 0.5 * std::min(in_rate, out_rate);
    const double pass = std::min(kPassHz, 0.92 * stop);
    const double df = stop - pass;
    const double fc = 0.5 * (pass + stop);
    // Формула Кайзера: N = (A − 7,95) / (2,285·Δω), Δω в рад на входной отсчёт.
    const double dw = 2.0 * sf::kPi * df / in_rate;
    int taps = (int)std::ceil((kStopbandDb - 7.95) / (2.285 * dw)) + 1;
    taps = std::max(16, taps + (taps & 1));
    s->taps = taps; s->half = taps / 2;
    s->fine = s->phases > kExactPhases;
    s->tablePhases = s->fine ? kExactPhases : (int)s->phases;
    s->table.assign((size_t)(s->tablePhases + 1) * taps, 0.0);
    const double beta = designBeta(kStopbandDb), invI0 = 1.0 / sf::bessel0(beta);
    const double fcNorm = fc / in_rate;
    for (int p = 0; p <= s->tablePhases; p++) designRow(s, (double)p / s->tablePhases, &s->table[(size_t)p * taps], fcNorm, beta, invI0);
    s->rowTmp.assign((size_t)taps, 0.0);
    s->cap = taps + kBlock + 8;
    s->buf.assign((size_t)channels * s->cap, 0.0f);
    return s;
}

void sf_src_destroy(sf_src* s) { delete s; }

void sf_src_reset(sf_src* s) {
    s->base = 0; s->count = 0; s->outIndex = 0; s->ended = false;
    std::fill(s->buf.begin(), s->buf.end(), 0.0f);
}

int sf_src_taps(const sf_src* s) { return s->taps; }
int sf_src_phases(const sf_src* s) { return (int)s->phases; }
int sf_src_channels(const sf_src* s) { return s->channels; }

int64_t sf_src_max_output(const sf_src* s, int64_t n_in) {
    return (n_in + s->taps) * (int64_t)s->outRate / s->inRate + 4;
}

namespace {

int drain(sf_src* s, float* out, int64_t cap) {
    const int ch = s->channels;
    const int64_t inputEnd = s->base + s->count;
    int64_t produced = 0;
    while (produced < cap) {
        const int64_t num = s->outIndex * s->step;
        const int64_t ip = num / s->phases;
        const int phase = (int)(num % s->phases);
        const int64_t first = ip - s->half + 1, last = ip + s->half;
        if (!s->ended && last >= inputEnd) break;
        if (s->ended && ip >= inputEnd) break;
        const double* row;
        if (!s->fine) row = &s->table[(size_t)phase * s->taps];
        else {
            const double pos = (double)phase / (double)s->phases * s->tablePhases;
            const int i0 = (int)pos;
            const double w = pos - i0;
            const double* a = &s->table[(size_t)i0 * s->taps];
            const double* b = &s->table[(size_t)(i0 + 1) * s->taps];
            for (int j = 0; j < s->taps; j++) s->rowTmp[(size_t)j] = a[j] + (b[j] - a[j]) * w;
            row = s->rowTmp.data();
        }
        // границы: кадры вне [base, inputEnd) — нули
        const int64_t lo = std::max<int64_t>(first, s->base), hi = std::min<int64_t>(last + 1, inputEnd);
        for (int c = 0; c < ch; c++) {
            double a0 = 0, a1 = 0, a2 = 0, a3 = 0;
            if (hi > lo) {
                const float* x = &s->buf[(size_t)c * s->cap + (size_t)(lo - s->base)];
                const double* r = row + (lo - first);
                const int64_t n = hi - lo;
                int64_t j = 0;
                for (; j + 4 <= n; j += 4) {
                    a0 += (double)x[j] * r[j]; a1 += (double)x[j + 1] * r[j + 1];
                    a2 += (double)x[j + 2] * r[j + 2]; a3 += (double)x[j + 3] * r[j + 3];
                }
                for (; j < n; j++) a0 += (double)x[j] * r[j];
            }
            out[produced * ch + c] = (float)((a0 + a1) + (a2 + a3));
        }
        produced++; s->outIndex++;
    }
    // убрать кадры, которые больше не нужны
    const int64_t needFrom = (s->outIndex * s->step / s->phases) - s->half + 1;
    const int64_t drop = std::max<int64_t>(0, std::min<int64_t>(needFrom - s->base, s->count));
    if (drop > 0) {
        for (int c = 0; c < ch; c++) {
            float* b = &s->buf[(size_t)c * s->cap];
            std::memmove(b, b + drop, (size_t)(s->count - drop) * sizeof(float));
        }
        s->count -= (int)drop; s->base += drop;
    }
    return (int)produced;
}

}  // namespace

int64_t sf_src_process(sf_src* s, const float* in, int64_t n_in, float* out, int64_t out_capacity) {
    sf::FtzGuard ftz;
    const int ch = s->channels;
    int64_t produced = 0, done = 0;
    while (done < n_in) {
        const int chunk = (int)std::min<int64_t>(kBlock, n_in - done);
        if (s->count + chunk > s->cap) return -1;  // не должно случаться: drain освобождает всё лишнее
        for (int c = 0; c < ch; c++) {
            float* b = &s->buf[(size_t)c * s->cap + s->count];
            for (int i = 0; i < chunk; i++) b[i] = in[(done + i) * ch + c];
        }
        s->count += chunk; done += chunk;
        produced += drain(s, out + produced * ch, out_capacity - produced);
    }
    return produced;
}

int64_t sf_src_flush(sf_src* s, float* out, int64_t out_capacity) {
    sf::FtzGuard ftz;
    s->ended = true;
    return drain(s, out, out_capacity);
}

int64_t sf_src_resample(int channels, int in_rate, int out_rate, const float* in, int64_t n_in, float* out, int64_t out_capacity) {
    sf_src* s = sf_src_create(channels, in_rate, out_rate);
    if (!s) return -1;
    int64_t n = sf_src_process(s, in, n_in, out, out_capacity);
    if (n >= 0) { const int64_t f = sf_src_flush(s, out + n * channels, out_capacity - n); n = f < 0 ? -1 : n + f; }
    sf_src_destroy(s);
    return n;
}

}
