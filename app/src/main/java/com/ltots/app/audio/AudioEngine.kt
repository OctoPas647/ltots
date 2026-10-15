package com.ltots.app.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.ltots.app.data.AppSettings
import com.ltots.app.data.SongProfile
import com.ltots.app.data.Store
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * App-wide sound engine. Owns the processing chain, per-song profiles, pitch/speed, output
 * monitoring and bit-perfect routing.
 *
 * The audio thread reads settings through [SoundSource] (by song id), so each song gets its own
 * EQ and pitch from its first sample. The UI edits through the functions below; pitch changes
 * reach the audio instantly, speed changes are applied when a slider is released.
 */
@UnstableApi
object AudioEngine : SoundSource {
    val tap = AnalyzerTap()
    val eqProcessor = EqProcessor(tap, this)
    val timePitch = TimePitchProcessor(this)
    val outputStage = OutputStage()
    private val scope = MainScope()
    private val versionCounter = AtomicInteger(0)

    private lateinit var appContext: Context
    lateinit var output: OutputMonitor
        private set

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player

    /** Whether sound is actually coming out (the visualizer sleeps when it isn't). */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    lateinit var settings: MutableStateFlow<AppSettings>
        private set
    lateinit var defaultEq: MutableStateFlow<EqState>
        private set

    /** Profile key (media id) of the song the UI shows as playing. */
    private val _songKey = MutableStateFlow<String?>(null)
    val songKey: StateFlow<String?> = _songKey

    private val _songProfile = MutableStateFlow<SongProfile?>(null)
    val songProfile: StateFlow<SongProfile?> = _songProfile

    lateinit var effectiveEq: StateFlow<EqState>
        private set

    private val _pitchCents = MutableStateFlow(0)
    val pitchCents: StateFlow<Int> = _pitchCents
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed
    private val _tape = MutableStateFlow(false)
    val tape: StateFlow<Boolean> = _tape

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = MutableStateFlow(Store.loadSettings())
        defaultEq = MutableStateFlow(Store.loadDefaultEq())
        output = OutputMonitor(appContext)
        effectiveEq = combine(defaultEq, _songProfile) { def, prof -> prof?.eq ?: def }
            .stateIn(scope, SharingStarted.Eagerly, defaultEq.value)
        outputStage.int16Output = settings.value.bitPerfectUsb
        if (!settings.value.perSongPitchSpeed) loadGlobalPitchSpeed()
        output.onDeviceChanged = { reapplyBitPerfect() }
        scope.launch { tap.info.collect { reapplyBitPerfect() } }
    }

    private fun changed() { versionCounter.incrementAndGet() }

    // ---- SoundSource (called on the audio thread) ------------------------------------------

    override val version: Int get() = versionCounter.get()

    override fun eqFor(mediaId: String?): EqState {
        if (!initialized) return EqState(enabled = false)
        val prof = mediaId?.let { Store.loadProfile(it) }
        return prof?.eq ?: defaultEq.value
    }

    override fun pitchScaleFor(mediaId: String?): Double {
        if (!initialized) return 1.0
        val s = settings.value
        if (!s.perSongPitchSpeed || mediaId == null) return scaleOf(_pitchCents.value, _speed.value, _tape.value)
        val p = Store.loadProfile(mediaId)
        return scaleOf(p?.pitchCents ?: 0, p?.speed ?: 1f, p?.tape ?: false)
    }

    override val preserveFormants: Boolean get() = initialized && settings.value.preserveFormants
    override val efficientPitch: Boolean get() = initialized && settings.value.efficientPitch

    private fun scaleOf(cents: Int, speed: Float, tape: Boolean): Double =
        if (tape) speed.toDouble() else 2.0.pow(cents / 1200.0)

    // ---- player ----------------------------------------------------------------------------

    fun buildPlayer(context: Context): ExoPlayer {
        val chain = LToTSProcessorChain(eqProcessor, timePitch, outputStage)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = buildSink(context, chain)
        }
        val p = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onSongChanged(mediaItem)
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
        })
        _player.value = p
        onSongChanged(p.currentMediaItem)
        applySpeedToPlayer()
        return p
    }

    /** Media3's audio output with LToTS' processing chain (shared with the tests). */
    fun buildSink(
        context: Context,
        chain: LToTSProcessorChain,
        output: androidx.media3.exoplayer.audio.AudioOutputProvider? = null,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .apply { if (output != null) setAudioOutputProvider(output) }
            .build()

    fun releasePlayer() {
        _player.value?.release()
        _player.value = null
        _isPlaying.value = false
    }

    private fun onSongChanged(item: MediaItem?) {
        val key = item?.mediaId?.takeIf { it.isNotBlank() }
        _songKey.value = key
        val prof = key?.let { Store.loadProfile(it) }
        _songProfile.value = prof
        if (settings.value.perSongPitchSpeed) {
            _pitchCents.value = prof?.pitchCents ?: 0
            _speed.value = prof?.speed ?: 1f
            _tape.value = prof?.tape ?: false
            applySpeedToPlayer()
        }
    }

    // ---- EQ editing ------------------------------------------------------------------------

    val songHasOwnEq: Boolean get() = _songProfile.value?.eq != null

    fun updateEq(transform: (EqState) -> EqState) {
        val key = _songKey.value
        val prof = _songProfile.value
        if (key != null && prof?.eq != null) {
            val updated = prof.copy(eq = transform(prof.eq))
            _songProfile.value = updated
            Store.saveProfile(key, updated)
        } else {
            val updated = transform(defaultEq.value)
            defaultEq.value = updated
            Store.saveDefaultEq(updated)
        }
        changed()
    }

    fun makeEqSongSpecific() {
        val key = _songKey.value ?: return
        val prof = (_songProfile.value ?: SongProfile()).copy(eq = effectiveEq.value)
        _songProfile.value = prof
        Store.saveProfile(key, prof)
        changed()
    }

    fun useDefaultEqForSong() {
        val key = _songKey.value ?: return
        val prof = _songProfile.value?.copy(eq = null)
        _songProfile.value = prof
        Store.saveProfile(key, prof)
        changed()
    }

    // ---- pitch & speed ---------------------------------------------------------------------

    /** Pitch in cents (100 = one semitone). Heard immediately — safe to call while dragging. */
    fun setPitch(cents: Int) {
        val c = cents.coerceIn(-1200, 1200)
        if (_tape.value) {
            // Tape mode: pitch and speed move together; the speed part is applied by commit().
            _pitchCents.value = c
            _speed.value = 2.0.pow(c / 1200.0).toFloat().coerceIn(0.5f, 2f)
            return
        }
        if (c == _pitchCents.value) return
        _pitchCents.value = c
        persist()
        changed()
    }

    /** Shows a new speed while the slider moves; it's applied by [commit]. */
    fun previewSpeed(s: Float) {
        val v = s.coerceIn(0.5f, 2f)
        _speed.value = v
        if (_tape.value) _pitchCents.value = (1200.0 * ln(v.toDouble()) / ln(2.0)).roundToInt()
    }

    /** Applies the previewed speed (and tape pitch) — call when a slider is released. */
    fun commit() {
        persist()
        applySpeedToPlayer()
        changed()
    }

    fun setTape(on: Boolean) {
        if (on == _tape.value) return
        _tape.value = on
        if (on) _speed.value = 2.0.pow(_pitchCents.value / 1200.0).toFloat().coerceIn(0.5f, 2f)
        else _speed.value = 1f
        commit()
    }

    fun resetPitchSpeed() {
        _pitchCents.value = 0
        _speed.value = 1f
        commit()
    }

    private fun applySpeedToPlayer() {
        val p = _player.value ?: return
        val s = _speed.value
        if (abs(p.playbackParameters.speed - s) > 1e-4f || p.playbackParameters.pitch != 1f) {
            p.playbackParameters = PlaybackParameters(s, 1f)
        }
    }

    private fun persist() {
        val c = _pitchCents.value
        val s = _speed.value
        val t = _tape.value
        if (settings.value.perSongPitchSpeed) {
            val key = _songKey.value ?: return
            val prof = (_songProfile.value ?: SongProfile()).copy(
                pitchCents = c.takeIf { it != 0 },
                speed = s.takeIf { abs(it - 1f) > 1e-4f },
                tape = t.takeIf { it },
            )
            _songProfile.value = prof
            Store.saveProfile(key, prof)
        } else {
            updateSettingsQuiet { it.copy(globalPitchCents = c, globalSpeed = s, globalTape = t) }
        }
    }

    private fun loadGlobalPitchSpeed() {
        val s = settings.value
        _pitchCents.value = s.globalPitchCents
        _speed.value = s.globalSpeed
        _tape.value = s.globalTape
    }

    // ---- settings & bit-perfect ------------------------------------------------------------

    private fun updateSettingsQuiet(transform: (AppSettings) -> AppSettings) {
        val new = transform(settings.value)
        settings.value = new
        Store.saveSettings(new)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = settings.value
        val new = transform(old)
        settings.value = new
        Store.saveSettings(new)
        if (old.perSongPitchSpeed != new.perSongPitchSpeed) {
            if (new.perSongPitchSpeed) onSongChanged(_player.value?.currentMediaItem) else {
                loadGlobalPitchSpeed()
                applySpeedToPlayer()
            }
        }
        if (old.bitPerfectUsb != new.bitPerfectUsb) {
            outputStage.int16Output = new.bitPerfectUsb
            reapplyBitPerfect(forceRebuild = true)
        }
        changed()
    }

    private fun reapplyBitPerfect(forceRebuild: Boolean = false) {
        if (!initialized) return
        val changed = output.applyBitPerfect(
            enabled = settings.value.bitPerfectUsb,
            sampleRate = tap.sampleRate.takeIf { tap.info.value.sampleRate > 0 } ?: 0,
            attrs = attributes.platformAudioAttributes,
        )
        if (changed || forceRebuild) rebuildAudioTrack()
    }

    /** Re-create the audio output (needed after switching output format or routing mode). */
    private fun rebuildAudioTrack() {
        val p = _player.value ?: return
        if (p.mediaItemCount == 0) return
        val pos = p.currentPosition
        val idx = p.currentMediaItemIndex
        val wasPlaying = p.playWhenReady
        p.stop()
        p.seekTo(idx, pos)
        p.prepare()
        p.playWhenReady = wasPlaying
    }
}
