#include <jni.h>
#include <cstdint>
#include <opus.h>

namespace {
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_createEncoder(JNIEnv* env, jclass) {
    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error != OPUS_OK) { fail(env, "Cannot create Opus encoder"); return 0; }
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(24000));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(3));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}
extern "C" JNIEXPORT jlong JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_createDecoder(JNIEnv* env, jclass) {
    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(16000, 1, &error);
    if (!decoder || error != OPUS_OK) { fail(env, "Cannot create Opus decoder"); return 0; }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(decoder));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_encodeFrame(JNIEnv* env, jclass, jlong handle, jshortArray input) {
    if (!handle || !input || env->GetArrayLength(input) != 320) { fail(env, "Invalid PCM input"); return nullptr; }
    opus_int16 pcm[320];
    unsigned char packet[1275];
    env->GetShortArrayRegion(input, 0, 320, pcm);
    if (env->ExceptionCheck()) return nullptr;
    int size = opus_encode(reinterpret_cast<OpusEncoder*>(static_cast<intptr_t>(handle)), pcm, 320, packet, sizeof(packet));
    if (size < 0) { fail(env, "Opus encoding failed"); return nullptr; }
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(packet));
    return result;
}
extern "C" JNIEXPORT jshortArray JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_decodeFrame(JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    if (!handle) { fail(env, "Invalid decoder handle"); return nullptr; }
    opus_int16 pcm[320];
    unsigned char packet[1275];
    int size = input ? env->GetArrayLength(input) : 0;
    if (input && (size < 1 || size > 1275)) { fail(env, "Invalid Opus input size"); return nullptr; }
    if (input) {
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(packet));
        if (env->ExceptionCheck()) return nullptr;
        if (opus_packet_get_nb_samples(packet, size, 16000) != 320) { fail(env, "Expected a 20 ms Opus frame"); return nullptr; }
    }
    int samples = opus_decode(reinterpret_cast<OpusDecoder*>(static_cast<intptr_t>(handle)), input ? packet : nullptr, size, pcm, 320, 0);
    if (samples < 0) { fail(env, "Opus decoding failed"); return nullptr; }
    jshortArray result = env->NewShortArray(samples);
    if (result) env->SetShortArrayRegion(result, 0, samples, pcm);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_destroyEncoder(JNIEnv*, jclass, jlong handle) {
    if (handle) opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(static_cast<intptr_t>(handle)));
}
extern "C" JNIEXPORT void JNICALL
Java_cn_sunnystring_bletalkie_audio_OpusCodec_destroyDecoder(JNIEnv*, jclass, jlong handle) {
    if (handle) opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(static_cast<intptr_t>(handle)));
}
