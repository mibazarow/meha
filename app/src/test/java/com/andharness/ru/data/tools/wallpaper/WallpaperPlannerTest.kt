package com.andharness.ru.data.tools.wallpaper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистых helper'ов [wallpaper_set]. Сам Android-слой (WallpaperManager, BitmapFactory,
 * OkHttp) здесь не тестируется — он требует реального устройства/Robolectric и проверяется
 * отдельно on-device.
 */
class WallpaperPlannerTest {

    // ---- target ----

    @Test
    fun missingOrBlankTargetDefaultsToHome() {
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget(null))
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget(""))
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget("   "))
    }

    @Test
    fun targetIsParsedCaseInsensitivelyAndTrimmed() {
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget("home"))
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget("HOME"))
        assertEquals(WallpaperTarget.HOME, parseWallpaperTarget("  Home  "))
        assertEquals(WallpaperTarget.LOCK, parseWallpaperTarget("lock"))
        assertEquals(WallpaperTarget.LOCK, parseWallpaperTarget("LOCK"))
        assertEquals(WallpaperTarget.BOTH, parseWallpaperTarget("both"))
    }

    @Test
    fun unknownTargetIsRejectedInsteadOfGuessed() {
        assertEquals(null, parseWallpaperTarget("screen"))
        assertEquals(null, parseWallpaperTarget("home+lock"))
    }

    // ---- target -> parts (флаги WallpaperManager) ----

    @Test
    fun targetMapsToTheExpectedWallpaperParts() {
        assertEquals(setOf(WallpaperPart.HOME), wallpaperParts(WallpaperTarget.HOME))
        assertEquals(setOf(WallpaperPart.LOCK), wallpaperParts(WallpaperTarget.LOCK))
        assertEquals(
            setOf(WallpaperPart.HOME, WallpaperPart.LOCK),
            wallpaperParts(WallpaperTarget.BOTH),
        )
    }

    @Test
    fun targetDescriptionIsRussianAndMatchesTheParts() {
        assertEquals("рабочий стол", wallpaperTargetDescription(WallpaperTarget.HOME))
        assertEquals("экран блокировки", wallpaperTargetDescription(WallpaperTarget.LOCK))
        assertEquals(
            "рабочий стол и экран блокировки",
            wallpaperTargetDescription(WallpaperTarget.BOTH),
        )
    }

    // ---- источник ----

    @Test
    fun pathOnlyIsALocalSource() {
        val source = selectWallpaperSource("Pictures/cat.jpg", null)
        assertEquals(WallpaperSource.LocalPath("Pictures/cat.jpg"), source)
    }

    @Test
    fun urlOnlyIsARemoteSource() {
        val source = selectWallpaperSource(null, "https://example.com/cat.jpg")
        assertEquals(WallpaperSource.RemoteUrl("https://example.com/cat.jpg"), source)
    }

    @Test
    fun sourceIsTrimmedAndBlankIsTreatedAsAbsent() {
        assertEquals(
            WallpaperSource.LocalPath("a.png"),
            selectWallpaperSource("  a.png  ", "   "),
        )
        assertEquals(
            WallpaperSource.RemoteUrl("https://x/y.png"),
            selectWallpaperSource("   ", " https://x/y.png "),
        )
    }

    @Test
    fun missingSourceIsAnHonestError() {
        val source = selectWallpaperSource(null, null)
        assertTrue(source is WallpaperSource.Invalid)
        assertTrue(
            (source as WallpaperSource.Invalid).message.contains("path"),
        )
    }

    @Test
    fun bothSourcesAtOnceAreRejected() {
        val source = selectWallpaperSource("a.png", "https://x/y.png")
        assertTrue(source is WallpaperSource.Invalid)
        assertTrue(
            (source as WallpaperSource.Invalid).message.contains("только один источник"),
        )
    }

    // ---- локальный файл-источник ----

    @Test
    fun existingRegularFilePassesValidation() {
        val f = File.createTempFile("wpfile", ".jpg").apply { deleteOnExit() }
        writeBytes(f, 1024)
        assertEquals(null, validateLocalWallpaperFile(f))
    }

    @Test
    fun missingFileIsAnHonestError() {
        val f = File(tempDir(), "does-not-exist-abc.jpg")
        val err = validateLocalWallpaperFile(f)
        assertTrue(err != null)
        assertTrue((err as String).contains("не найден"))
    }

    @Test
    fun directoryIsRejectedAsNotAFile() {
        val d = File(tempDir(), "wp-dir-${System.nanoTime()}").apply { mkdirs() }
        val err = validateLocalWallpaperFile(d)
        assertTrue(err != null)
        assertTrue((err as String).contains("Не файл"))
        d.delete()
    }

    private fun writeBytes(f: File, size: Int) {
        f.outputStream().use { it.write(ByteArray(size)) }
    }

    private fun tempDir(): String =
        System.getProperty("java.io.tmpdir")

    // ---- inSampleSize ----

    @Test
    fun inSampleSizeIsAPowerOfTwoThatPreservesTheRequestedFloor() {
        assertEquals(4, calculateInSampleSize(2048, 1536, 512, 384))
        assertEquals(8, calculateInSampleSize(1000, 1000, 100, 100))
    }

    @Test
    fun inSampleSizeIsOneWhenNoSubsamplingIsNeeded() {
        assertEquals(1, calculateInSampleSize(100, 100, 1080, 1920))
        assertEquals(1, calculateInSampleSize(0, 0, 100, 100))
        assertEquals(1, calculateInSampleSize(100, 100, 0, 0))
    }

    // ---- fit ----

    @Test
    fun fitWithinScalesDownPreservingAspectRatio() {
        assertEquals(1080 to 540, fitWithin(2000, 1000, 1080, 1920))
    }

    @Test
    fun fitWithinNeverUpscalesOrReturnsZero() {
        assertEquals(500 to 400, fitWithin(500, 400, 1080, 1920))
        assertEquals(1 to 1, fitWithin(0, 100, 1080, 1920))
        assertEquals(100 to 100, fitWithin(100, 100, 0, 0))
    }
}
