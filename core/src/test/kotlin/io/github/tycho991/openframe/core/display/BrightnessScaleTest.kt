package io.github.tycho991.openframe.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec `display-brightness` › Level scale. */
class BrightnessScaleTest {
    @Test
    fun `Ends of the scale`() {
        assertEquals(3, BrightnessScale.toRaw(1))
        assertEquals(255, BrightnessScale.toRaw(100))
        assertEquals(102, BrightnessScale.toRaw(40))
    }

    @Test
    fun `Raw values below one percent read as zero but are never written`() {
        assertEquals(0, BrightnessScale.toLevel(0))
        assertEquals(0, BrightnessScale.toLevel(1))
        assertEquals(100, BrightnessScale.toLevel(255))
        assertTrue((1..100).all { BrightnessScale.toRaw(it) >= 1 })
    }

    @Test
    fun `Every level survives the round trip`() {
        for (level in 1..100) assertEquals(level, BrightnessScale.toLevel(BrightnessScale.toRaw(level)), "level $level")
    }
}
