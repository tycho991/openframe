package io.github.tycho991.openframe.core.settings

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.places.PlaceIndex
import io.github.tycho991.openframe.core.testing.FakePlatform
import io.github.tycho991.openframe.core.testing.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/** The frame's language for texts, clock and place names (spec `photo-library`, "Language of the place name"). */
class FrameLanguageTest {
    private val places = PlaceIndex(AppLog(TestClock()))

    @Test
    fun `auto follows a German system`() {
        val platform = FakePlatform(systemLocale = Locale.GERMANY)
        assertEquals(Locale.GERMANY, FrameLanguage.AUTO.locale(platform))
        assertEquals("Mailand, Italien", places.placeName(45.4643, 9.1895, FrameLanguage.AUTO.locale(platform)))
    }

    @Test
    fun `auto follows an English system and a change of it`() {
        val platform = FakePlatform(systemLocale = Locale.US)
        assertEquals("Milan, Italy", places.placeName(45.4643, 9.1895, FrameLanguage.AUTO.locale(platform)))
        platform.systemLocale = Locale.GERMANY
        assertEquals("Mailand, Italien", places.placeName(45.4643, 9.1895, FrameLanguage.AUTO.locale(platform)))
    }

    @Test
    fun `A chosen language overrides the system`() {
        val platform = FakePlatform(systemLocale = Locale.GERMANY)
        assertEquals(Locale.ENGLISH, FrameLanguage.EN.locale(platform))
        assertEquals(Locale.GERMAN, FrameLanguage.DE.locale(FakePlatform(systemLocale = Locale.US)))
    }

    @Test
    fun `A country the platform cannot name is left out`() {
        // For a code without a name, getDisplayCountry returns the code itself.
        assertEquals("AA", Locale("", "AA").getDisplayCountry(Locale.GERMAN))
        assertEquals("Somewhere", PlaceIndex.label("Somewhere", "AA", Locale.GERMAN))
        assertEquals("Somewhere", PlaceIndex.label("Somewhere", "", Locale.GERMAN))
        assertEquals("Pristina, Kosovo", PlaceIndex.label("Pristina", "XK", Locale.ENGLISH))
    }
}
