package com.ltots.app.audio

/**
 * LToTS's native signal processing (cpp/ltots/ltots_dsp.cpp, in the same library as Rubber
 * Band): the EQ's filter cascade, the tape-mode resampler and FFTs. Each caller keeps its Kotlin
 * version as a fallback, used only if the library can't be loaded.
 *
 * It also carries the screen-off power governor: [suspendNonEssential] stops the live spectrum
 * and beat analysis at the native level, [preferBlockFrames] sets how big a block the processors
 * work in, and [markThread] / [setThreadEfficiency] put threads on the efficiency cores.
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
    /** Throws CancellationException on a non-essential thread while beats are suspended. */
    @JvmStatic external fun fftFloat(handle: Long, re: FloatArray, im: FloatArray)
    /** Throws CancellationException on a non-essential thread while beats are suspended. */
    @JvmStatic external fun fftDouble(handle: Long, re: DoubleArray, im: DoubleArray)
    /**
     * Windowed samples from [ring] (from [start], wrapped by [mask]) → FFT → magnitudes into [mags].
     * While visuals are suspended it does no FFT at all and writes silence.
     */
    @JvmStatic external fun spectrum(handle: Long, ring: FloatArray, mask: Int, start: Long, window: FloatArray, mags: FloatArray, binFrom: Int, binTo: Int, norm: Float)

    // ---- power governor (native side) ----------------------------------------------------

    @JvmStatic external fun setSuspendMask(mask: Int)
    @JvmStatic external fun suspendMask(): Int
    /** FFTs refused or skipped because of suspension (diagnostics and tests). */
    @JvmStatic external fun skippedCount(): Long
    /** Flags for the calling thread: [THREAD_BACKGROUND], [THREAD_NON_ESSENTIAL]. */
    @JvmStatic external fun markThread(flags: Int)
    @JvmStatic external fun setBlockFrames(frames: Int)
    @JvmStatic external fun blockFrames(): Int
    /**
     * Pins the calling thread to the efficiency cores (or lets it use all of them again).
     * Returns how many cores it may use now, 0 if there was nothing to do, or -errno.
     */
    @JvmStatic external fun setThreadEfficiency(efficient: Boolean): Int
    /** How many efficiency cores the phone has; 0 when all its cores are the same kind. */
    @JvmStatic external fun efficiencyCoreCount(): Int

    /** The live spectrum (the bars, the cover's spectrum, the icon ring). */
    const val SUSPEND_VISUALS = 1
    /** Beat analysis (Move with the beat). Not while charging: library prep runs then. */
    const val SUSPEND_BEATS = 2

    /** Always on the efficiency cores (pre-rendering, indexing). */
    const val THREAD_BACKGROUND = 1
    /** Its FFTs stop while [SUSPEND_BEATS] is on. */
    const val THREAD_NON_ESSENTIAL = 2

    /** The usual processing block (screen on: changes are heard quickly). */
    const val SHORT_BLOCK_FRAMES = 1024
    /** The screen-off block: fewer, bigger native calls per wake-up. */
    const val LONG_BLOCK_FRAMES = 8192

    @Volatile private var mask = 0
    @Volatile private var block = SHORT_BLOCK_FRAMES

    /** Whether the live spectrum is suspended (read by the UI and the audio thread). */
    val visualsSuspended: Boolean get() = (mask and SUSPEND_VISUALS) != 0
    /** Whether beat analysis is suspended. */
    val beatsSuspended: Boolean get() = (mask and SUSPEND_BEATS) != 0
    /** The block size the processors should work in right now. */
    val processBlockFrames: Int get() = block

    /** Suspends exactly the non-essential DSP in [newMask] (and resumes the rest). */
    fun suspendNonEssential(newMask: Int) {
        mask = newMask
        if (available) setSuspendMask(newMask)
    }

    /** The processors' block size, here and in the native code. */
    fun preferBlockFrames(frames: Int) {
        block = frames
        if (available) setBlockFrames(frames)
    }

    /** Marks the calling thread (no-op without the library). */
    fun markCurrentThread(flags: Int) {
        if (available) runCatching { markThread(flags) }
    }

    /** One FFT plan per size, shared, each with a lock (a plan has its own scratch space). */
    private val plans = HashMap<Int, Pair<Long, Any>>()

    /** The shared plan for size [n] and the lock to hold while using it, or null without the library. */
    fun plan(n: Int): Pair<Long, Any>? {
        if (!available) return null
        return synchronized(plans) { plans.getOrPut(n) { fftCreate(n) to Any() } }
    }
}
