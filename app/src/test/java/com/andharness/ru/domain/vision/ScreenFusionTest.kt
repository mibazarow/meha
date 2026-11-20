package com.andharness.ru.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFusionTest {

    private val treeFixture = """
        Настройки #settings [click] @0,0-1080,200
          Wi-Fi [click] @0,200-1080,300
          Bluetooth
          Battery
        Поиск #search [edit] @0,300-1080,400
    """.trimIndent()

    private val ocrFixture = """
        Настройки
        Wi-Fi
        Bluetooth
        Уровень заряда 87%
    """.trimIndent()

    @Test
    fun parsesDepthLabelIdRolesAndBounds() {
        val elements = ScreenFusion.parseTree(treeFixture)
        assertEquals(5, elements.size)

        val root = elements[0]
        assertEquals(0, root.depth)
        assertEquals("Настройки", root.label)
        assertEquals("settings", root.id)
        assertEquals(listOf("click"), root.roles)
        assertEquals("0,0-1080,200", root.bounds)

        val wifi = elements[1]
        assertEquals(1, wifi.depth)
        assertEquals("Wi-Fi", wifi.label)
        assertEquals(null, wifi.id)
    }

    @Test
    fun parsesMixedRolesAndEditField() {
        val elements = ScreenFusion.parseTree(
            "Список [scroll] @0,0-100,100\n" +
                "Поле [edit] [click] @0,100-100,200"
        )
        assertEquals(listOf("scroll"), elements[0].roles)
        // Role order is normalised to click → edit → scroll regardless of the input order.
        assertEquals(listOf("click", "edit"), elements[1].roles)
    }

    @Test
    fun ignoresBlankAndMeaninglessTreeLines() {
        val elements = ScreenFusion.parseTree("\n   \n   \n")
        assertTrue(elements.isEmpty())
    }

    @Test
    fun fusionRemovesOcrDuplicatesOfTreeLabels() {
        val fused = ScreenFusion.fuse(treeFixture, ocrFixture)
        assertEquals(5, fused.elementCount)
        assertEquals(4, fused.ocrCount)
        assertEquals(listOf("Уровень заряда 87%"), fused.ocrOnly)
    }

    @Test
    fun fusionAlsoDropsOcrLineThatContainsATreeLabel() {
        val fused = ScreenFusion.fuse("Настройки\n", "Настройки Wi-Fi\nОтдельный текст\n")
        assertEquals(listOf("Отдельный текст"), fused.ocrOnly)
    }

    @Test
    fun shortOcrLinesAreKeptBecauseTheyCannotBeJudged() {
        // "OK" normalizes to 2 chars (< MIN_DEDUP_CHARS) and must survive even if the tree has it.
        val fused = ScreenFusion.fuse("OK [click]\n", "OK\nOK\n")
        assertEquals(1, fused.ocrCount)
        assertEquals(listOf("OK"), fused.ocrOnly)
    }

    @Test
    fun ocrLinesAreDeduplicatedCaseInsensitivelyAndKeepOrder() {
        val fused = ScreenFusion.fuse("", "Готово\nГОТОВО\n  готово  \nВторой\n")
        assertEquals(listOf("Готово", "Второй"), fused.ocrLines)
        assertEquals(listOf("Готово", "Второй"), fused.ocrOnly)
    }

    @Test
    fun emptyTreeKeepsAllOcrLines() {
        val fused = ScreenFusion.fuse("", ocrFixture)
        assertEquals(0, fused.elementCount)
        assertEquals(4, fused.ocrOnlyCount)
    }

    @Test
    fun emptyOcrStillFormatsTheTree() {
        val fused = ScreenFusion.fuse(treeFixture, "")
        assertEquals(0, fused.ocrCount)
        val text = ScreenFusion.format(fused)
        assertTrue(text.contains("Настройки #settings [click] @0,0-1080,200"))
        assertTrue(text.contains("элементов дерева 5"))
        assertTrue(text.contains("только OCR 0"))
    }

    @Test
    fun formatListsElementsInReadingOrderThenOcrOnly() {
        val fused = ScreenFusion.fuse(treeFixture, ocrFixture)
        val text = ScreenFusion.format(fused)
        val wifiIndex = text.indexOf("Wi-Fi [click]")
        val batteryIndex = text.indexOf("Battery")
        val ocrOnlyIndex = text.indexOf("Уровень заряда 87%")
        assertTrue("должен быть элемент Wi-Fi", wifiIndex >= 0)
        assertTrue("Wi-Fi идёт раньше Battery (порядок дерева)", wifiIndex < batteryIndex)
        assertTrue("OCR-only идёт после элементов", batteryIndex < ocrOnlyIndex)
    }

    @Test
    fun formatTruncatesLongAnswersAndSaysHowMuchWasCut() {
        val longTree = (1..80).joinToString("\n") { "Элемент номер $it [click] @0,$it-10,${it + 1}" }
        val fused = ScreenFusion.fuse(longTree, "Уникальный текст только из OCR")
        val text = ScreenFusion.format(fused, maxChars = ScreenFusion.MIN_MAX_CHARS)
        assertTrue(text.contains("сокращено"))
        assertTrue(text.length < 900)
    }

    @Test
    fun formatVerboseAppendsBoundedRawSources() {
        val fused = ScreenFusion.fuse(treeFixture, ocrFixture)
        val text = ScreenFusion.format(fused, verbose = true, rawTree = treeFixture, rawOcr = ocrFixture)
        assertTrue(text.contains("RAW дерево"))
        assertTrue(text.contains("RAW OCR"))
        assertTrue(text.contains("Уровень заряда 87%"))
    }

    @Test
    fun formatWithoutVerboseHasNoRawDump() {
        val fused = ScreenFusion.fuse(treeFixture, ocrFixture)
        val text = ScreenFusion.format(fused, verbose = false, rawTree = treeFixture, rawOcr = ocrFixture)
        assertFalse(text.contains("RAW дерево"))
    }

    @Test
    fun formatClampsMaxCharsUpToTheAcceptedMinimum() {
        val longTree = (1..80).joinToString("\n") { "Строка номер $it @0,$it-1,${it + 1}" }
        val text = ScreenFusion.format(ScreenFusion.fuse(longTree, ""), maxChars = 1)
        assertTrue("бюджет поднят до MIN_MAX_CHARS (500)", text.length > 400)
    }

    @Test
    fun truncateLeavesShortTextUnchanged() {
        assertEquals("abc", ScreenFusion.truncate("abc", 10))
        assertEquals("", ScreenFusion.truncate("", 10))
    }

    @Test
    fun attachDecisionRequiresBothTheFlagAndDefinitiveVisionSupport() {
        assertTrue(ScreenFusion.shouldAttachImage(attachRequested = true, visionSupported = true).attach)
        assertFalse(ScreenFusion.shouldAttachImage(attachRequested = false, visionSupported = true).attach)
        assertFalse(ScreenFusion.shouldAttachImage(attachRequested = true, visionSupported = false).attach)
    }

    @Test
    fun attachRefusalReasonsAreDistinctAndHonest() {
        val notRequested = ScreenFusion.shouldAttachImage(false, false)
        val noVision = ScreenFusion.shouldAttachImage(true, false)
        assertTrue(notRequested.reason.contains("не запрошен"))
        assertTrue(noVision.reason.contains("Vision"))
        assertFalse(notRequested.reason == noVision.reason)
    }

    @Test
    fun normalizeStripsCaseWhitespaceAndPunctuation() {
        assertEquals("wifi", ScreenFusion.normalize("Wi-Fi"))
        assertEquals("настройки", ScreenFusion.normalize("  Настройки!  "))
    }
}
