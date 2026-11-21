package com.ltots.app.crash

import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.CrashActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Crash reports: saved, listed, opened; native crash records and freeze dumps read correctly. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CrashDetectionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun savesListsAndOpensReports() {
        CrashLog.deleteAll(ctx)
        val f = CrashLog.save(ctx, CrashLog.Kind.NATIVE, "LToTS — crashed\nWhat: Native crash: SIGSEGV in librubberband-jni.so\n\nbody", at = 1_700_000_000_000L)
        CrashLog.save(ctx, CrashLog.Kind.FREEZE_RECOVERED, "x\nWhat: Froze at Foo.bar\n", at = 1_700_000_100_000L)
        val all = CrashLog.list(ctx)
        assertEquals(2, all.size)
        // Newest first, with kind and summary.
        assertEquals(CrashLog.Kind.FREEZE_RECOVERED, all[0].kind)
        assertEquals("Native crash: SIGSEGV in librubberband-jni.so", all[1].summary)
        // A crash (not a recovered freeze) waits to be shown the next time the app opens.
        assertEquals(f.name, CrashLog.takeUnseen(ctx)?.name)
        assertNull(CrashLog.takeUnseen(ctx))

        val a = Robolectric.buildActivity(CrashActivity::class.java, Intent(ctx, CrashActivity::class.java).putExtra(CrashActivity.EXTRA_FILE, f.absolutePath)).setup().get()
        val texts = texts(a.window.decorView)
        assertTrue(texts.toString(), texts.any { "SIGSEGV" in it })
        assertTrue(texts.toString(), texts.any { it == "LToTS crashed (native code)" })
        CrashLog.deleteAll(ctx)
        assertTrue(CrashLog.list(ctx).isEmpty())
    }

    @Test fun keepsTheNewestForty() {
        CrashLog.deleteAll(ctx)
        repeat(45) { CrashLog.save(ctx, CrashLog.Kind.FREEZE_RECOVERED, "What: $it", at = 1_700_000_000_000L + it * 1000L) }
        val all = CrashLog.list(ctx)
        assertEquals(40, all.size)
        assertEquals("44", all.first().summary)
        CrashLog.deleteAll(ctx)
    }

    // ---- tombstone (native crash record) ----

    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
        fun int(f: Int, v: Long) { tag(f, 0); varint(v) }
        fun bytes(f: Int, b: ByteArray) { tag(f, 2); varint(b.size.toLong()); out.write(b) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun msg(f: Int, build: Pb.() -> Unit) = bytes(f, Pb().apply(build).out.toByteArray())
    }

    @Test fun readsANativeCrashRecord() {
        val t = Pb().apply {
            int(5, 1234) // pid
            int(6, 1240) // tid
            str(9, "com.ltots.app")
            msg(10) { int(1, 11); str(2, "SIGSEGV"); int(3, 1); str(4, "SEGV_MAPERR"); int(8, 1); int(9, 0x10) }
            str(14, "")
            msg(16) {
                int(1, 1240)
                msg(2) {
                    int(1, 1240); str(2, "ExoPlayer:Playb")
                    msg(4) { int(1, 0x1a2b); str(4, "Resampler::process"); int(5, 64); str(6, "/data/app/x/lib/arm64/librubberband-jni.so") }
                    msg(4) { int(1, 0x99); str(4, "art_quick_generic_jni_trampoline"); int(5, 8); str(6, "/apex/com.android.art/lib64/libart.so") }
                }
            }
            msg(16) {
                int(1, 1)
                msg(2) { int(1, 1); str(2, "main"); msg(4) { int(1, 5); str(4, "__epoll_pwait"); str(6, "/apex/com.android.runtime/lib64/bionic/libc.so") } }
            }
        }.out.toByteArray()
        val d = Tombstone.describe(t)
        assertNotNull(d)
        assertTrue(d!!, d.startsWith("Signal: SIGSEGV / SEGV_MAPERR at 0x10"))
        assertTrue(d, "Crashing thread: \"ExoPlayer:Playb\" (tid 1240)" in d)
        assertTrue(d, "librubberband-jni.so Resampler::process+64" in d)
        // Not a tombstone at all: no made-up report.
        assertNull(Tombstone.describe("hello".toByteArray()))
    }

    // ---- freeze ("not responding") dump ----

    @Test fun picksTheMainThreadAndWhatItWaitsFor() {
        val dump = """
            ----- pid 1234 at 2026-11-20 10:00:00 -----
            Cmd line: com.ltots.app
            Subject: Input dispatching timed out

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              at com.ltots.app.data.ListenStats.summary(ListenStats.kt:189)
              - waiting to lock <0x0abc> (a com.ltots.app.data.ListenStats) held by thread 22
              at com.ltots.app.ui.screens.HomeScreenKt.HomeScreen(HomeScreen.kt:211)

            "LToTS-stats" prio=4 tid=22 Runnable
              at com.ltots.app.data.ListenStats.backfill(ListenStats.kt:260)
              - locked <0x0abc> (a com.ltots.app.data.ListenStats)

            "Binder:1234_2" prio=5 tid=9 Native
              at android.os.BinderProxy.transactNative(Native method)

        """.trimIndent()
        val d = ExitScanner.anrDetails(dump)
        assertTrue(d, "Subject: Input dispatching timed out" in d)
        assertTrue(d, "\"main\" prio=5 tid=1 Blocked" in d)
        assertTrue(d, "ListenStats.backfill" in d) // the thread holding the lock
        assertTrue(d, "Binder:1234_2" !in d)
    }

    private fun texts(v: android.view.View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }
}
