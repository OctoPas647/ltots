package com.ltots.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The reference icon (a snake, test-only: it is not part of the app). One big dark body, a
 * separate small tongue, thin white slivers between the coils that open to the outside, and two
 * tiny white eye spots that must be ignored.
 */
class SnakeIconTest {
    private fun snake(): IconSource {
        val img = ImageIO.read(javaClass.getResourceAsStream("/icons/snake.jpg")!!)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return IconMask.prepare(px, img.width, img.height)
    }

    private fun ring(shape: IconShape) =
        IconRing.build(shape, 1000f, 1000f, marginPx = 48f, spacingPx = 8f, minPoints = 192, maxBarWidthPx = 18f)!!

    @Test fun bodyAndTongueAreTracedEyesAreNot() {
        val src = snake()
        assertTrue(src.width >= IconMask.MIN_SIDE)
        val mask = IconMask.build(src, 0.5f, invert = false)
        val c = IconContours.trace(mask)
        println("snake outlines: ${c.loops.size}, lengths ${c.lengths.joinToString { "%.0f".format(it) }}")
        assertEquals("the body and the tongue", 2, c.loops.size)
        assertTrue("the body is by far the longest", c.lengths[0] > 5 * c.lengths[1])
        // Without a minimum hole size the eye spots appear as extra outlines.
        val all = IconContours.trace(IconMask.build(src, 0.5f, invert = false, minHole = 0f))
        assertTrue("eyes traced only with no minimum", all.loops.size > c.loops.size)
    }

    @Test fun barsNeverCrossTheDarkParts() {
        val shape = IconShape.build(snake(), 0.5f, invert = false)
        val mask = shape.mask
        val ring = ring(shape)
        val s = ring.iconWidth / mask.width
        var crossings = 0
        for (k in 0 until ring.count) {
            // Sample along a full-length bar, skipping the first pixel or two at the outline.
            var t = 2f * s
            while (t < ring.reachOutPx[k]) {
                val mx = (ring.x[k] + ring.nx[k] * t - ring.iconLeft) / s
                val my = (ring.y[k] + ring.ny[k] * t - ring.iconTop) / s
                if (mask.darkAt(mx, my)) { crossings++; break }
                t += s
            }
            // Never into the safe margin at the edge.
            val ex = ring.x[k] + ring.nx[k] * ring.reachOutPx[k]
            val ey = ring.y[k] + ring.ny[k] * ring.reachOutPx[k]
            assertTrue(ex >= 47f && ex <= 953f && ey >= 47f && ey <= 953f)
        }
        assertEquals("bars over the dark parts", 0, crossings)
        // The slivers between the coils give some points only short bars.
        val short = (0 until ring.count).count { ring.reachOutPx[it] < 0.15f * ring.reachOutPx.max() }
        assertTrue("short bars in the slivers: $short", short > ring.count / 20)
        saveDebugPicture(ring, mask)
    }

    @Test fun thresholdAndInvertChangeTheShape() {
        val src = snake()
        val normal = IconMask.build(src, 0.5f, invert = false)
        val inverted = IconMask.build(src, 0.5f, invert = true)
        // Inverted, the white background becomes the dark part: it fills the whole picture.
        assertEquals(src.width, inverted.width)
        assertFalse(normal.width == inverted.width && normal.height == inverted.height)
    }

    /** Outline, dark parts and full-length bars, saved for a look by eye (build/ui-shots). */
    private fun saveDebugPicture(ring: IconRing, mask: IconMask) {
        val img = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK; g.fillRect(0, 0, 1000, 1000)
        g.color = Color(40, 40, 48)
        val shape = Path2D.Float(Path2D.WIND_EVEN_ODD)
        for (l in ring.shapeLoops) {
            shape.moveTo(l[0], l[1])
            for (i in 2 until l.size step 2) shape.lineTo(l[i], l[i + 1])
            shape.closePath()
        }
        g.fill(shape)
        g.color = Color(0, 220, 230)
        for (k in 0 until ring.count) {
            val bar = Path2D.Float()
            val hw = ring.halfWidthOut[k]; val len = ring.reachOutPx[k]
            val tx = -ring.ny[k]; val ty = ring.nx[k]
            bar.moveTo(ring.x[k] + tx * hw, ring.y[k] + ty * hw)
            bar.lineTo(ring.x[k] + ring.nx[k] * len + tx * hw, ring.y[k] + ring.ny[k] * len + ty * hw)
            bar.lineTo(ring.x[k] + ring.nx[k] * len - tx * hw, ring.y[k] + ring.ny[k] * len - ty * hw)
            bar.lineTo(ring.x[k] - tx * hw, ring.y[k] - ty * hw)
            bar.closePath()
            g.fill(bar)
        }
        g.color = Color(255, 80, 80); g.stroke = BasicStroke(1f)
        g.drawRect(48, 48, 904, 904)
        g.dispose()
        File("build/ui-shots").mkdirs()
        ImageIO.write(img, "png", File("build/ui-shots/icon-snake-reach.png"))
    }
}
