package com.ltots.app.identify

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the clip comes from. */
sealed interface ClipSource {
    data class Link(val url: String) : ClipSource
    data class LocalFile(val uri: Uri, val name: String?) : ClipSource
    data object Mic : ClipSource
}

sealed interface FinderState {
    data object Idle : FinderState
    data class Listening(val level: Float, val secondsLeft: Int) : FinderState
    data class Working(val label: String, val progress: IdentifyProgress) : FinderState
    data class Results(val label: String, val suggestions: List<Suggestion>, val progress: IdentifyProgress) : FinderState
    data class Failed(
        val label: String,
        val message: String,
        val progress: IdentifyProgress,
        /** The site to sign in to before trying again, when that's why it failed. */
        val signIn: Platform? = null,
    ) : FinderState
}

/**
 * The song finder on the Get tab. Runs in the app's scope, so it keeps going while you look
 * at other tabs, and its result stays until you start another search or close it.
 */
object SongFinder {
    const val MIC_SECONDS = 12

    private val _state = MutableStateFlow<FinderState>(FinderState.Idle)
    val state: StateFlow<FinderState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        AiModels.init(app)
    }

    /** Links the finder handles: short-form video sites, and YouTube Shorts. */
    fun isClipLink(text: String): Boolean {
        val url = Regex("""https?://[^\s"'<>]+""").find(text)?.value ?: return false
        val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.").removePrefix("m.")
        val path = url.substringAfter("://").substringAfter('/', "")
        return host.endsWith("tiktok.com") || host == "vm.tiktok.com" || host.endsWith("instagram.com") ||
            host.endsWith("facebook.com") || host == "fb.watch" || host.endsWith("snapchat.com") ||
            host == "x.com" || host.endsWith("twitter.com") || host.endsWith("reddit.com") ||
            ((host == "youtube.com" || host == "youtu.be") && path.startsWith("shorts/"))
    }

    fun linkIn(text: String): String? = Regex("""https?://[^\s"'<>]+""").find(text)?.value

    /** What was searched last, to try again after signing in. */
    var lastSource: ClipSource? = null
        private set

    fun retry() { lastSource?.let(::start) }

    fun start(source: ClipSource) {
        lastSource = source
        job?.cancel()
        job = scope.launch {
            val label = when (source) {
                is ClipSource.Link -> source.url
                is ClipSource.LocalFile -> source.name ?: "Your video"
                ClipSource.Mic -> "Listening"
            }
            var progress = IdentifyProgress()
            val identifier = Identifier(
                AiModels.file(AiModels.Model.SEPARATOR).takeIf { AiModels.isInstalled(AiModels.Model.SEPARATOR) },
                AiModels.file(AiModels.Model.LYRICS).takeIf { AiModels.isInstalled(AiModels.Model.LYRICS) },
            ) { p -> progress = p; (_state.value as? FinderState.Working)?.let { _state.value = it.copy(progress = p) } }
            var currentLabel = label
            try {
                val clip = when (source) {
                    is ClipSource.Link -> {
                        _state.value = FinderState.Working(label, progress)
                        ClipLoader.fromLink(app, source.url) { identifier.soundRunning(it) }
                    }
                    is ClipSource.LocalFile -> {
                        _state.value = FinderState.Working(label, progress)
                        identifier.soundRunning("Reading the file…")
                        ClipLoader.fromFile(app, source.uri, source.name)
                    }
                    ClipSource.Mic -> {
                        _state.value = FinderState.Listening(0f, MIC_SECONDS)
                        val started = System.currentTimeMillis()
                        val pcm = MicListener.record(MIC_SECONDS) { level ->
                            val left = MIC_SECONDS - ((System.currentTimeMillis() - started) / 1000).toInt()
                            _state.value = FinderState.Listening(level, left.coerceAtLeast(0))
                        }
                        _state.value = FinderState.Working("What I heard", progress)
                        ClipAudio(pcm, null, "What I heard")
                    }
                }
                currentLabel = clip.label
                (_state.value as? FinderState.Working)?.let { _state.value = it.copy(label = clip.label) }
                val rows = identifier.run(clip)
                _state.value = if (rows.isEmpty()) FinderState.Failed(clip.label, noMatchMessage(), progress)
                else FinderState.Results(clip.label, rows, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClipLoader.SignInNeeded) {
                _state.value = FinderState.Failed(currentLabel, e.message ?: "Sign in needed", progress, e.platform)
            } catch (e: Exception) {
                _state.value = FinderState.Failed(currentLabel, e.message ?: "Something went wrong", progress)
            }
        }
    }

    private fun noMatchMessage(): String {
        val missing = AiModels.Model.entries.filter { it.forFinder && !AiModels.isInstalled(it) }
        return if (missing.isEmpty()) "Couldn't find this song. It may not be released, or the music is too quiet under everything else."
        else "Couldn't find this song. Downloading the AI models below helps with talking over the music and with covers."
    }

    /** Downloads an AI model in the app's scope, so leaving the tab doesn't stop it. */
    fun downloadModel(m: AiModels.Model, then: () -> Unit = {}) {
        scope.launch { runCatching { AiModels.download(m) }.onSuccess { then() } }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = FinderState.Idle
    }

    fun clear() = cancel()
}
