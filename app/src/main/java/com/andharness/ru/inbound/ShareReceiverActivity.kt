package com.andharness.ru.inbound

import android.content.Intent
import android.os.Bundle
import com.andharness.ru.data.prefs.LicenseStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Share-sheet target (update.md idea #10). `ACTION_SEND` carries shared text (`text/plain`) or a
 * single picture (`image/&#42;`), and `ACTION_SEND_MULTIPLE` carries several files / URIs; the manifest
 * filters declare one mime type per filter.
 *
 * https://developer.android.com/training/sharing/receive
 */
@AndroidEntryPoint
class ShareReceiverActivity : InboundForwardActivity() {

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
