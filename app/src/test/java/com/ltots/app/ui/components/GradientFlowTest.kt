package com.ltots.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Gradient flow: each edge spot's colour is its 10-pixel strip's average, and the light spreads out from there. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GradientFlowTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun a(c: Int) = (c ushr 24) and 0xFF

    /** A 200×200 cover: red top band, blue bottom band, green left band, a grey middle. */
    private fun cover(): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun fill(color: Int, l: Float, t: Float, rr: Float, bb: Float) = c.drawRect(l, t, rr, bb, Paint().apply { this.color = color })
        fill(0xFF808080.toInt(), 0f, 0f, 200f, 200f)
        fill(0xFFFF0000.toInt(), 0f, 0f, 200f, 10f)    // exactly the 10 px the top edge reads
        fill(0xFF0000FF.toInt(), 0f, 190f, 200f, 200f)
        fill(0xFF00FF00.toInt(), 0f, 10f, 10f, 190f)
        return bmp
    }

    @Test fun edgesAreTheirOuterTenPixels() {
        val e = GradientFlow.edges(cover())
        assertEquals(GradientFlow.ZONES, e.top.size)
        for (c in e.top) assertEquals(0xFF0000, c and 0xFFFFFF)
        for (c in e.bottom) assertEquals(0xFF, c and 0xFFFFFF)
        // The left side: green except where the red and blue bands reach it.
        val mid = e.left[GradientFlow.ZONES / 2]
        assertTrue("left middle is green: ${Integer.toHexString(mid)}", g(mid) > 200 && r(mid) < 40 && b(mid) < 40)
        // Right side: the grey middle (11 px deep would have shown nothing else either).
        val right = e.right[GradientFlow.ZONES / 2]
        assertTrue(r(right) in 120..140 && g(right) in 120..140 && b(right) in 120..140)
    }

    @Test fun lightSpreadsOutFromEachEdgeAndFades() {
        val map = GradientFlow.lightMap(GradientFlow.edges(cover()), into = null)
        val n = map.width
        val above = map.getPixel(n / 2, (n * 0.17f).toInt())
        val below = map.getPixel(n / 2, (n * 0.83f).toInt())
        val left = map.getPixel((n * 0.17f).toInt(), n / 2)
        assertTrue("red above the cover", r(above) > 200 && b(above) < 60)
        assertTrue("blue below the cover", b(below) > 200 && r(below) < 60)
        assertTrue("green to the left", g(left) > 200)
        // Brighter near the cover, gone at the far edge.
        val near = map.getPixel(n / 2, (n * 0.25f).toInt())
        assertTrue(a(near) > a(above))
        assertTrue(a(map.getPixel(n / 2, 0)) < 20)
        File("build/ui-shots").mkdirs()
        Bitmap.createScaledBitmap(map, 480, 480, true).compress(Bitmap.CompressFormat.PNG, 100, File("build/ui-shots/30_gradient_flow_fade.png").outputStream())
    }

    @Test fun onAmoledItMeltsIntoYourColour() {
        val accent = 0xFF00FFFF.toInt()
        val map = GradientFlow.lightMap(GradientFlow.edges(cover()), into = accent)
        val n = map.width
        // Near the cover: the edge's red. Further out: your cyan.
        val near = map.getPixel(n / 2, (n * 0.26f).toInt())
        val far = map.getPixel(n / 2, (n * 0.08f).toInt())
        assertTrue("near is red: ${Integer.toHexString(near)}", r(near) > 180 && g(near) < 90)
        assertTrue("far is cyan: ${Integer.toHexString(far)}", r(far) < 60 && g(far) > 200 && b(far) > 200)
        assertTrue("still glowing there", a(far) > 40)
        assertTrue("black at the very edge", a(map.getPixel(n / 2, 0)) < 20)
        Bitmap.createScaledBitmap(map, 480, 480, true).compress(Bitmap.CompressFormat.PNG, 100, File("build/ui-shots/31_gradient_flow_amoled.png").outputStream())
    }
}
