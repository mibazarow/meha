package com.andharness.ru.data.intercept

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.andharness.ru.data.sms.SmsPhotoRuleStore
import com.andharness.ru.data.tools.camera.Camera2Controller
import com.andharness.ru.data.tools.camera.PhotoAnalyzer
import com.andharness.ru.data.trust.TrustedSourceRules
import com.andharness.ru.data.trust.TrustedSourceStore
import com.andharness.ru.domain.sms.SmsPhotoDecision
import com.andharness.ru.domain.sms.SmsPhotoRuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The integration point of the trusted-SMS photo report: an inbound SMS from a **trusted** number
 * that matches a photo command triggers an in-process rear-camera capture, an analysis
 * (`PhotoAnalyzer`: vision when available, honest OCR otherwise) and a reply SMS to that number.
 *
 * Safety, enforced here on top of the pure [SmsPhotoRuleEngine]:
 * - the rule is OFF by default ([SmsPhotoRuleStore]);
 * - the sender must be on the trusted-source SMS allowlist ([TrustedSourceStore]);
 * - service/short/emergency numbers are rejected ([SmsPhotoRuleEngine.isEligibleSender]);
 * - a per-number cooldown prevents an answer loop;
 * - the photo is written to the app-private cache, so no storage permission is needed, and it is
 *   never attached to the SMS (only the text analysis is).
 *
 * References:
 * - `SmsManager.sendTextMessage` (reply delivery; the platform enforces the premium/short-code
 *   restriction):
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 * - `BroadcastReceiver.goAsync` (the receiver keeps the process alive for this bounded work):
 *   https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
 */
@Singleton
class SmsPhotoReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleStore: SmsPhotoRuleStore,
    private val trustedSourceStore: TrustedSourceStore,
    private val photoAnalyzer: PhotoAnalyzer,
) {

    /** Whether a report was produced, plus an honest, log-readable note. */
    data class Outcome(val handled: Boolean, val note: String)

    /**
     * Handles one inbound SMS. Never throws for ordinary failures; a camera/model/SMS error is
     * reported in the [Outcome] note instead of being swallowed silently.
     */
    suspend fun onIncomingSms(
        sender: String,
        body: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val rule = ruleStore.current()
        val trusted = runCatching {
            trustedSourceStore.isTrusted(TrustedSourceRules.TYPE_SMS, sender)
        }.getOrDefault(false)
        val eligible = SmsPhotoRuleEngine.isEligibleSender(sender)
        val coolingDown = ruleStore.isCoolingDown(sender, nowMs)
        val plan = SmsPhotoRuleEngine.decide(rule, trusted, eligible, body, coolingDown)
        if (plan.decision != SmsPhotoDecision.REPORT) {
            return Outcome(handled = false, note = plan.reason)
        }

        val target = File(context.cacheDir, "sms_photo/sms_photo_$nowMs.jpg")
        val captured = try {
            Camera2Controller.capturePhoto(context, target, lens = "back", cameraId = null)
        } catch (e: Exception) {
            return Outcome(handled = true, note = "фото не снято: ${e.message}")
        }
        Log.i(TAG, "sms photo report: $captured")

        val report = try {
            photoAnalyzer.analyze(target)
        } catch (e: Exception) {
            return Outcome(handled = true, note = "анализ не удался: ${e.message}")
        }

        val reply = SmsPhotoRuleEngine.replyText(rule.replyTextPrefix, report.text.take(MAX_REPLY_CHARS))
        val sendNote = try {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: return Outcome(handled = true, note = "SmsManager недоступен — ответ не отправлен")
            // Fire-and-forget: the reply is queued by the telephony stack. We do not claim delivery.
            manager.sendTextMessage(sender, null, reply, null, null)
            "ответ поставлен в очередь на $sender"
        } catch (e: SecurityException) {
            return Outcome(handled = true, note = "нет разрешения SEND_SMS: ${e.message}")
        } catch (e: Exception) {
            return Outcome(handled = true, note = "ответ не отправлен: ${e.message}")
        }

        ruleStore.noteReply(sender, nowMs)
        val vision = if (report.visionAvailable) "vision" else "ocr-fallback"
        return Outcome(handled = true, note = "$sendNote ($vision)")
    }

    private companion object {
        const val TAG = "Andharness"

        /** Hard cap on the reply body, so a long description never becomes an SMS flood. */
        const val MAX_REPLY_CHARS = 600
    }
}
