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

}
