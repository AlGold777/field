// Защита аудиопотока от денормалов (FTZ/DAZ) на время process().
#pragma once
#include <cstdint>

#if defined(__aarch64__)
namespace sf {
struct FtzGuard {
    uint64_t saved;
    FtzGuard() {
        __asm__ volatile("mrs %0, fpcr" : "=r"(saved));
        uint64_t v = saved | (1ull << 24);  // FZ
        __asm__ volatile("msr fpcr, %0" ::"r"(v));
    }
    ~FtzGuard() { __asm__ volatile("msr fpcr, %0" ::"r"(saved)); }
    FtzGuard(const FtzGuard&) = delete;
    FtzGuard& operator=(const FtzGuard&) = delete;
};
}  // namespace sf
#elif defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
namespace sf {
struct FtzGuard {
    unsigned saved;
    FtzGuard() : saved(_mm_getcsr()) { _mm_setcsr(saved | 0x8040u); }  // FTZ | DAZ
    ~FtzGuard() { _mm_setcsr(saved); }
    FtzGuard(const FtzGuard&) = delete;
    FtzGuard& operator=(const FtzGuard&) = delete;
};
}  // namespace sf
#else
namespace sf {
struct FtzGuard {};
}  // namespace sf
#endif
