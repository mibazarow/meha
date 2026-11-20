package com.andharness.ru.data.tools.speech

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Builds the [speech_to_text] tool using the platform [SpeechRecognizer] (on-device/cloud engine as
 * configured by the user). Requires RECORD_AUDIO.
 *
 * Reference: https://developer.android.com/reference/android/speech/SpeechRecognizer
 */
class SpeechToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "speech_to_text"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    return@withContext ToolResult(false, "Распознавание речи недоступно на устройстве.")
                }
                val language = (arguments["language"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val timeoutMs = (arguments["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?.coerceIn(2000, 30_000) ?: 12_000L
                val text = withTimeoutOrNull(timeoutMs) { recognize(language) }
                if (text.isNullOrBlank()) {
                    ToolResult(false, "Речь не распознана (тишина, отказ в микрофоне или таймаут).")
                } else {
                    ToolResult(true, text)
                }
            }
        },
    )

    private suspend fun recognize(language: String?): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                cleanup()
                if (cont.isActive) cont.resume(list?.firstOrNull())
            }

            override fun onError(error: Int) {
                cleanup()
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            private fun cleanup() {
                runCatching { recognizer.stopListening() }
                runCatching { recognizer.destroy() }
            }
        }
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language ?: Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        cont.invokeOnCancellation {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
        recognizer.startListening(intent)
    }
}
