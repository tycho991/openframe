package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.settings.FillMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Specs `slideshow-display` (fill mode, automatic presentation, section) and `photo-library` (largest zoom). */
class PresentationTest {
    private val landscape = PixelSize(1920, 1200)

    @Test
    fun `Every case of the shared table`() {
        val file = File(System.getProperty("openframe.presentationCases"))
        val cases =
            Json
                .parseToJsonElement(file.readText())
                .jsonObject
                .getValue("cases")
                .jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases.map { it.jsonObject }) {
            val scenario = case.string("scenario")
            val photo = case.obj("photo")
            val screen = case.obj("screen")
            val crop =
                case.getValue("crop").takeIf { it != JsonNull }?.jsonObject?.let {
                    Crop(it.double("x"), it.double("y"), it.double("zoom"))
                }
            val mode = FillMode.valueOf(case.string("fillMode").uppercase())
            val actual =
                Presentation.of(photo.int("width"), photo.int("height"), PixelSize(screen.int("width"), screen.int("height")), crop, mode)
            val expected = case.obj("expected")
            for ((name, value) in listOf(
                "x" to actual.x,
                "y" to actual.y,
                "width" to actual.width,
                "height" to actual.height,
                "zoom" to actual.zoom,
            )) {
                assertEquals(expected.double(name), value, 1e-4, "$name in $scenario")
            }
            val output = expected.obj("output")
            assertEquals(PixelSize(output.int("width"), output.int("height")), actual.output, "output in $scenario")
        }
    }

    @Test
    fun `The window is moved inward at all four edges`() {
        // 4800×3000 at zoom 2 shows half of each direction.
        val left = Presentation.of(4800, 3000, landscape, Crop(0.0, 0.5, 2.0), FillMode.FILL)
        val right = Presentation.of(4800, 3000, landscape, Crop(1.0, 0.5, 2.0), FillMode.FILL)
        val top = Presentation.of(4800, 3000, landscape, Crop(0.5, 0.0, 2.0), FillMode.FILL)
        val bottom = Presentation.of(4800, 3000, landscape, Crop(0.5, 1.0, 2.0), FillMode.FILL)
        assertEquals(0.0, left.x, 1e-9)
        assertEquals(1.0, right.x + right.width, 1e-9)
        assertEquals(0.0, top.y, 1e-9)
        assertEquals(1.0, bottom.y + bottom.height, 1e-9)
        // The other direction stays centered on the focus point.
        assertEquals(0.25, left.y, 1e-9)
        assertEquals(0.25, top.x, 1e-9)
    }

    @Test
    fun `A stored zoom above the largest one is shown with the largest, below the smallest with the smallest`() {
        // 4000×3000 on 1920×1200: one original pixel per screen pixel at zoom 1920/(0.48·4000) ≈ 2.083.
        val above = Presentation.of(4000, 3000, landscape, Crop(0.5, 0.5, 4.0), FillMode.FILL)
        assertEquals(1 / 0.48, above.zoom, 1e-9)
        assertEquals(PixelSize(1920, 1200), above.output)
        assertEquals(0.48, above.width, 1e-9)
        val below = Presentation.of(3000, 4000, landscape, Crop(0.5, 0.5, 0.01), FillMode.FILL)
        assertEquals(Presentation.zoomRange(3000, 4000, landscape).start, below.zoom, 1e-9)
        assertEquals(1.0, below.width, 1e-9)
        assertEquals(1.0, below.height, 1e-9)
    }

    @Test
    fun `A section applies whatever the fill mode`() {
        val crop = Crop(0.7, 0.6, 2.0)
        val presentations = FillMode.entries.map { Presentation.of(4800, 3000, landscape, crop, it) }
        assertEquals(1, presentations.toSet().size)
    }

    @Test
    fun `The key changes with the window and is stable for the same one`() {
        val fill = Presentation.of(3000, 4000, landscape, null, FillMode.FILL)
        val auto = Presentation.of(3000, 4000, landscape, null, FillMode.AUTO)
        assertEquals("1920x1200_0_2656_10000_4688", fill.key)
        // The whole photo in `fit` is the same window in both orientations, but another file.
        val landscapeFit = Presentation.of(3000, 4000, landscape, null, FillMode.FIT)
        val portraitFit = Presentation.of(3000, 4000, PixelSize(1200, 1920), null, FillMode.FIT)
        assertEquals(Region(0.0, 0.0, 1.0, 1.0), Region(landscapeFit.x, landscapeFit.y, landscapeFit.width, landscapeFit.height))
        assertEquals(Region(0.0, 0.0, 1.0, 1.0), Region(portraitFit.x, portraitFit.y, portraitFit.width, portraitFit.height))
        assertTrue(landscapeFit.key != portraitFit.key)
        assertEquals(fill.key, Presentation.of(3000, 4000, landscape, null, FillMode.FILL).key)
        assertTrue(fill.key != auto.key)
    }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

    private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double
}
