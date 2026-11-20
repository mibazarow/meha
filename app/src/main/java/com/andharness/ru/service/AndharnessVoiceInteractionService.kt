package com.andharness.ru.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Top-level service of a voice interactor (backlog #11). Minimal and lightweight by design: it only
 * logs that the system bound and initialised it. No hotword detector, no session work and no
 * background tasks are started here — the actual per-interaction behavior lives in
 * [AndharnessVoiceInteractionSessionService].
 *
 * Manifest requirement: the service must require
 * `android.permission.BIND_VOICE_INTERACTION`, declare the
 * `android.service.voice.VoiceInteractionService` intent-filter, and point the
 * `android.voice_interaction` meta-data at `res/xml/voice_interaction_service.xml`.
 *
 * References:
 * - `VoiceInteractionService`, `onReady()`, `SERVICE_INTERFACE`, `SERVICE_META_DATA`:
 *   https://developer.android.com/reference/android/service/voice/VoiceInteractionService
 * - `BIND_VOICE_INTERACTION`:
 *   https://developer.android.com/reference/android/Manifest.permission#BIND_VOICE_INTERACTION
 */
class AndharnessVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService ready")
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
