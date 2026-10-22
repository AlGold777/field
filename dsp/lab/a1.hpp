// Метрики этапа A1: кэш 24 бит с дизерингом, SRC, лимитеры (ТЗ A1.1, A1.2, A1.4).
#pragma once
#include <cstdio>

#include "sfdsp.h"
#include "suite.hpp"

namespace sflab {

// ---- A1.1: float → 24 бит с TPDF → float ----
inline std::vector<float> cache24Roundtrip(const std::vector<float>& in, uint64_t seed) {
    sf_dither* d = sf_dither_create(seed);
    std::vector<uint8_t> b(in.size() * 3);
    sf_pcm24_encode(d, in.data(), 1, (int)in.size(), b.data(), 3, 0);
    sf_dither_destroy(d);
    std::vector<float> out(in.size());
    sf_pcm24_decode(b.data(), 3, (int)in.size(), 0, out.data(), 1);
    return out;
}

// Тот же путь без дизера — для сравнения (чистое округление до 24 бит).
inline std::vector<float> cache24Plain(const std::vector<float>& in) {
    std::vector<uint8_t> b(in.size() * 3);
    sf_pcm24_encode(nullptr, in.data(), 1, (int)in.size(), b.data(), 3, 0);
    std::vector<float> out(in.size());
    sf_pcm24_decode(b.data(), 3, (int)in.size(), 0, out.data(), 1);
    return out;
}

// ---- A1.2: SRC ----
inline std::vector<float> srcMono(const std::vector<float>& in, int rin, int rout) {
    std::vector<float> out((size_t)((double)in.size() * rout / rin) + 4096);
    const int64_t n = sf_src_resample(1, rin, rout, in.data(), (int64_t)in.size(), out.data(), (int64_t)out.size());
    out.resize((size_t)std::max<int64_t>(0, n));
    return out;
}

inline std::vector<float> sineAtRate(double f, int sr, double amp, size_t n) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; i++) x[i] = (float)(amp * std::sin(2 * kPi * f * (double)i / sr));
    return x;
}

inline void runSrcSuite(Sink& sink) {
    struct Case { int in, out; } cases[] = {{44100, 48000}, {88200, 48000}, {96000, 48000}, {192000, 48000}, {32000, 48000}};
    for (auto c : cases) {
        sink.prefix = "src." + std::to_string(c.in) + "_" + std::to_string(c.out) + ".";
        const double pass = std::min(20000.0, 0.92 * 0.5 * std::min(c.in, c.out));
        double ripple = 0;
        for (double f : {20.0, 100.0, 1000.0, 5000.0, 10000.0, 15000.0, 18000.0, 19000.0, 19500.0, 19900.0, 20000.0}) {
            if (f > pass) continue;
            auto y = srcMono(sineAtRate(f, c.in, 0.5, (size_t)c.in), c.in, c.out);
            std::vector<float> mid(y.begin() + 6000, y.begin() + 6000 + 32768);
            ripple = std::max(ripple, std::fabs(analyzeTone(mid, c.out, 0.5, 0, 32768).gainDb));
        }
        double spur = -300;
        for (double f = 700; f < 0.5 * c.in; f += 1019.0) {
            auto y = srcMono(sineAtRate(f, c.in, 0.5, (size_t)c.in), c.in, c.out);
            std::vector<float> mid(y.begin() + 6000, y.begin() + 6000 + 32768);
            double bh; auto m = amplitudeSpectrum(mid, c.out, 0, 32768, &bh);
            const double fr = std::fmod(f, (double)c.out);
            const long tone = std::lround((f < 0.5 * c.out ? f : std::min(fr, c.out - fr)) / bh);
            double pk = 0, pkTone = 0;
            for (size_t k = 0; k < m.size(); k++) {
                if (std::labs((long)k - tone) < 60) { pkTone = std::max(pkTone, m[k]); continue; }
                pk = std::max(pk, m[k]);
            }
            double s = pk;
            if (f > 0.5 * c.out) s = std::max(s, pkTone);
            spur = std::max(spur, dbAmp(s / 0.5));
        }
        sink.add("passband_ripple_db_20_" + std::to_string((int)pass / 1000) + "k", ripple, "lower", 0.002);
        sink.add("spurious_alias_db", spur, "lower", 3.0);
        auto t = srcMono(sineAtRate(1000, c.in, std::pow(10.0, -1.0 / 20), (size_t)c.in), c.in, c.out);
        std::vector<float> mid(t.begin() + 6000, t.begin() + 6000 + 32768);
        sink.add("thdn_1k_m1_db", analyzeTone(mid, c.out, std::pow(10.0, -1.0 / 20), 0, 32768).thdnDb, "lower", 3.0);
        sf_src* s = sf_src_create(1, c.in, c.out);
        sink.add("taps", sf_src_taps(s), "info", 0);
        sf_src_destroy(s);
    }
}

// Круговой проход корпуса 48 → 44,1 → 48 кГц: изменение громкости и пика (реальный материал).
inline void runSrcCorpus(const std::string& id, const Wav& w, Sink& sink) {
    sink.prefix = "src.roundtrip_48_44k1_48." + id + ".";
    std::vector<std::vector<float>> o;
    for (auto& ch : w.ch) o.push_back(srcMono(srcMono(ch, 48000, 44100), 44100, 48000));
    const size_t n = std::min(o[0].size(), w.ch[0].size());
    for (auto& v : o) v.resize(n);
    auto in = w.ch; for (auto& v : in) v.resize(n);
    sink.add("lufs_delta_lu", lufsIntegrated(o, 48000) - lufsIntegrated(in, 48000), "equal", 0.02);
    sink.add("null_db", nullTest(in[0], o[0], 0).residualDb, "lower", 3.0);
}

// ---- A1.4: лимитеры (линкованные, стерео) ----
struct LimResult { std::vector<std::vector<float>> out; double activePct = 0, maxRedDb = 0; };

inline LimResult limiterRun(const std::vector<std::vector<float>>& in, double ceiling, int block = 480) {
    const int C = (int)in.size();
    sf_limiter* l = sf_limiter_create(kSr, C, ceiling);
    const int lat = sf_limiter_latency_frames(l);
    const size_t n = in[0].size(), total = n + (size_t)lat;
    std::vector<float> io(total * (size_t)C, 0.0f);
    for (size_t i = 0; i < n; i++) for (int c = 0; c < C; c++) io[i * (size_t)C + (size_t)c] = in[(size_t)c][i];
    for (size_t s = 0; s < total; s += (size_t)block) sf_limiter_process(l, &io[s * (size_t)C], (int)std::min<size_t>((size_t)block, total - s));
    int64_t b = 0, a = 0; double mx = 0;
    sf_limiter_take_stats(l, &b, &a, &mx);
    sf_limiter_destroy(l);
    LimResult r;
    r.out.assign((size_t)C, std::vector<float>(n));
    for (size_t i = 0; i < n; i++) for (int c = 0; c < C; c++) r.out[(size_t)c][i] = io[(i + (size_t)lat) * (size_t)C + (size_t)c];
    r.activePct = b ? 100.0 * (double)a / (double)b : 0;
    r.maxRedDb = mx;
    return r;
}

inline double maxTruePeak(const std::vector<std::vector<float>>& x) {
    double m = kFloorDb;
    for (auto& c : x) m = std::max(m, truePeakDb(trimEdges(c, 4096)));
    return m;
}

inline void runLimiterSynth(Sink& sink) {
    for (double ceil : {-1.5, -1.0}) {
        sink.prefix = std::string("limiter.ceil") + (ceil < -1.2 ? "_m1p5" : "_m1p0") + ".";
        auto fs4 = sine(kSr / 4.0, 0.0, 1.0, kPi / 4);
        auto r = limiterRun({fs4}, ceil);
        sink.add("fs4_45.out_true_peak_dbtp", truePeakDb(trimEdges(r.out[0])), "lower", 0.02);
        sink.add("fs4_45.reduction_db", r.maxRedDb, "info", 0);
        auto m6 = sine(1000, -6, 1.0);
        auto q = limiterRun({m6}, ceil);
        sink.add("sine_1k_m6.null_db", nullTest(m6, q.out[0], 0).residualDb, "lower", 3.0);   // ниже потолка — прозрачен
        auto low = sine(100, 0.0, 2.0);
        auto lr = limiterRun({low}, ceil);
        sink.add("sine_100_0dbfs.out_true_peak_dbtp", truePeakDb(trimEdges(lr.out[0])), "lower", 0.02);
        sink.add("sine_100_0dbfs.thdn_db", analyzeTone(lr.out[0], kSr, std::pow(10.0, ceil / 20.0), 32768, 65536).thdnDb, "lower", 3.0);
        // сцепка: правый канал вдвое тише — соотношение сохраняется
        auto a = sine(7000, 0.0, 1.0, 0.3);
        std::vector<float> b(a.size());
        for (size_t i = 0; i < a.size(); i++) b[i] = 0.5f * a[i];
        auto lk = limiterRun({a, b}, ceil);
        double worst = 0, ref = 0;
        for (size_t i = 8192; i + 8192 < a.size(); i++) { worst = std::max(worst, std::fabs((double)lk.out[1][i] - 0.5 * lk.out[0][i])); ref = std::max(ref, (double)std::fabs(lk.out[0][i])); }
        sink.add("linked_ratio_err_db", dbAmp(worst / ref), "lower", 3.0);
    }
}

// Корпус: предлимитер −1,5 → лимитер устройства −1,0 при цифровом усилении 1.
inline void runLimiterCorpus(const std::string& id, const Wav& w, Sink& sink) {
    std::vector<std::vector<float>> in = w.ch;
    if (in.size() == 1) in.push_back(in[0]);
    const double lufsIn = lufsIntegrated(in, kSr);
    sink.prefix = "limiter.pre_m1p5.corpus." + id + ".";
    auto pre = limiterRun(in, -1.5);
    sink.add("true_peak_in_dbtp", maxTruePeak(in), "info", 0.05);
    sink.add("true_peak_out_dbtp", maxTruePeak(pre.out), "lower", 0.02);
    sink.add("lufs_delta_lu", lufsIntegrated(pre.out, kSr) - lufsIn, "equal", 0.05);
    sink.add("active_blocks_pct", pre.activePct, "info", 0);
    sink.add("max_reduction_db", pre.maxRedDb, "info", 0);
    sink.prefix = "limiter.chain_m1p5_m1p0.corpus." + id + ".";
    auto dev = limiterRun(pre.out, -1.0);
    sink.add("true_peak_out_dbtp", maxTruePeak(dev.out), "lower", 0.02);
    sink.add("lufs_delta_lu", lufsIntegrated(dev.out, kSr) - lufsIn, "equal", 0.05);
    sink.add("device_active_blocks_pct", dev.activePct, "lower", 0.5);
    // Без предлимитера на материале с пиками выше −1 dBTP: чистый лимитер устройства
    sink.prefix = "limiter.device_only_m1p0.corpus." + id + ".";
    auto d2 = limiterRun(in, -1.0);
    sink.add("true_peak_out_dbtp", maxTruePeak(d2.out), "lower", 0.02);
    sink.add("lufs_delta_lu", lufsIntegrated(d2.out, kSr) - lufsIn, "equal", 0.05);
    sink.add("active_blocks_pct", d2.activePct, "info", 0);
}

// ---- A1.1+: бит-в-бит хранение значений на сетке 24 бит (16/24-бит источники) ----
inline void runBitperfect(Sink& sink) {
    sink.prefix = "cache24.";
    Rng r(41);
    auto grid = [&](int bits, size_t n) {
        std::vector<float> x(n);
        for (auto& v : x) { const int64_t m = (int64_t)(r.next() >> 11) % (1ll << bits) - (1ll << (bits - 1)); v = (float)m / (float)(1ll << (bits - 1)); }
        return x;
    };
    auto maxDiffLsb = [](const std::vector<float>& a, const std::vector<float>& b) {
        double m = 0; for (size_t i = 0; i < a.size(); i++) m = std::max(m, std::fabs((double)a[i] - b[i]) * 8388608.0); return m;
    };
    auto p16 = grid(16, 96000), p24 = grid(24, 96000);
    std::vector<float> zero(48000, 0.0f);
    sink.add("bitperfect_pcm16.max_diff_lsb24", maxDiffLsb(p16, cache24Roundtrip(p16, 5)), "equal", 0);
    sink.add("bitperfect_pcm24.max_diff_lsb24", maxDiffLsb(p24, cache24Roundtrip(p24, 5)), "equal", 0);
    sink.add("bitperfect_silence.max_diff_lsb24", maxDiffLsb(zero, cache24Roundtrip(zero, 5)), "equal", 0);
    // вне сетки (результат SRC/усиления): дизер действует, ошибка ≤ 1,5 LSB
    auto off = sine(1000, -20, 1.0);
    sink.add("offgrid_sine.max_diff_lsb24", maxDiffLsb(off, cache24Roundtrip(off, 5)), "lower", 0.1);
}

// ---- A1.4+: статическое усиление по true-peak трека перед предлимитером ----
inline double staticGainDb(double tpDbtp) { return std::min(0.0, -1.55 - tpDbtp); }

inline double trackTruePeak(const std::vector<std::vector<float>>& ch) {
    const int C = (int)ch.size();
    sf_meter* m = sf_meter_create(C);
    std::vector<float> io(ch[0].size() * (size_t)C);
    for (size_t i = 0; i < ch[0].size(); i++) for (int c = 0; c < C; c++) io[i * (size_t)C + (size_t)c] = ch[(size_t)c][i];
    sf_meter_process(m, io.data(), (int64_t)ch[0].size());
    const double tp = sf_meter_true_peak_dbtp(m);
    sf_meter_destroy(m);
    return tp;
}

inline void runStaticGainCorpus(const std::string& id, const Wav& w, Sink& sink) {
    std::vector<std::vector<float>> in = w.ch;
    if (in.size() == 1) in.push_back(in[0]);
    const double tp = trackTruePeak(in), g = staticGainDb(tp), lin = std::pow(10.0, g / 20.0);
    auto scaled = in;
    for (auto& c : scaled) for (auto& v : c) v = (float)((double)v * lin);
    auto lim = limiterRun(scaled, -1.5);
    sink.prefix = "limiter.static_m1p5.corpus." + id + ".";
    sink.add("track_true_peak_dbtp", tp, "info", 0);
    sink.add("static_gain_db", g, "equal", 0.05);
    sink.add("active_blocks_pct", lim.activePct, "lower", 0.5);
    sink.add("max_reduction_db", lim.maxRedDb, "info", 0);
    sink.add("true_peak_out_dbtp", maxTruePeak(lim.out), "lower", 0.02);
    sink.add("lufs_delta_lu", lufsIntegrated(lim.out, kSr) - lufsIntegrated(in, kSr), "equal", 0.05);
}

inline void runStaticGainSynth(Sink& sink) {
    sink.prefix = "limiter.static_m1p5.";
    auto low = sine(100, 0.0, 2.0);
    const double g = staticGainDb(trackTruePeak({low}));
    for (auto& v : low) v = (float)((double)v * std::pow(10.0, g / 20.0));
    auto lr = limiterRun({low}, -1.5);
    sink.add("sine_100_0dbfs.thdn_db", analyzeTone(lr.out[0], kSr, std::pow(10.0, (-1.55) / 20.0), 32768, 65536).thdnDb, "lower", 3.0);
    sink.add("sine_100_0dbfs.active_blocks_pct", lr.activePct, "lower", 0.5);
}

// ---- A1.5: закон громкости (только измерение) ----
// Цифровое ослабление тона −20 dBFS на 30 dB с последующим выводом в float / 24 бит / 16 бит (TPDF). Показывает, что уровень шума
// определяется разрядностью ТРАКТА ПОСЛЕ ослабления: пока ОС микширует во float, ослабление в приложении и системной громкостью
// эквивалентны в цифровом домене. Различие на выходе ЦАП/усилителя телефона цифровой лабораторией не измеряется (нужен микрофон, A2).
inline std::vector<float> quantizeBits(const std::vector<float>& x, int bits, uint64_t seed) {
    Rng r(seed);
    const double q = std::pow(2.0, bits - 1);
    std::vector<float> y(x.size());
    for (size_t i = 0; i < x.size(); i++) {
        const double d = (r.uniform() - 0.5) + (r.uniform() - 0.5);
        y[i] = (float)(std::floor((double)x[i] * q + d + 0.5) / q);
    }
    return y;
}

inline void runVolumeLaw(Sink& sink) {
    const double tone = -20.0;
    for (double att : {-30.0, -50.0}) {
        sink.prefix = std::string("volume_law.sine_1k_m20_att") + (att == -30.0 ? "_m30" : "_m50") + ".";
        const double level = tone + att;
        const double lin = std::pow(10.0, att / 20.0);
        auto x = sine(1000, tone, 3.0);
        for (auto& v : x) v = (float)((double)v * lin);
        struct V { const char* name; std::vector<float> y; } vs[] = {
            {"float32", x}, {"pcm24", quantizeBits(x, 24, 31)}, {"pcm16", quantizeBits(x, 16, 32)}};
        for (auto& v : vs) {
            auto t = analyzeTone(v.y, kSr, std::pow(10.0, level / 20.0));
            sink.add(std::string(v.name) + ".thdn_db", t.thdnDb, "info", 0);
            sink.add(std::string(v.name) + ".residual_dbfs", t.thdnDb + level, "info", 0);
        }
    }
}

}  // namespace sflab
