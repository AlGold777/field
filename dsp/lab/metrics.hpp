// Метрики качества (ТЗ §2) в цифровом домене. Только для лаборатории/тестов.
#pragma once
#include <algorithm>
#include <cmath>
#include <vector>

#include "fft.hpp"

namespace sflab {

constexpr double kFloorDb = -200.0;
inline double dbPow(double p) { return p < 1e-20 ? kFloorDb : 10.0 * std::log10(p); }
inline double dbAmp(double a) { return a < 1e-10 ? kFloorDb : 20.0 * std::log10(a); }

// ---------- тон: THD+N, гармоники, усиление ----------
struct ToneResult {
    double freq = 0, gainDb = 0, thdnDb = 0, h2 = kFloorDb, h3 = kFloorDb, h4 = kFloorDb, h5 = kFloorDb;
};

inline ToneResult analyzeTone(const std::vector<float>& x, int sr, double expectedAmp, size_t skip = 8192, size_t n = 65536) {
    ToneResult r;
    if (x.size() <= skip) return r;   // короткий вход: иначе x.size() − skip переполнялось (size_t)
    if (x.size() < skip + n) { n = nextPow2(x.size() - skip) / 2; }
    const auto w = kaiser(n, 26.0);
    double sw2 = 0;
    std::vector<cd> a(n);
    for (size_t i = 0; i < n; i++) { a[i] = (double)x[skip + i] * w[i]; sw2 += w[i] * w[i]; }
    fft(a);
    const size_t half = n / 2;
    std::vector<double> p(half);
    for (size_t k = 0; k < half; k++) p[k] = std::norm(a[k]);
    const double binHz = (double)sr / (double)n;
    size_t k0 = 1;
    for (size_t k = 1; k < half; k++) if (p[k] > p[k0]) k0 = k;
    const int notch = 12;
    auto band = [&](size_t c, int wdt) { double s = 0; for (long k = (long)c - wdt; k <= (long)c + wdt; k++) if (k >= 0 && k < (long)half) s += p[k]; return s; };
    // уточнение частоты по центру масс в окне вокруг пика
    double num = 0, den = 0;
    for (long k = (long)k0 - 3; k <= (long)k0 + 3; k++) if (k >= 0 && k < (long)half) { num += (double)k * p[k]; den += p[k]; }
    r.freq = num / den * binHz;
    const double pf = band(k0, notch);
    const double amp = std::sqrt(4.0 * pf / ((double)n * sw2));
    r.gainDb = dbAmp(amp / expectedAmp);
    const size_t lo = (size_t)std::ceil(20.0 / binHz), hi = (size_t)std::floor(20000.0 / binHz);
    double pn = 0;
    for (size_t k = lo; k <= hi && k < half; k++) if ((long)k < (long)k0 - notch || (long)k > (long)k0 + notch) pn += p[k];
    r.thdnDb = dbPow(pn / pf);
    double* hs[4] = {&r.h2, &r.h3, &r.h4, &r.h5};
    for (int h = 2; h <= 5; h++) {
        const double fh = (double)h * r.freq;
        if (fh > (double)sr / 2 - 100) continue;
        const size_t c = (size_t)std::lround(fh / binHz);
        *hs[h - 2] = dbPow(band(c, 6) / pf);
    }
    return r;
}

// ---------- уровни ----------
inline double peakDb(const std::vector<float>& x) { double m = 0; for (float v : x) m = std::max(m, (double)std::fabs(v)); return dbAmp(m); }
inline double rmsDb(const std::vector<float>& x, size_t from = 0, size_t to = 0) {
    if (!to || to > x.size()) to = x.size();
    double s = 0; for (size_t i = from; i < to; i++) s += (double)x[i] * x[i];
    return dbPow(s / (double)std::max<size_t>(1, to - from));
}

// ---------- true-peak: 4x, 128 отводов (32 на фазу), Kaiser; точнее 48-отводного фильтра BS.1770 Annex 2 (ошибка ≤ 0,2 dB вниз на сетке 4x) ----------
inline const std::vector<double>& tpFilter() {
    static std::vector<double> h = [] {
        const int L = 4, N = 128;
        std::vector<double> t(N);
        const auto win = kaiser(N + 1, 8.0);
        const double c = N / 2.0;  // центр на целом отсчёте 24 (окно из N+1 точек)
        for (int i = 0; i < N; i++) {
            const double x = (i - c) / L;
            const double s = std::fabs(x) < 1e-12 ? 1.0 : std::sin(kPi * x) / (kPi * x);
            t[i] = s * win[i];
        }
        // нормировка: сумма каждой фазы = 1
        for (int ph = 0; ph < L; ph++) { double sum = 0; for (int i = ph; i < N; i += L) sum += t[i]; for (int i = ph; i < N; i += L) t[i] /= sum; }
        return t;
    }();
    return h;
}

inline double truePeakDb(const std::vector<float>& x) {
    const auto& h = tpFilter();
    const int L = 4, N = 128, taps = N / L;
    double peak = 0;
    const long n = (long)x.size();
    for (long i = 0; i < n; i++) {
        peak = std::max(peak, (double)std::fabs(x[i]));
        if (i < taps || i + taps >= n) continue;  // края: свёртка с обрезанным сигналом даёт выброс Гиббса, не пик сигнала
        for (int ph = 1; ph < L; ph++) {
            double s = 0;
            for (int j = 0; j < taps; j++) {
                const long idx = i + taps / 2 - j;
                if (idx >= 0 && idx < n) s += h[ph + j * L] * x[idx];
            }
            peak = std::max(peak, std::fabs(s));
        }
    }
    return dbAmp(peak);
}

// ---------- громкость BS.1770-4 (48 кГц) ----------
struct Biquad {
    double b0, b1, b2, a1, a2, z1 = 0, z2 = 0;
    double run(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
};

inline double lufsIntegrated(const std::vector<std::vector<float>>& chans, int sr) {
    if (sr != 48000 || chans.empty()) return std::nan("");
    const size_t n = chans[0].size();
    std::vector<std::vector<double>> sq(chans.size(), std::vector<double>(n));
    for (size_t c = 0; c < chans.size(); c++) {
        Biquad s1{1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585};
        Biquad s2{1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621};
        for (size_t i = 0; i < n; i++) { const double y = s2.run(s1.run(chans[c][i])); sq[c][i] = y * y; }
    }
    const size_t blk = 19200, hop = 4800;
    std::vector<double> z;
    for (size_t s = 0; s + blk <= n; s += hop) {
        double zsum = 0;
        for (size_t c = 0; c < chans.size(); c++) { double m = 0; for (size_t i = s; i < s + blk; i++) m += sq[c][i]; zsum += m / (double)blk; }
        z.push_back(zsum);
    }
    auto lufs = [](double e) { return -0.691 + 10.0 * std::log10(e); };
    double sum = 0; size_t cnt = 0;
    for (double e : z) if (e > 0 && lufs(e) > -70.0) { sum += e; cnt++; }
    if (!cnt) return -70.0;
    const double rel = lufs(sum / (double)cnt) - 10.0;
    sum = 0; cnt = 0;
    for (double e : z) if (e > 0 && lufs(e) > -70.0 && lufs(e) > rel) { sum += e; cnt++; }
    return cnt ? lufs(sum / (double)cnt) : -70.0;
}

// ---------- null-тест с поиском целого лага ----------
struct NullResult { double residualDb = 0; int lag = 0; };
inline NullResult nullTest(const std::vector<float>& in, const std::vector<float>& out, int maxLag = 160, size_t margin = 4096) {
    NullResult best; best.residualDb = 1e9;
    const size_t n = std::min(in.size(), out.size());
    double ein = 0; for (size_t i = margin; i + margin < n; i++) ein += (double)in[i] * in[i];
    for (int lag = 0; lag <= maxLag; lag++) {
        double e = 0;
        for (size_t i = margin; i + margin < n; i++) { const double d = (double)out[i] - in[i - lag]; e += d * d; }
        const double db = dbPow(e / std::max(ein, 1e-30));
        if (db < best.residualDb) { best.residualDb = db; best.lag = lag; }
    }
    return best;
}

// ---------- ESS (Фарина) ----------
struct Ess {
    double f1, f2, T; int sr;
    size_t N() const { return (size_t)std::llround(T * sr); }
    double L() const { return T / std::log(f2 / f1); }
    std::vector<float> signal(double amp) const {
        std::vector<float> x(N());
        const double K = 2 * kPi * f1 * L();
        for (size_t i = 0; i < x.size(); i++) x[i] = (float)(amp * std::sin(K * (std::exp((double)i / sr / L()) - 1.0)));
        return x;
    }
    // импульсная характеристика: свёртка с обратным фильтром; индекс 0 = (N-1) при нулевой задержке, пик нормируется на 1 для самого ESS
    std::vector<double> impulse(const std::vector<float>& y, double refAmp) const {
        const size_t n = N();
        const size_t M = nextPow2(y.size() + n);
        auto ref = signal(1.0);
        std::vector<cd> inv(M), Y(M), R(M);
        for (size_t i = 0; i < n; i++) inv[i] = (double)ref[n - 1 - i] * std::exp(-(double)i / sr / L());
        for (size_t i = 0; i < y.size(); i++) Y[i] = y[i];
        for (size_t i = 0; i < n; i++) R[i] = ref[i];
        fft(inv); fft(Y); fft(R);
        std::vector<cd> hy(M), hr(M);
        for (size_t k = 0; k < M; k++) { hy[k] = Y[k] * inv[k]; hr[k] = R[k] * inv[k]; }
        fft(hy, true); fft(hr, true);
        double pk = 0; for (size_t i = 0; i < M; i++) pk = std::max(pk, std::fabs(hr[i].real()));
        std::vector<double> h(M);
        for (size_t i = 0; i < M; i++) h[i] = hy[i].real() / (pk * refAmp);
        return h;
    }
};

struct EssResult {
    std::vector<double> freqs, magDb, gdSamples;  // на сетке 1/3 окт
    double h2_1k = kFloorDb, h3_1k = kFloorDb;
    long peakIndex = 0;
};

struct EssFr { std::vector<double> mag, gd; };  // gd — отсчёты относительно пика

inline EssFr essFr(const std::vector<double>& h, long pk, int sr, const std::vector<double>& freqs) {
    const size_t W = 65536;
    const long pre = 64, post = 2048, taper = 512;
    std::vector<cd> hh(W), nh(W);
    for (long i = -pre; i < post; i++) {
        double v = h[pk + i];
        if (i > post - taper) v *= 0.5 * (1 + std::cos(kPi * (double)(i - (post - taper)) / taper));
        if (i < -pre + 16) v *= 0.5 * (1 - std::cos(kPi * (double)(i + pre) / 16.0));
        hh[i + pre] = v; nh[i + pre] = v * (double)(i + pre);
    }
    fft(hh); fft(nh);
    const double binHz = (double)sr / (double)W;
    EssFr r;
    for (double f : freqs) {
        const size_t k = (size_t)std::lround(f / binHz);
        r.mag.push_back(dbAmp(std::abs(hh[k])));
        r.gd.push_back((nh[k] / hh[k]).real() - pre);
    }
    return r;
}

inline long essPeak(const std::vector<double>& h, long n0) {
    long pk = n0;
    for (long i = n0 - 64; i < n0 + 4096 && i < (long)h.size(); i++) if (std::fabs(h[i]) > std::fabs(h[pk])) pk = i;
    return pk;
}

// АЧХ/групповая задержка цепочки относительно самого ESS (его собственная полоса 20 Гц–23,5 кГц вычитается).
inline EssResult analyzeEss(const Ess& e, const std::vector<float>& y, double inputAmp) {
    EssResult r;
    const long n0 = (long)e.N() - 1;
    auto ref = e.signal(inputAmp);
    auto href = e.impulse(ref, inputAmp);
    auto h = e.impulse(y, inputAmp);
    const long pkRef = essPeak(href, n0), pk = essPeak(h, n0);
    r.peakIndex = pk - pkRef;
    for (double f = 20.0; f <= 20000.0 * 1.001; f *= std::pow(2.0, 1.0 / 3.0)) r.freqs.push_back(f);
    auto a = essFr(h, pk, e.sr, r.freqs), b = essFr(href, pkRef, e.sr, r.freqs);
    for (size_t i = 0; i < r.freqs.size(); i++) { r.magDb.push_back(a.mag[i] - b.mag[i]); r.gdSamples.push_back(a.gd[i] - b.gd[i]); }
    // гармоники: k-я гармоника опережает линейный отклик на L·ln(k) секунд; уровень относительно линейного отклика на 1 кГц
    const size_t W = 65536;
    const double binHz = (double)e.sr / (double)W;
    const long win = 2048;
    auto windowed = [&](long center, size_t bin) {
        std::vector<cd> g(W);
        for (long i = -win; i < win; i++) {
            const long idx = center + i;
            if (idx < 0 || idx >= (long)h.size()) continue;
            g[i + win] = h[idx] * 0.5 * (1 + std::cos(kPi * (double)i / win));
        }
        fft(g);
        return std::abs(g[bin]);
    };
    const double lin = windowed(pk, (size_t)std::lround(1000.0 / binHz));
    auto harm = [&](int k) {
        const long center = pk - (long)std::llround(e.L() * std::log((double)k) * e.sr);
        return dbAmp(windowed(center, (size_t)std::lround(1000.0 * k / binHz)) / lin);
    };
    r.h2_1k = harm(2); r.h3_1k = harm(3);
    return r;
}

// ---------- IMD ----------
inline double peakAtHz(const std::vector<double>& mag, double binHz, double f) {
    const long c = std::lround(f / binHz); double m = 0;
    for (long k = c - 4; k <= c + 4; k++) if (k >= 0 && k < (long)mag.size()) m = std::max(m, mag[k]);
    return m;
}
inline std::vector<double> amplitudeSpectrum(const std::vector<float>& x, int sr, size_t skip, size_t n, double* binHz) {
    const auto w = kaiser(n, 26.0);
    double sw = 0; for (double v : w) sw += v;
    std::vector<cd> a(n);
    for (size_t i = 0; i < n; i++) a[i] = (double)x[skip + i] * w[i];
    fft(a);
    std::vector<double> m(n / 2);
    for (size_t k = 0; k < n / 2; k++) m[k] = 2.0 * std::abs(a[k]) / sw;
    *binHz = (double)sr / (double)n;
    return m;
}
inline double imdSmpte(const std::vector<float>& x, int sr) {
    double bh; auto m = amplitudeSpectrum(x, sr, 8192, 65536, &bh);
    const double car = peakAtHz(m, bh, 7000.0);
    double s = 0;
    for (int k = 1; k <= 3; k++) for (int sgn : {-1, 1}) { const double a = peakAtHz(m, bh, 7000.0 + sgn * 60.0 * k); s += a * a; }
    return dbAmp(std::sqrt(s) / car);
}
inline double imdCcif(const std::vector<float>& x, int sr) {
    double bh; auto m = amplitudeSpectrum(x, sr, 8192, 65536, &bh);
    const double t1 = peakAtHz(m, bh, 19000.0), t2 = peakAtHz(m, bh, 20000.0);
    double s = 0;
    for (double f : {1000.0, 2000.0, 17000.0, 18000.0, 21000.0, 22000.0}) { const double a = peakAtHz(m, bh, f); s += a * a; }
    return dbAmp(std::sqrt(s) / std::sqrt(t1 * t1 + t2 * t2));
}

// ---------- статистика ----------
inline double percentile(std::vector<double> v, double p) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    const double pos = p * (double)(v.size() - 1);
    const size_t i = (size_t)pos;
    const double fr = pos - (double)i;
    return i + 1 < v.size() ? v[i] * (1 - fr) + v[i + 1] * fr : v[i];
}

}  // namespace sflab
