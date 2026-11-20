package com.andharness.ru.inbound

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.andharness.ru.MainActivity
import com.andharness.ru.data.prefs.LicenseStore
import com.andharness.ru.ui.navigation.LicenseGate
import kotlinx.coroutines.launch

/**
 * Shared body of the two thin inbound receivers (update.md idea #10): [ShareReceiverActivity]
 * (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) and [ProcessTextActivity] (`ACTION_PROCESS_TEXT`).
 *
 * Both are declared with a translucent theme and finish immediately, so the user never sees an
 * extra screen: the receiver prepares the chat (via [InboundIntentHandler]) and then brings the
 * already-running [MainActivity] to the front on that chat. If the Intent turns out to carry
 * nothing usable — or the preparation fails — the app is opened normally instead of showing an
 * empty chat.
 *
 * The concrete Activities are `@AndroidEntryPoint` and pass their injected handler in, which keeps
 * this base class free of Hilt field injection.
 */
abstract class InboundForwardActivity : ComponentActivity() {

    /** Runs [source] through [handler] and navigates, then finishes this forwarding Activity. */
    protected fun forwardInbound(
        source: Intent?,
        handler: InboundIntentHandler,
        licenseStore: LicenseStore
    ) {
        if (source == null) {
            finish()
            return
        }
        // Единая точка лицензионного гейта (EULA), тот же [LicenseGate], что и в MainActivity: пока
        // соглашение не принято, входящее намерение (share / process text) ОТБРАСЫВАЕТСЯ до любой
        // работы с чатом/сессией — иначе чат создавался бы и вложения копировались бы ещё до
        // принятия лицензии. Приложение при этом просто открывается на экране LICENSE.
        if (!LicenseGate.inboundAllowed(licenseStore.accepted(LicenseStore.LICENSE_VERSION))) {
            startActivity(Intent(this@InboundForwardActivity, MainActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch {
            val chatId = runCatching { handler.handle(source) }.getOrNull()
            val target = if (chatId != null) {
                InboundIntents.openChat(this@InboundForwardActivity, chatId)
            } else {
                Intent(this@InboundForwardActivity, MainActivity::class.java)
            }
            startActivity(target)
            // Plain `finish()`: `finishAndRemoveTask()` would also tear down the task that the
            // just-started singleTask `MainActivity` may have joined, killing the UI it opened.
            // `android:excludeFromRecents` on this Activity keeps the transitional task out of
            // recents instead.
            finish()
        }
    }
}
