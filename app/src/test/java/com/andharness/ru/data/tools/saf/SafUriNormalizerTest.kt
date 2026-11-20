package com.andharness.ru.data.tools.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SafUriNormalizer]. The normalizer is pure Kotlin (no Android imports), so it can
 * be exercised without an Android runtime. These cases mirror BUG-026: a stored tree grant must
 * match the URI reported by `persistedUriPermissions` even when percent-encoding or a trailing
 * slash differs.
 */
class SafUriNormalizerTest {

    private val tree =
        "content://com.android.externalstorage.documents/tree/primary%3AMehaTest"

    @Test
    fun decodesPercentEscapes() {
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary:MehaTest",
            SafUriNormalizer.normalize(tree)
        )
        assertTrue(
            SafUriNormalizer.matches(
                tree,
                "content://com.android.externalstorage.documents/tree/primary:MehaTest"
            )
        )
    }

    @Test
    fun treatsTrailingSlashAsEqual() {
        assertTrue(SafUriNormalizer.matches(tree, "$tree/"))
        assertTrue(SafUriNormalizer.matches("$tree/", "$tree//"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertTrue(SafUriNormalizer.matches(tree, "  $tree  "))
    }

    @Test
    fun keepsSchemeSeparatorIntact() {
        // The "//" after the scheme must survive even when the value is only a scheme.
        assertEquals("content://", SafUriNormalizer.normalize("content://"))
        assertEquals("content:", SafUriNormalizer.normalize("content:/"))
    }

    @Test
    fun distinctUrisDoNotMatch() {
        assertFalse(
            SafUriNormalizer.matches(
                tree,
                "content://com.android.externalstorage.documents/tree/primary%3AMehaTest2"
            )
        )
        assertFalse(
            SafUriNormalizer.matches(
                tree,
                "content://com.android.externalstorage.documents/tree/primary%3AOther"
            )
        )
    }

    @Test
    fun malformedEscapeIsLeftAlone() {
        assertEquals("%", SafUriNormalizer.normalize("%"))
        assertEquals("%ZZ", SafUriNormalizer.normalize("%ZZ"))
        assertEquals("%3", SafUriNormalizer.normalize("%3"))
    }

    @Test
    fun normalizesNestedDocumentTreeUri() {
        // A document URI built with buildDocumentUriUsingTree embeds the tree id and parent id.
        val a =
            "content://com.android.externalstorage.documents/tree/primary%3AMehaTest/document/primary%3AMehaTest"
        val b =
            "content://com.android.externalstorage.documents/tree/primary:MehaTest/document/primary:MehaTest/"
        assertTrue(SafUriNormalizer.matches(a, b))
    }
}
