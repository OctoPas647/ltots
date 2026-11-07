package com.ltots.app

import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The crash report screen opens and shows the report. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CrashReportTest {
    @Test fun showsTheReport() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(ctx, CrashActivity::class.java).putExtra("report", "java.lang.IllegalStateException: boom")
        val a = Robolectric.buildActivity(CrashActivity::class.java, intent).setup().get()
        fun texts(v: android.view.View): List<String> = when (v) {
            is TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
            else -> emptyList()
        }
        val all = texts(a.window.decorView)
        assertTrue(all.toString(), all.any { "boom" in it })
    }

    /** Every report says what the UI was doing: its state and the last steps, oldest first. */
    @Test fun reportIncludesTheUi() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        CrashReport.clearUi()
        CrashReport.note("tab", "LIBRARY")
        CrashReport.note("overlay", "EQ")
        CrashReport.step("tab HOME → LIBRARY")
        CrashReport.handled("test", IllegalArgumentException("odd value"))
        repeat(30) { CrashReport.step("step $it") }
        val text = CrashReport.report(ctx, Thread.currentThread(), IllegalStateException("boom"))
        assertTrue(text, "tab: LIBRARY" in text && "overlay: EQ" in text)
        // Only the last 20 steps are kept.
        assertTrue(text, "step 29" in text && "step 10" in text && "step 9\n" !in text)
        // The stack trace still follows.
        assertTrue(text, text.indexOf("UI at the time") < text.indexOf("IllegalStateException: boom"))
        CrashReport.clearUi()
        val bare = CrashReport.report(ctx, Thread.currentThread(), IllegalStateException("boom"))
        assertTrue(bare, "UI at the time" !in bare)
    }
}
