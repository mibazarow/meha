package com.andharness.ru.data.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the OSINT budget/parsing (DoD §10: «парсинг OSINT-бюджета (≤5 источников/15 с)»).
 * No network, no Android runtime.
 */
class OsintBudgetTest {

    private fun source(n: Int) = OsintSource(title = "t$n", url = "https://example.com/$n")

    @Test
    fun budgetIsFifteenSecondsAndFiveSources() {
        assertEquals(15_000L, OsintBudget.TIME_BUDGET_MS)
        assertEquals(5, OsintBudget.MAX_SOURCES)
    }

    @Test
    fun takeSourcesCapsAtFive() {
        val nine = (1..9).map { source(it) }
        val capped = OsintBudget.takeSources(nine)
        assertEquals(5, capped.size)
        assertEquals("https://example.com/1", capped.first().url)
        assertEquals("https://example.com/5", capped.last().url)
    }

    @Test
    fun expiryAndRemainingBudgetAreExact() {
        assertFalse(OsintBudget.isExpired(startedAt = 0L, now = 14_999L))
        assertTrue(OsintBudget.isExpired(startedAt = 0L, now = 15_000L))
        assertEquals(15_000L, OsintBudget.remainingMs(0L, 0L))
        assertEquals(1L, OsintBudget.remainingMs(0L, 14_999L))
        assertEquals(0L, OsintBudget.remainingMs(0L, 20_000L))
        assertTrue(OsintBudget.canFetchMore(0L, 13_000L))
        assertFalse(OsintBudget.canFetchMore(0L, 14_000L))
    }

    @Test
    fun parsesDuckDuckGoHtmlAndUnwrapsRedirects() {
        val html = """
            <html><body>
              <div class="result">
                <a rel="nofollow" class="result__a"
                   href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone&amp;rut=abc">First Result</a>
                <a class="result__snippet" href="https://example.com/one">Snippet one</a>
              </div>
              <div class="result">
                <a rel="nofollow" class="result__a" href="https://example.org/two">Second Result</a>
                <a class="result__snippet">Snippet two</a>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = OsintParsing.parseSearchHtml(html)
        assertEquals(2, parsed.size)
        assertEquals("https://example.com/one", parsed[0].url)
        assertEquals("First Result", parsed[0].title)
        assertEquals("Snippet one", parsed[0].snippet)
        assertEquals("https://example.org/two", parsed[1].url)
        assertEquals("Snippet two", parsed[1].snippet)
    }

    @Test
    fun parsesAtMostFiveFromALargerPage() {
        val items = (1..12).joinToString("\n") {
            """<a class="result__a" href="https://example.com/$it">Title $it</a>"""
        }
        val parsed = OsintParsing.parseSearchHtml("<html><body>$items</body></html>")
        assertEquals(5, OsintBudget.takeSources(parsed).size)
    }

    @Test
    fun extractSummaryIsBoundedAndNullWhenTooShort() {
        assertNull(OsintParsing.extractSummary("<html><body>short</body></html>"))
        val long = "<html><body>" + "a".repeat(1000) + "</body></html>"
        val summary = OsintParsing.extractSummary(long)
        assertNotNull(summary)
        assertTrue(summary!!.length <= OsintBudget.MAX_SUMMARY_CHARS)
    }

    @Test
    fun searchUrlEncodesTheQuery() {
        val url = OsintParsing.searchUrl("+7 999 телефон")
        assertTrue(url.startsWith("https://html.duckduckgo.com/html/?q="))
        assertFalse(url.contains(" "))
    }
}
