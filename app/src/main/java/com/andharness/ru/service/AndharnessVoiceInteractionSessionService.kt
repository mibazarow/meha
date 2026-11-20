package com.andharness.ru.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory for [AndharnessVoiceInteractionSession], wired from the `android:sessionService`
 * attribute of `res/xml/voice_interaction_service.xml` (backlog #11).
 *
 * The platform binds this service explicitly by component when an assist/voice-interaction session
 * is requested, so it needs no intent-filter in the manifest — only `exported="true"` guarded by
 * `android.permission.BIND_VOICE_INTERACTION`, mirroring the platform's own explicit bind
 * (`VoiceInteractionSessionConnection` sets the component before binding).
 *
 * References:
 * - `VoiceInteractionSessionService` / `onNewSession(Bundle)`:
 *   https://developer.android.com/reference/android/service/voice/VoiceInteractionSessionService
 * - `VoiceInteractionService` metadata / `android.voice_interaction`:
 *   https://developer.android.com/reference/android/service/voice/VoiceInteractionService
 */
class AndharnessVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AndharnessVoiceInteractionSession(this)
}
