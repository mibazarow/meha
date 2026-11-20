package com.andharness.ru.data.tools.web.handlers

import com.andharness.ru.data.tools.web.WebContentExtractor

/**
 * Timing and size limits shared by the extracted `web_*` handlers. Values are identical to the
 * private companion of [com.andharness.ru.data.tools.web.WebEngine] — the split must not change any
 * tool behaviour.
 */
internal object WebEngineConsts {
    const val TAG = "Andharness"
    const val LOAD_TIMEOUT_MS = 60_000L
    const val SETTLE_MS = 2_000L
    const val CLICK_SETTLE_MS = 1_800L
    const val SCROLL_SETTLE_MS = 600L
    const val SCROLL_UNTIL_SETTLE_MS = 600L
    const val POLL_INTERVAL_MS = 400L
    const val MAX_TEXT_CHARS = 20_000
    const val DEFAULT_READABLE_CHARS = 100_000
    const val MAX_READABLE_CHARS = 300_000
    const val MAX_EVAL_CHARS = 20_000

    const val MOBILE_CHROME_UA = WebContentExtractor.MOBILE_CHROME_UA
}
