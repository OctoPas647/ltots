package com.ltots.app.ui

import androidx.compose.ui.unit.dp
import com.ltots.app.ui.components.initialOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopAreaTest {
    @Test fun squareScreenNoCameraSitsAtTheGap() {
        assertEquals(6f, TopArea().minTop(30.dp, 40.dp, 11.dp).value, 0.01f)
    }

    @Test fun roundedCornerPushesACornerElementDown() {
        val area = TopArea(cornerRadius = 60.dp)
        val near = area.minTop(4.dp, 40.dp, 11.dp).value
        val far = area.minTop(30.dp, 40.dp, 11.dp).value
        assertTrue("an element tucked in the corner must move down ($near)", near > 10f)
        assertTrue("further in from the edge needs less room ($far < $near)", far < near)
    }

    @Test fun punchHoleInTheMiddleLeavesTheLogoAtTheTop() {
        // Like the Xiaomi 17 Ultra: a small camera in the middle of a ~400 dp wide screen.
        val area = TopArea(cutoutLeft = 192.dp, cutoutRight = 212.dp, cutoutBottom = 34.dp, hasCutout = true, cornerRadius = 36.dp)
        val logo = area.minTop(30.dp, 40.dp, 11.dp).value
        assertTrue("logo stays up by the camera, not below it ($logo)", logo < 12f)
        val button = area.minTop(30.dp, 42.dp, 21.dp, fromRight = true, screenWidth = 404.dp).value
        assertTrue("the round button on the right stays up too ($button)", button < 12f)
    }

    @Test fun cameraRightAboveMovesTheElementBelowIt() {
        val area = TopArea(cutoutLeft = 20.dp, cutoutRight = 60.dp, cutoutBottom = 30.dp, hasCutout = true)
        assertEquals(34f, area.minTop(30.dp, 40.dp, 11.dp).value, 0.01f)
    }

    @Test fun sliderBubbleInitials() {
        assertEquals("A", initialOf("abba"))
        assertEquals("Ч", initialOf("  чалга"))
        assertEquals("#", initialOf("2 Unlimited"))
        assertEquals("#", initialOf(""))
        assertEquals("B", initialOf("(Bonus) track"))
    }
}
