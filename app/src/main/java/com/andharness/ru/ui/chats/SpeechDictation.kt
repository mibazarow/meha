package com.andharness.ru.ui.chats

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.andharness.ru.R
import java.util.Locale

/**
 * UI-layer push-to-talk wrapper over the platform [SpeechRecognizer] — the same engine as the
 * `speech_to_text` tool ([com.andharness.ru.data.tools.speech.SpeechToolsHandler]), used by the chat
 * composer's microphone instead of the whisper model.
 *
 * Create it on the main thread (e.g. `remember { SpeechDictation(context) }`) and release it with
 * [destroy] from `onDispose`. Every public method is safe to call repeatedly and when no recognizer
 * exists; the recognizer is always torn down (`stopListening`/`destroy`, wrapped in `runCatching`)
 * before a result or error is delivered. Requires RECORD_AUDIO at the call site.
 *
 * References:
 * - SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
 * - RecognitionListener: https://developer.android.com/reference/android/speech/RecognitionListener
 * - RecognizerIntent: https://developer.android.com/reference/android/speech/RecognizerIntent
 */
class SpeechDictation(
    private val context: Context,
) {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    /** Notified `true` when listening starts, `false` when it stops (result, error or cancel). */
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** Delivered with the top recognition result when listening ends with non-blank text. */
    var onResult: ((String) -> Unit)? = null

    /** Delivered with a user-facing message when listening ends without usable text. */
    var onError: ((String) -> Unit)? = null

    /** `true` when a recognition service is installed and enabled on the device. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts listening. No-op while already listening; reports via [onError] when unavailable. */
    fun start() {
        if (isListening) return
        if (!isAvailable()) {
            onError?.invoke(context.getString(R.string.chat_dictation_unavailable))
            return
        }
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (created == null) {
            onError?.invoke(context.getString(R.string.chat_dictation_unavailable))
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { created.startListening(intent) }
            .onSuccess {
                isListening = true
                onListeningChanged?.invoke(true)
            }
            .onFailure {
                release()
                onError?.invoke(context.getString(R.string.chat_dictation_no_speech))
            }
    }

    /**
     * Stops listening and lets the engine deliver its result: [onResults]/[onError] run afterwards,
     * where the recognizer is destroyed.
     */
    fun stop() {
        val current = recognizer ?: return
        runCatching { current.stopListening() }
    }

    /** Tears the session down without delivering a result (e.g. the gesture was cancelled). */
    fun cancel() = release()

    /** Releases the recognizer. Call from `onDispose`. */
    fun destroy() = release()

    private fun release() {
        val current = recognizer
        recognizer = null
        if (current != null) {
            runCatching { current.stopListening() }
            runCatching { current.destroy() }
        }
        if (isListening) {
            isListening = false
            onListeningChanged?.invoke(false)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            release()
            if (!text.isNullOrBlank()) {
                onResult?.invoke(text.trim())
            } else {
                onError?.invoke(context.getString(R.string.chat_dictation_no_speech))
            }
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    context.getString(R.string.chat_dictation_no_speech)
                // Every other platform error (busy engine, client/audio failure, …) is not
                // actionable for the user; surface the same honest «not recognized» notice
                // instead of a raw code.
                else -> context.getString(R.string.chat_dictation_no_speech)
            }
            release()
            onError?.invoke(message)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
