package com.andharness.ru.data.tools.web

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM (Android-free) tests for the `web_click` by-text JavaScript builder. The builder is pure,
 * so the matching/escaping rules can be asserted without a WebView.
 */
class WebClickJsTest {

    private val text = "Войти"
    private val js = WebContentExtractor.clickJs(selector = null, text = text)

    @Test
    fun byTextSelectorIncludesLabelAriaAndRoles() {
        assertTrue("must query <label> elements", js.contains("label"))
        assertTrue("must query [aria-label] elements", js.contains("[aria-label]"))
        assertTrue("must query [role=link] elements", js.contains("[role=link]"))
        assertTrue("must query [role=menuitem] elements", js.contains("[role=menuitem]"))
        assertTrue("must query [role=tab] elements", js.contains("[role=tab]"))
        assertTrue("must query generic input elements", js.contains("input"))
        assertTrue("must query select elements", js.contains("select"))
        assertTrue("must query option elements", js.contains("option"))
        assertTrue("must query summary elements", js.contains("summary"))
    }

    @Test
    fun textIsJsonEscapedNotInterpolated() {
        val raw = "He said \"hi\" \\ now"
        val built = WebContentExtractor.clickJs(selector = null, text = raw)

        val escaped = WebContentExtractor.jsonStr(raw)
        assertTrue("escaped literal must be embedded: $escaped", built.contains(escaped))
        assertTrue("quote must be backslash-escaped", built.contains("\\\""))
        assertTrue("backslash must be double-escaped", built.contains("\\\\"))
        assertTrue("raw unescaped text must not appear", built.contains(raw).not())
    }

    @Test
    fun matchOrderIsExactThenCaseInsensitiveThenSubstring() {
        val exact = js.indexOf("txt(all[i])===t")
        val ci = js.indexOf("txt(all[j]).toLowerCase()===tl")
        val sub = js.indexOf("if(t&&txt(e).indexOf(t)>=0)")

        assertTrue("exact pass present", exact >= 0)
        assertTrue("case-insensitive pass present", ci > exact)
        assertTrue("substring pass present and last", sub > ci)
    }

    @Test
    fun notFoundFallbackPreserved() {
        assertTrue(js.contains("return 'NOT_FOUND'"))
    }

    @Test
    fun selectorOnlyBuildsSelectorClickJs() {
        val built = WebContentExtractor.clickJs(selector = "a.login", text = null)

        assertTrue(built.contains(WebContentExtractor.jsonStr("a.login")))
        assertTrue(built.contains("document.querySelector"))
        assertTrue(built.contains("return 'NOT_FOUND'"))
    }

    @Test
    fun bothSelectorAndTextAreBothIncluded() {
        val built = WebContentExtractor.clickJs(selector = "#go", text = "Поехали")

        assertTrue(built.contains(WebContentExtractor.jsonStr("#go")))
        assertTrue(built.contains(WebContentExtractor.jsonStr("Поехали")))
        assertTrue(built.contains("return 'NOT_FOUND'"))
    }

    @Test
    fun clickResolvesClickableAncestorAndLabelControl() {
        assertTrue("must use Element.closest", js.contains(".closest(clickable)"))
        assertTrue("must use label.control", js.contains("c.control"))
        assertTrue("must scroll target into view", js.contains("scrollIntoView"))
    }
}
