// Набор сигналов и прогон цепочки через метрики ТЗ §2.
#pragma once
#include <cstdint>
#include <functional>
#include <map>
#include <string>

#include "json.hpp"
#include "metrics.hpp"
#include "wavio.hpp"

namespace sflab {

constexpr int kSr = 48000;

struct Rng {  // xorshift128+, детерминированный
    uint64_t s0, s1;
    explicit Rng(uint64_t seed) : s0(seed * 0x9E3779B97F4A7C15ull + 1), s1(seed ^ 0xD1B54A32D192ED03ull) { for (int i = 0; i < 8; i++) next(); }
    uint64_t next() { uint64_t x = s0; const uint64_t y = s1; s0 = y; x ^= x << 23; s1 = x ^ y ^ (x >> 17) ^ (y >> 26); return s1 + y; }
    double uniform() { return (double)(next() >> 11) * (1.0 / 9007199254740992.0); }          // [0,1)
    double gauss() { const double u1 = std::max(uniform(), 1e-300), u2 = uniform(); return std::sqrt(-2 * std::log(u1)) * std::cos(2 * kPi * u2); }
};

inline std::vector<float> sine(double f, double dbfs, double seconds, double phase = 0.0) {
    const size_t n = (size_t)std::llround(seconds * kSr);
    std::vector<float> x(n);
    const double a = std::pow(10.0, dbfs / 20.0);
    for (size_t i = 0; i < n; i++) x[i] = (float)(a * std::sin(2 * kPi * f * (double)i / kSr + phase));
    return x;
}

constexpr double kEssF1 = 20.0, kEssF2 = 23500.0, kEssT = 6.0, kEssAmp = 0.5;
inline Ess essSpec() { return Ess{kEssF1, kEssF2, kEssT, kSr}; }

// имя → сигнал; порядок фиксирован
inline std::vector<std::pair<std::string, std::vector<float>>> synthSignals() {
    std::vector<std::pair<std::string, std::vector<float>>> s;
    s.push_back({"sine_1k_m1", sine(1000, -1, 2.0)});
    s.push_back({"sine_1k_m20", sine(1000, -20, 2.0)});
    s.push_back({"sine_1k_m60", sine(1000, -60, 2.0)});
    for (int f : {20, 100, 5000, 10000, 16000, 20000}) s.push_back({"sine_" + std::to_string(f) + "_m6", sine(f, -6, 2.0)});
    s.push_back({"silence", std::vector<float>(kSr, 0.0f)});
    {
        std::vector<float> x(kSr / 2, 0.0f); x[8192] = 0.5f;
        s.push_back({"impulse", x});
    }
    s.push_back({"tp_fs4_45", sine(kSr / 4.0, 0.0, 1.0, kPi / 4)});
    {
        auto lo = sine(60, 20 * std::log10(0.56), 3.0), hi = sine(7000, 20 * std::log10(0.14), 3.0);
        for (size_t i = 0; i < lo.size(); i++) lo[i] += hi[i];
        s.push_back({"imd_smpte", lo});
    }
    {
        auto a = sine(19000, 20 * std::log10(0.35), 3.0), b = sine(20000, 20 * std::log10(0.35), 3.0);
        for (size_t i = 0; i < a.size(); i++) a[i] += b[i];
        s.push_back({"imd_ccif", a});
    }
    {
        Rng r(7); std::vector<float> x(3 * kSr);
        for (auto& v : x) v = (float)((r.uniform() * 2 - 1) * 0.25);
        s.push_back({"noise_wb", x});
    }
    s.push_back({"ess", essSpec().signal(kEssAmp)});
    return s;
}

using ChainFn = std::function<std::vector<float>(const std::string& name, const std::vector<float>& in)>;

struct Sink {
    MetricList list;
    std::string prefix;
    void add(const std::string& key, double v, const char* better, double tol) { list.push_back({prefix + key, Metric{v, better, tol}}); }
};

struct ChainTraits {
    bool integerPath = true;     // выход = вход с целым лагом → имеет смысл null-тест
    bool timeInvariant = true;   // стационарная цепочка → имеет смысл свип (ESS)
};

inline std::vector<float> trimEdges(const std::vector<float>& x, size_t m = 2048) {
    return x.size() > 2 * m ? std::vector<float>(x.begin() + (long)m, x.end() - (long)m) : x;
}

inline void runChainSuite(const std::string& chain, const ChainFn& fn, Sink& sink, ChainTraits tr = {}) {
    sink.prefix = chain + ".";
    std::map<std::string, std::vector<float>> out;
    auto sigs = synthSignals();
    std::map<std::string, std::vector<float>> in;
    for (auto& [name, x] : sigs) { in[name] = x; out[name] = fn(name, x); }

    for (const char* nm : {"sine_1k_m1", "sine_1k_m20", "sine_1k_m60"}) {
        const double level = std::string(nm) == "sine_1k_m1" ? -1 : std::string(nm) == "sine_1k_m20" ? -20 : -60;
        auto t = analyzeTone(out[nm], kSr, std::pow(10.0, level / 20.0));
        const std::string p = std::string(nm) + ".";
        sink.add(p + "thdn_db", t.thdnDb, "lower", 1.5);
        sink.add(p + "gain_err_db", t.gainDb, "equal", 0.01);
        sink.add(p + "h2_dbc", t.h2, "lower", 3); sink.add(p + "h3_dbc", t.h3, "lower", 3);
        sink.add(p + "h4_dbc", t.h4, "lower", 3); sink.add(p + "h5_dbc", t.h5, "lower", 3);
    }
    for (int f : {100, 5000, 10000, 16000, 20000}) {
        const std::string nm = "sine_" + std::to_string(f) + "_m6";
        auto t = analyzeTone(out[nm], kSr, std::pow(10.0, -6.0 / 20.0));
        sink.add(nm + ".gain_err_db", t.gainDb, "equal", 0.01);
        sink.add(nm + ".thdn_db", t.thdnDb, "lower", 1.5);
    }
    {   // 20 Гц: окно шире, только усиление
        auto t = analyzeTone(out["sine_20_m6"], kSr, std::pow(10.0, -6.0 / 20.0));
        sink.add("sine_20_m6.gain_err_db", t.gainDb, "equal", 0.01);
    }
    {
        const auto& y = out["silence"];
        double m = 0; for (size_t i = 4096; i + 4096 < y.size(); i++) m = std::max(m, (double)std::fabs(y[i]));
        sink.add("silence.floor_peak_dbfs", dbAmp(m), "lower", 3);
    }
    if (tr.integerPath) for (const char* nm : {"sine_1k_m1", "noise_wb", "ess"}) {
        auto nr = nullTest(in[nm], out[nm]);
        sink.add(std::string(nm) + ".null_db", nr.residualDb, "lower", 1.5);
        sink.add(std::string(nm) + ".null_lag", nr.lag, "info", 0);
    }
    if (tr.timeInvariant) {
        auto e = analyzeEss(essSpec(), out["ess"], kEssAmp);
        double ref = 0; for (size_t i = 0; i < e.freqs.size(); i++) if (std::fabs(e.freqs[i] - 1000) < 100) ref = e.magDb[i];
        double dev = 0, gmin = 1e9, gmax = -1e9, m20k = 0;
        for (size_t i = 0; i < e.freqs.size(); i++) {
            dev = std::max(dev, std::fabs(e.magDb[i] - ref));
            if (e.freqs[i] >= 100 && e.freqs[i] <= 16000) { gmin = std::min(gmin, e.gdSamples[i]); gmax = std::max(gmax, e.gdSamples[i]); }
            if (e.freqs[i] > 19000) m20k = e.magDb[i] - ref;
        }
        sink.add("ess.fr_dev_db_20_20k", dev, "lower", 0.02);
        sink.add("ess.fr_20k_db", m20k, "equal", 0.02);
        sink.add("ess.gd_spread_us", (gmax - gmin) * 1e6 / kSr, "lower", 1.0);
        sink.add("ess.peak_offset_samples", (double)e.peakIndex, "info", 0);
        sink.add("ess.h2_1k_db", e.h2_1k, "lower", 3);
        sink.add("ess.h3_1k_db", e.h3_1k, "lower", 3);
    }
    sink.add("imd_smpte.imd_db", imdSmpte(out["imd_smpte"], kSr), "lower", 2);
    sink.add("imd_ccif.imd_db", imdCcif(out["imd_ccif"], kSr), "lower", 2);
    sink.add("tp_fs4_45.in_true_peak_dbtp", truePeakDb(trimEdges(in["tp_fs4_45"])), "info", 0.05);
    sink.add("tp_fs4_45.out_true_peak_dbtp", truePeakDb(trimEdges(out["tp_fs4_45"])), "lower", 0.05);
    sink.add("tp_fs4_45.out_sample_peak_dbfs", peakDb(trimEdges(out["tp_fs4_45"])), "info", 0.05);
}

// Корпус: поканально, громкость по паре каналов.
inline void runCorpus(const std::string& chain, const ChainFn& fn, const std::string& id, const Wav& w, Sink& sink, bool integerPath = true) {
    sink.prefix = chain + ".corpus." + id + ".";
    std::vector<std::vector<float>> outs;
    for (size_t c = 0; c < w.ch.size(); c++) outs.push_back(fn("corpus_" + id + "_" + std::to_string(c), w.ch[c]));
    sink.add("lufs_in", lufsIntegrated(w.ch, w.sampleRate), "info", 0.05);
    sink.add("lufs_delta_lu", lufsIntegrated(outs, w.sampleRate) - lufsIntegrated(w.ch, w.sampleRate), "equal", 0.02);
    sink.add("true_peak_in_dbtp", truePeakDb(trimEdges(w.ch[0], 4096)), "info", 0.05);
    sink.add("true_peak_out_dbtp", truePeakDb(trimEdges(outs[0], 4096)), "lower", 0.05);
    sink.add("sample_peak_in_dbfs", peakDb(w.ch[0]), "info", 0.05);
    if (integerPath) sink.add("null_db", nullTest(w.ch[0], outs[0]).residualDb, "lower", 1.5);
}

}  // namespace sflab
