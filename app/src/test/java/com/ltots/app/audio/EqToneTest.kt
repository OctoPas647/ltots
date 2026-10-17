package com.ltots.app.audio

import com.ltots.app.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqToneTest {
    @Test fun sliderLandsExactlyOnItsValue() {
        var s = EqState()
        s = EqTone.set(s, ToneRegion.BASS, 6f)
        assertEquals(6f, EqTone.level(s, ToneRegion.BASS), 0.15f)
        // Treble bands are untouched by the bass slider.
        assertTrue(s.bands.filter { it.freqHz >= 2000f }.all { it.gainDb == 0f })
        s = EqTone.set(s, ToneRegion.TREBLE, -4f)
        assertEquals(-4f, EqTone.level(s, ToneRegion.TREBLE), 0.15f)
        assertEquals(6f, EqTone.level(s, ToneRegion.BASS), 0.15f)
    }

    @Test fun keepsTheShapeYouBuilt() {
        val rock = Store.builtInPresets.first { it.name == "Rock" }.eq
        val s = EqTone.set(rock, ToneRegion.BASS, EqTone.level(rock, ToneRegion.BASS) + 2f)
        // The two lowest bands both rose by about the same amount: the shape is kept.
        val d0 = s.bands[0].gainDb - rock.bands[0].gainDb
        val d1 = s.bands[1].gainDb - rock.bands[1].gainDb
        assertEquals(d0, d1, 0.15f)
        assertTrue(d0 > 1f)
    }

    @Test fun addsABandWhenTheRegionIsEmpty() {
        val air = Store.builtInPresets.first { it.name == "Air" }.eq
        val s = EqTone.set(air, ToneRegion.BASS, 3f)
        assertEquals(air.bands.size + 1, s.bands.size)
        assertEquals(3f, EqTone.level(s, ToneRegion.BASS), 0.01f)
    }

    @Test fun flatAndMatching() {
        val rock = Store.builtInPresets.first { it.name == "Rock" }
        assertTrue(EqMath.isNeutral(EqTone.flatten(rock.eq)))
        // Same sound with fresh band ids still counts as the preset.
        val copy = rock.eq.copy(bands = rock.eq.bands.map { it.copy(id = EqDefaults.newId()) }.reversed())
        assertTrue(EqTone.sameShape(rock.eq, copy))
        assertFalse(EqTone.sameShape(rock.eq, Store.builtInPresets.first { it.name == "Pop" }.eq))
        assertTrue(EqTone.sameShape(EqState(), EqTone.flatten(rock.eq)))
    }

    @Test fun presetsAreSaneAndDistinct() {
        val ps = Store.builtInPresets
        assertTrue(ps.size >= 30)
        assertEquals(ps.size, ps.map { it.name }.toSet().size)
        assertTrue(ps.all { it.category in Store.presetCategories && it.hint.isNotBlank() })
        // No two presets sound the same (otherwise picking one would highlight the other).
        for (i in ps.indices) for (j in i + 1 until ps.size) assertFalse("${ps[i].name} = ${ps[j].name}", EqTone.sameShape(ps[i].eq, ps[j].eq))
        // Nothing clips badly with automatic headroom.
        assertTrue(ps.all { EqMath.peakBoostDb(it.eq) <= 12.0 })
        // The original presets kept their names.
        assertTrue(listOf("Flat", "Bass boost", "Warm", "Vocal", "Bright", "V-shape", "Smooth shelves").all { n -> ps.any { it.name == n } })
    }
}
