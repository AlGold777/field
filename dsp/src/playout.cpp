#include <algorithm>
#include <cmath>
#include <new>

#include "rt.hpp"
#include "sinc.hpp"

// Порт app.soundfield.core.PlayoutCorrector (Kotlin) без изменения арифметики.
struct sf_playout {
    int sampleRate;
    int hardResyncFrames;
    double readPos = NAN;
    double filt = 0.0;
    double integral = 0.0;
    double start = 0.0;
    double step = 1.0;
    double syncErrorUs = 0.0;
    int32_t hardJumps = 0;
    int64_t slewEvents = 0;
};

namespace {
constexpr double kKpPerSecond = 2.0 / 3.0;
constexpr double kKiPerSecond2 = 1.0 / 9.0;
constexpr double kMaxRateCorrection = 200e-6;
}  // namespace

extern "C" {

sf_playout* sf_playout_create(int sample_rate, int hard_resync_frames) {
    sfdsp_init();
    auto* p = new (std::nothrow) sf_playout();
    if (p) { p->sampleRate = sample_rate; p->hardResyncFrames = hard_resync_frames; }
    return p;
}

void sf_playout_destroy(sf_playout* p) { delete p; }

void sf_playout_reset(sf_playout* p) {
    p->readPos = NAN; p->filt = 0.0; p->integral = 0.0; p->step = 1.0;
}

void sf_playout_plan(sf_playout* p, double ideal, int n) {
    if (std::isnan(p->readPos) || std::fabs(ideal - p->readPos) > p->hardResyncFrames) {
        if (!std::isnan(p->readPos)) p->hardJumps++;
        p->readPos = ideal;
        p->filt = 0.0;
        p->integral = 0.0;
    }
    const double err = ideal - p->readPos;
    p->filt += 0.1 * (err - p->filt);
    const double dt = (double)std::max(n, 1) / p->sampleRate;
    const double errorSeconds = p->filt / p->sampleRate;
    p->integral = std::min(0.05, std::max(-0.05, p->integral + errorSeconds * dt));
    const double correction = std::min(kMaxRateCorrection, std::max(-kMaxRateCorrection,
        kKpPerSecond * errorSeconds + kKiPerSecond2 * p->integral));
    p->step = 1.0 + correction;
    if (std::fabs(correction) > 1e-6) p->slewEvents++;
    p->start = p->readPos;
    p->readPos += n * p->step;
    p->syncErrorUs = p->filt * 1e6 / p->sampleRate;
}

double sf_playout_position(const sf_playout* p, int i) { return p->start + i * p->step; }

int64_t sf_playout_frame(const sf_playout* p, int i) { return (int64_t)std::floor(sf_playout_position(p, i) + 0.5); }

double sf_playout_sync_error_us(const sf_playout* p) { return p->syncErrorUs; }
int32_t sf_playout_hard_jumps(const sf_playout* p) { return p->hardJumps; }
int64_t sf_playout_slew_events(const sf_playout* p) { return p->slewEvents; }

void sf_playout_render(sf_playout* p, const float* src, int64_t src_base, int64_t src_len,
                       double ideal, int n, float* out) {
    sf::FtzGuard ftz;
    sf_playout_plan(p, ideal, n);
    for (int i = 0; i < n; i++) out[i] = sf_sinc_interpolate(src, src_base, src_len, sf_playout_position(p, i));
}

int sf_playout_latency_frames(const sf_playout*) { return SF_SINC_GROUP_DELAY_FRAMES; }

}
