package com.ltots.app.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.ui.theme.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class IconColorTest {
    @Test fun huesMapToTheNearestOfTwentyIcons() {
        assertEquals(0, IconColor.variantFor(0f, 1f, 1f))
        assertEquals(0, IconColor.variantFor(359f, 1f, 1f))
        assertEquals(1, IconColor.variantFor(18f, 0.8f, 0.9f))
        assertEquals(10, IconColor.variantFor(180f, 0.8f, 0.9f)) // cyan
        assertEquals(13, IconColor.variantFor(240f, 0.8f, 0.9f)) // blue
        assertEquals(19, IconColor.variantFor(342f, 0.8f, 0.9f))
        // Grey and near-black covers keep the original icon.
        assertEquals(-1, IconColor.variantFor(200f, 0.05f, 0.8f))
        assertEquals(-1, IconColor.variantFor(200f, 0.9f, 0.05f))
        assertEquals("com.ltots.app.LauncherDefault", IconColor.componentFor(-1))
        assertEquals("com.ltots.app.LauncherC07", IconColor.componentFor(7))
    }

    @Test fun manifestHasOneIconPerColourAndOnlyTheOriginalIsOn() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        val aliases = Regex("""<activity-alias[\s\S]*?</activity-alias>""").findAll(manifest).map { it.value }.toList()
        assertEquals(IconColor.COUNT + 1, aliases.size)
        assertEquals(1, aliases.count { "android:enabled=\"true\"" in it })
        assertTrue(aliases.all { "category.LAUNCHER" in it && "targetActivity=\".MainActivity\"" in it })
        val res = manifest.let { File(File(listOf("src/main", "app/src/main").first { File(it).isDirectory }), "res/mipmap-anydpi-v26") }
        for (i in 0 until IconColor.COUNT) {
            assertTrue("C%02d".format(i) in manifest)
            assertTrue(File(res, "ic_launcher_c%02d.xml".format(i)).isFile)
        }
    }

    @Test fun leavingTheAppSwitchesToTheAccentsIcon() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        IconColor.init(app)
        IconColor.setEnabled(true)
        // Own accent, no cover: cyan.
        ThemeState.update { it.copy(dynamicColors = false, accent = 0xFF00E5FFL) }
        IconColor.onForeground(true)
        IconColor.onForeground(false)
        val pm = app.packageManager
        fun state(c: String) = pm.getComponentEnabledSetting(ComponentName(app, c))
        val deadline = System.currentTimeMillis() + 5000
        while (state("com.ltots.app.LauncherC10") != PackageManager.COMPONENT_ENABLED_STATE_ENABLED && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("com.ltots.app.LauncherC10"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("com.ltots.app.LauncherDefault"))
        // Turning the setting off goes back to the original icon.
        IconColor.setEnabled(false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("com.ltots.app.LauncherDefault"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("com.ltots.app.LauncherC10"))
    }
}
