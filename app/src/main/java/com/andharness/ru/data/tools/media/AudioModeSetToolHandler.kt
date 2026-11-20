package com.andharness.ru.data.tools.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `audio_mode_set`: sets the audio mode and optionally the speakerphone, honestly. */
internal class AudioModeSetToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "audio_mode_set"

    @Suppress("DEPRECATION")
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val requested = (arguments["mode"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
            val mode = when (requested) {
                "normal" -> AudioManager.MODE_NORMAL
                "in_communication", "communication" -> AudioManager.MODE_IN_COMMUNICATION
                "in_call", "call" -> AudioManager.MODE_IN_CALL
                "ringtone" -> AudioManager.MODE_RINGTONE
                else -> return@withContext ToolResult(
                    false,
                    "Недопустимый mode «$requested». Разрешены: normal, in_communication, in_call, ringtone.",
                )
            }
            val audioManager = context.getSystemService(AudioManager::class.java)
                ?: return@withContext ToolResult(false, "AudioManager недоступен на этом устройстве.")

            val speakerArg = arguments["speaker"] as? JsonPrimitive
            val speakerWanted = speakerArg?.content?.toBooleanStrictOrNull()
            if (speakerArg != null && speakerWanted == null) {
                return@withContext ToolResult(false, "Некорректный speaker: «${speakerArg.content}».")
            }

            audioManager.mode = mode
            val appliedMode = audioManager.mode
            var speakerApplied: Boolean? = null
            if (speakerWanted != null) {
                audioManager.isSpeakerphoneOn = speakerWanted
                speakerApplied = audioManager.isSpeakerphoneOn
            }

            val report = StringBuilder()
            report.append("Режим аудио: запрошен $mode (${modeName(mode)}), фактически $appliedMode (${modeName(appliedMode)}).")
            if (appliedMode != mode) {
                report.append(
                    " Платформа проигнорировала запрос: этот режим (например in_call/ringtone) " +
                        "доступен только системным компонентам или требует активного вызова.",
                )
            }
            if (speakerApplied != null) {
                report.append(" Громкая связь: запрошено $speakerWanted, фактически $speakerApplied.")
                if (speakerApplied != speakerWanted) {
                    report.append(" Переключение может быть доступно только во время вызова.")
                }
            }
            ToolResult(true, report.toString())
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "audio_mode_set failed", e)
            ToolResult(false, "Не удалось изменить режим аудио: ${e.message}")
        }
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in_call"
        AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        else -> "неизвестный"
    }
}
