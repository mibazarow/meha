package com.andharness.ru.data.tools.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure parts of the new image-editing tools: argument coercion, crop-rect
 * normalization, watermark placement, tiling origins, collage grid layout, colour parsing and the
 * `screen_annotate` shape JSON parser. No Android/Robolectric is needed — [ImageEditParsing] has no
 * Android dependency by design.
 */
class ImageEditParsingTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    // ---- crop ---------------------------------------------------------------------------------

    @Test
    fun cropRectFromPixelArguments() {
        val rect = ImageEditParsing.cropRect(
            args("x" to "10", "y" to "20", "width" to "100", "height" to "50"),
            400,
            200,
        )
        assertEquals(listOf(10, 20, 100, 50), rect.toList())
    }

    @Test
    fun cropRectFromNormalizedBox() {
        val rect = ImageEditParsing.cropRect(args("box" to "0.25,0.5,0.75,1.0"), 400, 200)
        assertEquals(listOf(100, 100, 200, 100), rect.toList())
    }

    @Test
    fun cropRectClampsToSourceBounds() {
        val rect = ImageEditParsing.cropRect(
            args("x" to "350", "y" to "150", "width" to "100", "height" to "100"),
            400,
            200,
        )
        assertEquals(listOf(350, 150, 50, 50), rect.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropRectRejectsEmptyRegion() {
        ImageEditParsing.cropRect(args("x" to "500", "width" to "10", "height" to "10"), 400, 200)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropRectRequiresSizesOrBox() {
        ImageEditParsing.cropRect(args("x" to "10"), 400, 200)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropBoxOutOfRangeIsRejected() {
        ImageEditParsing.cropRect(args("box" to "0,0,1.5,1"), 100, 100)
    }

    // ---- rotation / flip ----------------------------------------------------------------------

    @Test
    fun flipFlagsAreDerivedFromTheToken() {
        assertTrue(ImageEditParsing.flipHorizontal("both"))
        assertFalse(ImageEditParsing.flipVertical("h"))
        assertTrue(ImageEditParsing.flipVertical("V"))
        assertEquals("none", ImageEditParsing.parseFlip("OFF"))
        assertNull(ImageEditParsing.parseFlip(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFlipIsRejected() {
        ImageEditParsing.parseFlip("diagonal")
    }

    // ---- watermark placement / tiling ---------------------------------------------------------

    @Test
    fun watermarkOriginCornersAndCenter() {
        assertEquals(
            listOf(290, 240),
            ImageEditParsing.watermarkOrigin("bottom-right", 400, 300, 100, 50, 10).toList(),
        )
        assertEquals(
            listOf(10, 10),
            ImageEditParsing.watermarkOrigin("top-left", 400, 300, 100, 50, 10).toList(),
        )
        assertEquals(
            listOf(150, 125),
            ImageEditParsing.watermarkOrigin("center", 400, 300, 100, 50, 10).toList(),
        )
    }

    @Test
    fun watermarkOriginDefaultsToBottomRight() {
        assertEquals(
            ImageEditParsing.watermarkOrigin("bottom-right", 400, 300, 100, 50, 10).toList(),
            ImageEditParsing.watermarkOrigin(null, 400, 300, 100, 50, 10).toList(),
        )
    }

    @Test
    fun tileOriginsCoverTheGrid() {
        val origins = ImageEditParsing.tileOrigins(100, 100, 40, 40, inset = 0)
        assertEquals(9, origins.size)
        assertEquals(listOf(0, 0), origins.first().toList())
        assertEquals(listOf(80, 80), origins.last().toList())
    }

    // ---- collage grid -------------------------------------------------------------------------

    @Test
    fun gridAutoPicksANearSquare() {
        assertEquals(listOf(2, 2), ImageEditParsing.grid(4, null, null).toList())
        assertEquals(listOf(2, 3), ImageEditParsing.grid(5, null, null).toList())
    }

    @Test
    fun gridDerivesTheMissingSide() {
        assertEquals(listOf(2, 2), ImageEditParsing.grid(3, null, 2).toList())
        assertEquals(listOf(1, 3), ImageEditParsing.grid(3, 1, null).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun gridTooSmallForTheImageCountIsRejected() {
        ImageEditParsing.grid(5, 2, 2)
    }

    @Test
    fun collageCellPositionsStepByCellPlusSpacing() {
        // Cell (0,0) is inset by the spacing, so cells are not flush with the edges.
        assertEquals(listOf(10, 10), ImageEditParsing.collageCell(0, 2, 100, 80, 10).toList())
        assertEquals(listOf(120, 100), ImageEditParsing.collageCell(3, 2, 100, 80, 10).toList())
    }

    // ---- colours ------------------------------------------------------------------------------

    @Test
    fun colorsParseRgbArgbShortHexAndNames() {
        assertEquals(0xFFFF0000.toInt(), ImageEditParsing.parseColor("#FF0000"))
        assertEquals(0x80FF0000.toInt(), ImageEditParsing.parseColor("#80FF0000"))
        assertEquals(0xFFFF0000.toInt(), ImageEditParsing.parseColor("#F00"))
        assertEquals(0xFF00FF00.toInt(), ImageEditParsing.parseColor("green"))
        assertEquals(0xFF123456.toInt(), ImageEditParsing.parseColor(null, 0xFF123456.toInt()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidColorIsRejected() {
        ImageEditParsing.parseColor("#GGGGGG")
    }

    // ---- scalars ------------------------------------------------------------------------------

    @Test
    fun doublesAreClampedIntoRange() {
        assertEquals(1.0, ImageEditParsing.optDoubleClamped(args("v" to "2"), "v", -1.0, 1.0, 0.0), 0.0)
        assertEquals(-1.0, ImageEditParsing.optDoubleClamped(args("v" to "-5"), "v", -1.0, 1.0, 0.0), 0.0)
        assertEquals(0.5, ImageEditParsing.optDoubleClamped(args("v" to "0.5"), "v", -1.0, 1.0, 0.0), 0.0)
        assertEquals(0.0, ImageEditParsing.optDoubleClamped(args(), "v", -1.0, 1.0, 0.0), 0.0)
    }

    @Test
    fun boolAndPathsAndAlign() {
        assertTrue(ImageEditParsing.optBool(args("t" to "true"), "t"))
        assertFalse(ImageEditParsing.optBool(args("t" to "0"), "t", default = true))
        assertTrue(ImageEditParsing.optBool(args(), "t", default = true))
        assertEquals(listOf("a", "b", "c"), ImageEditParsing.splitPaths("a, b; c"))
        assertEquals(emptyList<String>(), ImageEditParsing.splitPaths(null))
        assertEquals("center", ImageEditParsing.parseAlign("Center"))
        assertEquals("left", ImageEditParsing.parseAlign(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeIntIsRejected() {
        ImageEditParsing.optInt(args("n" to "-1"), "n", min = 0)
    }

    // ---- screen_annotate shapes ---------------------------------------------------------------

    @Test
    fun shapesParseAllSupportedTypes() {
        val json = """
            [
              {"type":"rect","x":1,"y":2,"w":3,"h":4,"color":"#00FF00","filled":true},
              {"type":"ellipse","x":0,"y":0,"w":8,"h":6},
              {"type":"arrow","x1":0,"y1":0,"x2":5,"y2":5,"stroke":6},
              {"type":"text","x":1,"y":1,"text":"hi","size":20,"color":"#FFFFFF"},
              {"type":"highlight","x":0,"y":0,"w":10,"h":10}
            ]
        """.trimIndent()
        val shapes = ImageEditParsing.parseShapes(json)
        assertEquals(5, shapes.size)

        val rect = shapes[0] as RectShape
        assertEquals(1f, rect.x, 0f)
        assertEquals(3f, rect.width, 0f)
        assertTrue(rect.filled)
        assertEquals(0xFF00FF00.toInt(), rect.color)

        val ellipse = shapes[1] as EllipseShape
        assertEquals(8f, ellipse.width, 0f)

        val arrow = shapes[2] as ArrowShape
        assertEquals(5f, arrow.x2, 0f)
        assertEquals(6f, arrow.strokeWidth, 0f)

        val text = shapes[3] as TextShape
        assertEquals("hi", text.text)
        assertEquals(20f, text.size, 0f)

        assertTrue(shapes[4] is HighlightShape)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownShapeTypeIsRejected() {
        ImageEditParsing.parseShapes("""[{"type":"triangle","x":1,"y":1}]""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidShapeJsonIsRejected() {
        ImageEditParsing.parseShapes("not-json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyShapeArrayIsRejected() {
        ImageEditParsing.parseShapes("[]")
    }
}
