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

/* ---- PCM 24 бит и TPDF-дизеринг (A1.1) ---- */

typedef struct sf_dither sf_dither;
/* Генератор xorshift128+, один на канал. seed детерминирует поток. */
SFDSP_API sf_dither* sf_dither_create(uint64_t seed);
SFDSP_API void sf_dither_destroy(sf_dither* d);
/*
 * float → знаковое 24 бит (3 байта). d == NULL — округление без дизера; иначе TPDF: сумма двух независимых
 * равномерных значений ±0,5 LSB. Шаги in_stride — в float, out_stride — в байтах (6 для интерлива стерео).
 * Значения вне ±1 ограничиваются, NaN → 0. big_endian: 1 — сетевой порядок, 0 — кэш на диске.
 */
SFDSP_API void sf_pcm24_encode(sf_dither* d, const float* in, int in_stride, int n, uint8_t* out, int out_stride, int big_endian);
SFDSP_API void sf_pcm24_decode(const uint8_t* in, int in_stride, int n, int big_endian, float* out, int out_stride);

/* ---- Ресемплер: полифазный sinc, окно Кайзера (A1.2) ---- */

/* Расчётное подавление зеркал, dB (Кайзер: β = 0,1102·(A − 8,7)). */
#define SF_SRC_DESIGN_STOPBAND_DB 128.0

typedef struct sf_src sf_src;
/* Интерлив, channels 1..8. Выход m = вход в позиции m·in_rate/out_rate; нули вне входа. NULL при неверных параметрах. */
SFDSP_API sf_src* sf_src_create(int channels, int in_rate, int out_rate);
SFDSP_API void sf_src_destroy(sf_src* s);
SFDSP_API void sf_src_reset(sf_src* s);
SFDSP_API int sf_src_taps(const sf_src* s);
SFDSP_API int sf_src_phases(const sf_src* s);
/* Верхняя граница числа выходных кадров для n_in входных (и для flush при n_in = 0). */
SFDSP_API int64_t sf_src_max_output(const sf_src* s, int64_t n_in);
/* Возвращает число выходных кадров (< 0 — ошибка). out_capacity ≥ sf_src_max_output. */
SFDSP_API int64_t sf_src_process(sf_src* s, const float* in, int64_t n_in, float* out, int64_t out_capacity);
SFDSP_API int64_t sf_src_flush(sf_src* s, float* out, int64_t out_capacity);
/* Целиком: вход n_in кадров → выход (out_capacity ≥ n_in·out_rate/in_rate + taps). Возвращает число кадров. */
SFDSP_API int64_t sf_src_resample(int channels, int in_rate, int out_rate, const float* in, int64_t n_in, float* out, int64_t out_capacity);

/* ---- Лимитер true-peak с упреждением (A1.4) ---- */

#define SF_LIMITER_LOOKAHEAD_S 0.0015

typedef struct sf_limiter sf_limiter;
/* channels 1..2 (сцеплены: общее усиление). Потолок — в dBTP (4× оверсэмплинг). Задержка: sf_limiter_latency_frames. */
SFDSP_API sf_limiter* sf_limiter_create(int sample_rate, int channels, double ceiling_dbtp);
SFDSP_API void sf_limiter_destroy(sf_limiter* l);
SFDSP_API void sf_limiter_reset(sf_limiter* l);
SFDSP_API int sf_limiter_latency_frames(const sf_limiter* l);
/* На месте, интерлив. Выход задержан на latency кадров (первые latency кадров потока — тишина). Без аллокаций. */
SFDSP_API void sf_limiter_process(sf_limiter* l, float* io, int frames);
/* Забирает и обнуляет: число вызовов process, из них с ослаблением > 0,5 dB, максимальное ослабление (dB). */
SFDSP_API void sf_limiter_take_stats(sf_limiter* l, int64_t* blocks, int64_t* active_blocks, double* max_reduction_db);

#ifdef __cplusplus
}
#endif

#endif
