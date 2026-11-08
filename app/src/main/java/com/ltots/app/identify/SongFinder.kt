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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the clip comes from. */
sealed interface ClipSource {
    data class Link(val url: String) : ClipSource
    data class LocalFile(val uri: Uri, val name: String?) : ClipSource
    data object Mic : ClipSource
}

sealed interface FinderState {
    /** In line behind another search. */
    data class Waiting(val label: String) : FinderState
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

/** One link (or recording) given to the song finder. */
data class FinderEntry(val id: Long, val source: ClipSource, val state: FinderState)

/**
 * The song finder on the Get tab. Runs in the app's scope, so it keeps going while you look at
 * other tabs. Links wait their turn, one search at a time, and each result stays until closed.
 */
object SongFinder {
    const val MIC_SECONDS = 12

    private val _entries = MutableStateFlow<List<FinderEntry>>(emptyList())
    /** Everything given to the finder, oldest first; empty when there's nothing to show. */
    val entries: StateFlow<List<FinderEntry>> = _entries
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = java.util.concurrent.atomic.AtomicLong(1)
    private val lock = Any()
    private var job: Job? = null
    private var runningId = 0L
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

    private fun labelOf(source: ClipSource): String = when (source) {
        is ClipSource.Link -> source.url
        is ClipSource.LocalFile -> source.name ?: "Your video"
        ClipSource.Mic -> "Listening"
    }

    /** A change for an entry that's been closed in the meantime goes nowhere. */
    private fun set(id: Long, change: (FinderState) -> FinderState) {
        _entries.update { l -> l.map { if (it.id == id) it.copy(state = change(it.state)) else it } }
    }

    /** Adds [source] to the line; the same link still waiting or running isn't added twice. */
    fun start(source: ClipSource) {
        val busy = _entries.value.any { it.source == source && it.state !is FinderState.Results && it.state !is FinderState.Failed }
        if (busy) return
        val entry = FinderEntry(nextId.getAndIncrement(), source, FinderState.Waiting(labelOf(source)))
        _entries.update { it + entry }
        pump()
    }

    /** Tries [id] again (after signing in). */
    fun retry(id: Long) {
        _entries.update { l -> l.map { if (it.id == id) it.copy(state = FinderState.Waiting(labelOf(it.source))) else it } }
        pump()
    }

    private fun pump() {
        synchronized(lock) {
            if (job?.isActive != true) {
                _entries.value.firstOrNull { it.state is FinderState.Waiting }?.let { next ->
                    runningId = next.id
                    val j = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { run(next.id, next.source) }
                    job = j
                    j.invokeOnCompletion { pump() }
                    j.start()
                }
            }
        }
    }

    private suspend fun run(id: Long, source: ClipSource) {
        val label = labelOf(source)
        var progress = IdentifyProgress()
        val identifier = Identifier(
            AiModels.file(AiModels.Model.SEPARATOR).takeIf { AiModels.isInstalled(AiModels.Model.SEPARATOR) },
            AiModels.file(AiModels.Model.LYRICS).takeIf { AiModels.isInstalled(AiModels.Model.LYRICS) },
            onProgress = { p -> progress = p; set(id) { (it as? FinderState.Working)?.copy(progress = p) ?: it } },
            context = app,
        )
        var currentLabel = label
        try {
            val clip = when (source) {
                is ClipSource.Link -> {
                    set(id) { FinderState.Working(label, progress) }
                    ClipLoader.fromLink(app, source.url) { identifier.soundRunning(it) }
                }
                is ClipSource.LocalFile -> {
                    set(id) { FinderState.Working(label, progress) }
                    identifier.soundRunning("Reading the file…")
                    ClipLoader.fromFile(app, source.uri, source.name)
                }
                ClipSource.Mic -> {
                    set(id) { FinderState.Listening(0f, MIC_SECONDS) }
                    val started = System.currentTimeMillis()
                    val pcm = MicListener.record(MIC_SECONDS) { level ->
                        val left = MIC_SECONDS - ((System.currentTimeMillis() - started) / 1000).toInt()
                        set(id) { FinderState.Listening(level, left.coerceAtLeast(0)) }
                    }
                    set(id) { FinderState.Working("What I heard", progress) }
                    ClipAudio(pcm, null, "What I heard")
                }
            }
            currentLabel = clip.label
            set(id) { (it as? FinderState.Working)?.copy(label = clip.label) ?: it }
            val rows = identifier.run(clip)
            // What was found counts towards the Home tab's picks.
            runCatching { com.ltots.app.recs.FinderFinds.record(rows) }
            set(id) { if (rows.isEmpty()) FinderState.Failed(clip.label, noMatchMessage(), progress) else FinderState.Results(clip.label, rows, progress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClipLoader.SignInNeeded) {
            set(id) { FinderState.Failed(currentLabel, e.message ?: "Sign in needed", progress, e.platform) }
        } catch (e: Exception) {
            set(id) { FinderState.Failed(currentLabel, e.message ?: "Something went wrong", progress) }
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

    /** Stops and removes [id]; the next one in line starts. One tap is enough, even mid-recording. */
    fun cancel(id: Long) {
        _entries.update { l -> l.filterNot { it.id == id } }
        synchronized(lock) { if (runningId == id) job?.cancel() }
    }

    /** Stops everything and empties the list. */
    fun clear() {
        _entries.value = emptyList()
        synchronized(lock) { job?.cancel() }
    }
}
