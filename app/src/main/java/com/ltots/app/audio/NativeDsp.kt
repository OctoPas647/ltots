package com.ltots.app.audio

/**
 * LToTS's native signal processing (cpp/ltots/ltots_dsp.cpp, in the same library as Rubber
 * Band): the EQ's filter cascade, the tape-mode resampler and FFTs. Each caller keeps its Kotlin
 * version as a fallback, used only if the library can't be loaded.
 */
object NativeDsp {
    /** Whether the native library loaded (it always does on the phone; tests load a desktop build). */
    val available: Boolean = runCatching {
        System.loadLibrary("rubberband-jni")
        // Make sure this build of the library has LToTS's functions in it.
        fftDestroy(fftCreate(4))
        true
    }.getOrDefault(false)

    /**
     * Runs interleaved [x] ([frames] × [channels], in place) through [gain] and then [sections]
     * biquads. [coefs]: b0, b1, b2, a1, a2 per section; [state]: z1 per channel, then z2 per
     * channel, for each section (updated).
     */
    @JvmStatic external fun cascade(x: DoubleArray, frames: Int, channels: Int, gain: Double, coefs: DoubleArray, state: DoubleArray, sections: Int)

    @JvmStatic external fun resamplerCreate(channels: Int, speed: Double): Long
    @JvmStatic external fun resamplerDestroy(handle: Long)
    @JvmStatic external fun resamplerReset(handle: Long)
    /** Interleaved [input] in; frames written to [out] returned. [last]: the end of the song. */
    @JvmStatic external fun resamplerProcess(handle: Long, input: FloatArray, frames: Int, out: FloatArray, outCapacity: Int, last: Boolean): Int

    @JvmStatic external fun fftCreate(n: Int): Long
    @JvmStatic external fun fftDestroy(handle: Long)
    @JvmStatic external fun fftFloat(handle: Long, re: FloatArray, im: FloatArray)
    @JvmStatic external fun fftDouble(handle: Long, re: DoubleArray, im: DoubleArray)
    /** Windowed samples from [ring] (from [start], wrapped by [mask]) → FFT → magnitudes into [mags]. */
    @JvmStatic external fun spectrum(handle: Long, ring: FloatArray, mask: Int, start: Long, window: FloatArray, mags: FloatArray, binFrom: Int, binTo: Int, norm: Float)

    /** One FFT plan per size, shared, each with a lock (a plan has its own scratch space). */
    private val plans = HashMap<Int, Pair<Long, Any>>()

    /** The shared plan for size [n] and the lock to hold while using it, or null without the library. */
    fun plan(n: Int): Pair<Long, Any>? {
        if (!available) return null
        return synchronized(plans) { plans.getOrPut(n) { fftCreate(n) to Any() } }
    }
}
