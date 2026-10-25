// JNI bridge to whisper.cpp for LToTS's song finder: turns sung words into text.
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ltots_app_identify_WhisperNative_init(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_ltots_app_identify_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Returns "lang\ntext of segment 1\ntext of segment 2…", or null on failure.
JNIEXPORT jstring JNICALL
Java_com_ltots_app_identify_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm,
                                                     jint threads, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = langStr.c_str();       // "auto" detects the language
    p.translate = false;
    p.no_timestamps = true;
    p.no_context = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;              // no "[Music]" / "♪" tokens
    p.temperature_inc = 0.2f;

    if (whisper_full(ctx, p, samples.data(), n) != 0) return nullptr;

    std::string out;
    int id = whisper_full_lang_id(ctx);
    out += (id >= 0 ? whisper_lang_str(id) : "");
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        out += '\n';
        out += whisper_full_get_segment_text(ctx, i);
    }
    // Make sure the text is valid modified UTF-8 for NewStringUTF: drop broken multibyte tails.
    std::string clean;
    clean.reserve(out.size());
    for (size_t i = 0; i < out.size();) {
        auto c = static_cast<unsigned char>(out[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0 || i + len > out.size()) { i++; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; k++) if ((static_cast<unsigned char>(out[i + k]) >> 6) != 0x2) ok = false;
        // 4-byte sequences (emoji) aren't valid modified UTF-8; skip them.
        if (ok && len < 4) clean.append(out, i, len);
        i += ok ? len : 1;
    }
    return env->NewStringUTF(clean.c_str());
}

}
