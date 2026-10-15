package com.ltots.app.ytm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns YouTube Music's internal JSON into LToTS models. Written defensively: YouTube moves
 * things around, so lookups search the tree by renderer name instead of fixed paths, and any
 * item that can't be understood is skipped rather than failing the whole page.
 */
object Parser {

    // ---- JSON helpers ----------------------------------------------------------------------

    /** Every value stored under [key] anywhere below [node], in document order. */
    fun findAll(node: Any?, key: String, out: MutableList<Any> = mutableListOf()): MutableList<Any> {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v != null) out += v
                    findAll(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) findAll(node.opt(i), key, out)
        }
        return out
    }

    fun findFirst(node: Any?, key: String): Any? = findFirstImpl(node, key)

    private fun findFirstImpl(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                node.opt(key)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findFirstImpl(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirstImpl(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.path(vararg keys: String): Any? {
        var cur: Any? = this
        for (k in keys) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }

    private fun runs(textObj: Any?): List<JSONObject> {
        val arr = (textObj as? JSONObject)?.optJSONArray("runs") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    private fun text(textObj: Any?): String = runs(textObj).joinToString("") { it.optString("text") }

    private fun JSONObject.browseId(): String? =
        (path("navigationEndpoint", "browseEndpoint", "browseId") as? String)

    private fun JSONObject.pageType(): String? = path(
        "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType",
    ) as? String

    /** Largest thumbnail URL below [node]. */
    fun bestThumb(node: Any?): String? {
        val lists = findAll(node, "thumbnails")
        val arr = lists.firstOrNull() as? JSONArray ?: return null
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (best == null || t.optInt("width") >= best.optInt("width")) best = t
        }
        return best?.optString("url")?.takeIf { it.isNotBlank() }
    }

    private val durationRe = Regex("""^(\d+:)?\d{1,2}:\d{2}$""")
    private val yearRe = Regex("""^(19|20)\d{2}$""")

    fun parseDuration(s: String?): Int? {
        if (s == null || !durationRe.matches(s.trim())) return null
        return s.trim().split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }
    }

    private val separators = setOf(" • ", "•", " & ", ", ", " and ", " x ", " X ", " · ")
    private val itemTypes = setOf(
        "Song", "Video", "Album", "Single", "EP", "Playlist", "Artist", "Episode", "Podcast", "Profile",
    )

    private fun musicVideoType(node: Any?): String? = findFirst(node, "musicVideoType") as? String

    // ---- list items ------------------------------------------------------------------------

    /** Parses one entry of a list (search results, playlists, albums, artist "top songs"). */
    fun parseListItem(r: JSONObject, fallbackArtist: String? = null, fallbackAlbum: YtRef? = null): YtItem? {
        val cols = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.opt("text")
        val titleRuns = runs(col(0))
        val title = titleRuns.joinToString("") { it.optString("text") }.trim()
        if (title.isEmpty()) return null
        val subRuns = (1 until cols.length()).flatMap { runs(col(it)) }
        val thumb = bestThumb(r.opt("thumbnail"))

        val videoId = (r.path("playlistItemData", "videoId") as? String)
            ?: (findFirst(r.opt("overlay"), "watchEndpoint") as? JSONObject)?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: (titleRuns.firstOrNull()?.path("navigationEndpoint", "watchEndpoint", "videoId") as? String)

        val browseId = r.browseId()
        val pageType = r.pageType()

        if (videoId == null && browseId != null) {
            val subtitle = subRuns.map { it.optString("text") }.filterNot { it in separators }
            val kindLabel = subtitle.firstOrNull { it in itemTypes }
            val people = subRuns.filter { it.browseId()?.startsWith("UC") == true }.map { it.optString("text") }
            return when {
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") && pageType == null ->
                    YtArtist(browseId, title, thumb, subtitle.filterNot { it == "Artist" }.joinToString(" • ").ifBlank { null })
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPREb") ->
                    YtAlbum(
                        browseId, title,
                        artist = people.joinToString(", ").ifBlank { subtitle.firstOrNull { it !in itemTypes && !yearRe.matches(it) } ?: "" },
                        year = subtitle.firstOrNull { yearRe.matches(it) },
                        thumbUrl = thumb, kind = kindLabel ?: "Album",
                    )
                pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || browseId.startsWith("VL") ->
                    YtPlaylist(
                        browseId, title,
                        author = people.firstOrNull() ?: subtitle.firstOrNull { it !in itemTypes } ?: "",
                        thumbUrl = thumb,
                        countText = subtitle.lastOrNull { it.contains("song") || it.contains("track") || it.contains("view") },
                    )
                else -> null // podcasts, profiles…
            }
        }
        if (videoId == null) return null

        val mvt = musicVideoType(r)
        if (mvt == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") return null
        val artists = mutableListOf<YtRef>()
        var album: YtRef? = null
        var duration: Int? = null
        var year: String? = null
        val plain = mutableListOf<String>()
        for (run in subRuns) {
            val t = run.optString("text")
            val id = run.browseId()
            when {
                id != null && (id.startsWith("MPREb") || run.pageType() == "MUSIC_PAGE_TYPE_ALBUM") -> album = YtRef(t, id)
                id != null && (id.startsWith("UC") || run.pageType() == "MUSIC_PAGE_TYPE_ARTIST" || run.pageType() == "MUSIC_PAGE_TYPE_USER_CHANNEL") -> artists += YtRef(t, id)
                parseDuration(t) != null -> duration = parseDuration(t)
                yearRe.matches(t.trim()) -> year = t.trim()
                t in separators || t.isBlank() -> Unit
                else -> plain += t
            }
        }
        // Rows without links (e.g. some search results): first plain text that isn't a type/stat is the artist.
        if (artists.isEmpty()) {
            plain.firstOrNull { it !in itemTypes && !it.contains(" views") && !it.contains(" plays") }
                ?.let { artists += YtRef(it, null) }
        }
        if (artists.isEmpty() && fallbackArtist != null) artists += YtRef(fallbackArtist, null)
        if (duration == null) {
            val fixed = r.optJSONArray("fixedColumns")
            if (fixed != null) for (i in 0 until fixed.length()) {
                val t = text(fixed.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.opt("text"))
                parseDuration(t)?.let { duration = it }
            }
        }
        val trackNo = text(r.opt("index")).trim().toIntOrNull()
        val isVideo = when (mvt) {
            "MUSIC_VIDEO_TYPE_ATV" -> false
            null -> plain.firstOrNull() == "Video"
            else -> true
        }
        return YtSong(
            videoId = videoId, title = title, artists = artists,
            album = album ?: fallbackAlbum, durationSec = duration, thumbUrl = thumb,
            isVideo = isVideo, year = year, trackNumber = trackNo,
        )
    }

    /** Parses a carousel card (artist page, home shelves). */
    fun parseTwoRowItem(r: JSONObject): YtItem? {
        val title = text(r.opt("title")).trim().ifEmpty { return null }
        val subRuns = runs(r.opt("subtitle"))
        val subtitle = subRuns.map { it.optString("text") }.filterNot { it in separators }
        val thumb = bestThumb(r.opt("thumbnailRenderer"))
        val watch = r.path("navigationEndpoint", "watchEndpoint") as? JSONObject
        if (watch != null) {
            val vid = watch.optString("videoId").ifBlank { return null }
            val artists = subRuns.filter { it.browseId()?.startsWith("UC") == true }.map { YtRef(it.optString("text"), it.browseId()) }
            return YtSong(vid, title, artists, thumbUrl = thumb, isVideo = musicVideoType(r) != "MUSIC_VIDEO_TYPE_ATV")
        }
        val id = r.browseId() ?: return null
        val type = r.pageType()
        val people = subRuns.filter { it.browseId()?.startsWith("UC") == true }.map { it.optString("text") }
        return when {
            id.startsWith("MPREb") || type == "MUSIC_PAGE_TYPE_ALBUM" -> YtAlbum(
                id, title,
                artist = people.joinToString(", ").ifBlank { subtitle.firstOrNull { it !in itemTypes && !yearRe.matches(it) } ?: "" },
                year = subtitle.firstOrNull { yearRe.matches(it) }, thumbUrl = thumb,
                kind = subtitle.firstOrNull { it in itemTypes } ?: "Album",
            )
            id.startsWith("VL") || type == "MUSIC_PAGE_TYPE_PLAYLIST" -> YtPlaylist(id, title, people.firstOrNull() ?: subtitle.firstOrNull() ?: "", thumb)
            id.startsWith("UC") || type == "MUSIC_PAGE_TYPE_ARTIST" -> YtArtist(id, title, thumb, subtitle.firstOrNull())
            else -> null
        }
    }

    private fun parseCard(c: JSONObject): YtItem? {
        val titleRun = runs(c.opt("title")).firstOrNull() ?: return null
        val title = titleRun.optString("text")
        val thumb = bestThumb(c.opt("thumbnail"))
        val subRuns = runs(c.opt("subtitle"))
        val watch = titleRun.path("navigationEndpoint", "watchEndpoint") as? JSONObject
        if (watch != null) {
            val artists = subRuns.filter { it.browseId()?.startsWith("UC") == true }.map { YtRef(it.optString("text"), it.browseId()) }
                .ifEmpty { subRuns.map { it.optString("text") }.filterNot { it in separators || it in itemTypes || it.contains(" views") || parseDuration(it) != null }.take(1).map { YtRef(it, null) } }
            val album = subRuns.firstOrNull { it.browseId()?.startsWith("MPREb") == true }?.let { YtRef(it.optString("text"), it.browseId()) }
            return YtSong(
                watch.optString("videoId"), title, artists, album,
                durationSec = subRuns.firstNotNullOfOrNull { parseDuration(it.optString("text")) },
                thumbUrl = thumb, isVideo = musicVideoType(titleRun) != "MUSIC_VIDEO_TYPE_ATV",
            )
        }
        val fake = JSONObject().put("navigationEndpoint", titleRun.optJSONObject("navigationEndpoint") ?: return null)
            .put("title", c.opt("title")).put("subtitle", c.opt("subtitle"))
            .put("thumbnailRenderer", c.opt("thumbnail"))
        return parseTwoRowItem(fake)
    }

    // ---- pages -----------------------------------------------------------------------------

    fun parseSearch(root: JSONObject): SearchResult {
        val chips = findAll(root, "chipCloudChipRenderer").mapNotNull { c ->
            c as JSONObject
            val t = text(c.opt("text")).ifBlank { return@mapNotNull null }
            val params = (c.path("navigationEndpoint", "searchEndpoint", "params") as? String)
            SearchChip(t, params, c.optBoolean("isSelected", false))
        }
        val sections = mutableListOf<SearchSection>()
        // Walk the result blocks in order. YouTube uses shelves (titled groups), a top-result
        // card, or loose item sections depending on the query and layout version.
        val loose = mutableListOf<YtItem>()
        fun flushLoose() {
            if (loose.isNotEmpty()) sections += SearchSection(if (sections.isEmpty()) null else "More results", loose.toList())
            loose.clear()
        }
        val blocks = (findFirst(root, "sectionListRenderer") as? JSONObject)?.optJSONArray("contents")
        if (blocks != null) for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            b.optJSONObject("musicCardShelfRenderer")?.let { c ->
                flushLoose()
                val top = mutableListOf<YtItem>()
                parseCard(c)?.let { top += it }
                findAll(c.opt("contents"), "musicResponsiveListItemRenderer").forEach { r -> parseListItem(r as JSONObject)?.let { top += it } }
                if (top.isNotEmpty()) sections += SearchSection("Top result", top)
            }
            b.optJSONObject("musicShelfRenderer")?.let { s ->
                flushLoose()
                val items = findAll(s.opt("contents"), "musicResponsiveListItemRenderer").mapNotNull { parseListItem(it as JSONObject) }
                if (items.isNotEmpty()) sections += SearchSection(text(s.opt("title")).ifBlank { null }, items)
            }
            b.optJSONObject("itemSectionRenderer")?.let { s ->
                findAll(s.opt("contents"), "musicResponsiveListItemRenderer").forEach { r -> parseListItem(r as JSONObject)?.let { loose += it } }
            }
        }
        flushLoose()
        // Continuation pages ("load more") arrive as musicShelfContinuation.
        findAll(root, "musicShelfContinuation").forEach { s ->
            val items = findAll((s as JSONObject).opt("contents"), "musicResponsiveListItemRenderer").mapNotNull { parseListItem(it as JSONObject) }
            if (items.isNotEmpty()) sections += SearchSection(null, items)
        }
        val cont = (findFirst(root, "nextContinuationData") as? JSONObject)?.optString("continuation")?.takeIf { it.isNotBlank() }
        return SearchResult(chips, sections, cont)
    }

    /** Album ("MPREb…") or playlist ("VL…") page. */
    fun parseCollection(id: String, root: JSONObject): Collection {
        val header = findFirst(root, "musicResponsiveHeaderRenderer") as? JSONObject
            ?: findFirst(root, "musicDetailHeaderRenderer") as? JSONObject
            ?: findFirst(root, "musicEditablePlaylistDetailHeaderRenderer")?.let { findFirst(it, "musicResponsiveHeaderRenderer") } as? JSONObject
        val title = text(header?.opt("title")).ifBlank { "Untitled" }
        val subRuns = runs(header?.opt("subtitle")).map { it.optString("text") }.filterNot { it in separators }
        val strapRuns = runs(header?.opt("straplineTextOne"))
        val isAlbum = id.startsWith("MPREb") || subRuns.firstOrNull()?.let { it == "Album" || it == "Single" || it == "EP" } == true
        val artist = strapRuns.filterNot { it.optString("text") in separators }.joinToString(", ") { it.optString("text") }.ifBlank { null }
            ?: if (isAlbum) subRuns.getOrNull(1)?.takeIf { !yearRe.matches(it) } else null
        val year = subRuns.firstOrNull { yearRe.matches(it) }
        val thumb = bestThumb(header?.opt("thumbnail"))
        val albumRef = if (isAlbum) YtRef(title, id) else null
        val tracks = findAll(root, "musicResponsiveListItemRenderer").mapNotNull {
            parseListItem(it as JSONObject, fallbackArtist = artist, fallbackAlbum = albumRef) as? YtSong
        }.map { s -> if (isAlbum) s.copy(year = s.year ?: year, thumbUrl = thumb ?: s.thumbUrl) else s }
        val second = runs(header?.opt("secondSubtitle")).map { it.optString("text") }.filterNot { it in separators }
        val subtitle = (listOfNotNull(subRuns.firstOrNull(), artist.takeIf { !isAlbum }, year) + second.take(1))
            .filter { !it.isNullOrBlank() }.joinToString(" • ")
        return Collection(
            id = id, title = title, subtitle = subtitle, thumbUrl = thumb, isAlbum = isAlbum,
            artist = artist, year = year, tracks = tracks, continuation = continuationToken(root),
        )
    }

    /** Next page of a long playlist. */
    fun parseContinuationTracks(root: JSONObject): Pair<List<YtSong>, String?> {
        val tracks = findAll(root, "musicResponsiveListItemRenderer").mapNotNull { parseListItem(it as JSONObject) as? YtSong }
        return tracks to continuationToken(root)
    }

    private fun continuationToken(root: JSONObject): String? {
        (findFirst(root, "continuationItemRenderer") as? JSONObject)?.let { c ->
            (findFirst(c, "continuationCommand") as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return (findFirst(root, "nextContinuationData") as? JSONObject)?.optString("continuation")?.takeIf { it.isNotBlank() }
    }

    fun parseArtist(id: String, root: JSONObject): ArtistPage {
        val header = findFirst(root, "musicImmersiveHeaderRenderer") as? JSONObject
            ?: findFirst(root, "musicVisualHeaderRenderer") as? JSONObject
            ?: findFirst(root, "musicResponsiveHeaderRenderer") as? JSONObject
        val name = text(header?.opt("title")).ifBlank { "Artist" }
        val sections = mutableListOf<SearchSection>()
        findAll(root, "musicShelfRenderer").forEach { s ->
            s as JSONObject
            val items = findAll(s.opt("contents"), "musicResponsiveListItemRenderer").mapNotNull { parseListItem(it as JSONObject, fallbackArtist = name) }
            if (items.isNotEmpty()) {
                val more = (s.path("bottomEndpoint", "browseEndpoint", "browseId") as? String)
                    ?: (runs(s.opt("title")).firstOrNull()?.browseId())
                val list = if (more != null && more.startsWith("VL")) items + YtPlaylist(more, "All songs", name, null) else items
                sections += SearchSection(text(s.opt("title")).ifBlank { "Songs" }, list)
            }
        }
        findAll(root, "musicCarouselShelfRenderer").forEach { s ->
            s as JSONObject
            val title = text(findFirst(s.opt("header"), "title")).ifBlank { null }
            val items = findAll(s.opt("contents"), "musicTwoRowItemRenderer").mapNotNull { parseTwoRowItem(it as JSONObject) }
            if (items.isNotEmpty()) sections += SearchSection(title, items)
        }
        return ArtistPage(id, name, bestThumb(header?.opt("thumbnail")), sections)
    }

    /** Song details from the "next" (watch) endpoint: artists, album, year, square cover. */
    fun parseNext(videoId: String, root: JSONObject): YtSong? {
        val panels = findAll(root, "playlistPanelVideoRenderer").map { it as JSONObject }
        val p = panels.firstOrNull { it.optString("videoId") == videoId } ?: panels.firstOrNull() ?: return null
        val title = text(p.opt("title")).ifBlank { return null }
        val byline = runs(p.opt("longBylineText"))
        val artists = mutableListOf<YtRef>()
        var album: YtRef? = null
        var year: String? = null
        for (run in byline) {
            val t = run.optString("text")
            val id = run.browseId()
            when {
                id != null && id.startsWith("MPREb") -> album = YtRef(t, id)
                id != null && id.startsWith("UC") -> artists += YtRef(t, id)
                yearRe.matches(t.trim()) -> year = t.trim()
            }
        }
        if (artists.isEmpty()) {
            // Plain-text byline: "Artist • 12M views • 2:43" — the first part is the artist.
            byline.map { it.optString("text") }.firstOrNull { it !in separators && !it.contains(" views") && parseDuration(it) == null }
                ?.let { artists += YtRef(it, null) }
        }
        return YtSong(
            videoId = p.optString("videoId", videoId), title = title, artists = artists, album = album,
            durationSec = parseDuration(text(p.opt("lengthText"))), thumbUrl = bestThumb(p.opt("thumbnail")),
            isVideo = musicVideoType(p) != "MUSIC_VIDEO_TYPE_ATV", year = year,
        )
    }

    /**
     * When a music video has an official-audio twin, YouTube Music pairs them in the watch
     * queue ("counterpart"). Returns the audio version, or null.
     */
    fun parseCounterpart(videoId: String, root: JSONObject): YtSong? {
        for (w in findAll(root, "playlistPanelVideoWrapperRenderer")) {
            w as JSONObject
            val primary = findFirst(w.opt("primaryRenderer"), "playlistPanelVideoRenderer") as? JSONObject ?: continue
            if (primary.optString("videoId") != videoId) continue
            val cps = w.optJSONArray("counterpart") ?: continue
            for (i in 0 until cps.length()) {
                val cr = findFirst(cps.opt(i), "playlistPanelVideoRenderer") as? JSONObject ?: continue
                val wrapper = JSONObject().put("contents", JSONObject().put("playlistPanelVideoRenderer", cr))
                val song = parseNext(cr.optString("videoId"), wrapper) ?: continue
                if (!song.isVideo) return song
            }
        }
        return null
    }

    private val noise = Regex(
        """[\(\[](official|music|lyric|lyrics|audio|video|hd|hq|4k|visualizer|remaster(ed)?|explicit|clean|mv|m/v)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** "Daft Punk - Get Lucky (Official Video) ft. X" → "get lucky", for matching versions. */
    fun normalizeTitle(title: String, artist: String? = null): String {
        var t = noise.replace(title, " ")
        if (artist != null) {
            val a = Regex.escape(artist.substringBefore(',').trim())
            t = t.replace(Regex("^\\s*$a\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE), "")
        }
        t = t.replace(Regex("""\s(ft\.?|feat\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), "")
        return t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }
}
