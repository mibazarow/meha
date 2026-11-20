package com.andharness.ru.ui.chats

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Lifecycle-safe wrapper around the platform [TextToSpeech] engine, used by the chat's hands-free
 * voice mode (backlog №12). It owns exactly one engine and speaks complete utterances only — never
 * partial/streaming assistant text — so the owner can react to start/done/error.
 *
 * Initialization is asynchronous and may fail: [speak] is safe before the engine is ready (the text
 * is held as a single pending utterance and spoken once initialization succeeds), and when the
 * device has no usable engine [speak] returns false and [onUnavailable] fires — never a crash.
 *
 * Foreground-only: this class starts no service and requests no permission; it only renders audio
 * while the chat screen is in the foreground.
 *
 * Threading: create and call this class from the main thread. [UtteranceProgressListener] callbacks
 * arrive on the engine's binder thread, so they are marshalled to the main thread before the owner's
 * callbacks run.
 *
 * API reference:
 * - `TextToSpeech` (OnInitListener, setLanguage, setOnUtteranceProgressListener, speak with
 *   `QUEUE_FLUSH`, stop, shutdown):
 *   https://developer.android.com/reference/android/speech/tts/TextToSpeech
 * - `UtteranceProgressListener` (`onStart` / `onDone` / `onError`):
 *   https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener
 * - Lifecycle of the owning component (`onCleared` is where the engine is released):
 *   https://developer.android.com/topic/libraries/architecture/viewmodel
 */
class TtsController(context: Context) {

    /** Posted to the main thread when an utterance actually starts playing. */
    var onUtteranceStart: (() -> Unit)? = null

    /** Posted to the main thread when the current utterance finished normally. */
    var onUtteranceDone: (() -> Unit)? = null

    /** Posted to the main thread when playback failed or was interrupted by [stop]. */
    var onUtteranceError: (() -> Unit)? = null

    /** Posted to the main thread when no usable synthesis engine exists (honest message). */
    var onUnavailable: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var ready = false
    private var unavailable = false
    private var shutdownCalled = false
    private var pendingText: String? = null

    /** True while an utterance is (or is about to be) played. Read from any thread. */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    /** True once the engine finished initializing successfully. */
    val isReady: Boolean get() = ready

    init {
        engine = try {
            TextToSpeech(context.applicationContext, ::onInit)
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech construction failed", t)
            null
        }
        if (engine == null) {
            unavailable = true
            notifyUnavailable()
        }
    }

    private fun onInit(status: Int) {
        if (shutdownCalled) {
            // The owner released us before initialization finished: drop the engine, never speak.
            runCatching { engine?.shutdown() }
            engine = null
            return
        }
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed with status $status")
            unavailable = true
            runCatching { engine?.shutdown() }
            engine = null
            notifyUnavailable()
            return
        }
        val tts = engine ?: return
        val languageResult = runCatching { tts.setLanguage(Locale.getDefault()) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // Honest log only: the engine may still speak with a fallback, so this is not fatal.
            Log.w(TAG, "Default locale is not supported for speech synthesis ($languageResult)")
        }
        tts.setOnUtteranceProgressListener(progressListener)
        ready = true
        val queued = pendingText
        pendingText = null
        if (queued != null) speak(queued)
    }

    /**
     * Speaks [text] as one utterance, flushing anything already queued (barge-in replaces the
     * previous reply). Returns false when no engine is available; returns true when the text was
     * accepted or queued for a still-initializing engine.
     */
    fun speak(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || shutdownCalled || unavailable) return false
        val tts = engine
        if (!ready || tts == null) {
            pendingText = clean
            return true
        }
        val result = runCatching {
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "speak() returned ERROR")
            return false
        }
        isSpeaking = true
        return true
    }

    /**
     * Stops the current utterance and drops the queue. Safe before initialization completes: it then
     * just clears the queued text.
     */
    fun stop() {
        if (shutdownCalled) return
        pendingText = null
        isSpeaking = false
        runCatching { engine?.stop() }
    }

    /**
     * Releases the engine. Idempotent; call it from the owner's `onCleared` and when hands-free mode
     * is turned off, so nothing is leaked (and no audio survives a backgrounded screen).
     */
    fun shutdown() {
        if (shutdownCalled) return
        shutdownCalled = true
        pendingText = null
        isSpeaking = false
        ready = false
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                isSpeaking = true
                onUtteranceStart?.invoke()
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                isSpeaking = false
                onUtteranceDone?.invoke()
            }
        }

        @Deprecated("Deprecated in Java; the engine may still call the single-arg form")
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                isSpeaking = false
                onUtteranceError?.invoke()
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post {
                isSpeaking = false
                onUtteranceError?.invoke()
            }
        }
    }

    private fun notifyUnavailable() {
        mainHandler.post { onUnavailable?.invoke(UNAVAILABLE_MESSAGE) }
    }

    private companion object {
        const val TAG = "AndharnessTts"
        const val UTTERANCE_ID = "andharness_reply"
        const val UNAVAILABLE_MESSAGE =
            "Синтез речи недоступен на устройстве — ответ не будет озвучен."
    }
}
