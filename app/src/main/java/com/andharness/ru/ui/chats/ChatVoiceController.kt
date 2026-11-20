package com.andharness.ru.ui.chats

import android.content.Context

/**
 * Owns the chat's text-to-speech: the per-message «Озвучить» action reads an assistant reply aloud
 * through the lazily created [TtsController]. The engine is created on first use and released from
 * the owner's `onCleared`, so nothing is leaked.
 *
 * Composer dictation and the foreground hands-free voice mode were removed in redesign §2 phase 5;
 * this controller is now purely the manual TTS path. API reference for the engine itself lives in
 * [TtsController] (android.speech.tts.TextToSpeech).
 */
internal class ChatVoiceController(
    private val appContext: Context,
    private val state: ChatStateHolder
) {

    /** Created lazily the first time a reply is spoken; released by [destroy]. */
    private var tts: TtsController? = null

    /**
     * Speaks one assistant message on demand (the per-message «Озвучить» action). A missing or
     * unavailable engine is reported honestly through [ChatUiState.error] — never a crash.
     */
    fun speakMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (ensureTts().speak(clean) != true) {
            state.update { it.copy(error = TTS_UNAVAILABLE_MESSAGE) }
        }
    }

    /**
     * Called by the screen when it is no longer visible (§21): explicit per-message speech is
     * foreground-only, so never keep talking off-screen. Stopping is safe before the engine is ready.
     */
    fun onLeaveForeground() {
        tts?.stop()
    }

    /** Releases the TTS engine; called from the owner's `onCleared`. */
    fun destroy() {
        tts?.shutdown()
        tts = null
    }

    /** Returns the shared [TtsController], creating it (and its honest-unavailable callback) lazily. */
    private fun ensureTts(): TtsController = tts ?: TtsController(appContext).also { controller ->
        controller.onUnavailable = { message -> state.update { it.copy(error = message) } }
    }.also { tts = it }

    private companion object {
        /** Honest message when the explicit «speak» action finds no usable synthesis engine. */
        const val TTS_UNAVAILABLE_MESSAGE =
            "Синтез речи недоступен на устройстве — сообщение не будет озвучено."
    }
}
