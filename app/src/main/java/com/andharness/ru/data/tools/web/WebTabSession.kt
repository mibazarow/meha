package com.andharness.ru.data.tools.web

/**
 * Owns the tab list, the active-tab index and tab id allocation, plus tab resolution and the human
 * summary used by the `web_tabs_*` tools. One tab is an independent [WebTab].
 */
internal class WebTabSession {

    val tabs = mutableListOf<WebTab>()

    var activeIndex = -1

    private var nextTabId = 1

    fun newId(): Int = nextTabId++

    fun active(): WebTab? = tabs.getOrNull(activeIndex)

    fun summary(): String = buildString {
        appendLine("Вкладки веб-сессии (всего ${tabs.size}, активная $activeIndex):")
        tabs.forEachIndexed { i, tab ->
            val marker = if (i == activeIndex) "*" else " "
            val label = tab.name?.let { " name=\"$it\"" } ?: ""
            val url = tab.url.ifBlank { "about:blank" }
            appendLine("$marker [$i] id=${tab.id}$label $url")
        }
    }.trim()

    fun resolve(indexArg: String?, nameArg: String?): WebTab? {
        if (indexArg != null) {
            val i = indexArg.trim().toIntOrNull()
            if (i != null && i in tabs.indices) return tabs[i]
        }
        if (nameArg != null) return tabs.firstOrNull { it.name == nameArg }
        return null
    }

    /**
     * Destroys every live tab's [android.webkit.WebView] and forgets the tabs. MUST be called on the
     * main thread (`Service.onDestroy` is); used when the hosting service is torn down, so the
     * engine never keeps a view alive after its host is gone.
     */
    fun destroyAll() {
        tabs.forEach { tab -> runCatching { tab.view?.destroy() } }
        tabs.clear()
        activeIndex = -1
    }
}
