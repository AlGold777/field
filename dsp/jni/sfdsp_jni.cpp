// JNI-обёртка ядра для app.soundfield.dsp.SfDsp. Один вызов на блок.
#include <jni.h>

#include "sfdsp.h"

#define JFN(name) Java_app_soundfield_dsp_SfDsp_##name

namespace {
struct Critical {
    JNIEnv* env; jarray arr; void* p;
    Critical(JNIEnv* e, jarray a) : env(e), arr(a), p(e->GetPrimitiveArrayCritical(a, nullptr)) {}
    ~Critical() { if (p) env->ReleasePrimitiveArrayCritical(arr, p, 0); }
};
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JFN(nativeAbiVersion)(JNIEnv*, jclass) { return sfdsp_abi_version(); }
JNIEXPORT jstring JNICALL JFN(nativeVersion)(JNIEnv* env, jclass) { return env->NewStringUTF(sfdsp_version()); }
JNIEXPORT void JNICALL JFN(nativeInit)(JNIEnv*, jclass) { sfdsp_init(); }

JNIEXPORT jfloat JNICALL JFN(nativeCoefficient)(JNIEnv*, jclass, jint phase, jint tap) {
    return sf_sinc_coefficient(phase, tap);
}

JNIEXPORT jfloat JNICALL JFN(nativeInterpolate)(JNIEnv* env, jclass, jfloatArray src, jlong base, jint len, jdouble position) {
    Critical c(env, src);
    if (!c.p) return 0.0f / 0.0f;
    return sf_sinc_interpolate((const float*)c.p, base, len, position);
}

JNIEXPORT jlong JNICALL JFN(nativePlayoutCreate)(JNIEnv*, jclass, jint sampleRate, jint hardResync) {
    return (jlong)(intptr_t)sf_playout_create(sampleRate, hardResync);
}
JNIEXPORT void JNICALL JFN(nativePlayoutDestroy)(JNIEnv*, jclass, jlong h) { sf_playout_destroy((sf_playout*)(intptr_t)h); }
JNIEXPORT void JNICALL JFN(nativePlayoutReset)(JNIEnv*, jclass, jlong h) { sf_playout_reset((sf_playout*)(intptr_t)h); }
JNIEXPORT void JNICALL JFN(nativePlayoutPlan)(JNIEnv*, jclass, jlong h, jdouble ideal, jint n) {
    sf_playout_plan((sf_playout*)(intptr_t)h, ideal, n);
}
JNIEXPORT jdouble JNICALL JFN(nativePlayoutPosition)(JNIEnv*, jclass, jlong h, jint i) {
    return sf_playout_position((const sf_playout*)(intptr_t)h, i);
}
JNIEXPORT jdouble JNICALL JFN(nativePlayoutSyncErrorUs)(JNIEnv*, jclass, jlong h) {
    return sf_playout_sync_error_us((const sf_playout*)(intptr_t)h);
}
JNIEXPORT jint JNICALL JFN(nativePlayoutHardJumps)(JNIEnv*, jclass, jlong h) {
    return sf_playout_hard_jumps((const sf_playout*)(intptr_t)h);
}
JNIEXPORT jlong JNICALL JFN(nativePlayoutSlewEvents)(JNIEnv*, jclass, jlong h) {
    return sf_playout_slew_events((const sf_playout*)(intptr_t)h);
}
JNIEXPORT void JNICALL JFN(nativePlayoutRender)(JNIEnv* env, jclass, jlong h, jfloatArray src, jlong base, jint len,
                                                jdouble ideal, jint n, jfloatArray out) {
    Critical s(env, src);
    if (!s.p) return;
    Critical o(env, out);
    if (!o.p) return;
    sf_playout_render((sf_playout*)(intptr_t)h, (const float*)s.p, base, len, ideal, n, (float*)o.p);
}

// ---- A1: PCM 24 бит, SRC, лимитер ----

JNIEXPORT jlong JNICALL JFN(nativeDitherCreate)(JNIEnv*, jclass, jlong seed) { return (jlong)(intptr_t)sf_dither_create((uint64_t)seed); }
JNIEXPORT void JNICALL JFN(nativeDitherDestroy)(JNIEnv*, jclass, jlong h) { sf_dither_destroy((sf_dither*)(intptr_t)h); }

// handle == 0 — без дизера. in: FloatArray с шагом inStride (float), out: ByteArray с шагом outStride (байт).
JNIEXPORT void JNICALL JFN(nativePcm24Encode)(JNIEnv* env, jclass, jlong h, jfloatArray in, jint inOff, jint inStride, jint n,
                                              jbyteArray out, jint outOff, jint outStride, jboolean bigEndian) {
    Critical a(env, in);
    if (!a.p) return;
    Critical b(env, out);
    if (!b.p) return;
    sf_pcm24_encode((sf_dither*)(intptr_t)h, (const float*)a.p + inOff, inStride, n, (uint8_t*)b.p + outOff, outStride, bigEndian ? 1 : 0);
}

JNIEXPORT void JNICALL JFN(nativePcm24Decode)(JNIEnv* env, jclass, jbyteArray in, jint inOff, jint inStride, jint n, jboolean bigEndian,
                                              jfloatArray out, jint outOff, jint outStride) {
    Critical a(env, in);
    if (!a.p) return;
    Critical b(env, out);
    if (!b.p) return;
    sf_pcm24_decode((const uint8_t*)a.p + inOff, inStride, n, bigEndian ? 1 : 0, (float*)b.p + outOff, outStride);
}

JNIEXPORT jlong JNICALL JFN(nativeSrcCreate)(JNIEnv*, jclass, jint channels, jint inRate, jint outRate) {
    return (jlong)(intptr_t)sf_src_create(channels, inRate, outRate);
}
JNIEXPORT void JNICALL JFN(nativeSrcDestroy)(JNIEnv*, jclass, jlong h) { sf_src_destroy((sf_src*)(intptr_t)h); }
JNIEXPORT void JNICALL JFN(nativeSrcReset)(JNIEnv*, jclass, jlong h) { sf_src_reset((sf_src*)(intptr_t)h); }
JNIEXPORT jint JNICALL JFN(nativeSrcTaps)(JNIEnv*, jclass, jlong h) { return sf_src_taps((const sf_src*)(intptr_t)h); }
JNIEXPORT jlong JNICALL JFN(nativeSrcMaxOutput)(JNIEnv*, jclass, jlong h, jlong nIn) { return sf_src_max_output((const sf_src*)(intptr_t)h, nIn); }
// Возвращает число выходных кадров; in/out — интерлив (кадры × channels), смещения и ёмкость — в кадрах.
JNIEXPORT jlong JNICALL JFN(nativeSrcProcess)(JNIEnv* env, jclass, jlong h, jfloatArray in, jint inOffFrames, jint nIn,
                                              jfloatArray out, jint outOffFrames, jint outCapFrames) {
    sf_src* s = (sf_src*)(intptr_t)h;
    Critical a(env, in);
    if (!a.p) return -1;
    Critical b(env, out);
    if (!b.p) return -1;
    return sf_src_process(s, (const float*)a.p + (int64_t)inOffFrames * sf_src_channels(s), nIn,
                          (float*)b.p + (int64_t)outOffFrames * sf_src_channels(s), outCapFrames);
}
JNIEXPORT jlong JNICALL JFN(nativeSrcFlush)(JNIEnv* env, jclass, jlong h, jfloatArray out, jint outOffFrames, jint outCapFrames) {
    sf_src* s = (sf_src*)(intptr_t)h;
    Critical b(env, out);
    if (!b.p) return -1;
    return sf_src_flush(s, (float*)b.p + (int64_t)outOffFrames * sf_src_channels(s), outCapFrames);
}

JNIEXPORT jlong JNICALL JFN(nativeLimiterCreate)(JNIEnv*, jclass, jint sampleRate, jint channels, jdouble ceilingDbtp) {
    return (jlong)(intptr_t)sf_limiter_create(sampleRate, channels, ceilingDbtp);
}
JNIEXPORT void JNICALL JFN(nativeLimiterDestroy)(JNIEnv*, jclass, jlong h) { sf_limiter_destroy((sf_limiter*)(intptr_t)h); }
JNIEXPORT void JNICALL JFN(nativeLimiterReset)(JNIEnv*, jclass, jlong h) { sf_limiter_reset((sf_limiter*)(intptr_t)h); }
JNIEXPORT jint JNICALL JFN(nativeLimiterLatency)(JNIEnv*, jclass, jlong h) { return sf_limiter_latency_frames((const sf_limiter*)(intptr_t)h); }
// io — интерлив; смещение в float'ах.
JNIEXPORT void JNICALL JFN(nativeLimiterProcess)(JNIEnv* env, jclass, jlong h, jfloatArray io, jint offFloats, jint frames) {
    Critical a(env, io);
    if (!a.p) return;
    sf_limiter_process((sf_limiter*)(intptr_t)h, (float*)a.p + offFloats, frames);
}
// stats[0] = блоки, [1] = блоки с ослаблением > 0,5 dB, [2] = максимум ослабления (dB)
JNIEXPORT void JNICALL JFN(nativeLimiterTakeStats)(JNIEnv* env, jclass, jlong h, jdoubleArray stats) {
    int64_t b = 0, act = 0; double mx = 0;
    sf_limiter_take_stats((sf_limiter*)(intptr_t)h, &b, &act, &mx);
    jdouble v[3] = {(jdouble)b, (jdouble)act, mx};
    env->SetDoubleArrayRegion(stats, 0, 3, v);
}

}
