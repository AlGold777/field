// Минимальный радикс-2 БПФ (double) для лаборатории. Не для аудиопотока.
#pragma once
#include <cmath>
#include <complex>
#include <cstddef>
#include <vector>

namespace sflab {

using cd = std::complex<double>;
constexpr double kPi = 3.14159265358979323846;

inline size_t nextPow2(size_t n) { size_t p = 1; while (p < n) p <<= 1; return p; }

inline void fft(std::vector<cd>& a, bool inverse = false) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = 2 * kPi / (double)len * (inverse ? 1 : -1);
        const cd wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            cd w(1);
            for (size_t k = 0; k < len / 2; k++) {
                // точная фаза: пересчёт, а не накопление, иначе падает динамический диапазон
                if ((k & 63) == 0) w = cd(std::cos(ang * (double)k), std::sin(ang * (double)k));
                const cd u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
    if (inverse) for (auto& x : a) x /= (double)n;
}

inline double bessel0(double x) {
    const double y = x * x / 4.0;
    double term = 1.0, sum = 1.0;
    for (int k = 1; k < 200; k++) {
        term *= y / ((double)k * k);
        sum += term;
        if (term < sum * 1e-17) break;
    }
    return sum;
}

inline std::vector<double> kaiser(size_t n, double beta) {
    std::vector<double> w(n);
    const double d = bessel0(beta);
    for (size_t i = 0; i < n; i++) {
        const double r = 2.0 * (double)i / (double)(n - 1) - 1.0;
        w[i] = bessel0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / d;
    }
    return w;
}

}  // namespace sflab
