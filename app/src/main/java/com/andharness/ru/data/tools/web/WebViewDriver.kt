package com.andharness.ru.data.tools.web

import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DOM/eval + navigation driver for one session: builds the offscreen tab [WebView]s, runs
 * JavaScript and loads URLs, all on [Dispatchers.Main.immediate] (a `WebView` needs a thread with a
 * `Looper`).
 *
 * References (official Android docs):
 * - `WebView` / `WebViewClient` / `evaluateJavascript`:
 *   https://developer.android.com/reference/android/webkit/WebView
 *   https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)
 *   https://developer.android.com/reference/android/webkit/WebViewClient
 */
internal class WebViewDriver(
    private val appContext: Context,
    private val webViewContext: MutableContextWrapper,
    private val userAgent: () -> String?,
    private val onDownload: (url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) -> Unit,
) {

    /** Creates the offscreen [WebView] for [tab] (main thread). Each tab has its own client/signal. */
    fun buildView(tab: WebTab): WebView {
        val view = WebView(webViewContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.userAgentString = userAgent() ?: WebContentExtractor.MOBILE_CHROME_UA
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String?) {
                    tab.url = url ?: tab.url
                    if (!tab.signal.isCompleted) tab.signal.complete(true)
                }

                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !tab.signal.isCompleted) tab.signal.complete(false)
                }
            }
            // `DownloadListener` is the only hook for downloads a page triggers itself; the system
            // DownloadManager does the actual writing (we never fabricate a "saved" path):
            // https://developer.android.com/reference/android/webkit/DownloadListener
            setDownloadListener { url, ua, contentDisposition, mimeType, _ ->
                if (WebContentExtractor.isHttpUrl(url)) {
                    onDownload(url, ua ?: userAgent(), contentDisposition, mimeType)
                }
            }
            // Give the offscreen view a real viewport so layout/text extraction (and the screenshot
            // canvas) have sensible dimensions even though the view is never attached to a window.
            val metrics = appContext.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, width, height)
        }
        // Accept cookies (incl. third-party) and persist them to disk so logins/sessions survive
        // between tool calls and app restarts (one process-wide CookieManager, like a real browser
        // profile). Third-party cookies default to OFF on API 21+, so they must be enabled per WebView:
        // https://developer.android.com/reference/android/webkit/CookieManager#setAcceptCookie(boolean)
        // https://developer.android.com/reference/android/webkit/CookieManager#setAcceptThirdPartyCookies(android.webkit.WebView,%20boolean)
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(view, true)
        // Flush so cookies collected so far are written to storage immediately:
        // https://developer.android.com/reference/android/webkit/CookieManager#flush()
        manager.flush()
        return view
    }

    suspend fun view(tab: WebTab): WebView = withContext(Dispatchers.Main.immediate) {
        tab.view ?: buildView(tab).also { tab.view = it }
    }

    suspend fun load(tab: WebTab, url: String): Boolean {
        val v = view(tab)
        tab.signal = CompletableDeferred()
        withContext(Dispatchers.Main.immediate) { v.loadUrl(url) }
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { tab.signal.await() } ?: false
        if (loaded) delay(SETTLE_MS)
        tab.url = url
        return loaded
    }

    /**
     * Runs [js] in [tab]'s WebView and returns its result.
     *
     * `WebView.evaluateJavascript` delivers its result through a callback posted on the WebView's
     * message loop (docs: https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)).
     * When the renderer is unresponsive, the view was detached, or the renderer process died, that
     * callback may NEVER arrive. Without a bound the `suspendCancellableCoroutine` below would then
     * suspend forever: the web tool would never return, no exception would be thrown, and the whole
     * agent run would stay stuck with `isStreaming=1` until the process was killed. The timeout
     * reports "no value" (null), which every caller already handles.
     */
    suspend fun evalJs(tab: WebTab, js: String): String? {
        val v = tab.view ?: return null
        return withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    try {
                        v.evaluateJavascript(js) { value -> cont.resumeWith(Result.success(value)) }
                    } catch (e: Exception) {
                        cont.resumeWith(Result.success(null))
                    }
                }
            }
        }
    }

    suspend fun pageSummary(tab: WebTab): String {
        val title = WebContentExtractor.decode(evalJs(tab, "document.title"))?.take(200).orEmpty()
        val url = WebContentExtractor.decode(evalJs(tab, "location.href")).orEmpty()
        val preview = WebContentExtractor
            .parseExtract(WebContentExtractor.decode(evalJs(tab, WebContentExtractor.EXTRACT_JS)))
            ?.take(300)
            .orEmpty()
        return buildString {
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Заголовок: $title")
            if (preview.isNotBlank()) appendLine("Текст (начало): $preview")
        }.trim()
    }

    private companion object {
        private const val LOAD_TIMEOUT_MS = 60_000L

        // Bound for one `evaluateJavascript` round-trip: the callback is delivered on the WebView
        // message loop and can be lost forever (unresponsive/detached/dead renderer), which must
        // never be able to hang a tool call.
        private const val EVAL_TIMEOUT_MS = 15_000L

        private const val SETTLE_MS = 2_000L
    }
}
