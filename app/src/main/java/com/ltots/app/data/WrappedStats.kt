package com.ltots.app.data

import java.util.Locale
import kotlin.math.ceil

/** How Wrapped talks to you. */
enum class WrappedTone(val label: String) { MILD("Mild"), SNARKY("Snarky"), BRUTAL("Brutal") }

/** What counts as falling asleep to a song. */
enum class SleepRule(val label: String) {
    /** Playing between midnight and 5 am with the phone untouched for 20 minutes or more. */
    NIGHT("Late & untouched"),
    /** Playing while the sleep timer was running. */
    TIMER("Sleep timer"),
    EITHER("Either"),
}

/** Which slides Wrapped shows. */
enum class WrappedSlides(val label: String) { BOTH("Both"), STATS("New stats"), CLASSIC("Classic") }

/** One line of Wrapped: a small heading, the big words, and the remark under them. */
data class WrappedLine(val id: String, val small: String, val big: String, val sub: String)

/**
 * The stat screens with an opinion (after the end-of-match stats in Stick Fight): what else the
 * time could have bought, what played to nobody, who nearly won, what keeps getting skipped,
 * what was never played and what was played to death. Each only shows when there's enough to
 * say; [tone] picks how kindly it's said.
 */
object WrappedStats {
    /**
     * Class hours an English speaker needs for working fluency (the US Foreign Service
     * Institute's estimates: Spanish 24-30 weeks, German 36, Russian 44, at 25 hours a week).
     */
    val LANGUAGE_HOURS = listOf("Spanish" to 750.0, "German" to 900.0, "Russian" to 1100.0)

    internal fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min >= 120 -> "${min / 60} h ${min % 60} min"
            min >= 1 -> "$min min"
            else -> "under a minute"
        }
    }

    internal fun percent(p: Double): String = when {
        p >= 10 -> "%.0f".format(Locale.ROOT, p)
        p >= 0.1 -> "%.1f".format(Locale.ROOT, p)
        else -> "%.2f".format(Locale.ROOT, p)
    }

    private fun pick(tone: WrappedTone, mild: String, snarky: String, brutal: String) = when (tone) {
        WrappedTone.MILD -> mild; WrappedTone.SNARKY -> snarky; WrappedTone.BRUTAL -> brutal
    }

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** How often [s] played to someone asleep, by [rule]. */
    fun sleeps(s: SongStat, rule: SleepRule): Int = when (rule) {
        SleepRule.NIGHT -> s.night; SleepRule.TIMER -> s.timer; SleepRule.EITHER -> s.night + s.timer
    }

    /**
     * @param neverPlayed songs on the phone that have never been played at all, out of [librarySize]
     *   (both 0 when the library isn't known).
     */
    fun lines(s: StatsSummary, tone: WrappedTone, rule: SleepRule, neverPlayed: Int = 0, librarySize: Int = 0): List<WrappedLine> {
        val out = ArrayList<WrappedLine>()
        val top = s.songs.firstOrNull()

        // The opportunity cost: the top song's time, as a share of learning a language.
        if (top != null && top.ms >= 10 * 60_000L) {
            val hours = top.ms / 3_600_000.0
            val langs = LANGUAGE_HOURS.map { (name, need) -> "${percent(hours / need * 100)}% of $name" }
            val list = langs.dropLast(1).joinToString(", ") + " or " + langs.last()
            val time = duration(top.ms)
            out += WrappedLine("cost", "The opportunity cost", top.title, pick(tone,
                "You spent $time with it. That time could also have been $list.",
                "Instead of listening to it for $time, you could have learned $list. You chose the chorus.",
                "$time. That's $list, and you still can't order a coffee in any of them.",
            ))
        }

        // The sleep track.
        s.songs.maxByOrNull { sleeps(it, rule) }?.takeIf { sleeps(it, rule) >= 2 }?.let { z ->
            val n = sleeps(z, rule)
            out += WrappedLine("sleep", "The sleep track", z.title, pick(tone,
                "You mostly fell asleep listening to it: $n nights it played on with nobody there.",
                "You mostly fell asleep listening to it. $n times it played to an audience of none.",
                "$n times it sang you to sleep. You have never once heard how it ends.",
            ))
        }

        // The runner-up.
        val second = s.songs.getOrNull(1)
        if (top != null && second != null && second.ms > 0) {
            val gap = top.ms - second.ms
            val each = (second.ms / second.plays.coerceAtLeast(1)).coerceAtLeast(30_000L)
            val more = ceil(gap / each.toDouble()).toInt().coerceAtLeast(1)
            val gapText = if (gap < 60_000L) "under a minute" else "just ${gap / 60_000} minute${if (gap / 60_000 == 1L) "" else "s"}"
            val times = "$more more time${if (more == 1) "" else "s"}"
            out += WrappedLine("second", "The runner-up", second.title, pick(tone,
                "Your #2 song, trailing by $gapText. Playing it $times would have taken the crown.",
                "Your #2 song, trailing by $gapText. You only had to listen to it $times to take the crown.",
                "Second place by $gapText. $times was all it needed, and you couldn't be bothered.",
            ))
        }

        // The skip victim.
        s.songs.maxByOrNull { it.skips }?.takeIf { it.skips >= 3 }?.let { v ->
            out += WrappedLine("skip", "The skip victim", v.title, pick(tone,
                "You skipped it ${v.skips} times within its first seconds.",
                "Skipped ${v.skips} times in under 20 seconds. Deleting it is free.",
                "${v.skips} skips. It is still on your phone. Explain yourself.",
            ))
        }

        // The graveyard.
        if (neverPlayed >= 3 && librarySize > 0) {
            val share = neverPlayed * 100 / librarySize
            out += WrappedLine("grave", "The graveyard", "$neverPlayed songs", pick(tone,
                "are on your phone and haven't been played yet.",
                "downloaded and never played once. They're still waiting.",
                "never played. That's $share% of your library you carry around for nothing.",
            ))
        }

        // The broken record.
        s.streak?.takeIf { it.count >= 3 }?.let { r ->
            val day = "${MONTHS[r.month.coerceIn(0, 11)]} ${r.day}"
            out += WrappedLine("streak", "The broken record", "${r.count}× in a row", pick(tone,
                "${r.title}, back to back, on $day.",
                "${r.title}, on $day. Everything okay?",
                "${r.title}, on $day. The neighbours know the words now.",
            ))
        }
        return out
    }
}
