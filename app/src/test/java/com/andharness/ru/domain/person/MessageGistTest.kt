package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [MessageGist] (update.md §4.4/§4.9): the persisted summary is always a bounded
 * gist, never the full body.
 */
class MessageGistTest {

    @Test
    fun blankInputYieldsNull() {
        assertNull(MessageGist.bounded(null))
        assertNull(MessageGist.bounded(""))
        assertNull(MessageGist.bounded("   "))
        assertNull(MessageGist.bounded("\n\t  \n"))
    }

    @Test
    fun shortTextIsKeptAsIs() {
        assertEquals("Привет, как дела?", MessageGist.bounded("Привет, как дела?"))
        assertEquals("ok", MessageGist.bounded("  ok  "))
    }

    @Test
    fun whitespaceRunsCollapseIntoASingleSpace() {
        assertEquals(
            "Строка один строка два",
            MessageGist.bounded("Строка один\n\n\t строка   два  "),
        )
    }

    @Test
    fun textOfExactlyMaxCharsIsNotTruncated() {
        val exactly = "a".repeat(MessageGist.MAX_CHARS)
        val gist = MessageGist.bounded(exactly)
        assertEquals(exactly, gist)
        assertEquals(MessageGist.MAX_CHARS, gist!!.length)
    }

    @Test
    fun longTextIsTruncatedToTheCapWithEllipsisInsideTheBudget() {
        val long = "b".repeat(MessageGist.MAX_CHARS + 250)
        val gist = MessageGist.bounded(long)
        requireNotNull(gist)
        assertEquals(MessageGist.MAX_CHARS, gist.length)
        assertTrue("gist must end with an ellipsis", gist.endsWith("…"))
    }

    @Test
    fun longWhitespaceHeavyTextStaysWithinTheCap() {
        val messy = List(200) { "слово$it" }.joinToString(" ")
        val gist = MessageGist.bounded(messy)
        requireNotNull(gist)
        assertTrue(gist.length <= MessageGist.MAX_CHARS)
    }
}
