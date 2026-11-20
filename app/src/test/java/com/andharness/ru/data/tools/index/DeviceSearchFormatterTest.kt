package com.andharness.ru.data.tools.index

import com.andharness.ru.data.index.DeviceIndexKind
import com.andharness.ru.data.index.DeviceSearchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DeviceSearchFormatter]: the actionable rendering of a `device_search` hit (plain
 * URI, optional absolute path, optional MediaStore id) and the footer that advertises the
 * `media_open` / `media_share` tools. Pure — no Android types.
 */
class DeviceSearchFormatterTest {

    private fun image(uri: String = "content://media/external/images/media/423") = DeviceSearchResult(
        kind = DeviceIndexKind.IMAGE,
        sourceUri = uri,
        displayName = "beach.jpg",
        snippet = "",
        score = 0.9,
        mimeType = "image/jpeg",
        chunkIndex = 0,
    )

    private fun document(snippet: String = "договор аренды срок действия") = DeviceSearchResult(
        kind = DeviceIndexKind.DOC_CHUNK,
        sourceUri = "content://media/external/file/9",
        displayName = "lease.pdf",
        snippet = snippet,
        score = 0.8,
        mimeType = "application/pdf",
        chunkIndex = 0,
    )

    @Test
    fun imageLineCarriesNameUriAndMediaStoreId() {
        val line = DeviceSearchFormatter.formatLine(image())
        assertTrue(line, line.contains("[фото]"))
        assertTrue(line, line.contains("beach.jpg"))
        assertTrue(line, line.contains("URI: content://media/external/images/media/423"))
        assertTrue(line, line.contains("(id 423)"))
    }

    @Test
    fun absolutePathShownWhenPresent() {
        val line = DeviceSearchFormatter.formatLine(image(), "/storage/emulated/0/DCIM/beach.jpg")
        assertTrue(line, line.contains("(путь: /storage/emulated/0/DCIM/beach.jpg)"))
    }

    @Test
    fun blankAbsolutePathIsOmitted() {
        assertFalse(DeviceSearchFormatter.formatLine(image(), "   ").contains("путь"))
        assertFalse(DeviceSearchFormatter.formatLine(image(), null).contains("путь"))
    }

    @Test
    fun documentLineHasSnippetAndNoId() {
        val line = DeviceSearchFormatter.formatLine(document())
        assertTrue(line, line.contains("[документ]"))
        assertTrue(line, line.contains("URI: content://media/external/file/9"))
        assertTrue(line, line.contains("договор аренды срок действия"))
        assertFalse(line, line.contains("(id "))
    }

    @Test
    fun longSnippetIsCollapsedAndTruncated() {
        val line = DeviceSearchFormatter.formatLine(document("x".repeat(500)))
        assertTrue(line, line.contains("…"))
        assertTrue("line too long: ${line.length}", line.length < 400)
    }

    @Test
    fun footerPointsAtActionTools() {
        val footer = DeviceSearchFormatter.footer()
        assertTrue(footer, footer.contains("media_open"))
        assertTrue(footer, footer.contains("media_share"))
    }
}
