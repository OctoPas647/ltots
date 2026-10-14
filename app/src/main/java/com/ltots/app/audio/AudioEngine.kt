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
import kotlin.math.pow

/**
 * App-wide sound engine. Owns the EQ processor, per-song profiles, pitch/speed, output
 * monitoring and bit-perfect routing. The playback service builds its player from here, and
 * the UI reads/edits everything through these flows.
 */
@UnstableApi
object AudioEngine {
    val tap = AnalyzerTap()
    val eqProcessor = EqProcessor(tap)
    private val scope = MainScope()

    private lateinit var appContext: Context
    lateinit var output: OutputMonitor
        private set

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player

    lateinit var settings: MutableStateFlow<AppSettings>
        private set
    lateinit var defaultEq: MutableStateFlow<EqState>
        private set

    /** Profile key (media id) of the song that's playing. */
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
        scope.launch { effectiveEq.collect { eqProcessor.setState(it) } }
        eqProcessor.setState(effectiveEq.value)
        eqProcessor.int16Output = settings.value.bitPerfectUsb

        output.onDeviceChanged = { reapplyBitPerfect() }
        scope.launch { tap.info.collect { reapplyBitPerfect() } }
    }

    // ---- player ----------------------------------------------------------------------------

    fun buildPlayer(context: Context): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // Our EQ runs first, then Media3's Sonic handles pitch & speed.
                .setAudioProcessorChain(LToTSProcessorChain(eqProcessor))
                .build()
        }
        val p = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onSongChanged(mediaItem)
        })
        _player.value = p
        p.playbackParameters = PlaybackParameters(_speed.value, 2.0.pow(_pitchCents.value / 1200.0).toFloat())
        onSongChanged(p.currentMediaItem)
        return p
    }

    fun releasePlayer() {
        _player.value?.release()
        _player.value = null
    }

    private fun onSongChanged(item: MediaItem?) {
        val key = item?.mediaId?.takeIf { it.isNotBlank() }
        _songKey.value = key
        val prof = key?.let { Store.loadProfile(it) }
        _songProfile.value = prof
        if (settings.value.perSongPitchSpeed) {
            setPitchSpeed(prof?.pitchCents ?: 0, prof?.speed ?: 1f, persist = false)
        }
    }

    // ---- EQ editing ------------------------------------------------------------------------

    /** True when the playing song has its own EQ (edits go to the song, not the default). */
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
    }

    /** Give the current song its own EQ, starting from what's playing now. */
    fun makeEqSongSpecific() {
        val key = _songKey.value ?: return
        val prof = (_songProfile.value ?: SongProfile()).copy(eq = effectiveEq.value)
        _songProfile.value = prof
        Store.saveProfile(key, prof)
    }

    /** Drop the song's own EQ so it follows the default again. */
    fun useDefaultEqForSong() {
        val key = _songKey.value ?: return
        val prof = _songProfile.value?.copy(eq = null)
        _songProfile.value = prof
        Store.saveProfile(key, prof)
    }

    // ---- pitch & speed ---------------------------------------------------------------------

    fun setPitchSpeed(cents: Int, speed: Float, persist: Boolean = true) {
        val c = cents.coerceIn(-2400, 2400)
        val s = speed.coerceIn(0.25f, 3f)
        _pitchCents.value = c
        _speed.value = s
        _player.value?.playbackParameters = PlaybackParameters(s, 2.0.pow(c / 1200.0).toFloat())
        if (persist && settings.value.perSongPitchSpeed) {
            val key = _songKey.value ?: return
            val base = _songProfile.value ?: SongProfile()
            val prof = base.copy(
                pitchCents = c.takeIf { it != 0 },
                speed = s.takeIf { it != 1f },
            )
            _songProfile.value = prof
            Store.saveProfile(key, prof)
        }
    }

    // ---- settings & bit-perfect ------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = settings.value
        val new = transform(old)
        settings.value = new
        Store.saveSettings(new)
        if (old.bitPerfectUsb != new.bitPerfectUsb) {
            eqProcessor.int16Output = new.bitPerfectUsb
            reapplyBitPerfect(forceRebuild = true)
        }
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
