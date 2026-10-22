// PCM 24 бит и TPDF-дизеринг (ТЗ A1.1).
#include <algorithm>
#include <cmath>
#include <new>

#include "sfdsp.h"

// xorshift128+, свой генератор на канал.
struct sf_dither {
    uint64_t s0, s1;
    uint64_t next() {
        uint64_t x = s0;
        const uint64_t y = s1;
        s0 = y;
        x ^= x << 23;
        s1 = x ^ y ^ (x >> 17) ^ (y >> 26);
        return s1 + y;
    }
    double uniform() { return (double)(next() >> 11) * (1.0 / 9007199254740992.0); }  // [0, 1)
};

extern "C" {

sf_dither* sf_dither_create(uint64_t seed) {
    sf_dither* d = new (std::nothrow) sf_dither;
    if (!d) return nullptr;
    d->s0 = seed * 0x9E3779B97F4A7C15ull + 1;
    d->s1 = seed ^ 0xD1B54A32D192ED03ull;
    for (int i = 0; i < 8; i++) d->next();
    return d;
}

void sf_dither_destroy(sf_dither* d) { delete d; }

void sf_pcm24_encode(sf_dither* d, const float* in, int in_stride, int n, uint8_t* out, int out_stride, int big_endian) {
    for (int i = 0; i < n; i++) {
        double v = (double)in[(int64_t)i * in_stride] * 8388608.0;
        if (d) v += (d->uniform() - 0.5) + (d->uniform() - 0.5);  // TPDF: сумма двух равномерных ±0,5 LSB
        double q = 0.0;
        if (!std::isnan(v)) q = std::min(8388607.0, std::max(-8388608.0, std::floor(v + 0.5)));
        const int32_t s = (int32_t)q;
        uint8_t* o = out + (int64_t)i * out_stride;
        if (big_endian) { o[0] = (uint8_t)(s >> 16); o[1] = (uint8_t)(s >> 8); o[2] = (uint8_t)s; }
        else { o[0] = (uint8_t)s; o[1] = (uint8_t)(s >> 8); o[2] = (uint8_t)(s >> 16); }
    }
}

void sf_pcm24_decode(const uint8_t* in, int in_stride, int n, int big_endian, float* out, int out_stride) {
    for (int i = 0; i < n; i++) {
        const uint8_t* p = in + (int64_t)i * in_stride;
        int32_t s = big_endian ? (int32_t)(((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2])
                               : (int32_t)(((uint32_t)p[2] << 16) | ((uint32_t)p[1] << 8) | p[0]);
        if (s & 0x800000) s -= 0x1000000;
        out[(int64_t)i * out_stride] = (float)s * (1.0f / 8388608.0f);
    }
}

}
