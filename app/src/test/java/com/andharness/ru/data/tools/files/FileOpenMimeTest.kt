package com.andharness.ru.data.tools.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [FileOpenMime]: the MIME → family mapping and the curated Google Play fallback list.
 * Both are pure (no Android), so they run on the JVM without a Context or Robolectric.
 */
class FileOpenMimeTest {

    // ---- family mapping ----

    @Test
    fun mapsRepresentativeMimes() {
        assertEquals(FileOpenMime.Family.PDF, FileOpenMime.family("application/pdf"))
        assertEquals(FileOpenMime.Family.VIDEO, FileOpenMime.family("video/mp4"))
        assertEquals(FileOpenMime.Family.AUDIO, FileOpenMime.family("audio/mpeg"))
        assertEquals(FileOpenMime.Family.IMAGE, FileOpenMime.family("image/png"))
        assertEquals(FileOpenMime.Family.TEXT, FileOpenMime.family("text/plain"))
        assertEquals(FileOpenMime.Family.TEXT, FileOpenMime.family("application/json"))
        assertEquals(FileOpenMime.Family.OFFICE, FileOpenMime.family("application/msword"))
        assertEquals(
            FileOpenMime.Family.OFFICE,
            FileOpenMime.family("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        )
        assertEquals(
            FileOpenMime.Family.OFFICE,
            FileOpenMime.family("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        )
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.family("application/zip"))
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.family("application/vnd.rar"))
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.family("application/x-7z-compressed"))
        assertEquals(FileOpenMime.Family.EBOOK, FileOpenMime.family("application/epub+zip"))
        assertEquals(FileOpenMime.Family.OTHER, FileOpenMime.family("application/x-unknown-thing"))
    }

    @Test
    fun ignoresParametersAndCase() {
        assertEquals(FileOpenMime.Family.TEXT, FileOpenMime.family("TEXT/PLAIN; charset=utf-8"))
        assertEquals(FileOpenMime.Family.IMAGE, FileOpenMime.family(" Image/JPEG ; q=1"))
    }

    @Test
    fun blankAndGenericMapToOther() {
        assertEquals(FileOpenMime.Family.OTHER, FileOpenMime.family(""))
        assertEquals(FileOpenMime.Family.OTHER, FileOpenMime.family("   "))
        assertEquals(FileOpenMime.Family.OTHER, FileOpenMime.family("*/*"))
    }

    // ---- Play suggestions ----

    @Test
    fun everyFamilyHasThreeToFiveDistinctSuggestions() {
        for (family in FileOpenMime.Family.values()) {
            val suggestions = FileOpenMime.playSuggestions(family)
            assertTrue(
                "$family must have 3..5 suggestions, has ${suggestions.size}",
                suggestions.size in 3..5
            )
            val names = suggestions.map { it.app }
            assertEquals("duplicate app names in $family: $names", names.size, names.toSet().size)
            names.forEach { assertTrue("empty app name in $family", it.isNotBlank()) }
        }
    }

    @Test
    fun suggestionUrlsAreTappablePlaySearchLinks() {
        for (family in FileOpenMime.Family.values()) {
            for (suggestion in FileOpenMime.playSuggestions(family)) {
                assertTrue(
                    "not a Play search link: ${suggestion.url}",
                    suggestion.url.startsWith("https://play.google.com/store/search?q=")
                )
                assertTrue("missing app-only constraint: ${suggestion.url}", suggestion.url.endsWith("&c=apps"))
                val query = suggestion.url.substringAfter("?q=").substringBefore("&c=apps")
                assertFalse("empty Play query for ${suggestion.app}", query.isBlank())
            }
        }
    }

    @Test
    fun spacesInAppNamesAreEncoded() {
        // "Adobe Acrobat Reader" must not leak a raw space into the URL.
        val url = FileOpenMime.playSuggestions(FileOpenMime.Family.PDF).first().url
        assertFalse("raw space in $url", url.contains(' '))
    }

    @Test
    fun suggestionsExposeMarketDeepLinksAndWebFallback() {
        for (family in FileOpenMime.Family.values()) {
            for (suggestion in FileOpenMime.playSuggestions(family)) {
                assertTrue(
                    "not a market deep link for ${suggestion.app}: ${suggestion.market}",
                    suggestion.market.startsWith("market://search?q=")
                )
                assertFalse("raw space in ${suggestion.market}", suggestion.market.contains(' '))
                assertTrue(
                    "web fallback missing for ${suggestion.app}",
                    suggestion.url.startsWith("https://play.google.com/store/search?q=")
                )
            }
        }
    }

    // ---- format resolution (mime / extension / friendly kind / free text) ----

    @Test
    fun resolvesMimeExtensionsAndKinds() {
        assertEquals(FileOpenMime.Family.PDF, FileOpenMime.resolve("application/pdf").family)
        assertEquals(FileOpenMime.Family.PDF, FileOpenMime.resolve("pdf").family)
        assertEquals(FileOpenMime.Family.PDF, FileOpenMime.resolve(".PDF").family)
        assertEquals(FileOpenMime.Family.OFFICE, FileOpenMime.resolve("docx").family)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileOpenMime.resolve("docx").mime
        )
        assertEquals(FileOpenMime.Family.OFFICE, FileOpenMime.resolve("office").family)
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.resolve("rar").family)
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.resolve("7z").family)
        assertEquals(FileOpenMime.Family.IMAGE, FileOpenMime.resolve("jpg").family)
        assertEquals(FileOpenMime.Family.AUDIO, FileOpenMime.resolve("audio").family)
        assertEquals(FileOpenMime.Family.VIDEO, FileOpenMime.resolve("video").family)
        assertEquals(FileOpenMime.Family.EBOOK, FileOpenMime.resolve("epub").family)
        assertTrue(FileOpenMime.resolve("video").matched)
    }

    @Test
    fun resolvesFreeTextTaskInRussianAndEnglish() {
        assertEquals(FileOpenMime.Family.VIDEO, FileOpenMime.resolve("открой видео файл").family)
        assertEquals(FileOpenMime.Family.IMAGE, FileOpenMime.resolve("open a photo").family)
        assertEquals(FileOpenMime.Family.ARCHIVE, FileOpenMime.resolve("распакуй архив").family)
        assertEquals(FileOpenMime.Family.OFFICE, FileOpenMime.resolve("edit a word document").family)
    }

    @Test
    fun unknownInputIsHonestlyUnmatched() {
        val resolution = FileOpenMime.resolve("что-то совсем непонятное qwerty")
        assertFalse(resolution.matched)
        assertEquals(null, resolution.mime)
        assertEquals(FileOpenMime.Family.OTHER, resolution.family)
        assertFalse(FileOpenMime.resolve("").matched)
    }
}
