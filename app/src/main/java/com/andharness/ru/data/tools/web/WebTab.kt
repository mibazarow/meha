package com.andharness.ru.data.tools.web

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred

/** One web session tab: its own [WebView], its own history and page-finished signal. */
class WebTab(val id: Int, var name: String?) {
    var view: WebView? = null
    var signal: CompletableDeferred<Boolean> = CompletableDeferred()
    var url: String = ""
}
