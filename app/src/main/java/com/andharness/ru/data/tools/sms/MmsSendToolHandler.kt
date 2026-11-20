package com.andharness.ru.data.tools.sms

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import com.andharness.ru.data.tools.SharedFileContent
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `mms_send`: sends a multimedia message from a `content://` URI or a local file path.
 *
 * `sendMultimediaMessage` only needs SEND_SMS; persisting/receiving MMS requires the default-SMS
 * role, which this tool states honestly instead of faking it.
 * https://developer.android.com/reference/android/telephony/SmsManager#sendMultimediaMessage(android.content.Context,%20android.net.Uri,%20java.lang.String,%20android.os.Bundle,%20android.app.PendingIntent)
 */
internal class MmsSendToolHandler(
    private val context: Context,
    private val support: SmsSupport,
) : ToolHandler {

    override val name = "mms_send"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val number = (arguments["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (number.isEmpty()) {
                return@withContext ToolResult(false, "Укажите number (адрес получателя MMS).")
            }
            val contentUriRaw = (arguments["content_uri"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val filePath = (arguments["file_path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val contentUri: Uri = when {
                contentUriRaw.isNotEmpty() -> {
                    val parsed = Uri.parse(contentUriRaw)
                    if (parsed.scheme != "content") {
                        return@withContext ToolResult(false, "content_uri должен быть content:// URI.")
                    }
                    parsed
                }
                filePath.isNotEmpty() -> {
                    val file = try {
                        resolveSafePath(context.allowedToolRoots(), filePath)
                    } catch (e: IllegalArgumentException) {
                        return@withContext ToolResult(false, e.message ?: "Недопустимый путь.")
                    }
                    if (!file.isFile) {
                        return@withContext ToolResult(false, "Файл не найден: ${file.path}")
                    }
                    SharedFileContent.uriFor(context, file)
                }
                else -> return@withContext ToolResult(false, "Укажите content_uri или file_path с медиа.")
            }
            val manager = context.getSystemService(SmsManager::class.java)
                ?: return@withContext ToolResult(false, "SmsManager недоступен на этом устройстве.")

            val resultCode = support.awaitSentResult(
                timeoutMs = 60_000L,
                send = { pending -> manager.sendMultimediaMessage(context, contentUri, null, null, pending) },
            )
            when (resultCode) {
                null -> ToolResult(
                    false,
                    "Таймаут: подтверждение отправки MMS не получено. Проверьте настройки MMS/APN.",
                )
                Activity.RESULT_OK -> ToolResult(
                    true,
                    "MMS отправлено на $number. Сохранение и приём MMS доступны только приложению " +
                        "с ролью SMS по умолчанию (role_request ROLE_SMS).",
                )
                else -> ToolResult(
                    false,
                    "Отправка MMS не удалась (код $resultCode). Частая причина — отсутствие настроек MMS/APN; " +
                        "приём и сохранение MMS требуют роли SMS по умолчанию.",
                )
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на отправку MMS (нужен SEND_SMS): ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
