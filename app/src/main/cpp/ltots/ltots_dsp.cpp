// LToTS's own signal processing in native code (see NativeDsp.kt): the EQ's filter cascade,
// a windowed-sinc resampler (tape-style pitch & speed) and FFTs. These run on the phone's main
// processor, so doing them here instead of in Kotlin means far less work per sample: no array
// bounds checks or per-sample calls, and on 64-bit ARM the left and right channels of the EQ are
// filtered together in one NEON register.
//
// It also holds the screen-off power governor (see the section below): which non-essential DSP
// is suspended, how big a block the processors work in, and which processor cores the audio
// threads may run on.

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>
#include <algorithm>
#include <sched.h>
#include <unistd.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {

// ---- Power governor ---------------------------------------------------------------------------
//
// Screen off, nothing but the sound matters:
//  - Suspension: the live spectrum (VISUALS) and beat analysis (BEATS) stop at this level, so
//    no FFT for them runs whatever the Kotlin side asks for. A visual FFT returns at once with
//    silent bins; a beat FFT on a non-essential thread throws CancellationException, so a beat
//    map that was half worked out is dropped (never saved wrong) and redone with the screen on.
//  - Block size: the processors work in bigger blocks (fewer native calls, and the tape
//    resampler compacts its input far less often).
//  - Efficiency cores: the audio thread is pinned to the phone's lowest-capacity cluster.

constexpr int SUSPEND_VISUALS = 1;
constexpr int SUSPEND_BEATS = 2;
constexpr int THREAD_BACKGROUND = 1;    // always on the efficiency cores (pre-render, indexing)
constexpr int THREAD_NON_ESSENTIAL = 2; // its FFTs stop while BEATS is suspended

std::atomic<int> g_suspend{0};
std::atomic<int> g_blockFrames{1024};
std::atomic<int64_t> g_skipped{0};
thread_local int t_flags = 0;

struct CoreTopology {
    cpu_set_t little;  // the lowest-capacity cluster
    cpu_set_t all;     // every core the phone has
    int littleCount = 0;
    int allCount = 0;
    bool hetero = false; // more than one kind of core (otherwise pinning gains nothing)
};

long readSysLong(const char* path) {
    FILE* f = std::fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (std::fscanf(f, "%ld", &v) != 1) v = -1;
    std::fclose(f);
    return v;
}

// Each core's capacity (the scheduler's own ranking, Android kernels with EAS), or else its
// top clock speed. Read once.
CoreTopology detectCores() {
    CoreTopology t;
    CPU_ZERO(&t.little);
    CPU_ZERO(&t.all);
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) return t;
    n = std::min<long>(n, CPU_SETSIZE);
    std::vector<long> cap((size_t)n, -1);
    char path[128];
    for (long c = 0; c < n; ++c) {
        CPU_SET((int)c, &t.all);
        ++t.allCount;
        std::snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpu_capacity", c);
        long v = readSysLong(path);
        if (v <= 0) {
            std::snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", c);
            v = readSysLong(path);
        }
        cap[(size_t)c] = v;
    }
    long lo = -1, hi = -1;
    for (long v : cap) if (v > 0) { lo = lo < 0 ? v : std::min(lo, v); hi = std::max(hi, v); }
    if (lo <= 0 || hi <= lo) return t; // unreadable, or every core the same
    for (long c = 0; c < n; ++c) {
        if (cap[(size_t)c] == lo) { CPU_SET((int)c, &t.little); ++t.littleCount; }
    }
    t.hetero = t.littleCount > 0 && t.littleCount < t.allCount;
    return t;
}

const CoreTopology& cores() {
    static const CoreTopology t = detectCores(); // thread-safe one-time init
    return t;
}

// Pins (or frees) the calling thread. Returns how many cores it may now use, 0 when there's
// nothing to do (one kind of core, or a background thread asked to leave), or -errno.
int setThreadEfficiency(bool efficient) {
    const CoreTopology& t = cores();
    if (!t.hetero) return 0;
    if (!efficient && (t_flags & THREAD_BACKGROUND)) return 0; // stays on the efficiency cores
    const cpu_set_t* m = efficient ? &t.little : &t.all;
    // pid 0: the calling thread only (Linux affinity is per thread). The kernel also keeps it
    // within the cores Android allows the app (its cpuset).
    if (sched_setaffinity(0, sizeof(cpu_set_t), m) != 0) return -errno;
    return efficient ? t.littleCount : t.allCount;
}

bool beatsSuspendedHere() {
    return (t_flags & THREAD_NON_ESSENTIAL) && (g_suspend.load(std::memory_order_relaxed) & SUSPEND_BEATS);
}

void throwSuspended(JNIEnv* env) {
    g_skipped.fetch_add(1, std::memory_order_relaxed);
    jclass c = env->FindClass("java/util/concurrent/CancellationException");
    if (c) env->ThrowNew(c, "LToTS: non-essential DSP is suspended while the screen is off");
}

// ---- EQ: a cascade of biquads (transposed direct form II), double precision -----------------
//
// coefs: 5 per section (b0, b1, b2, a1, a2). state: 2 * channels per section, laid out
// [z1 ch0, z1 ch1, ..., z2 ch0, z2 ch1, ...]. x: interleaved, processed in place.

void cascadeScalar(double* x, int frames, int ch, double gain, const double* k, double* st, int nsec) {
    for (int f = 0; f < frames; ++f) {
        double* fr = x + (size_t)f * ch;
        for (int c = 0; c < ch; ++c) {
            double y = fr[c] * gain;
            for (int s = 0; s < nsec; ++s) {
                const double* q = k + s * 5;
                double* z = st + s * 2 * ch;
                const double in = y;
                y = q[0] * in + z[c];
                z[c] = q[1] * in - q[3] * y + z[ch + c];
                z[ch + c] = q[2] * in - q[4] * y;
            }
            fr[c] = y;
        }
    }
}

#if defined(__aarch64__)
// Stereo: left and right side by side in one 2-lane register, every section at once.
void cascadeStereoNeon(double* x, int frames, double gain, const double* k, double* st, int nsec) {
    // Keep the state in registers for the whole block (up to 64 sections).
    float64x2_t z1[64], z2[64];
    for (int s = 0; s < nsec; ++s) { z1[s] = vld1q_f64(st + s * 4); z2[s] = vld1q_f64(st + s * 4 + 2); }
    const float64x2_t g = vdupq_n_f64(gain);
    for (int f = 0; f < frames; ++f) {
        float64x2_t y = vmulq_f64(vld1q_f64(x + 2 * f), g);
        for (int s = 0; s < nsec; ++s) {
            const double* q = k + s * 5;
            const float64x2_t in = y;
            y = vaddq_f64(vmulq_n_f64(in, q[0]), z1[s]);
            z1[s] = vaddq_f64(vsubq_f64(vmulq_n_f64(in, q[1]), vmulq_n_f64(y, q[3])), z2[s]);
            z2[s] = vsubq_f64(vmulq_n_f64(in, q[2]), vmulq_n_f64(y, q[4]));
        }
        vst1q_f64(x + 2 * f, y);
    }
    for (int s = 0; s < nsec; ++s) { vst1q_f64(st + s * 4, z1[s]); vst1q_f64(st + s * 4 + 2, z2[s]); }
}
#endif

// ---- Resampler: windowed sinc, for tape-style speed (pitch and speed move together) ----------
//
// Output frame n is the input at time n * speed (in input frames), so a whole song comes out
// exactly speed times shorter, starting at the same instant (no added delay). When speeding up,
// the filter's cutoff drops to keep what would alias out.

double besselI0(double x) {
    double sum = 1, term = 1, k = 1;
    while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k += 1; }
    return sum;
}

struct Resampler {
    int ch;
    double speed;
    int half;          // filter half-width in input frames
    int phases;        // table resolution between input frames
    std::vector<float> table; // (phases + 1) rows of 2 * half taps
    std::vector<float> buf;   // interleaved input not yet used up
    int64_t base;      // input frame index of buf[0]
    double t;          // input time of the next output frame
    int64_t inTotal;
    int64_t outTotal;

    Resampler(int channels, double sp) : ch(channels), speed(sp) {
        const double cutoff = 0.97 * std::min(1.0, 1.0 / speed);
        half = (int)std::ceil(24.0 / cutoff);
        phases = 256;
        const double beta = 8.6;
        const double i0b = besselI0(beta);
        table.assign((size_t)(phases + 1) * 2 * half, 0.f);
        for (int p = 0; p <= phases; ++p) {
            const double frac = (double)p / phases;
            for (int j = 0; j < 2 * half; ++j) {
                const double d = (j - half + 1) - frac; // distance from the output time, input frames
                const double r = d / half;
                const double w = std::fabs(r) >= 1 ? 0 : besselI0(beta * std::sqrt(1 - r * r)) / i0b;
                const double xx = M_PI * cutoff * d;
                const double s = std::fabs(xx) < 1e-9 ? 1.0 : std::sin(xx) / xx;
                table[(size_t)p * 2 * half + j] = (float)(cutoff * s * w);
            }
        }
        reset();
    }

    void reset() {
        // Silence before the start, so the first output frame can look back.
        buf.assign((size_t)half * ch, 0.f);
        base = -half;
        t = 0;
        inTotal = 0;
        outTotal = 0;
    }

    // Appends input; writes as many output frames as are ready (up to outCap). With last, the
    // song's end: pads with silence and finishes at exactly inTotal / speed frames.
    int process(const float* in, int frames, float* out, int outCap, bool last) {
        buf.insert(buf.end(), in, in + (size_t)frames * ch);
        inTotal += frames;
        const int64_t wantTotal = last ? (int64_t)std::floor(inTotal / speed) : INT64_MAX;
        if (last) buf.insert(buf.end(), (size_t)(half + 2) * ch, 0.f);
        const int64_t avail = base + (int64_t)(buf.size() / ch); // input frames held: [base, avail)
        int written = 0;
        while (written < outCap && outTotal < wantTotal) {
            const int64_t i0 = (int64_t)std::floor(t);
            if (i0 + half >= avail) break; // needs input that hasn't come yet
            const double frac = t - (double)i0;
            const double pf = frac * phases;
            const int p = (int)pf;
            const float a = (float)(pf - p);
            const float* r0 = table.data() + (size_t)p * 2 * half;
            const float* r1 = r0 + 2 * half;
            const float* src = buf.data() + (size_t)(i0 - half + 1 - base) * ch;
            for (int c = 0; c < ch; ++c) {
                float acc = 0.f;
                for (int j = 0; j < 2 * half; ++j) {
                    const float w = r0[j] + (r1[j] - r0[j]) * a;
                    acc += w * src[(size_t)j * ch + c];
                }
                out[(size_t)written * ch + c] = acc;
            }
            ++written;
            ++outTotal;
            t = outTotal * speed;
        }
        // Drop input that no future output frame will reach. Erasing shifts everything after
        // it down, so it's done once a whole block has been used up rather than on every call:
        // with the screen off (big blocks) the buffer is compacted several times less often.
        const int64_t keepFrom = (int64_t)std::floor(t) - half + 1;
        const int64_t usedUp = keepFrom - base;
        const int block = g_blockFrames.load(std::memory_order_relaxed);
        if (usedUp > 0 && (usedUp >= block || last)) {
            const int64_t drop = std::min<int64_t>(usedUp, (int64_t)(buf.size() / ch));
            buf.erase(buf.begin(), buf.begin() + (size_t)drop * ch);
            base += drop;
        }
        // Room for the next call without growing (no allocation on the audio thread).
        const size_t want = (size_t)(2 * half + 2 * block + frames) * ch;
        if (buf.capacity() < want) buf.reserve(want);
        return written;
    }
};

// ---- FFT ------------------------------------------------------------------------------------
//
// One plan per size: power-of-two sizes in float (the live spectrum), sizes made of 2s and 3s in
// double (fingerprints, beats, moods, the music separator), the same algorithms as the Kotlin
// versions they replace.

struct FftPlan {
    int n;
    bool pow2;
    int levels;
    std::vector<float> cosF, sinF;   // pow2, float: cos/sin(2*pi*k/n)
    std::vector<int> rev;
    std::vector<double> cosD, sinD;  // mixed radix, double: cos/sin(-2*pi*k/n)
    std::vector<int> factors;
    std::vector<double> sre, sim;    // scratch
    std::vector<float> fre, fim;
};

void recMixed(FftPlan* P, const double* inRe, const double* inIm, int i0, int stride,
              double* outRe, double* outIm, int o0, int len, int fi) {
    if (len == 1) { outRe[o0] = inRe[i0]; outIm[o0] = inIm[i0]; return; }
    const int p = P->factors[fi];
    const int m = len / p;
    for (int q = 0; q < p; ++q) recMixed(P, inRe, inIm, i0 + q * stride, stride * p, outRe, outIm, o0 + q * m, m, fi + 1);
    const int tw = P->n / len;
    const double* cosT = P->cosD.data();
    const double* sinT = P->sinD.data();
    if (p == 2) {
        for (int k = 0; k < m; ++k) {
            const int a = o0 + k, b = a + m;
            const double c = cosT[k * tw], s = sinT[k * tw];
            const double br = outRe[b] * c - outIm[b] * s;
            const double bi = outRe[b] * s + outIm[b] * c;
            const double ar = outRe[a], ai = outIm[a];
            outRe[a] = ar + br; outIm[a] = ai + bi;
            outRe[b] = ar - br; outIm[b] = ai - bi;
        }
    } else {
        const double c3 = -0.5;
        const double s3 = -std::sin(2 * M_PI / 3);
        for (int k = 0; k < m; ++k) {
            const int i0k = o0 + k, i1 = i0k + m, i2 = i1 + m;
            const int w1 = k * tw, w2 = 2 * k * tw;
            const double x1r = outRe[i1] * cosT[w1] - outIm[i1] * sinT[w1];
            const double x1i = outRe[i1] * sinT[w1] + outIm[i1] * cosT[w1];
            const double x2r = outRe[i2] * cosT[w2] - outIm[i2] * sinT[w2];
            const double x2i = outRe[i2] * sinT[w2] + outIm[i2] * cosT[w2];
            const double x0r = outRe[i0k], x0i = outIm[i0k];
            const double sr = x1r + x2r, si = x1i + x2i;
            const double dr = x1r - x2r, di = x1i - x2i;
            const double mr = x0r + c3 * sr, mi = x0i + c3 * si;
            const double rr = -s3 * di, ri = s3 * dr;
            outRe[i0k] = x0r + sr; outIm[i0k] = x0i + si;
            outRe[i1] = mr + rr; outIm[i1] = mi + ri;
            outRe[i2] = mr - rr; outIm[i2] = mi - ri;
        }
    }
}

void fftPow2(FftPlan* P, float* re, float* im) {
    const int n = P->n;
    for (int i = 0; i < n; ++i) {
        const int j = P->rev[i];
        if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    const float* cosT = P->cosF.data();
    const float* sinT = P->sinF.data();
    for (int size = 2; size <= n; size *= 2) {
        const int half = size / 2, step = n / size;
        for (int i = 0; i < n; i += size) {
            int k = 0;
            for (int j = i; j < i + half; ++j, k += step) {
                const int l = j + half;
                const float tre = re[l] * cosT[k] + im[l] * sinT[k];
                const float tim = -re[l] * sinT[k] + im[l] * cosT[k];
                re[l] = re[j] - tre; im[l] = im[j] - tim;
                re[j] += tre; im[j] += tim;
            }
        }
    }
}

} // namespace

extern "C" {

// ---- EQ ---------------------------------------------------------------------------------------

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_cascade(
        JNIEnv* env, jclass, jdoubleArray jx, jint frames, jint ch, jdouble gain,
        jdoubleArray jk, jdoubleArray jst, jint nsec) {
    double* x = (double*)env->GetPrimitiveArrayCritical(jx, nullptr);
    double* k = (double*)env->GetPrimitiveArrayCritical(jk, nullptr);
    double* st = (double*)env->GetPrimitiveArrayCritical(jst, nullptr);
    if (x && k && st) {
#if defined(__aarch64__)
        if (ch == 2 && nsec <= 64) cascadeStereoNeon(x, frames, gain, k, st, nsec);
        else cascadeScalar(x, frames, ch, gain, k, st, nsec);
#else
        cascadeScalar(x, frames, ch, gain, k, st, nsec);
#endif
    }
    if (st) env->ReleasePrimitiveArrayCritical(jst, st, 0);
    if (k) env->ReleasePrimitiveArrayCritical(jk, k, JNI_ABORT);
    if (x) env->ReleasePrimitiveArrayCritical(jx, x, 0);
}

// ---- Resampler --------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_com_ltots_app_audio_NativeDsp_resamplerCreate(JNIEnv*, jclass, jint ch, jdouble speed) {
    return (jlong)(intptr_t)new Resampler(ch, speed);
}

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_resamplerDestroy(JNIEnv*, jclass, jlong h) {
    delete (Resampler*)(intptr_t)h;
}

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_resamplerReset(JNIEnv*, jclass, jlong h) {
    ((Resampler*)(intptr_t)h)->reset();
}

JNIEXPORT jint JNICALL Java_com_ltots_app_audio_NativeDsp_resamplerProcess(
        JNIEnv* env, jclass, jlong h, jfloatArray jin, jint frames, jfloatArray jout, jint outCap, jboolean last) {
    auto* r = (Resampler*)(intptr_t)h;
    float* in = (float*)env->GetPrimitiveArrayCritical(jin, nullptr);
    float* out = (float*)env->GetPrimitiveArrayCritical(jout, nullptr);
    int n = 0;
    if (in && out) n = r->process(in, frames, out, outCap, last);
    if (out) env->ReleasePrimitiveArrayCritical(jout, out, 0);
    if (in) env->ReleasePrimitiveArrayCritical(jin, in, JNI_ABORT);
    return n;
}

// ---- FFT --------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_com_ltots_app_audio_NativeDsp_fftCreate(JNIEnv*, jclass, jint n) {
    auto* P = new FftPlan();
    P->n = n;
    P->levels = 0;
    while ((1 << P->levels) < n) ++P->levels;
    P->pow2 = (1 << P->levels) == n;
    if (P->pow2) {
        P->cosF.resize(n / 2); P->sinF.resize(n / 2); P->rev.resize(n);
        for (int k = 0; k < n / 2; ++k) { P->cosF[k] = (float)std::cos(2 * M_PI * k / n); P->sinF[k] = (float)std::sin(2 * M_PI * k / n); }
        for (int i = 0; i < n; ++i) {
            int r = 0;
            for (int b = 0; b < P->levels; ++b) if (i & (1 << b)) r |= 1 << (P->levels - 1 - b);
            P->rev[i] = r;
        }
        P->fre.resize(n); P->fim.resize(n);
    }
    // Mixed radix (2s and 3s), double.
    int m = n;
    std::vector<int> twos, threes;
    while (m % 2 == 0) { twos.push_back(2); m /= 2; }
    while (m % 3 == 0) { threes.push_back(3); m /= 3; }
    if (m == 1 && n > 1) {
        P->factors = threes; P->factors.insert(P->factors.end(), twos.begin(), twos.end());
        P->cosD.resize(n); P->sinD.resize(n);
        for (int k = 0; k < n; ++k) { P->cosD[k] = std::cos(-2 * M_PI * k / n); P->sinD[k] = std::sin(-2 * M_PI * k / n); }
        P->sre.resize(n); P->sim.resize(n);
    }
    return (jlong)(intptr_t)P;
}

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_fftDestroy(JNIEnv*, jclass, jlong h) {
    delete (FftPlan*)(intptr_t)h;
}

// Float, power of two, in place (the same convention as the Kotlin Fft class).
JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_fftFloat(JNIEnv* env, jclass, jlong h, jfloatArray jre, jfloatArray jim) {
    if (beatsSuspendedHere()) { throwSuspended(env); return; }
    auto* P = (FftPlan*)(intptr_t)h;
    float* re = (float*)env->GetPrimitiveArrayCritical(jre, nullptr);
    float* im = (float*)env->GetPrimitiveArrayCritical(jim, nullptr);
    if (re && im && P->pow2) fftPow2(P, re, im);
    if (im) env->ReleasePrimitiveArrayCritical(jim, im, 0);
    if (re) env->ReleasePrimitiveArrayCritical(jre, re, 0);
}

// The live spectrum's whole step: window the samples out of the ring, FFT, magnitudes for the
// bins the bars use.
JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_spectrum(
        JNIEnv* env, jclass, jlong h, jfloatArray jring, jint mask, jlong start, jfloatArray jwin,
        jfloatArray jmags, jint binFrom, jint binTo, jfloat norm) {
    auto* P = (FftPlan*)(intptr_t)h;
    if (!P->pow2) return;
    if (g_suspend.load(std::memory_order_relaxed) & SUSPEND_VISUALS) {
        // Screen off: no window, no FFT, no square roots. The bars read silence and fall.
        g_skipped.fetch_add(1, std::memory_order_relaxed);
        float* mags = (float*)env->GetPrimitiveArrayCritical(jmags, nullptr);
        if (mags) {
            for (int k = binFrom; k < binTo; ++k) mags[k] = 0.f;
            env->ReleasePrimitiveArrayCritical(jmags, mags, 0);
        }
        return;
    }
    float* ring = (float*)env->GetPrimitiveArrayCritical(jring, nullptr);
    float* win = (float*)env->GetPrimitiveArrayCritical(jwin, nullptr);
    float* mags = (float*)env->GetPrimitiveArrayCritical(jmags, nullptr);
    if (ring && win && mags) {
        float* re = P->fre.data();
        float* im = P->fim.data();
        for (int i = 0; i < P->n; ++i) {
            re[i] = ring[(start + i) & mask] * win[i];
            im[i] = 0.f;
        }
        fftPow2(P, re, im);
        for (int k = binFrom; k < binTo; ++k) mags[k] = std::sqrt(re[k] * re[k] + im[k] * im[k]) * norm;
    }
    if (mags) env->ReleasePrimitiveArrayCritical(jmags, mags, 0);
    if (win) env->ReleasePrimitiveArrayCritical(jwin, win, JNI_ABORT);
    if (ring) env->ReleasePrimitiveArrayCritical(jring, ring, JNI_ABORT);
}

// Double, sizes of 2s and 3s, forward, in place (the same convention as MixedFft.forward).
JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_fftDouble(JNIEnv* env, jclass, jlong h, jdoubleArray jre, jdoubleArray jim) {
    if (beatsSuspendedHere()) { throwSuspended(env); return; }
    auto* P = (FftPlan*)(intptr_t)h;
    if (P->factors.empty()) return;
    double* re = (double*)env->GetPrimitiveArrayCritical(jre, nullptr);
    double* im = (double*)env->GetPrimitiveArrayCritical(jim, nullptr);
    if (re && im) {
        recMixed(P, re, im, 0, 1, P->sre.data(), P->sim.data(), 0, P->n, 0);
        std::memcpy(re, P->sre.data(), sizeof(double) * P->n);
        std::memcpy(im, P->sim.data(), sizeof(double) * P->n);
    }
    if (im) env->ReleasePrimitiveArrayCritical(jim, im, 0);
    if (re) env->ReleasePrimitiveArrayCritical(jre, re, 0);
}

// ---- Power governor ---------------------------------------------------------------------------

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_setSuspendMask(JNIEnv*, jclass, jint mask) {
    g_suspend.store(mask & (SUSPEND_VISUALS | SUSPEND_BEATS), std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL Java_com_ltots_app_audio_NativeDsp_suspendMask(JNIEnv*, jclass) {
    return g_suspend.load(std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL Java_com_ltots_app_audio_NativeDsp_skippedCount(JNIEnv*, jclass) {
    return (jlong)g_skipped.load(std::memory_order_relaxed);
}

// The calling thread: background work (pinned to the efficiency cores for good) and/or
// non-essential (its FFTs stop while beats are suspended).
JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_markThread(JNIEnv*, jclass, jint flags) {
    t_flags |= flags & (THREAD_BACKGROUND | THREAD_NON_ESSENTIAL);
    if (flags & THREAD_BACKGROUND) setThreadEfficiency(true);
}

JNIEXPORT void JNICALL Java_com_ltots_app_audio_NativeDsp_setBlockFrames(JNIEnv*, jclass, jint frames) {
    g_blockFrames.store(std::max(64, std::min((int)frames, 1 << 16)), std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL Java_com_ltots_app_audio_NativeDsp_blockFrames(JNIEnv*, jclass) {
    return g_blockFrames.load(std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL Java_com_ltots_app_audio_NativeDsp_setThreadEfficiency(JNIEnv*, jclass, jboolean efficient) {
    return setThreadEfficiency(efficient == JNI_TRUE);
}

// How many efficiency cores the phone has (0: all its cores are the same kind).
JNIEXPORT jint JNICALL Java_com_ltots_app_audio_NativeDsp_efficiencyCoreCount(JNIEnv*, jclass) {
    const CoreTopology& t = cores();
    return t.hetero ? t.littleCount : 0;
}

} // extern "C"
