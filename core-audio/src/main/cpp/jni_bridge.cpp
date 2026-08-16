#include <jni.h>

#include <string>

#include "engine.h"

using estem::Engine;
using estem::EffectId;
using estem::kStemCount;

namespace {

inline Engine* toEngine(jlong handle) { return reinterpret_cast<Engine*>(handle); }

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete toEngine(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStart(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStop(JNIEnv*, jobject, jlong handle) {
    toEngine(handle)->stop();
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeIsRunning(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeLoadTrack(JNIEnv* env,
                                                          jobject,
                                                          jlong handle,
                                                          jint deck,
                                                          jobjectArray paths,
                                                          jint sampleRate,
                                                          jint channelCount) {
    if (env->GetArrayLength(paths) != kStemCount) return JNI_FALSE;

    std::string resolved[kStemCount];
    for (int i = 0; i < kStemCount; ++i) {
        auto path = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        resolved[i] = toStdString(env, path);
        env->DeleteLocalRef(path);
        if (resolved[i].empty()) return JNI_FALSE;
    }

    return toEngine(handle)->loadTrack(deck, resolved, sampleRate, channelCount) ? JNI_TRUE
                                                                                   : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeUnloadTrack(JNIEnv*, jobject, jlong handle, jint deck) {
    toEngine(handle)->unloadTrack(deck);
}

// ---- transport ----

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetPlaying(JNIEnv*, jobject, jlong handle,
                                                           jint deck, jboolean playing) {
    toEngine(handle)->deck(deck).setPlaying(playing == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeIsPlaying(JNIEnv*, jobject, jlong handle, jint deck) {
    return toEngine(handle)->deck(deck).isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSeek(JNIEnv*, jobject, jlong handle, jint deck,
                                                     jlong frame) {
    toEngine(handle)->deck(deck).seekFrames(frame);
}

JNIEXPORT jlong JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativePositionFrames(JNIEnv*, jobject, jlong handle, jint deck) {
    return toEngine(handle)->deck(deck).positionFrames();
}

JNIEXPORT jlong JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeTotalFrames(JNIEnv*, jobject, jlong handle, jint deck) {
    return toEngine(handle)->deck(deck).totalFrames();
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetLooping(JNIEnv*, jobject, jlong handle,
                                                           jint deck, jboolean looping) {
    toEngine(handle)->deck(deck).setLooping(looping == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetRate(JNIEnv*, jobject, jlong handle, jint deck,
                                                        jfloat rate) {
    toEngine(handle)->deck(deck).setRate(rate);
}

JNIEXPORT jfloat JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeGetRate(JNIEnv*, jobject, jlong handle, jint deck) {
    return toEngine(handle)->deck(deck).rate();
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetLoopRegion(JNIEnv*, jobject, jlong handle, jint deck,
                                                              jlong startFrame, jlong endFrame) {
    toEngine(handle)->deck(deck).setLoopRegion(startFrame, endFrame);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetKeepPitch(JNIEnv*, jobject, jlong handle, jint deck,
                                                             jboolean keep) {
    toEngine(handle)->deck(deck).setStretchEnabled(keep == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeKeepPitch(JNIEnv*, jobject, jlong handle, jint deck) {
    return toEngine(handle)->deck(deck).stretchEnabled() ? JNI_TRUE : JNI_FALSE;
}

// ---- stem mixing ----

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetStemGain(JNIEnv*, jobject, jlong handle, jint deck,
                                                            jint stem, jfloat gain) {
    toEngine(handle)->deck(deck).setStemGain(stem, gain);
}

JNIEXPORT jfloat JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeGetStemGain(JNIEnv*, jobject, jlong handle, jint deck,
                                                            jint stem) {
    return toEngine(handle)->deck(deck).stemGain(stem);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetIsolateMask(JNIEnv*, jobject, jlong handle, jint deck,
                                                               jint mask) {
    toEngine(handle)->deck(deck).setIsolateMask(static_cast<uint32_t>(mask));
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStemEnergies(JNIEnv* env, jobject, jlong handle,
                                                             jint index, jfloatArray out) {
    if (env->GetArrayLength(out) < kStemCount) return;
    jfloat values[kStemCount];
    auto& deck = toEngine(handle)->deck(index);
    for (int i = 0; i < kStemCount; ++i) values[i] = deck.stemEnergy(i);
    env->SetFloatArrayRegion(out, 0, kStemCount, values);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetMuteMask(JNIEnv*, jobject, jlong handle, jint deck,
                                                            jint mask) {
    toEngine(handle)->deck(deck).setMuteMask(static_cast<uint32_t>(mask));
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetMasterVolume(JNIEnv*, jobject, jlong handle,
                                                                jfloat volume) {
    toEngine(handle)->setMasterVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetCrossfade(JNIEnv*, jobject, jlong handle,
                                                             jfloat position) {
    toEngine(handle)->setCrossfade(position);
}

JNIEXPORT jfloat JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeCrossfade(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->crossfade();
}

// ---- effects ----

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetEffect(JNIEnv*, jobject, jlong handle,
                                                          jint effectId) {
    toEngine(handle)->effects().setEffect(static_cast<EffectId>(effectId));
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeSetEffectIntensity(JNIEnv*, jobject, jlong handle,
                                                                   jfloat intensity) {
    toEngine(handle)->effects().setIntensity(intensity);
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeClearEffects(JNIEnv*, jobject, jlong handle) {
    toEngine(handle)->effects().clear();
}

// ---- recording ----

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStartRecording(JNIEnv* env, jobject, jlong handle,
                                                               jstring path, jlong maxFrames) {
    auto* engine = toEngine(handle);
    return engine->recorder().start(toStdString(env, path), engine->streamSampleRate(), maxFrames)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStopRecording(JNIEnv*, jobject, jlong handle) {
    toEngine(handle)->recorder().stop();
}

JNIEXPORT jboolean JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeIsRecording(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->recorder().isRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeRecordedFrames(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->recorder().framesWritten();
}

// ---- diagnostics ----

JNIEXPORT jint JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeStreamSampleRate(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->streamSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeXRunCount(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->xRunCount();
}

JNIEXPORT jint JNICALL
Java_com_sss_estem_audio_StemPlayerEngine_nativeBufferFrames(JNIEnv*, jobject, jlong handle) {
    return toEngine(handle)->bufferFrames();
}

} // extern "C"
