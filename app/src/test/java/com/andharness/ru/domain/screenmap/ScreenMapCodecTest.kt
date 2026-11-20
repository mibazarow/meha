package com.andharness.ru.domain.screenmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android-free screen-map building blocks: the signature rule, element selection
 * (roles/dedup/cap), the codec bounds and the tool rendering (including the honest "stale cache"
 * warning).
 */
class ScreenMapCodecTest {

    // ---------------------------------------------------------------- signature

    @Test
    fun signatureIsStableForTheSameScreen() {
        val first = ScreenSignature.of("com.app.MainActivity", listOf("Настройки", "Профиль", "Выйти"))
        val second = ScreenSignature.of("com.app.MainActivity", listOf("Настройки", "Профиль", "Выйти"))
        assertEquals(first, second)
        assertTrue(first.startsWith("MainActivity#"))
    }

    @Test
    fun signatureIgnoresCaseAndSurroundingWhitespace() {
        val first = ScreenSignature.of("com.app.MainActivity", listOf("Настройки", "Профиль"))
        val second = ScreenSignature.of("com.app.MainActivity", listOf("  настройки ", "ПРОФИЛЬ"))
        assertEquals(first, second)
    }

    @Test
    fun signatureDiffersForDifferentScreens() {
        val first = ScreenSignature.of("com.app.MainActivity", listOf("Настройки", "Профиль"))
        val second = ScreenSignature.of("com.app.MainActivity", listOf("Настройки", "Корзина"))
        assertNotEquals(first, second)
        val third = ScreenSignature.of("com.app.OtherActivity", listOf("Настройки", "Профиль"))
        assertNotEquals(first, third)
    }

    @Test
    fun signatureUsesOnlyTheTopLabels() {
        val base = (1..ScreenSignature.TOP_LABELS).map { "label$it" }
        val first = ScreenSignature.of("com.app.MainActivity", base + listOf("tail-a"))
        val second = ScreenSignature.of("com.app.MainActivity", base + listOf("tail-b"))
        assertEquals(first, second)

        val changedTop = ScreenSignature.of("com.app.MainActivity", listOf("label0-changed") + base.drop(1))
        assertNotEquals(first, changedTop)
    }

    @Test
    fun signatureFallsBackToLabelsWhenActivityIsUnknown() {
        val signature = ScreenSignature.of(null, listOf("Настройки"))
        assertTrue(signature.startsWith(ScreenSignature.UNKNOWN + "#"))
        assertNotEquals(ScreenSignature.of(null, emptyList()), signature)
    }

    // ---------------------------------------------------------------- elements

    private fun raw(
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = ScreenMapElements.Raw(
        text = text,
        contentDescription = desc,
        resourceId = id,
        className = "android.widget.Button",
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
    )

    @Test
    fun notableKeepsInteractiveAndLabeledIdNodesOnly() {
        val notable = ScreenMapElements.notable(
            listOf(
                raw(text = "Плейн текст"),
                raw(text = "OK", clickable = true),
                raw(text = "Поиск", id = "search"),
                raw(desc = "Назад", clickable = true),
                raw(scrollable = true),
            )
        )
        assertEquals(4, notable.size)
        assertTrue(notable.none { it.text == "Плейн текст" })
    }

    @Test
    fun notableAssignsRolesByPriority() {
        val notable = ScreenMapElements.notable(
            listOf(
                raw(text = "Поле", editable = true, clickable = true),
                raw(text = "Кнопка", clickable = true),
                raw(text = "Список", scrollable = true),
            )
        )
        assertEquals(ScreenElementInfo.ROLE_INPUT, notable[0].role)
        assertEquals(ScreenElementInfo.ROLE_BUTTON, notable[1].role)
        assertEquals(ScreenElementInfo.ROLE_SCROLL, notable[2].role)
    }

    @Test
    fun dedupeAndCapDropsDuplicatesAndEmptyElements() {
        val elements = listOf(
            ScreenElementInfo(text = "OK", resourceId = "ok"),
            ScreenElementInfo(text = "ok", resourceId = "OK"),
            ScreenElementInfo(),
            ScreenElementInfo(text = "  "),
            ScreenElementInfo(text = "Отмена"),
        )
        val result = ScreenMapCodec.dedupeAndCap(elements)
        assertEquals(2, result.size)
        assertEquals("OK", result[0].text)
    }

    @Test
    fun cleanClampsFieldLengthAndRestoresRole() {
        val long = "x".repeat(ScreenMapCodec.MAX_FIELD_CHARS + 50)
        val cleaned = ScreenMapCodec.clean(
            ScreenElementInfo(text = long, role = "  ")
        )
        assertEquals(ScreenMapCodec.MAX_FIELD_CHARS, cleaned.text!!.length)
        assertEquals(ScreenElementInfo.ROLE_TEXT, cleaned.role)
    }

    // ---------------------------------------------------------------- codec bounds

    @Test
    fun decodeClampsScreensAndApps() {
        val screens = (1..ScreenMapCodec.MAX_SCREENS_PER_APP + 5).map {
            ScreenInfo(signature = "S$it#sig", lastSeen = it.toLong())
        }
        val apps = (1..ScreenMapCodec.MAX_APPS + 2).map { AppScreenMap("com.app$it", screens) }
        val decoded = ScreenMapCodec.decode(ScreenMapCodec.encode(apps))

        assertEquals(ScreenMapCodec.MAX_APPS, decoded.size)
        assertEquals(ScreenMapCodec.MAX_SCREENS_PER_APP, decoded.first().screens.size)
    }

    @Test
    fun decodeCorruptOrBlankReturnsEmpty() {
        assertTrue(ScreenMapCodec.decode(null).isEmpty())
        assertTrue(ScreenMapCodec.decode("").isEmpty())
        assertTrue(ScreenMapCodec.decode("not json").isEmpty())
        assertTrue(ScreenMapCodec.decode("[{\"appPackage\":\"\"}]").isEmpty())
    }

    // ---------------------------------------------------------------- titles

    @Test
    fun titleUsesFirstUsableLabelThenActivityShortName() {
        assertEquals("Настройки", ScreenMapTitles.guess(listOf("x", "Настройки", "Профиль"), "com.app.MainActivity"))
        assertEquals("ProfileActivity", ScreenMapTitles.guess(emptyList(), "com.app.ProfileActivity"))
        assertNull(ScreenMapTitles.guess(emptyList(), null))
    }

    // ---------------------------------------------------------------- render

    @Test
    fun summaryListsAppsAndCarriesTheStaleWarning() {
        val maps = listOf(
            AppScreenMap("com.a", listOf(ScreenInfo("A#1", "Экран A", lastSeen = 1_000L))),
            AppScreenMap("com.b", listOf(ScreenInfo("B#1", null, lastSeen = 2_000L))),
        )
        val text = ScreenMapRender.summary(maps, now = 2_000L + 5 * 60_000L)
        assertTrue(text.contains("приложений 2"))
        assertTrue(text.contains("com.a"))
        assertTrue(text.contains("экранов 1"))
        assertTrue(text.contains(ScreenMapRender.STALE_WARNING))
    }

    @Test
    fun emptySummaryIsHonest() {
        val text = ScreenMapRender.summary(emptyList(), now = 1L)
        assertTrue(text.contains("пуста"))
        assertFalse(text.contains(ScreenMapRender.STALE_WARNING))
    }

    @Test
    fun appRenderingShowsElementsAndWarning() {
        val map = AppScreenMap(
            "com.app",
            listOf(
                ScreenInfo(
                    signature = "MainActivity#abcd1234",
                    title = "Настройки",
                    lastSeen = 1_000L,
                    elements = listOf(
                        ScreenElementInfo(text = "OK", resourceId = "ok", className = "android.widget.Button", role = "button"),
                    ),
                )
            ),
        )
        val text = ScreenMapRender.app(map, "com.app", now = 1_000L)
        assertTrue(text.contains("Настройки"))
        assertTrue(text.contains("MainActivity#abcd1234"))
        assertTrue(text.contains("«OK»"))
        assertTrue(text.contains("id=ok"))
        assertTrue(text.contains(ScreenMapRender.STALE_WARNING))
    }

    @Test
    fun appRenderingIsHonestWhenUnknown() {
        val text = ScreenMapRender.app(null, "com.нет", now = 1L)
        assertTrue(text.contains("нет приложения"))
        assertTrue(text.contains("ui_tree"))
    }

    @Test
    fun forgetMessagesAreHonest() {
        assertTrue(ScreenMapRender.forget(0, null).contains("и так пуста"))
        assertTrue(ScreenMapRender.forget(2, null).contains("удалено приложений 2"))
        assertTrue(ScreenMapRender.forget(0, "com.нет").contains("нет приложения"))
        assertTrue(ScreenMapRender.forget(1, "com.app").contains("очищена"))
    }

    @Test
    fun agoIsHumanReadable() {
        assertEquals("только что", ScreenMapRender.ago(now = 1_000L, timestamp = 1_000L))
        assertEquals("5 мин назад", ScreenMapRender.ago(now = 6 * 60_000L, timestamp = 60_000L))
        assertEquals("2 ч назад", ScreenMapRender.ago(now = 3 * 3_600_000L, timestamp = 3_600_000L))
        assertEquals("неизвестно", ScreenMapRender.ago(now = 1L, timestamp = 0L))
    }
}
