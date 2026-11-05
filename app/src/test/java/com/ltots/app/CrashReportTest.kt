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
}
