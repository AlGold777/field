// Тесты ядра sfdsp: собственный минимальный раннер, без внешних библиотек.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "sfdsp.h"
#include "suite.hpp"

using namespace sflab;

namespace {
int g_fail = 0, g_checks = 0;
const char* g_test = "";

#define CHECK(cond) do { g_checks++; if (!(cond)) { g_fail++; std::printf("  FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, g_test, #cond); } } while (0)
#define CHECK_NEAR(a, b, tol) do { g_checks++; const double _a = (a), _b = (b); if (!(std::fabs(_a - _b) <= (tol))) { g_fail++; std::printf("  FAIL %s:%d [%s] %s=%.9g vs %s=%.9g (tol %g)\n", __FILE__, __LINE__, g_test, #a, _a, #b, _b, (double)(tol)); } } while (0)

struct T { const char* name; std::function<void()> fn; };
std::vector<T>& tests() { static std::vector<T> t; return t; }
struct Reg { Reg(const char* n, std::function<void()> f) { tests().push_back({n, f}); } };
#define TEST(name) static void name(); static Reg reg_##name(#name, name); static void name()

std::vector<float> render(const std::vector<float>& in, double frac, double ppm, int block) {
    sf_playout* p = sf_playout_create(48000, 960);
    std::vector<float> out(in.size(), 0.f), tmp((size_t)block);
    for (size_t s = 0; s < in.size(); s += (size_t)block) {
        const int n = (int)std::min<size_t>((size_t)block, in.size() - s);
        sf_playout_render(p, in.data(), 0, (int64_t)in.size(), (double)s * (1 + ppm * 1e-6) + frac - 16, n, tmp.data());
        for (int i = 0; i < n; i++) out[s + (size_t)i] = std::isnan(tmp[(size_t)i]) ? 0.f : tmp[(size_t)i];
    }
    sf_playout_destroy(p);
    return out;
}

// ---- ядро ----
TEST(version_and_abi) {
    CHECK(std::strcmp(sfdsp_version(), SFDSP_VERSION) == 0);
    CHECK(sfdsp_abi_version() == SFDSP_ABI_VERSION);
}

TEST(sinc_rows_sum_to_one_and_are_symmetric) {
    for (int ph = 0; ph < SF_SINC_PHASES; ph++) {
        double s = 0; for (int t = 0; t < SF_SINC_TAPS; t++) s += sf_sinc_coefficient(ph, t);
        CHECK_NEAR(s, 1.0, 1e-6);
    }
    CHECK(std::isnan(sf_sinc_coefficient(-1, 0)) && std::isnan(sf_sinc_coefficient(0, 32)));
    CHECK(sf_sinc_coefficient(0, 15) == 1.0f);  // фаза 0: тождественный отвод
}

TEST(sinc_integer_positions_are_exact) {
    Rng r(1); std::vector<float> x(1000);
    for (auto& v : x) v = (float)(r.uniform() * 2 - 1);
    for (int64_t i = 20; i < 900; i += 7) CHECK(sf_sinc_interpolate(x.data(), 0, 1000, (double)i) == x[(size_t)i]);
}

TEST(sinc_missing_taps_give_nan) {
    std::vector<float> x(100, 1.f);
    CHECK(std::isnan(sf_sinc_interpolate(x.data(), 0, 100, 5.0)));       // слева нет 10 отводов
    CHECK(std::isnan(sf_sinc_interpolate(x.data(), 0, 100, 95.0)));      // справа
    CHECK(!std::isnan(sf_sinc_interpolate(x.data(), 0, 100, 50.5)));
    CHECK(std::isnan(sf_sinc_interpolate(x.data(), 0, 100, NAN)));
    std::vector<float> h = x; h[50] = NAN;
    CHECK(std::isnan(sf_sinc_interpolate(h.data(), 0, 100, 50.3)));      // NaN во входе = отсутствующий кадр
}

TEST(sinc_base_offset) {
    Rng r(2); std::vector<float> x(200);
    for (auto& v : x) v = (float)(r.uniform() - 0.5);
    CHECK(sf_sinc_interpolate(x.data(), 0, 200, 100.3) == sf_sinc_interpolate(x.data() + 50, 50, 150, 100.3));
}

TEST(sinc_dc_and_low_tone_accuracy) {
    std::vector<float> dc(400, 0.5f);
    CHECK_NEAR(sf_sinc_interpolate(dc.data(), 0, 400, 200.37), 0.5, 1e-6);
    auto x = sine(1000, -6, 0.05);
    for (double fr : {0.1, 0.5, 0.9}) {
        const double pos = 1000 + fr;
        const double ref = std::pow(10.0, -6.0 / 20) * std::sin(2 * kPi * 1000 * pos / 48000);
        CHECK_NEAR(sf_sinc_interpolate(x.data(), 0, (int64_t)x.size(), pos), ref, 2e-4);
    }
}

TEST(playout_block_size_independent_when_on_time) {
    auto in = sine(997, -3, 1.0);
    auto ref = render(in, 0.37, 0, 480);
    for (int b : {1, 37, 240, 4096}) {
        auto o = render(in, 0.37, 0, b);
        bool same = true;
        for (size_t i = 0; i < o.size(); i++) if (o[i] != ref[i]) { same = false; break; }
        CHECK(same);
    }
}

TEST(playout_integer_path_is_bit_exact) {
    Rng r(3); std::vector<float> in(20000);
    for (auto& v : in) v = (float)(r.uniform() * 1.6 - 0.8);
    auto out = render(in, 0.0, 0, 480);
    bool same = true;
    for (size_t i = 100; i + 100 < in.size(); i++) if (out[i] != in[i - 16]) { same = false; break; }
    CHECK(same);
}

TEST(playout_fractional_positions_stay_fractional) {
    sf_playout* p = sf_playout_create(48000, 960);
    sf_playout_plan(p, 10000.25, 480);
    CHECK_NEAR(sf_playout_position(p, 0), 10000.25, 1e-12);
    sf_playout_plan(p, 10485.05, 480);
    CHECK(sf_playout_position(p, 1) > sf_playout_position(p, 0) + 1.0);
    CHECK(sf_playout_position(p, 1) < sf_playout_position(p, 0) + 1.001);
    sf_playout_destroy(p);
}

TEST(playout_converges_under_100ppm_drift) {
    sf_playout* p = sf_playout_create(48000, 960);
    double ideal = 100000.0;
    for (int i = 0; i < 12000; i++) { sf_playout_plan(p, ideal, 480); ideal += 480 * 1.0001; }
    sf_playout_plan(p, ideal, 480);
    CHECK(std::fabs(sf_playout_sync_error_us(p)) <= 2.0);
    CHECK(sf_playout_hard_jumps(p) == 0);
    CHECK(sf_playout_slew_events(p) > 0);
    sf_playout_destroy(p);
}

TEST(playout_100us_step_settles) {
    sf_playout* p = sf_playout_create(48000, 960);
    sf_playout_plan(p, 1000.0, 480);
    double ideal = 1000.0 + 480 + 4.8;
    for (int i = 0; i < 1000; i++) { sf_playout_plan(p, ideal, 480); ideal += 480; }
    sf_playout_plan(p, ideal, 480);
    CHECK(std::fabs(sf_playout_sync_error_us(p)) <= 20.0);
    sf_playout_destroy(p);
}

TEST(playout_hard_resync_and_reset) {
    sf_playout* p = sf_playout_create(48000, 960);
    sf_playout_plan(p, 5000.0, 480);
    sf_playout_plan(p, 5480.0, 480);
    sf_playout_plan(p, 90000.0, 480);
    CHECK(sf_playout_hard_jumps(p) == 1);
    CHECK_NEAR(sf_playout_position(p, 0), 90000.0, 1e-9);
    sf_playout_reset(p);
    sf_playout_plan(p, 1.0, 480);
    CHECK(sf_playout_hard_jumps(p) == 1);  // после reset скачка нет
    CHECK(sf_playout_latency_frames(p) == SF_SINC_GROUP_DELAY_FRAMES);
    sf_playout_destroy(p);
}

// ---- аналитика лаборатории (проверка самих измерителей) ----
TEST(lab_tone_analyzer_known_distortion) {
    auto x = sine(1000, -6, 2.0);
    auto h = sine(2000, -6 - 80, 2.0);  // вторая гармоника −80 dBc
    for (size_t i = 0; i < x.size(); i++) x[i] += h[i];
    auto t = analyzeTone(x, 48000, std::pow(10.0, -6.0 / 20));
    CHECK_NEAR(t.h2, -80.0, 0.3);
    CHECK_NEAR(t.thdnDb, -80.0, 0.5);
    CHECK_NEAR(t.gainDb, 0.0, 0.01);
}

TEST(lab_thdn_resolves_minus_120) {
    auto x = sine(1000, -6, 2.0);
    auto h = sine(3000, -6 - 120, 2.0);
    for (size_t i = 0; i < x.size(); i++) x[i] += h[i];
    auto t = analyzeTone(x, 48000, std::pow(10.0, -6.0 / 20));
    CHECK_NEAR(t.h3, -120.0, 1.0);
}

TEST(lab_true_peak_fs4_45) {
    auto x = sine(12000, 0.0, 0.5, kPi / 4);
    CHECK_NEAR(peakDb(x), -3.0103, 0.01);
    CHECK_NEAR(truePeakDb(x), 0.0, 0.05);
    auto y = sine(1000, -6, 0.5);
    CHECK_NEAR(truePeakDb(y), -6.0, 0.05);
    for (double f : {5000.0, 9000.0, 15000.0, 18000.0})
        for (double ph : {0.3, 1.1, 2.0}) { const double v = truePeakDb(sine(f, -1.0, 0.2, ph)); CHECK(v < -0.99 && v > -1.2); }
}

TEST(lab_loudness_997hz_calibration) {
    auto a = sine(997, -23.0, 5.0);
    CHECK_NEAR(lufsIntegrated({a, a}, 48000), -23.0, 0.1);
    auto q = sine(997, -23.0 - 20, 5.0);
    CHECK_NEAR(lufsIntegrated({q, q}, 48000), -43.0, 0.1);
    // абсолютный гейт: тишина ниже −70 LUFS
    auto z = sine(997, -90.0, 3.0);
    CHECK(lufsIntegrated({z, z}, 48000) <= -70.0);
}

TEST(lab_ess_identity_is_flat_with_zero_distortion) {
    auto e = essSpec();
    auto y = e.signal(kEssAmp);
    auto r = analyzeEss(e, y, kEssAmp);
    CHECK(r.peakIndex == 0);
    for (size_t i = 0; i < r.freqs.size(); i++) CHECK_NEAR(r.magDb[i], 0.0, 0.05);
    CHECK(r.h2_1k < -100 && r.h3_1k < -100);
}

TEST(lab_ess_sees_delay_and_cubic_distortion) {
    auto e = essSpec();
    auto x = e.signal(kEssAmp);
    std::vector<float> y(x.size() + 10, 0.f);
    for (size_t i = 0; i < x.size(); i++) y[i + 10] = (float)((double)x[i] + 0.01 * (double)x[i] * x[i] * x[i] / (kEssAmp * kEssAmp));  // H3 ≈ 0.01·(1/4)
    auto r = analyzeEss(e, y, kEssAmp);
    CHECK(r.peakIndex == 10);
    CHECK(r.h3_1k > -60 && r.h3_1k < -40);
}

TEST(lab_imd_detects_intermodulation) {
    auto s = synthSignals();
    std::vector<float> lo, ccif;
    for (auto& [n, x] : s) { if (n == "imd_smpte") lo = x; if (n == "imd_ccif") ccif = x; }
    CHECK(imdSmpte(lo, 48000) < -140);
    CHECK(imdCcif(ccif, 48000) < -140);
    std::vector<float> d = lo;
    for (auto& v : d) v = v + 0.01f * v * v;  // квадратичная нелинейность
    CHECK(imdSmpte(d, 48000) > -60);
}

TEST(lab_null_finds_lag) {
    Rng r(9); std::vector<float> in(20000), out(20000, 0.f);
    for (auto& v : in) v = (float)(r.uniform() - 0.5);
    for (size_t i = 5; i < in.size(); i++) out[i] = in[i - 5];
    auto n = nullTest(in, out);
    CHECK(n.lag == 5 && n.residualDb < -190);
}

TEST(lab_json_roundtrip) {
    MetricList m = {{"a.b", Metric{-12.5, "lower", 1.5}}, {"a.c", Metric{3, "equal", 0.01}}, {"x.nan", Metric{NAN, "info", 0}}};
    auto parsed = parseMetrics(toJson(m, "  \"v\": 1,\n"));
    CHECK(parsed.size() == 3);
    CHECK_NEAR(parsed["a.b"].v, -12.5, 1e-9);
    CHECK(parsed["a.b"].better == "lower");
    CHECK_NEAR(parsed["a.c"].tol, 0.01, 1e-9);
    CHECK(std::isnan(parsed["x.nan"].v));
}

}  // namespace

int main() {
    for (auto& t : tests()) {
        g_test = t.name;
        const int before = g_fail;
        t.fn();
        std::printf("%s %s\n", g_fail == before ? "ok  " : "FAIL", t.name);
    }
    std::printf("dsp_tests: %d checks, %d failed\n", g_checks, g_fail);
    return g_fail ? 1 : 0;
}
