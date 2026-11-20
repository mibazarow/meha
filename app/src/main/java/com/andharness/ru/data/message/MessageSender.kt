package com.andharness.ru.data.message

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.andharness.ru.data.sms.SmsSentOutcome
import com.andharness.ru.data.sms.SmsSentResultAwait
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that actually puts a composed message on the wire (update.md §4.7).
 *
 * - **SMS** goes through [SmsManager.sendTextMessage] with a one-shot SENT `PendingIntent` and the
 *   platform's result code is verified — an unconfirmed send is reported as [MessageSendOutcome.Failed],
 *   never as success:
 *   https://developer.android.com/reference/android/telephony/SmsManager#sendTextMessage(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.app.PendingIntent,%20android.content.Intent)
 * - **MESSENGER** has no public send API, so the text is handed to an app via `ACTION_SEND` /
 *   `EXTRA_TEXT` (opened, not delivered):
 *   https://developer.android.com/training/sharing/send
 *
 * The class never logs or persists message text.
 */
@Singleton
class MessageSender @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** `true` when SEND_SMS is granted; used to fail fast with an honest reason. */
    fun canSendSms(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Sends [text] to the (already normalized) [number] and waits for the platform's confirmation.
     *
     * @return [MessageSendOutcome.Sent] only on a real `RESULT_OK`; a timeout is a failure.
     */
    suspend fun sendSms(number: String, text: String): MessageSendOutcome {
        if (!canSendSms()) {
            return MessageSendOutcome.Failed("Нет разрешения SEND_SMS — SMS не отправлено.")
        }
        val manager = context.getSystemService(SmsManager::class.java)
            ?: return MessageSendOutcome.Failed("SmsManager недоступен на этом устройстве.")
        return try {
            val code = SmsSentResultAwait.await(
                context = context,
                actionPrefix = SmsSentResultAwait.PREFIX_COMPOSE_SMS_SENT,
                timeoutMs = 30_000L,
            ) { pending ->
                manager.sendTextMessage(number, null, text, pending, null)
            }
            when (SmsSentResultAwait.outcomeFor(code)) {
                SmsSentOutcome.Timeout ->
                    MessageSendOutcome.Failed("Таймаут: подтверждение отправки на $number не получено.")
                SmsSentOutcome.Ok ->
                    MessageSendOutcome.Sent("SMS отправлено на $number (подтверждено системой).")
                SmsSentOutcome.RadioOff ->
                    MessageSendOutcome.Failed("Отправка не удалась: радио выключено (код $code).")
                SmsSentOutcome.NoService ->
                    MessageSendOutcome.Failed("Отправка не удалась: нет сети (код $code).")
                SmsSentOutcome.NullPdu, is SmsSentOutcome.GenericFailure ->
                    MessageSendOutcome.Failed("Отправка не удалась (код $code).")
            }
        } catch (e: SecurityException) {
            MessageSendOutcome.Failed("Нет разрешения на отправку SMS: ${e.message}")
        } catch (e: IllegalArgumentException) {
            MessageSendOutcome.Failed("Неверный номер или аргументы: ${e.message}")
        } catch (e: Exception) {
            MessageSendOutcome.Failed("Ошибка отправки SMS: ${e.message}")
        }
    }

    /**
     * Opens a messenger with [text] pre-filled (`ACTION_SEND`, `text/plain`, `EXTRA_TEXT`).
     *
     * @param appPackage the messenger to target when known; falls back to the system chooser.
     * @return [MessageSendOutcome.Opened] when an activity really started, otherwise `Failed`.
     */
    fun openMessenger(text: String, appPackage: String?): MessageSendOutcome {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targeted = appPackage?.trim()?.takeIf { it.isNotEmpty() }?.let { pkg ->
            Intent(send).setPackage(pkg)
        }
        if (targeted != null) {
            try {
                context.startActivity(targeted)
                return MessageSendOutcome.Opened(
                    "Открыл приложение $appPackage с предзаполненным текстом. Доставка не подтверждена — " +
                        "пользователь должен нажать «Отправить»."
                )
            } catch (_: ActivityNotFoundException) {
                // The app does not accept ACTION_SEND; fall through to the chooser below.
            } catch (_: SecurityException) {
                // Background activity start or visibility restriction; fall through.
            } catch (_: Exception) {
                // Any other start failure: try the chooser before giving up.
            }
        }
        val chooser = Intent.createChooser(send, "Отправить сообщение").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            MessageSendOutcome.Opened(
                "Открыл системный выбор приложения с предзаполненным текстом. Прямая отправка без API " +
                    "приложения невозможна, доставка не подтверждена."
            )
        } catch (e: Exception) {
            MessageSendOutcome.Failed(
                "Не удалось открыть мессенджер с текстом (${e.javaClass.simpleName}: ${e.message}). " +
                    "В фоне Android может запрещать запуск экрана отправки."
            )
        }
    }
}
