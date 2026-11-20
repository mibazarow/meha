package com.andharness.ru.inbound

import android.content.Intent
import android.os.Bundle
import com.andharness.ru.data.prefs.LicenseStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * «Что это?» / «Спросить Меху» — the share-sheet and text-selection target that asks Меха to
 * explain what the user is looking at.
 *
 * It is a thin translucent receiver like [ShareReceiverActivity] / [ProcessTextActivity]: it only
 * tags the incoming Intent with [InboundIntents.EXTRA_INBOUND_EXPLAIN] and forwards it, so the chat
 * is created and seeded with an explain instruction (`InboundExplainPolicy`) and runs immediately.
 * The same EULA gate as every other inbound path applies (`InboundForwardActivity`).
 *
 * Matched intent actions (see the manifest declarations):
 * - `ACTION_SEND` with `text/plain` — selected / shared text;
 * - `ACTION_SEND` with `image/&#42;` — a single shared picture (`EXTRA_STREAM`, a content URI);
 * - `ACTION_SEND_MULTIPLE` with `image/&#42;` — several shared pictures;
 * - `ACTION_PROCESS_TEXT` with `text/plain` — text selected in another app's text toolbar.
 *
 * References (official docs):
 * - receiving shared content (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`, `EXTRA_STREAM`, `EXTRA_TEXT`,
 *   content URIs) — https://developer.android.com/training/sharing/receive
 * - `Intent#ACTION_PROCESS_TEXT` —
 *   https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT
 * - `EXTRA_PROCESS_TEXT` / `EXTRA_PROCESS_TEXT_READONLY` —
 *   https://developer.android.com/reference/android/content/Intent#EXTRA_PROCESS_TEXT
 */
@AndroidEntryPoint
class ExplainReceiverActivity : InboundForwardActivity() {

    @Inject
    lateinit var handler: InboundIntentHandler

    @Inject
    lateinit var licenseStore: LicenseStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardInbound(explainTagged(intent), handler, licenseStore)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardInbound(explainTagged(intent), handler, licenseStore)
    }

    /**
     * The Intent the system delivered is immutable to us in spirit (we should not rewrite its
     * action / extras), but adding a private extra is safe and keeps the explain flag alongside the
     * shared payload all the way into `InboundIntentHandler`.
     */
    private fun explainTagged(source: Intent): Intent =
        source.putExtra(InboundIntents.EXTRA_INBOUND_EXPLAIN, true)
}
