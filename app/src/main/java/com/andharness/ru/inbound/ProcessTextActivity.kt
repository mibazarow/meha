package com.andharness.ru.inbound

import android.content.Intent
import android.os.Bundle
import com.andharness.ru.data.prefs.LicenseStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * «Спросить агента» — the text-selection toolbar target (update.md idea #10). Receives
 * `ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT` (the text the user selected) and opens a new chat
 * pre-filled with it; it does NOT auto-run the agent, because the user has not asked a question yet.
 *
 * https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT
 */
@AndroidEntryPoint
class ProcessTextActivity : InboundForwardActivity() {

    @Inject
    lateinit var handler: InboundIntentHandler

    @Inject
    lateinit var licenseStore: LicenseStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardInbound(intent, handler, licenseStore)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardInbound(intent, handler, licenseStore)
    }
}
