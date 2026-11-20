package com.andharness.ru.data.screenmap

import com.andharness.ru.domain.screenmap.ScreenElementInfo
import com.andharness.ru.domain.screenmap.ScreenMapCodec
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [ScreenMapStore]. It is Android-free by construction (it only takes a
 * directory), so real file CRUD, the app/screen/element bounds, eviction, dedup and corrupt-file
 * degradation are all exercised here on a temp directory — no Room/Android runtime involved.
 */
class ScreenMapStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("screen-map-store-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = ScreenMapStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun element(
        text: String? = null,
        id: String? = null,
        role: String = ScreenElementInfo.ROLE_BUTTON,
    ) = ScreenElementInfo(text = text, resourceId = id, role = role)

    @Test
    fun recordGetListRoundTrip() {
        val store = store()
        assertTrue(store.list().isEmpty())
        assertNull(store.get("com.app"))

        assertTrue(store.record("com.app", "MainActivity#abcd1234", listOf(element("OK", "ok")), now = 100L))

        val map = store.get("com.app")
        assertNotNull(map)
        assertEquals("com.app", map!!.appPackage)
        assertEquals(1, map.screens.size)
        assertEquals("MainActivity#abcd1234", map.screens.first().signature)
        assertEquals(100L, map.screens.first().lastSeen)
        assertEquals(1, store.list().size)
    }

    @Test
    fun blankPackageOrSignatureIsRejected() {
        val store = store()
        assertFalse(store.record("", "sig", emptyList(), now = 1L))
        assertFalse(store.record("com.app", "  ", emptyList(), now = 1L))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun recordUpsertsSameSignatureAndMovesItLast() {
        val store = store()
        store.record("com.app", "A#1", listOf(element("first")), now = 10L)
        store.record("com.app", "B#2", listOf(element("second")), now = 20L)
        // Re-observing A must replace it (not duplicate) and move it to the newest end.
        store.record("com.app", "A#1", listOf(element("first-updated")), now = 30L)

        val screens = store.get("com.app")!!.screens
        assertEquals(2, screens.size)
        assertEquals(listOf("B#2", "A#1"), screens.map { it.signature })
        assertEquals("first-updated", screens.last().elements.first().text)
        assertEquals(30L, screens.last().lastSeen)
    }

    @Test
    fun keepsExistingTitleWhenNoNewTitleIsGiven() {
        val store = store()
        store.record("com.app", "A#1", emptyList(), now = 1L, title = "Настройки")
        store.record("com.app", "A#1", emptyList(), now = 2L, title = null)
        assertEquals("Настройки", store.get("com.app")!!.screens.first().title)
        store.record("com.app", "A#1", emptyList(), now = 3L, title = "Профиль")
        assertEquals("Профиль", store.get("com.app")!!.screens.first().title)
    }

    @Test
    fun elementsAreDedupedAndCappedPerScreen() {
        val store = store()
        val duplicated = listOf(
            element("OK", "ok"),
            element("OK", "ok"),
            element("Отмена", "cancel"),
        )
        store.record("com.app", "A#1", duplicated, now = 1L)
        assertEquals(2, store.get("com.app")!!.screens.first().elements.size)

        val many = (1..ScreenMapCodec.MAX_ELEMENTS_PER_SCREEN + 10).map { element("item$it", "id$it") }
        store.record("com.app", "B#2", many, now = 2L)
        assertEquals(
            ScreenMapCodec.MAX_ELEMENTS_PER_SCREEN,
            store.get("com.app")!!.screens.first { it.signature == "B#2" }.elements.size,
        )
    }

    @Test
    fun screensPerAppAreBoundedAndOldestEvicted() {
        val store = store()
        val total = ScreenMapCodec.MAX_SCREENS_PER_APP + 3
        for (i in 1..total) {
            store.record("com.app", "Screen$i#sig", listOf(element("s$i")), now = i.toLong())
        }
        val screens = store.get("com.app")!!.screens
        assertEquals(ScreenMapCodec.MAX_SCREENS_PER_APP, screens.size)
        assertFalse(screens.any { it.signature == "Screen1#sig" })
        assertEquals("Screen${total}#sig", screens.last().signature)
    }

    @Test
    fun appsAreBoundedAndLeastRecentlySeenEvicted() {
        val store = store()
        val total = ScreenMapCodec.MAX_APPS + 2
        for (i in 1..total) {
            store.record("com.app$i", "Main#sig", emptyList(), now = i.toLong())
        }
        assertEquals(ScreenMapCodec.MAX_APPS, store.list().size)
        assertNull(store.get("com.app1"))
        assertNotNull(store.get("com.app$total"))
    }

    @Test
    fun getIsCaseInsensitiveAsFallback() {
        val store = store()
        store.record("com.App", "A#1", emptyList(), now = 1L)
        assertNotNull(store.get("com.app"))
    }

    @Test
    fun forgetOneAppThenAll() {
        val store = store()
        store.record("com.a", "A#1", emptyList(), now = 1L)
        store.record("com.b", "B#1", emptyList(), now = 2L)

        assertEquals(1, store.forget("com.a"))
        assertEquals(0, store.forget("com.нет"))
        assertNull(store.get("com.a"))
        assertNotNull(store.get("com.b"))

        assertEquals(1, store.forget(null))
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.forget(null))
    }

    @Test
    fun statePersistsAcrossInstances() {
        val dir = newDir()
        ScreenMapStore(dir).record("com.app", "A#1", listOf(element("OK", "ok")), now = 42L)

        val reopened = ScreenMapStore(dir)
        val map = reopened.get("com.app")
        assertNotNull(map)
        assertEquals(1, map!!.screens.size)
        assertEquals("OK", map.screens.first().elements.first().text)
        assertEquals(42L, map.screens.first().lastSeen)
    }

    @Test
    fun corruptFileDegradesToEmptyInsteadOfThrowing() {
        val dir = newDir()
        dir.mkdirs()
        File(dir, ScreenMapStore.FILE_NAME).writeText("{ not valid json ]")

        val store = ScreenMapStore(dir)
        assertTrue(store.list().isEmpty())
        // And it is usable afterwards.
        assertTrue(store.record("com.app", "A#1", emptyList(), now = 1L))
        assertEquals(1, store.list().size)
    }

    @Test
    fun observableStateReflectsWrites() {
        val store = store()
        store.record("com.app", "A#1", emptyList(), now = 1L)
        assertEquals(1, store.maps.value.size)
        store.forget(null)
        assertTrue(store.maps.value.isEmpty())
    }
}
