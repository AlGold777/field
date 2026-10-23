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


// ---- A1.1: PCM 24 бит и дизеринг ----
TEST(pcm24_roundtrip_exact_without_dither) {
    Rng r(11); std::vector<float> in(100000);
    for (auto& v : in) v = (float)(int32_t)((r.next() >> 40) & 0xFFFFFF) / 8388608.0f - 1.0f;  // значения сетки 24 бит
    for (int be : {0, 1}) {
        std::vector<uint8_t> b(in.size() * 3);
        sf_pcm24_encode(nullptr, in.data(), 1, (int)in.size(), b.data(), 3, be);
        std::vector<float> out(in.size());
        sf_pcm24_decode(b.data(), 3, (int)in.size(), be, out.data(), 1);
        bool same = true; for (size_t i = 0; i < in.size(); i++) if (in[i] != out[i]) { same = false; break; }
        CHECK(same);
    }
}

TEST(pcm24_byte_order_clamp_nan_and_strides) {
    const float in[6] = {1.0f, -1.0f, 2.0f, NAN, 0.5f, -0.5f};
    uint8_t le[18], be[18];
    sf_pcm24_encode(nullptr, in, 1, 6, le, 3, 0);
    sf_pcm24_encode(nullptr, in, 1, 6, be, 3, 1);
    CHECK(le[0] == 0xFF && le[1] == 0xFF && le[2] == 0x7F);   // +1.0 → 8388607
    CHECK(be[0] == 0x7F && be[1] == 0xFF && be[2] == 0xFF);
    CHECK(le[3] == 0x00 && le[4] == 0x00 && le[5] == 0x80);   // −1.0 → −8388608
    CHECK(le[6] == 0xFF && le[8] == 0x7F);                    // 2.0 ограничено
    CHECK(le[9] == 0 && le[10] == 0 && le[11] == 0);          // NaN → 0
    CHECK(le[12] == 0x00 && le[13] == 0x00 && le[14] == 0x40);
    // интерлив стерео: шаг 6 байт / 2 float
    float st[8] = {0.25f, -0.25f, 0.5f, -0.5f, 0.125f, -0.125f, 0.0f, 0.0f};
    uint8_t sb[24];
    sf_pcm24_encode(nullptr, st + 0, 2, 4, sb + 0, 6, 0);
    sf_pcm24_encode(nullptr, st + 1, 2, 4, sb + 3, 6, 0);
    float back[8];
    sf_pcm24_decode(sb + 0, 6, 4, 0, back + 0, 2);
    sf_pcm24_decode(sb + 3, 6, 4, 0, back + 1, 2);
    for (int i = 0; i < 8; i++) CHECK(back[i] == st[i]);
}

TEST(pcm24_tpdf_dither_statistics) {
    sf_dither* d = sf_dither_create(5);
    const int n = 2000000;
    const double off = 0.37;   // доля LSB: значение вне сетки 24 бит — дизер применяется
    std::vector<float> in((size_t)n, (float)(off / 8388608.0));
    std::vector<uint8_t> b((size_t)n * 3);
    sf_pcm24_encode(d, in.data(), 1, n, b.data(), 3, 0);
    std::vector<float> o((size_t)n);
    sf_pcm24_decode(b.data(), 3, n, 0, o.data(), 1);
    double sum = 0, sq = 0; int hist[7] = {0};
    for (float v : o) { const double q = (double)v * 8388608.0; sum += q; sq += (q - off) * (q - off); const int k = (int)std::lround(q); if (k >= -3 && k <= 3) hist[k + 3]++; }
    CHECK_NEAR(sum / n, off, 0.003);                        // дизер без смещения
    CHECK_NEAR(sq / n, 1.0 / 6.0 + 1.0 / 12.0, 0.01);       // TPDF (2·1/12) + округление (1/12)
    CHECK(hist[0] == 0 && hist[6] == 0);                    // в пределах ±1 LSB после округления
    sf_dither* d2 = sf_dither_create(6);
    std::vector<uint8_t> b2((size_t)1000 * 3);
    sf_pcm24_encode(d2, in.data(), 1, 1000, b2.data(), 3, 0);
    int diff = 0; for (int i = 0; i < 1000 * 3; i++) if (b[(size_t)i] != b2[(size_t)i]) diff++;
    CHECK(diff > 200);                                      // разные seed → разные потоки
    sf_dither_destroy(d); sf_dither_destroy(d2);
}

TEST(pcm24_values_on_the_24bit_grid_are_stored_bit_exact_even_with_dither) {
    Rng r(12);
    std::vector<float> in(200000);
    for (size_t i = 0; i < in.size(); i++) {
        const int bits = (i % 3 == 0) ? 16 : 24;               // 16-бит и 24-бит PCM, приведённые к float
        const int64_t m = (int64_t)(r.next() >> 11) % (1ll << bits) - (1ll << (bits - 1));
        in[i] = (float)m / (float)(1ll << (bits - 1));
    }
    in[0] = 0.0f; in[1] = -1.0f;
    sf_dither* d = sf_dither_create(9);
    std::vector<uint8_t> b(in.size() * 3);
    sf_pcm24_encode(d, in.data(), 1, (int)in.size(), b.data(), 3, 0);
    std::vector<float> out(in.size());
    sf_pcm24_decode(b.data(), 3, (int)in.size(), 0, out.data(), 1);
    bool same = true; for (size_t i = 0; i < in.size(); i++) if (in[i] != out[i]) { same = false; break; }
    CHECK(same);
    sf_dither_destroy(d);
}

TEST(meter_true_peak_matches_lab_measurement_and_is_block_independent) {
    auto x = sine(12000, 0.0, 1.0, kPi / 4);                 // sample peak −3 dB, true-peak 0 dBTP
    sf_meter* m = sf_meter_create(1);
    for (size_t s = 0; s < x.size(); s += 333) sf_meter_process(m, x.data() + s, (int64_t)std::min<size_t>(333, x.size() - s));
    // анализатор считает и края потока (резкое начало даёт выброс Гиббса до ~0,1 dB), лабораторный измеритель края отбрасывает
    CHECK_NEAR(sf_meter_true_peak_dbtp(m), 0.0, 0.15);
    CHECK_NEAR(sf_meter_true_peak_dbtp(m), truePeakDb(x), 0.15);
    sf_meter_destroy(m);
    // стерео: максимум по каналам; тишина
    auto q = sine(1000, -6, 0.5);
    std::vector<float> st(q.size() * 2, 0.0f);
    for (size_t i = 0; i < q.size(); i++) st[2 * i + 1] = q[i];
    m = sf_meter_create(2);
    sf_meter_process(m, st.data(), (int64_t)q.size());
    CHECK_NEAR(sf_meter_true_peak_dbtp(m), -6.0, 0.05);
    sf_meter_destroy(m);
    m = sf_meter_create(2);
    std::vector<float> z(2000, 0.0f);
    sf_meter_process(m, z.data(), 1000);
    CHECK(sf_meter_true_peak_dbtp(m) <= -199.0);
    sf_meter_destroy(m);
}

// ---- A1.2: SRC ----
std::vector<float> srcAll(const std::vector<float>& in, int ch, int rin, int rout, int chunk) {
    sf_src* s = sf_src_create(ch, rin, rout);
    const int64_t nIn = (int64_t)in.size() / ch;
    std::vector<float> out((size_t)(sf_src_max_output(s, nIn) + sf_src_max_output(s, 0)) * (size_t)ch);
    int64_t w = 0;
    for (int64_t p = 0; p < nIn; p += chunk) {
        const int64_t m = std::min<int64_t>(chunk, nIn - p);
        w += sf_src_process(s, in.data() + p * ch, m, out.data() + w * ch, (int64_t)out.size() / ch - w);
    }
    w += sf_src_flush(s, out.data() + w * ch, (int64_t)out.size() / ch - w);
    out.resize((size_t)(w * ch));
    sf_src_destroy(s);
    return out;
}

std::vector<float> sineSr(double f, double sr, double amp, size_t n, double ph = 0.0) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; i++) x[i] = (float)(amp * std::sin(2 * kPi * f * (double)i / sr + ph));
    return x;
}

TEST(src_output_length_and_params) {
    CHECK(sf_src_create(0, 44100, 48000) == nullptr);
    CHECK(sf_src_create(2, 100, 48000) == nullptr);
    std::vector<float> x(44100, 0.1f);
    CHECK(srcAll(x, 1, 44100, 48000, 4096).size() == 48000);
    CHECK(srcAll(x, 1, 96000, 48000, 4096).size() == 22050);
    CHECK(srcAll(std::vector<float>(10, 0.1f), 1, 44100, 48000, 4096).size() == 11);
    sf_src* s = sf_src_create(1, 44100, 48000);
    CHECK(sf_src_phases(s) == 160 && sf_src_taps(s) >= 160 && sf_src_taps(s) <= 200);
    sf_src_destroy(s);
}

TEST(src_passband_flat_and_spurs_below_minus_120) {
    struct Case { int in, out; } cases[] = {{44100, 48000}, {96000, 48000}, {88200, 48000}, {32000, 48000}, {192000, 48000}};
    for (auto c : cases) {
        double worstRipple = 0, worstSpur = -300;
        const double pass = std::min(20000.0, 0.92 * 0.5 * std::min(c.in, c.out));
        for (double f : {20.0, 100.0, 1000.0, 5000.0, 10000.0, 15000.0, 18000.0, 19000.0, 19900.0, 20000.0}) {
            if (f > pass) continue;
            auto y = srcAll(sineSr(f, c.in, 0.5, (size_t)c.in), 1, c.in, c.out, 4096);
            std::vector<float> mid(y.begin() + 6000, y.end() - 6000);
            worstRipple = std::max(worstRipple, std::fabs(analyzeTone(mid, c.out, 0.5, 0, 32768).gainDb));
        }
        for (double f = 700; f < 0.5 * c.in; f += 1019.0) {
            auto y = srcAll(sineSr(f, c.in, 0.5, (size_t)c.in), 1, c.in, c.out, 4096);
            std::vector<float> mid(y.begin() + 6000, y.begin() + 6000 + 32768);
            double bh; auto m = amplitudeSpectrum(mid, c.out, 0, 32768, &bh);
            const double fr = std::fmod(f, (double)c.out);
            const long tone = std::lround((f < 0.5 * c.out ? f : std::min(fr, c.out - fr)) / bh);
            double pk = 0, pkTone = 0;
            for (size_t k = 0; k < m.size(); k++) {
                if (std::labs((long)k - tone) < 60) { pkTone = std::max(pkTone, m[k]); continue; }
                pk = std::max(pk, m[k]);
            }
            double spur = pk;
            if (f > 0.5 * c.out) spur = std::max(spur, pkTone);   // выше Найквиста выхода тон не должен проходить вовсе (алиас)
            worstSpur = std::max(worstSpur, dbAmp(spur / 0.5));
        }
        CHECK(worstRipple <= 0.01);
        CHECK(worstSpur <= -120.0);
    }
}

TEST(src_fine_phase_path_44101_to_48000) {
    for (double f : {1000.0, 15000.0, 20000.0}) {
        auto y = srcAll(sineSr(f, 44101, 0.5, 44101), 1, 44101, 48000, 4096);
        std::vector<float> mid(y.begin() + 6000, y.end() - 6000);
        CHECK(std::fabs(analyzeTone(mid, 48000, 0.5, 0, 32768).gainDb) <= 0.01);
    }
}

TEST(src_timing_is_exact_and_block_independent) {
    // выход m — значение исходной синусоиды в позиции m·fin/fout: фаза не сдвинута
    const double f = 1234.5;
    auto y = srcAll(sineSr(f, 44100, 0.5, 44100), 1, 44100, 48000, 4096);
    double worst = 0;
    for (size_t m = 5000; m < 40000; m++) worst = std::max(worst, std::fabs((double)y[m] - 0.5 * std::sin(2 * kPi * f * (double)m / 48000.0)));
    CHECK(worst < 2e-6);
    Rng r(4); std::vector<float> st(2 * 30000);
    for (auto& v : st) v = (float)(r.uniform() - 0.5);
    auto ref = srcAll(st, 2, 44100, 48000, 4096);
    for (int ch : {1, 37, 1000}) {
        auto o = srcAll(st, 2, 44100, 48000, ch);
        CHECK(o.size() == ref.size());
        bool same = o.size() == ref.size(); for (size_t i = 0; same && i < o.size(); i++) if (o[i] != ref[i]) same = false;
        CHECK(same);
    }
    // каналы независимы
    std::vector<float> l(30000), rr(30000), mono_l, mono_r;
    for (size_t i = 0; i < 30000; i++) { l[i] = st[2 * i]; rr[i] = st[2 * i + 1]; }
    mono_l = srcAll(l, 1, 44100, 48000, 4096); mono_r = srcAll(rr, 1, 44100, 48000, 4096);
    bool indep = true; for (size_t i = 0; i < mono_l.size(); i++) if (mono_l[i] != ref[2 * i] || mono_r[i] != ref[2 * i + 1]) indep = false;
    CHECK(indep);
}

TEST(src_one_shot_matches_streaming) {
    Rng r(8); std::vector<float> x(20000);
    for (auto& v : x) v = (float)(r.uniform() - 0.5);
    auto ref = srcAll(x, 1, 96000, 48000, 777);
    std::vector<float> out(ref.size() + 400);
    const int64_t n = sf_src_resample(1, 96000, 48000, x.data(), (int64_t)x.size(), out.data(), (int64_t)out.size());
    CHECK(n == (int64_t)ref.size());
    bool same = n == (int64_t)ref.size(); for (int64_t i = 0; same && i < n; i++) if (out[(size_t)i] != ref[(size_t)i]) same = false;
    CHECK(same);
}

// ---- A1.4: лимитер ----
std::vector<float> limRun(const std::vector<float>& io, int ch, double ceil, int block, int64_t* active = nullptr) {
    sf_limiter* l = sf_limiter_create(48000, ch, ceil);
    const int lat = sf_limiter_latency_frames(l);
    const size_t n = io.size() / (size_t)ch;
    std::vector<float> buf = io; buf.resize((n + (size_t)lat) * (size_t)ch, 0.0f);
    const size_t total = n + (size_t)lat;
    for (size_t s = 0; s < total; s += (size_t)block) sf_limiter_process(l, &buf[s * (size_t)ch], (int)std::min<size_t>((size_t)block, total - s));
    if (active) { int64_t b, a; double m; sf_limiter_take_stats(l, &b, &a, &m); *active = a; }
    sf_limiter_destroy(l);
    return std::vector<float>(buf.begin() + (long)((size_t)lat * (size_t)ch), buf.end());
}

TEST(limiter_latency_and_transparency_below_ceiling) {
    sf_limiter* l = sf_limiter_create(48000, 1, -1.0);
    CHECK(sf_limiter_latency_frames(l) == 16 + 72 - 1);
    sf_limiter_destroy(l);
    auto x = sine(1000, -6, 1.0);
    auto y = limRun(x, 1, -1.0, 480);
    bool same = true; for (size_t i = 0; i < x.size(); i++) if (x[i] != y[i]) same = false;
    CHECK(same);   // ниже потолка — побитно прозрачен (со сдвигом на latency, снятым в limRun)
}

TEST(limiter_true_peak_guarantee) {
    Rng r(21);
    struct S { const char* n; std::vector<float> x; } sigs[] = {
        {"fs4_45", sine(12000, 0.0, 1.0, kPi / 4)},
        {"sine_100", sine(100, 0.0, 1.0)},
        {"sine_19k", sine(19000, 0.0, 1.0, 0.7)},
        {"noise", [&] { std::vector<float> v(96000); for (auto& e : v) e = (float)((r.uniform() * 2 - 1) * 1.2); return v; }()},
    };
    for (auto& s : sigs) for (double ceil : {-1.5, -1.0}) {
        auto y = limRun(s.x, 1, ceil, 480);
        const double tp = truePeakDb(trimEdges(y, 4096));
        CHECK(tp <= ceil + 1e-6);
        CHECK(tp >= ceil - 0.2);   // не пережимает: потолок достигается
    }
}

TEST(limiter_block_size_independent_and_stereo_linked) {
    Rng r(22); std::vector<float> st(2 * 60000);
    for (size_t i = 0; i < 60000; i++) {
        const float a = (float)(1.3 * std::sin(0.05 * (double)i) * (0.5 + 0.5 * std::sin(0.0007 * (double)i)) + 0.05 * (r.uniform() - 0.5));
        st[2 * i] = a; st[2 * i + 1] = 0.5f * a;
    }
    auto ref = limRun(st, 2, -1.5, 480);
    for (int b : {1, 37, 240, 4096}) {
        auto o = limRun(st, 2, -1.5, b);
        bool same = o.size() == ref.size(); for (size_t i = 0; same && i < o.size(); i++) if (o[i] != ref[i]) same = false;
        CHECK(same);
    }
    double worst = 0, red = 0;
    for (size_t i = 0; i < 60000; i++) {
        worst = std::max(worst, std::fabs((double)ref[2 * i + 1] - 0.5 * (double)ref[2 * i]));
        red = std::max(red, std::fabs((double)ref[2 * i] - (double)st[2 * i]));
    }
    CHECK(worst < 1e-6);   // одинаковое усиление L и R: соотношение каналов сохраняется
    CHECK(red > 0.05);     // лимитер действительно работал
}

TEST(limiter_release_two_speeds_and_stats) {
    // единичный пик: восстановление быстрое; частые пики — медленное
    auto make = [&](int period) {
        std::vector<float> x(48000 * 2, 0.0f);
        for (size_t i = 0; i < x.size(); i++) x[i] = (float)(0.1 * std::sin(2 * kPi * 440.0 * (double)i / 48000));
        for (size_t i = 24000; i < x.size(); i += (size_t)period) x[i] = 1.6f;
        return x;
    };
    auto recovery = [&](const std::vector<float>& x) {   // доля восстановления уровня синуса через 80 мс после одиночного пика
        auto y = limRun(x, 1, -1.0, 480);
        return std::fabs((double)y[24000 + 3840 + 100]) / std::fabs((double)x[24000 + 3840 + 100]);
    };
    auto sparse = make(1000000);
    auto dense = make(2400);
    (void)recovery;
    int64_t a1 = 0, a2 = 0;
    limRun(sparse, 1, -1.0, 480, &a1);
    limRun(dense, 1, -1.0, 480, &a2);
    CHECK(a1 > 0 && a2 > a1);
    // после единичного пика уровень на фоне вернулся (через 400 мс усиление > 0,99)
    auto y = limRun(sparse, 1, -1.0, 480);
    double g = 0, n = 0;
    for (size_t i = 24000 + 19200; i < 24000 + 19200 + 4800; i++) if (std::fabs(sparse[i]) > 0.05f) { g += std::fabs((double)y[i] / sparse[i]); n++; }
    CHECK(g / n > 0.99);
}

TEST(limiter_reset_is_deterministic) {
    sf_limiter* l = sf_limiter_create(48000, 1, -1.0);
    auto x = sine(5000, 0.0, 0.3);
    std::vector<float> a = x, b = x;
    sf_limiter_process(l, a.data(), (int)a.size());
    sf_limiter_reset(l);
    sf_limiter_process(l, b.data(), (int)b.size());
    CHECK(a == b);
    sf_limiter_destroy(l);
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


// ---- коррекция динамика (A2.4) ----
std::vector<float> speakerRun(const std::vector<sf_speaker_section>& secs, double pre, const std::vector<float>& in, int block, int ch = 1) {
    sf_speaker* sp = sf_speaker_create(48000, ch);
    sf_speaker_set_chain(sp, secs.data(), (int)secs.size(), pre);
    std::vector<float> out = in;
    const size_t frames = in.size() / (size_t)ch;
    for (size_t s0 = 0; s0 < frames; s0 += (size_t)block)
        sf_speaker_process(sp, out.data() + s0 * (size_t)ch, (int)std::min<size_t>((size_t)block, frames - s0));
    sf_speaker_destroy(sp);
    return out;
}

double speakerGainDb(const sf_speaker_section& sec, double f) {
    std::vector<float> x(48000 * 2);
    for (size_t i = 0; i < x.size(); i++) x[i] = (float)(0.5 * std::sin(2 * 3.14159265358979323846 * f * (double)i / 48000.0));
    auto y = speakerRun({sec}, 0.0, x, 480);
    double a = 0, b = 0;
    for (size_t i = 48000; i < x.size(); i++) { a += (double)x[i] * x[i]; b += (double)y[i] * y[i]; }
    return 10 * std::log10(b / a);
}

TEST(speaker_empty_chain_is_bit_exact) {
    std::vector<float> x(1000);
    for (size_t i = 0; i < x.size(); i++) x[i] = (float)std::sin(0.01 * (double)i);
    auto y = speakerRun({}, 0.0, x, 37);
    CHECK(std::memcmp(x.data(), y.data(), x.size() * sizeof(float)) == 0);
}

TEST(speaker_section_magnitudes_match_design) {
    CHECK_NEAR(speakerGainDb({SF_SECTION_PEAK, 1000, 1.0, 6.0}, 1000), 6.0, 0.02);
    CHECK_NEAR(speakerGainDb({SF_SECTION_PEAK, 1000, 1.0, -6.0}, 1000), -6.0, 0.02);
    CHECK_NEAR(speakerGainDb({SF_SECTION_HIGHPASS, 250, 0.7071, 0}, 250), -3.01, 0.05);
    CHECK(speakerGainDb({SF_SECTION_HIGHPASS, 250, 0.7071, 0}, 25) < -38);
    CHECK_NEAR(speakerGainDb({SF_SECTION_HIGHPASS, 250, 0.7071, 0}, 5000), 0.0, 0.01);
    CHECK_NEAR(speakerGainDb({SF_SECTION_LOWSHELF, 200, 0.7071, 4.0}, 30), 4.0, 0.1);
    CHECK_NEAR(speakerGainDb({SF_SECTION_HIGHSHELF, 8000, 0.7071, 3.0}, 20000), 3.0, 0.25);
    CHECK_NEAR(speakerGainDb({SF_SECTION_ALLPASS, 1800, 0.6, 0}, 300), 0.0, 1e-4);
    CHECK_NEAR(speakerGainDb({SF_SECTION_ALLPASS, 1800, 0.6, 0}, 9000), 0.0, 1e-4);
}

TEST(speaker_block_size_independent_and_stereo) {
    std::vector<sf_speaker_section> c = {{SF_SECTION_HIGHPASS, 180, 0.7071, 0}, {SF_SECTION_PEAK, 2500, 1.2, -4}, {SF_SECTION_HIGHSHELF, 9000, 0.7071, 2}};
    std::vector<float> x(2 * 9600);
    for (size_t i = 0; i < x.size() / 2; i++) { x[2 * i] = (float)std::sin(0.03 * (double)i); x[2 * i + 1] = (float)(0.5 * std::sin(0.11 * (double)i)); }
    auto ref = speakerRun(c, -3.0, x, 4096, 2);
    for (int blk : {1, 37, 240}) {
        auto y = speakerRun(c, -3.0, x, blk, 2);
        CHECK(std::memcmp(ref.data(), y.data(), ref.size() * sizeof(float)) == 0);
    }
}

TEST(speaker_chain_change_crossfades_and_rejects_invalid) {
    sf_speaker* sp = sf_speaker_create(48000, 1);
    sf_speaker_section hp = {SF_SECTION_HIGHPASS, 250, 0.7071, 0};
    CHECK(sf_speaker_set_chain(sp, &hp, 1, 1.0) == -1);                       // preamp > 0
    sf_speaker_section bad = {SF_SECTION_PEAK, 30000, 1.0, 3.0};
    CHECK(sf_speaker_set_chain(sp, &bad, 1, 0.0) == -1);                      // f0 ≥ fs/2
    CHECK(sf_speaker_set_chain(sp, &hp, SF_SPEAKER_MAX_SECTIONS + 1, 0.0) == -1);
    std::vector<float> x(48000, 0.5f);                                        // постоянная составляющая: ФВЧ гасит её
    sf_speaker_process(sp, x.data(), 100);                                    // пустая цепочка — без изменений
    CHECK(x[99] == 0.5f);
    CHECK(sf_speaker_set_chain(sp, &hp, 1, 0.0) == 0);
    std::vector<float> y(48000, 0.5f);
    for (size_t s0 = 0; s0 < y.size(); s0 += 64) sf_speaker_process(sp, y.data() + s0, 64);
    double maxStep = 0;
    for (size_t i = 1; i < y.size(); i++) maxStep = std::max(maxStep, (double)std::fabs(y[i] - y[i - 1]));
    CHECK(maxStep < 0.01);                                                    // без щелчка при включении
    CHECK(std::fabs(y.back()) < 0.01f);                                       // после кроссфейда ФВЧ в силе
    sf_speaker_destroy(sp);
}


// ---- громкость BS.1770 в анализаторе (A3.1) ----
static double meterLufs(const std::vector<float>& st, int block) {
    sf_meter* m = sf_meter_create(2);
    for (size_t o = 0; o < st.size() / 2; o += (size_t)block)
        sf_meter_process(m, st.data() + 2 * o, (int64_t)std::min<size_t>((size_t)block, st.size() / 2 - o));
    const double l = sf_meter_loudness_lufs(m);
    sf_meter_destroy(m);
    return l;
}

static std::vector<float> stereoSine(double hz, double dbfs, double seconds, double phase = 0) {
    const size_t n = (size_t)(seconds * 48000);
    std::vector<float> x(2 * n);
    const double a = std::pow(10.0, dbfs / 20.0);
    for (size_t i = 0; i < n; i++) x[2 * i] = x[2 * i + 1] = (float)(a * std::sin(2 * 3.14159265358979323846 * hz * (double)i / 48000.0 + phase));
    return x;
}

TEST(meter_loudness_ebu_reference_sine) {
    CHECK_NEAR(meterLufs(stereoSine(997, -23.0, 20), 480), -23.0, 0.1);   // EBU Tech 3341, случай 1
    CHECK_NEAR(meterLufs(stereoSine(997, -33.0, 20), 480), -33.0, 0.1);   // случай 2
}

TEST(meter_loudness_matches_lab_reference_and_block_size) {
    std::vector<float> x(2 * 48000 * 12);
    uint32_t r = 12345;
    for (size_t i = 0; i < x.size(); i++) { r = r * 1664525u + 1013904223u; x[i] = (float)(((double)(r >> 8) / 8388608.0 - 1.0) * 0.1 * (1.0 + std::sin((double)i * 2e-6))); }
    std::vector<std::vector<float>> ch(2, std::vector<float>(x.size() / 2));
    for (size_t i = 0; i < x.size() / 2; i++) { ch[0][i] = x[2 * i]; ch[1][i] = x[2 * i + 1]; }
    const double ref = sflab::lufsIntegrated(ch, 48000);
    CHECK_NEAR(meterLufs(x, 4096), ref, 1e-9);
    CHECK_NEAR(meterLufs(x, 1), ref, 1e-9);
    CHECK_NEAR(meterLufs(x, 37), ref, 1e-9);
}

TEST(meter_loudness_gating_and_silence) {
    auto a = stereoSine(997, -20.0, 10), b = stereoSine(997, -70.0, 10);   // −70 dBFS: ниже абсолютного порога
    a.insert(a.end(), b.begin(), b.end());
    CHECK_NEAR(meterLufs(a, 480), -20.0, 0.1);
    auto c = stereoSine(997, -20.0, 10), d = stereoSine(997, -45.0, 10);   // выше −70, но ниже −10 LU от общего → отсечён
    c.insert(c.end(), d.begin(), d.end());
    CHECK_NEAR(meterLufs(c, 480), -20.0, 0.1);
    std::vector<float> z(2 * 48000 * 2, 0.f);
    CHECK(meterLufs(z, 480) == -200.0);
    CHECK(meterLufs(std::vector<float>(2 * 1000, 0.5f), 480) == -200.0);   // короче 400 мс
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
