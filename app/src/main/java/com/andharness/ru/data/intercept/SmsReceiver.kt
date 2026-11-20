package com.andharness.ru.data.intercept

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.andharness.ru.data.notify.notificationTriageEntryPoint
import com.andharness.ru.data.person.PersonIngestEntryPoint
import com.andharness.ru.data.person.PersonRef
import com.andharness.ru.data.person.personIngestEntryPoint
import com.andharness.ru.domain.automation.AutomationEvent
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.messages.MessageExternalRefs
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessageDirection
import com.andharness.ru.domain.messages.StoredMessageSource
import com.andharness.ru.domain.notify.NotificationEvent
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMS interception (update.md §2.3) **and** incremental ingestion into the personal base
 * (update.md §4.4).
 *
 * - Receives `SMS_RECEIVED` (`Telephony.Sms.Intents.SMS_RECEIVED_ACTION`); multipart messages are
 *   joined with `Telephony.Sms.Intents.getMessagesFromIntent` and their bodies concatenated.
 * - **Non-abortable by design**: [abortBroadcast] is never called and the app never claims to be the
 *   default SMS app, so the user's normal SMS client still shows its notification.
 * - Unknown sender → the shared async pipeline (OSINT + auto-chat + notification) via
 *   [NumberChatManager]; unknown senders are not persisted anywhere by that pipeline.
 * - Personal base: when indexing is opted in, the sender's PHONE identity is upserted, the **full**
 *   body is written to `stored_messages` (encrypted, device-only) for later search/summaries, and the
 *   message is also recorded as an INCOMING `SMS` interaction — metadata plus a bounded gist (the
 *   interaction table still keeps only the ≤80-char summary). A broadcast carries no row id, so the
 *   `stored_messages` row is de-duplicated with a hashed `externalRef` (sender|at|body) so a repeated
 *   delivery is a no-op ([com.andharness.ru.domain.messages.MessageExternalRefs]).
 * - Automation (BUG-006): an incoming SMS always fires the `sms` automation trigger through the
 *   `RuleEngine` (sender as `{value}`, bounded body as `{payload}`), independently of the
 *   interception / indexing opt-ins above.
 * - `goAsync()` keeps the process alive for the (bounded) background pipeline.
 *
 * Manifest: exported receiver with the `SMS_RECEIVED` filter, guarded by
 * `android:permission="android.permission.BROADCAST_SMS"` so only the system can address it, and
 * requiring `RECEIVE_SMS`.
 *
 * References:
 * - `Telephony.Sms.Intents.SMS_RECEIVED_ACTION` / `getMessagesFromIntent`:
 *   https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION
 * - `SmsMessage.getTimestampMillis()`:
 *   https://developer.android.com/reference/android/telephony/SmsMessage#getTimestampMillis()
 * - `Manifest.permission.RECEIVE_SMS`:
 *   https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS
 * - `BroadcastReceiver.goAsync`:
 *   https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable SMS intent", e)
            null
        } ?: return

        val sender = messages.firstOrNull()?.originatingAddress?.takeIf { it.isNotBlank() }
        if (sender == null) {
            Log.i(TAG, "incoming SMS ignored: no originating address (${messages.size} part(s))")
            return
        }
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }.trim()
        val at = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        Log.i(TAG, "SMS_RECEIVED broadcast received (${messages.size} part(s))")

        val entry = try {
            interceptEntryPoint(context)
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable", e)
            return
        }
        val personEntry = try {
            personIngestEntryPoint(context)
        } catch (e: Exception) {
            Log.e(TAG, "person ingest entry point unavailable", e)
            null
        }

        val interceptionEnabled = entry.watchdogConfigStore().isInterceptionEnabled()
        val indexingEnabled = personEntry?.personIndexer()?.isEnabled() == true

        // The `sms` automation trigger fires on its own, independently of the interception / indexing
        // opt-ins, so the receiver always goes async and dispatches it first. The rest of the
        // pipeline stays gated exactly as before.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dispatchAutomation(entry, sender, body, at)
                // Trusted-SMS photo report (checklist §1): independent of the interception/indexing
                // opt-ins. Off by default; inside, it is gated on the trusted-number allowlist, the
                // photo-command match and a per-number cooldown, so an untrusted SMS does nothing.
                try {
                    val photoOutcome = entry.smsPhotoReporter().onIncomingSms(sender, body, at)
                    if (photoOutcome.handled) {
                        Log.i(TAG, "sms photo report: ${photoOutcome.note}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sms photo report failed", e)
                }
                // Auto-reply to SMS (checklist §1): independent of the interception/indexing opt-ins,
                // gated by the call_rules store. Off by default (strict no-op); when on it replies only
                // to unknown/busy senders, never to allowlisted/short/service/emergency numbers, never
                // to a photo command, and at most once per number per 6 h.
                try {
                    val replyOutcome = entry.callSmsRuleApplier().onIncomingSms(sender, body, at)
                    if (replyOutcome.acted) {
                        Log.i(TAG, "sms auto-reply: ${replyOutcome.note}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sms auto-reply failed", e)
                }
                if (!interceptionEnabled && !indexingEnabled) {
                    // Honest skip: logged (not silent) so the tester/agent can see exactly why the
                    // interception/indexing pipeline did nothing for this SMS.
                    Log.i(
                        TAG,
                        "incoming SMS: interception/indexing disabled " +
                            "(interceptionEnabled=$interceptionEnabled, " +
                            "personIndexingEnabled=$indexingEnabled); automation rules still evaluated"
                    )
                    return@launch
                }
                if (indexingEnabled) {
                    indexIncoming(context, personEntry, sender, body, at)
                    // Idea #17: the same opt-in also feeds the notification/ SMS triage. The engine
                    // applies its own toggle, private-mode and memorize gates.
                    try {
                        notificationTriageEntryPoint(context).notificationTriageEngine().onEvent(
                            NotificationEvent(
                                app = SMS_APP,
                                sender = sender,
                                title = sender,
                                text = body,
                                at = at,
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "sms triage failed", e)
                    }
                }
                if (interceptionEnabled && body.isNotEmpty()) {
                    val outcome = entry.numberChatManager().onEvent(
                        rawNumber = sender,
                        eventKind = NumberChatManager.EVENT_SMS,
                        detail = body.take(200),
                    )
                    Log.i(TAG, "sms post-processing: ${outcome.note}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "async sms post-processing failed", e)
            } finally {
                pending.finish()
            }
        }
        // Deliberately NO abortBroadcast(): the user's SMS app keeps its notification.
    }

    /**
     * Best-effort hand-off to the automation engine (BUG-006): one `sms` [AutomationEvent] whose
     * `value` is the sender and whose `payload` is the body (bounded to [SMS_BODY_MAX] characters,
     * consistent with the bounded `detail` handed to [NumberChatManager]). The engine uses these as
     * the `{value}` / `{payload}` placeholders of an `agent_task` action. `RuleEngine.dispatch` is
     * non-blocking and its failures never propagate, so this can never break the receiver; a failure
     * is logged only.
     */
    private fun dispatchAutomation(
        entry: InterceptEntryPoint,
        sender: String,
        body: String,
        at: Long
    ) {
        try {
            entry.ruleEngine().dispatch(
                AutomationEvent(
                    triggerType = AutomationSpec.TRIGGER_SMS,
                    value = sender,
                    payload = body.take(SMS_BODY_MAX),
                    at = at,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "sms automation dispatch failed", e)
        }
    }

    /**
     * Records the incoming SMS in the personal base: the sender's PHONE identity, the **full** body in
     * `stored_messages` (deduped by a hashed `externalRef`), plus one INCOMING `SMS` interaction whose
     * summary is a bounded gist of [body]. Nothing here logs the body.
     */
    private suspend fun indexIncoming(
        context: Context,
        personEntry: PersonIngestEntryPoint,
        sender: String,
        body: String,
        at: Long
    ) {
        // A contact display name is a display hint only; a failed lookup is fine (null label).
        val label = ContactLookup.displayName(context, sender)
        val indexer = personEntry.personIndexer()
        val personId = indexer.upsertIdentity(
            type = IdentityType.PHONE,
            value = sender,
            label = label,
        )
        val externalRef = MessageExternalRefs.smsLive(sender, at, body)
        val repository = personEntry.storedMessagesRepository()
        if (repository.findByExternalRef(externalRef) == null) {
            repository.insert(
                StoredMessage(
                    id = 0,
                    sourceType = StoredMessageSource.SMS,
                    packageName = null,
                    conversationKey = sender,
                    personId = personId,
                    direction = StoredMessageDirection.INCOMING,
                    sender = sender,
                    isGroup = false,
                    at = at,
                    text = body,
                    externalRef = externalRef,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        indexer.recordInteraction(
            channel = InteractionChannel.SMS,
            direction = InteractionDirection.INCOMING,
            at = at,
            personIdOrIdentity = PersonRef.Identity(
                type = IdentityType.PHONE,
                value = sender,
                label = label,
            ),
            summary = body,
        )
    }

    companion object {
        private const val TAG = "Andharness"

        /** Pseudo-app name used for SMS events in the triage capture log and memory tags. */
        private const val SMS_APP = "sms"

        /** Cap on the body carried into an automation event (the same bound as the chat `detail`). */
        private const val SMS_BODY_MAX = 200
    }
}
