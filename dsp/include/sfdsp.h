/*
 * SoundField DSP core: C ABI.
 *
 * Правила: функции process/render не выделяют память, не блокируют и не пишут в лог; вся память — в create().
 * Блок произвольного размера. Один экземпляр — один аудиопоток (потокобезопасности между потоками нет).
 */
#ifndef SFDSP_H
#define SFDSP_H

#include <stdint.h>

#include "sfdsp_version.h"

#if defined(_WIN32)
#define SFDSP_API __declspec(dllexport)
#else
#define SFDSP_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

SFDSP_API const char* sfdsp_version(void);
SFDSP_API int sfdsp_abi_version(void);
/* Строит статические таблицы. Вызывать вне аудиопотока (create-функции вызывают сами). */
SFDSP_API void sfdsp_init(void);

/* ---- Дробная интерполяция: окно Кайзера, 32 отвода, 256 фаз (β = 8.6) ---- */

#define SF_SINC_TAPS 32
#define SF_SINC_PHASES 256
/* Постоянная задержка интерполятора в кадрах: вызывающий вычитает её из идеальной позиции. */
#define SF_SINC_GROUP_DELAY_FRAMES 16

SFDSP_API float sf_sinc_coefficient(int phase, int tap);

/*
 * src[0] — кадр src_base; кадры вне [src_base, src_base + src_len) считаются отсутствующими.
 * Если нужен хотя бы один отсутствующий отвод — результат NaN (как в Kotlin WindowedSinc).
 */
SFDSP_API float sf_sinc_interpolate(const float* src, int64_t src_base, int64_t src_len, double position);

/* ---- Дробный корректор воспроизведения (PI-регулятор фазы), порт Kotlin PlayoutCorrector ---- */

typedef struct sf_playout sf_playout;

SFDSP_API sf_playout* sf_playout_create(int sample_rate, int hard_resync_frames);
SFDSP_API void sf_playout_destroy(sf_playout* p);
SFDSP_API void sf_playout_reset(sf_playout* p);
/* ideal — дробный индекс кадра, который должен прозвучать первым в буфере из n кадров. */
SFDSP_API void sf_playout_plan(sf_playout* p, double ideal, int n);
SFDSP_API double sf_playout_position(const sf_playout* p, int i);
SFDSP_API int64_t sf_playout_frame(const sf_playout* p, int i);
SFDSP_API double sf_playout_sync_error_us(const sf_playout* p);
SFDSP_API int32_t sf_playout_hard_jumps(const sf_playout* p);
SFDSP_API int64_t sf_playout_slew_events(const sf_playout* p);
/* plan + чтение n выходных кадров из src через интерполятор (NaN для недоступных). FTZ/DAZ внутри. */
SFDSP_API void sf_playout_render(sf_playout* p, const float* src, int64_t src_base, int64_t src_len,
                                 double ideal, int n, float* out);
SFDSP_API int sf_playout_latency_frames(const sf_playout* p);

#ifdef __cplusplus
}
#endif

#endif
