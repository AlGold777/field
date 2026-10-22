// Общие математические функции ядра (не для аудиопотока: используются при создании таблиц).
#pragma once
#include <cmath>

namespace sf {

constexpr double kPi = 3.14159265358979323846;

// Модифицированная функция Бесселя нулевого порядка (ряд; сходится для β ≤ ~30).
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

// Окно Кайзера на [-1, 1]: u = t / полуширина.
inline double kaiserWindow(double u, double beta, double invI0Beta) {
    if (std::fabs(u) >= 1.0) return 0.0;
    return bessel0(beta * std::sqrt(1.0 - u * u)) * invI0Beta;
}

}  // namespace sf
