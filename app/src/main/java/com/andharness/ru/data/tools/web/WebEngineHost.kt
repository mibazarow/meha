package com.andharness.ru.data.tools.web

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import com.andharness.ru.domain.agent.ToolResult
import java.io.File

/**
 * The plumbing of [WebEngine] that its `web_*` tool handlers (package
 * `com.andharness.ru.data.tools.web.handlers`) depend on: tab/session state, the JS/navigation
 * driver, downloads, capture and the pure parsing helpers. Extracted so each handler can live in a
 * named class with a single injected dependency instead of an inline closure over the engine.
 *
 * Every member mirrors the corresponding private member of [WebEngine] exactly; [WebEngine] is the
 * only implementation.
 */
internal interface WebEngineHost {

    /** Application context (used for path resolution and capture fallbacks). */
    val appContext: Context

    /** Live tabs of the session (same list the engine mutates). */
    val tabs: MutableList<WebTab>

    /** Index of the active tab inside [tabs]; -1 when there are none. */
    var activeIndex: Int

    /** Custom User-Agent for the whole session (null → default mobile Chrome UA). */
    var customUserAgent: String?

    /** Runs [block], mapping any non-cancellation exception to an honest failure result. */
    suspend fun guarded(block: suspend () -> ToolResult): ToolResult

    fun activeTab(): WebTab?

    suspend fun ensureActiveTab(): WebTab

    suspend fun createTab(name: String?): WebTab

    suspend fun applyUserAgent(ua: String)

    fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Long?

    suspend fun reportDownload(id: Long, url: String): ToolResult

    suspend fun load(tab: WebTab, url: String): Boolean

    suspend fun evalJs(tab: WebTab, js: String): String?

    suspend fun pageSummary(tab: WebTab): String

    fun tabsSummary(): String

    fun resolveTab(indexArg: String?, nameArg: String?): WebTab?

    fun sessionFile(name: String): File

    fun captureBitmap(view: WebView): Bitmap?

    suspend fun writePngAtomically(bitmap: Bitmap, file: File): Long

    fun parseExtract(raw: String?): String?

    fun decode(raw: String?): String?

    fun isHttpUrl(url: String): Boolean

    fun sourceLabel(s: ImageSearchClient.Src): String

    fun jsonStr(value: String): String
}
