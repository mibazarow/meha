package com.andharness.ru.data.tools.web

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.webkit.WebView
import com.andharness.ru.data.tools.web.handlers.ImageSearchHandler
import com.andharness.ru.data.tools.web.handlers.WebBackHandler
import com.andharness.ru.data.tools.web.handlers.WebClickHandler
import com.andharness.ru.data.tools.web.handlers.WebDownloadHandler
import com.andharness.ru.data.tools.web.handlers.WebEvalJsHandler
import com.andharness.ru.data.tools.web.handlers.WebExtractHandler
import com.andharness.ru.data.tools.web.handlers.WebFindHandler
import com.andharness.ru.data.tools.web.handlers.WebForwardHandler
import com.andharness.ru.data.tools.web.handlers.WebNavigateHandler
import com.andharness.ru.data.tools.web.handlers.WebOpenHandler
import com.andharness.ru.data.tools.web.handlers.WebReadableHandler
import com.andharness.ru.data.tools.web.handlers.WebReloadHandler
import com.andharness.ru.data.tools.web.handlers.WebScreenshotHandler
import com.andharness.ru.data.tools.web.handlers.WebScrollHandler
import com.andharness.ru.data.tools.web.handlers.WebScrollUntilHandler
import com.andharness.ru.data.tools.web.handlers.WebSearchHandler
import com.andharness.ru.data.tools.web.handlers.WebSessionLoadHandler
import com.andharness.ru.data.tools.web.handlers.WebSessionSaveHandler
import com.andharness.ru.data.tools.web.handlers.WebSetUserAgentHandler
import com.andharness.ru.data.tools.web.handlers.WebSubmitHandler
import com.andharness.ru.data.tools.web.handlers.WebTabsCloseHandler
import com.andharness.ru.data.tools.web.handlers.WebTabsListHandler
import com.andharness.ru.data.tools.web.handlers.WebTabsNewHandler
import com.andharness.ru.data.tools.web.handlers.WebTabsSwitchHandler
import com.andharness.ru.data.tools.web.handlers.WebTypeHandler
import com.andharness.ru.data.tools.web.handlers.WebWaitHandler
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * An on-device "mini-Playwright": a long-lived offscreen [WebView] (the real Chromium engine of the
 * device) that keeps its session (cookies, DOM, scroll position) between tool calls, plus
 * [ToolHandler]s to drive it.
 *
 * Hosting: the engine is a plain object that owns no `Service`/`Activity`; it is created and torn
 * down on the main thread (a [WebView] needs a thread with a `Looper`). `WebEngineService` hosts a
 * shared instance and keeps the process in the foreground while browsing, so the session survives
 * the app being backgrounded; `WebAutomationToolsHandler` forwards the `web_*` tools to that hosted
 * instance and falls back to an in-process instance when the service cannot be started.
 *
 * Session model: the engine owns a list of **tabs**. Every tab is an independent [WebView] with its
 * own DOM, history and load signal; cookies/storage are shared process-wide (one `CookieManager`),
 * exactly like a real browser profile. The active tab is what the `web_*` tools act on. Creating
 * more tabs just creates more offscreen `WebView`s — no second engine.
 *
 * Tools (each implemented by a named class in `data/tools/web/handlers/`):
 * - session: [com.andharness.ru.data.tools.web.handlers.WebOpenHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebNavigateHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebClickHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebTypeHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebScrollHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebBackHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebForwardHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebReloadHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebSubmitHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebExtractHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebReadableHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebFindHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebScrollUntilHandler]
 * - JS / checks: [com.andharness.ru.data.tools.web.handlers.WebEvalJsHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebWaitHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebScreenshotHandler]
 * - search: [com.andharness.ru.data.tools.web.handlers.WebSearchHandler]
 * - downloads: [com.andharness.ru.data.tools.web.handlers.WebDownloadHandler]
 * - user agent: [com.andharness.ru.data.tools.web.handlers.WebSetUserAgentHandler]
 * - tabs: [com.andharness.ru.data.tools.web.handlers.WebTabsNewHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebTabsSwitchHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebTabsCloseHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebTabsListHandler]
 * - cookies: [com.andharness.ru.data.tools.web.handlers.WebSessionSaveHandler],
 *   [com.andharness.ru.data.tools.web.handlers.WebSessionLoadHandler]
 *
 * References (official Android docs):
 * - `WebView` / `WebViewClient` / `evaluateJavascript`:
 *   https://developer.android.com/reference/android/webkit/WebView
 *   https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)
 *   https://developer.android.com/reference/android/webkit/WebViewClient
 * - `CookieManager` (getCookie/setCookie/flush):
 *   https://developer.android.com/reference/android/webkit/CookieManager
 * - `WebSettings.setUserAgentString`:
 *   https://developer.android.com/reference/android/webkit/WebSettings#setUserAgentString(java.lang.String)
 * - `DownloadListener` / `DownloadManager` (system download queue) / `URLUtil.guessFileName`:
 *   https://developer.android.com/reference/android/webkit/DownloadListener
 *   https://developer.android.com/reference/android/app/DownloadManager
 *   https://developer.android.com/reference/android/webkit/URLUtil#guessFileName(java.lang.String,%20java.lang.String,%20java.lang.String)
 * - WebView must be created/used on a thread with a Looper (main thread) →
 *   [Dispatchers.Main.immediate].
 *
 * Limits (honest): it is NOT the real Playwright — no CDP, and an offscreen `WebView` is not
 * guaranteed to be painted into a bitmap, so [com.andharness.ru.data.tools.web.handlers.WebScreenshotHandler]
 * may return a blank image on some devices. It cannot bypass hard anti-bot WAFs (Cloudflare Turnstile
 * etc.), and when blocked it reports failure instead of fabricating page content.
 */
class WebEngine(
    context: Context,
    private val okHttpClient: OkHttpClient? = null,
) : WebEngineHost {

    override val appContext = context.applicationContext

    /**
     * Context handed to every [WebView]. A `WebView` must be created with a `ContextWrapper` rather
     * than a bare application context; wrapping the application context in a [MutableContextWrapper]
     * is the documented-safe pattern when the engine is hosted outside an Activity (here: inside a
     * foreground service), and it works identically for the in-process fallback.
     * https://developer.android.com/reference/android/content/MutableContextWrapper
     */
    private val webViewContext = MutableContextWrapper(appContext)

    /** Custom User-Agent for the whole session (null → [WebContentExtractor.MOBILE_CHROME_UA]). */
    @Volatile
    override var customUserAgent: String? = null

    /** Tab list / active index / id allocation. */
    private val session = WebTabSession()

    /** Pure decoding/parsing + page-side JS snippets. */
    private val extractor = WebContentExtractor

    /** System DownloadManager integration. */
    private val downloads = WebDownloadManager(appContext)

    /** Screenshot capture. */
    private val capture = WebPageCapture(appContext)

    /** Named cookie-session files. */
    private val sessionStore = WebSessionStore(appContext)

    /** Search-results extraction. */
    private val search = WebSearchClient(okHttpClient)

    /** Keyless CC image search (Openverse / Wikimedia). */
    private val imageSearch = ImageSearchClient(okHttpClient)

    /** DOM/eval + navigation driver for the offscreen [WebView]s. */
    private val driver = WebViewDriver(
        appContext = appContext,
        webViewContext = webViewContext,
        userAgent = { customUserAgent },
        onDownload = { url, userAgent, contentDisposition, mimeType ->
            enqueueDownload(url, userAgent ?: customUserAgent, contentDisposition, mimeType)
        },
    )

    override val tabs: MutableList<WebTab> get() = session.tabs

    override var activeIndex: Int
        get() = session.activeIndex
        set(value) {
            session.activeIndex = value
        }

    /**
     * Every `web_*` tool handler, built once and cached. Building the list is cheap and creates no
     * [WebView] — the real views appear only on the main thread on first use of a tool.
     * The order of the list is the tool order and must not change.
     */
    val handlerList: List<ToolHandler> by lazy { buildHandlers() }

    private val handlersByName: Map<String, ToolHandler> by lazy { handlerList.associateBy { it.name } }

    /** The engine's tool handlers (same instances as [handlerList]). */
    fun handlers(): List<ToolHandler> = handlerList

    /** Names of every `web_*` tool this engine implements, in declaration order. */
    fun handlerNames(): List<String> = handlerList.map { it.name }

    /** Resolves one tool handler by name (null when this engine does not implement it). */
    fun handler(name: String): ToolHandler? = handlersByName[name]

    /**
     * Destroys every live tab's [WebView] and forgets the tabs. MUST be called on the main thread
     * (`Service.onDestroy` is); used when the hosting service is torn down, so the engine never
     * keeps a view alive after its host is gone.
     */
    fun destroyAll() = session.destroyAll()

    private fun buildHandlers(): List<ToolHandler> = listOf(
        WebOpenHandler(this),
        WebNavigateHandler(this),
        WebWaitHandler(this),
        WebClickHandler(this),
        WebTypeHandler(this),
        WebScrollHandler(this),
        WebScrollUntilHandler(this),
        WebBackHandler(this),
        WebExtractHandler(this),
        WebReadableHandler(this),
        WebEvalJsHandler(this),
        WebFindHandler(this),
        WebScreenshotHandler(this),
        WebSearchHandler(this, search),
        ImageSearchHandler(this, imageSearch),
        WebTabsNewHandler(this),
        WebTabsSwitchHandler(this),
        WebTabsCloseHandler(this),
        WebTabsListHandler(this),
        WebSessionSaveHandler(this),
        WebSessionLoadHandler(this),
        WebForwardHandler(this),
        WebReloadHandler(this),
        WebSubmitHandler(this),
        WebSetUserAgentHandler(this),
        WebDownloadHandler(this),
    )

    // ---- tab / session plumbing ----

    override fun activeTab(): WebTab? = session.active()

    override suspend fun ensureActiveTab(): WebTab = activeTab() ?: createTab(name = null)

    override suspend fun createTab(name: String?): WebTab = withContext(Dispatchers.Main.immediate) {
        val tab = WebTab(session.newId(), name)
        tab.view = driver.buildView(tab)
        tabs += tab
        activeIndex = tabs.lastIndex
        tab
    }

    /** Applies [ua] to every live tab (main thread); new tabs pick it up from [customUserAgent]. */
    override suspend fun applyUserAgent(ua: String) = withContext(Dispatchers.Main.immediate) {
        tabs.forEach { tab -> tab.view?.settings?.userAgentString = ua }
    }

    // ---- downloads ----

    /**
     * Enqueues a download with the system [android.app.DownloadManager] into the public «Загрузки» folder.
     * Returns the download id, or null when the manager is unavailable / the request is rejected.
     *
     * Official docs: `DownloadManager.Request.setDestinationInExternalPublicDir` and
     * `URLUtil.guessFileName`:
     * https://developer.android.com/reference/android/app/DownloadManager.Request#setDestinationInExternalPublicDir(java.lang.String,%20java.lang.String)
     * https://developer.android.com/reference/android/webkit/URLUtil#guessFileName(java.lang.String,%20java.lang.String,%20java.lang.String)
     */
    override fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Long? = downloads.enqueue(url, userAgent, contentDisposition, mimeType)

    override suspend fun reportDownload(id: Long, url: String): ToolResult = downloads.report(id, url)

    override suspend fun load(tab: WebTab, url: String): Boolean = driver.load(tab, url)

    /**
     * Runs [js] in [tab]'s WebView and returns its result. The timeout bound lives in
     * [WebViewDriver.evalJs]: a lost `evaluateJavascript` callback must never hang a tool call.
     */
    override suspend fun evalJs(tab: WebTab, js: String): String? = driver.evalJs(tab, js)

    override suspend fun pageSummary(tab: WebTab): String = driver.pageSummary(tab)

    override fun tabsSummary(): String = session.summary()

    override fun resolveTab(indexArg: String?, nameArg: String?): WebTab? = session.resolve(indexArg, nameArg)

    override fun sessionFile(name: String): File = sessionStore.file(name)

    override fun captureBitmap(view: WebView): Bitmap? = capture.capture(view)

    override suspend fun writePngAtomically(bitmap: Bitmap, file: File): Long =
        capture.writePngAtomically(bitmap, file)

    // ---- parsing / helpers ----

    /** [raw] is the already-decoded inner JSON text `{"title":..,"text":..}` (see [decode]). */
    override fun parseExtract(raw: String?): String? = extractor.parseExtract(raw)

    /** evaluateJavascript returns a JS string literal; decode the JSON string wrapper. */
    override fun decode(raw: String?): String? = extractor.decode(raw)

    override fun isHttpUrl(url: String): Boolean = extractor.isHttpUrl(url)

    override fun sourceLabel(s: ImageSearchClient.Src): String = when (s) {
        ImageSearchClient.Src.WIKIMEDIA -> "Wikimedia Commons"
        ImageSearchClient.Src.OPENVERSE -> "Openverse"
    }

    override fun jsonStr(value: String): String = extractor.jsonStr(value)

    override suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Cancellation is NOT an ordinary tool failure: swallowing it here would defeat every
        // timeout/stop in the caller (the tool would "return" a result after the run was aborted).
        throw e
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
}
