package com.ltots.app.audio

import com.ltots.app.data.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery EQ: LToTS's curve squeezed onto the phone's 5 fixed bands. */
class BatteryEqFitTest {
    private val phone = PhoneEqLayout.STANDARD

    private fun preset(name: String) = Store.builtInPresets.first { it.name == name }.eq

    @Test fun flatOrOffNeedsNothing() {
        assertArrayEquals(FloatArray(5), BatteryEqFit.fit(EqState(), phone), 0f)
        val off = preset("Bass boost").copy(enabled = false)
        assertArrayEquals(FloatArray(5), BatteryEqFit.fit(off, phone), 0f)
    }

    @Test fun broadShapesCarryOver() {
        for (name in listOf("Bass boost", "Bass reducer", "Bright", "Warm", "V-shape", "Loudness", "Rock", "Pop", "Hip-hop", "Classical")) {
            val eq = preset(name)
            val levels = BatteryEqFit.fit(eq, phone)
            val err = BatteryEqFit.errorDb(eq, phone, levels)
            println("BATTERYEQ %-12s levels=%s rms=%.2f dB".format(name, levels.joinToString(" "), err))
            assertTrue("$name: phone version off by $err dB", err < 1.5)
            assertTrue("$name: within the phone's limits", levels.all { it in phone.minDb..phone.maxDb })
        }
    }

    @Test fun bassBoostRaisesTheBassAgainstTheRest() {
        val levels = BatteryEqFit.fit(preset("Bass boost"), phone)
        // Automatic headroom moves everything down; the bass stays well above the treble.
        assertTrue(levels.joinToString(), levels[0] - levels[4] > 4f)
    }

    @Test fun staysWithinThePhonesLimits() {
        val extreme = EqState(bands = EqDefaults.tenBand().map { it.copy(gainDb = 24f) }, autoHeadroom = false)
        val levels = BatteryEqFit.fit(extreme, phone)
        assertTrue(levels.joinToString(), levels.all { it <= 15f && it >= -15f })
    }

    @Test fun oddPhoneReportsAreMadeSafe() {
        // A phone (or a stand-in) reporting zero centres, no ranges and no level limits.
        val odd = PhoneEqLayout.sanitized(List(5) { 0f }, List(5) { 0f }, List(5) { 0f }, 0f, 0f)
        assertTrue(odd.toString(), odd.centersHz.all { it in 20f..20000f } && odd.minDb < odd.maxDb)
        val levels = BatteryEqFit.fit(preset("Rock"), odd)
        assertTrue(levels.joinToString(), levels.all { it.isFinite() && it in odd.minDb..odd.maxDb })
    }

    @Test fun graphCurveLinesUpWithTheRealOne() {
        val eq = preset("V-shape")
        val levels = BatteryEqFit.fit(eq, phone)
        val curve = BatteryEqFit.phoneCurve(eq, phone, levels)
        // At 1 kHz (scooped) the phone's version must sit below its 60 Hz (boosted) end.
        val at = { hz: Double -> curve[(Math.log(hz / 20.0) / Math.log(1000.0) * 239).toInt()] }
        assertTrue(at(60.0) > at(1000.0) + 3f)
    }
}
