package com.ltots.app.elsewhere

import android.content.Context
import android.content.pm.ApplicationInfo

/** A streaming service LToTS can sign in to (Profile > Accounts). */
enum class Service(val label: String) {
    YOUTUBE_MUSIC("YouTube Music"),
    SPOTIFY("Spotify"),
    DEEZER("Deezer"),
    SOUNDCLOUD("SoundCloud"),
    APPLE_MUSIC("Apple Music"),
    LASTFM("Last.fm"),
}

/** Music apps LToTS recognises when they play (see [OtherApps]). */
enum class MusicApp(val label: String, val packages: List<String>, val service: Service?) {
    SPOTIFY("Spotify", listOf("com.spotify.music", "com.spotify.lite"), Service.SPOTIFY),
    YOUTUBE_MUSIC("YouTube Music", listOf("com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music", "app.rvx.android.apps.youtube.music"), Service.YOUTUBE_MUSIC),
    YTM_CLIENT("YouTube Music app", listOf("com.metrolist.music", "com.zionhuang.music", "it.vfsfitvnm.vimusic", "it.fast4x.rimusic", "com.malopieds.innertune", "com.dd3boh.outertune"), Service.YOUTUBE_MUSIC),
    DEEZER("Deezer", listOf("deezer.android.app"), Service.DEEZER),
    SOUNDCLOUD("SoundCloud", listOf("com.soundcloud.android"), Service.SOUNDCLOUD),
    APPLE_MUSIC("Apple Music", listOf("com.apple.android.music"), Service.APPLE_MUSIC),
    TIDAL("TIDAL", listOf("com.aspiro.tidal"), null),
    AMAZON("Amazon Music", listOf("com.amazon.mp3"), null),
    PANDORA("Pandora", listOf("com.pandora.android"), null),
    OTHER("another app", emptyList(), null),
    ;

    companion object {
        /** Video apps, browsers, calls, podcasts: their sessions aren't music plays. */
        private val NOT_MUSIC = listOf(
            "com.google.android.youtube", "com.android.chrome", "org.mozilla", "com.whatsapp", "org.telegram",
            "com.google.android.apps.podcasts", "com.audible", "com.netflix", "com.instagram", "com.zhiliaoapp.musically",
            "com.ss.android.ugc", "com.google.android.dialer", "com.android.systemui", "com.miui.video", "tv.twitch",
        )

        /** Which app a package is, or null when its sessions shouldn't count as music. */
        fun of(context: Context, pkg: String): MusicApp? {
            if (pkg == context.packageName) return null
            entries.firstOrNull { pkg in it.packages }?.let { return it }
            if (NOT_MUSIC.any { pkg.startsWith(it) }) return null
            // Any other app Android files under "audio" (players like Poweramp, Mi Music).
            val audio = runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_AUDIO
            }.getOrDefault(false)
            return if (audio) OTHER else null
        }

        /** The app's name as the phone shows it. */
        fun labelOf(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
