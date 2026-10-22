// Коррекция динамика (A2.4): каскад минимально-фазовых биквадов RBJ в форме TDF-II с double-состоянием.
// Смена цепочки — лок-фри «тройной буфер» (один управляющий поток пишет, аудиопоток забирает на границе блока)
// и кроссфейд 50 мс между старой и новой цепочкой (обе работают параллельно на время перехода).
#include <algorithm>
#include <atomic>
#include <cmath>
#include <new>

#include "rt.hpp"
#include "sfdsp.h"

namespace {
constexpr double kPi = 3.14159265358979323846;

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
};

struct Chain {
    int n = 0;
    double gain = 1.0;
    Biquad bq[SF_SPEAKER_MAX_SECTIONS];
    double z[2][SF_SPEAKER_MAX_SECTIONS][2] = {};  // [канал][секция][z1,z2]

    void clearState() {
        for (auto& ch : z) for (auto& s : ch) s[0] = s[1] = 0;
    }
    bool identity() const { return n == 0 && gain == 1.0; }
};

struct Params {
    int n = 0;
    double preamp_db = 0;
    sf_speaker_section sec[SF_SPEAKER_MAX_SECTIONS];
};

bool design(const sf_speaker_section& s, double fs, Biquad* out) {
    if (!(s.f0_hz > 0) || !(s.f0_hz < fs * 0.5) || !(s.q > 0)) return false;
    const double w0 = 2 * kPi * s.f0_hz / fs, cw = std::cos(w0), sw = std::sin(w0), alpha = sw / (2 * s.q);
    const double A = std::pow(10.0, s.gain_db / 40.0);
    double b0, b1, b2, a0, a1, a2;
    switch (s.type) {
        case SF_SECTION_HIGHPASS:
            b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        case SF_SECTION_PEAK:
            b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
            a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
            break;
        case SF_SECTION_LOWSHELF: {
            const double t = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + t); b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - t);
            a0 = (A + 1) + (A - 1) * cw + t; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - t;
            break;
        }
        case SF_SECTION_HIGHSHELF: {
            const double t = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + t); b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - t);
            a0 = (A + 1) - (A - 1) * cw + t; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - t;
            break;
        }
        case SF_SECTION_ALLPASS:
            b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        default:
            return false;
    }
    out->b0 = b0 / a0; out->b1 = b1 / a0; out->b2 = b2 / a0; out->a1 = a1 / a0; out->a2 = a2 / a0;
    return true;
}

bool build(const Params& p, double fs, Chain* c) {
    c->n = p.n;
    c->gain = std::pow(10.0, p.preamp_db / 20.0);
    for (int i = 0; i < p.n; i++)
        if (!design(p.sec[i], fs, &c->bq[i])) return false;
    c->clearState();
    return true;
}

inline double run(Chain& c, int ch, double x) {
    double y = x * c.gain;
    for (int i = 0; i < c.n; i++) {
        const Biquad& b = c.bq[i];
        double* z = c.z[ch][i];
        const double o = b.b0 * y + z[0];
        z[0] = b.b1 * y - b.a1 * o + z[1];
        z[1] = b.b2 * y - b.a2 * o;
        y = o;
    }
    return y;
}
}  // namespace

struct sf_speaker {
    int fs, channels;
    int xfade_total;
    Chain cur, next;
    bool fading = false;
    int fade_pos = 0;
    // Тройной буфер: writer пишет в slot[back], публикует индекс через `mid` (бит 2 — «есть новое»).
    Params slot[3];
    int back = 0, front = 2;
    std::atomic<int> mid{1};
};

extern "C" {

sf_speaker* sf_speaker_create(int sample_rate, int channels) {
    if (channels < 1 || channels > 2 || sample_rate < 8000 || sample_rate > 384000) return nullptr;
    sf_speaker* s = new (std::nothrow) sf_speaker;
    if (!s) return nullptr;
    s->fs = sample_rate;
    s->channels = channels;
    s->xfade_total = std::max(1, (int)std::lround(SF_SPEAKER_XFADE_S * sample_rate));
    return s;
}

void sf_speaker_destroy(sf_speaker* s) { delete s; }

void sf_speaker_reset(sf_speaker* s) {
    s->cur.clearState();
    s->next.clearState();
    s->fading = false;
    s->fade_pos = 0;
}

int sf_speaker_set_chain(sf_speaker* s, const sf_speaker_section* sections, int n, double preamp_db) {
    if (n < 0 || n > SF_SPEAKER_MAX_SECTIONS || (n > 0 && !sections) || !(preamp_db <= 0.0) || !std::isfinite(preamp_db)) return -1;
    Params& p = s->slot[s->back];
    p.n = n;
    p.preamp_db = preamp_db;
    for (int i = 0; i < n; i++) p.sec[i] = sections[i];
    Chain probe;
    if (!build(p, s->fs, &probe)) return -1;
    s->back = s->mid.exchange(s->back | 4, std::memory_order_acq_rel) & 3;
    return 0;
}

int sf_speaker_latency_frames(const sf_speaker*) { return 0; }

void sf_speaker_process(sf_speaker* s, float* io, int frames) {
    sf::FtzGuard ftz;
    if (!s->fading && (s->mid.load(std::memory_order_acquire) & 4)) {
        const int m = s->mid.exchange(s->front, std::memory_order_acq_rel);
        s->front = m & 3;
        if (build(s->slot[s->front], s->fs, &s->next)) {
            s->fading = true;
            s->fade_pos = 0;
        }
    }
    if (!s->fading && s->cur.identity()) return;
    const int ch = s->channels;
    for (int i = 0; i < frames; i++) {
        float* f = io + (size_t)i * ch;
        if (s->fading) {
            const double t = (double)s->fade_pos / s->xfade_total;
            for (int c = 0; c < ch; c++) {
                const double x = f[c];
                f[c] = (float)((1 - t) * run(s->cur, c, x) + t * run(s->next, c, x));
            }
            if (++s->fade_pos >= s->xfade_total) {
                s->cur = s->next;
                s->fading = false;
            }
        } else {
            for (int c = 0; c < ch; c++) f[c] = (float)run(s->cur, c, f[c]);
        }
    }
}

}  // extern "C"
