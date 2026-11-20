package com.andharness.ru.service

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.andharness.ru.MainActivity

/**
 * Minimal `VoiceInteractionSession` backing the assistant role (backlog #11).
 *
 * What it does: when the system starts an assist/voice-interaction session (the user holds the home
 * button / triggers the assist gesture, or an app calls `VoiceInteractor` while ANDharness is the
 * selected assistant), it brings [MainActivity] to the front with `FLAG_ACTIVITY_NEW_TASK` and then
 * finishes the session. Both [onShow] and [onHandleAssist] funnel into [openAssistantOnce], so the
 * activity is opened at most once per session regardless of which callback arrives first.
 *
 * Why [onShow] *and* [onHandleAssist]: `onHandleAssist` is delivered only when the request carried
 * `SHOW_WITH_ASSIST`; a plain assist gesture may show the session without any assist payload, so
 * [onShow] is the reliable trigger.
 *
 * Intentionally OUT OF SCOPE — and deliberately not faked:
 * - The assist payload is ignored. The `Bundle` / `AssistStructure` / `AssistContent` passed to
 *   [onHandleAssist] is never parsed, read, stored or forwarded; the session only opens the app.
 *   Richer `AssistStructure` consumption is out of scope because screen context is already provided
 *   by the accessibility tools ([AccessibilityAutomationService]); reading the structure here would
 *   add a second, user-invisible screen-content channel for no benefit.
 * - No hotword detection, no background work, no voice recognition — this class only reacts to a
 *   session the platform has already started.
 *
 * References:
 * - `VoiceInteractionSession`, `onShow(Bundle, int)`,
 *   `onHandleAssist(Bundle, AssistStructure, AssistContent)`, `SHOW_WITH_ASSIST`:
 *   https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
 * - `VoiceInteractionSessionService` (which creates this class):
 *   https://developer.android.com/reference/android/service/voice/VoiceInteractionSessionService
 */
class AndharnessVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    @Volatile
    private var opened = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        openAssistantOnce("onShow")
    }

    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        // Deprecated since API 29 but still the variant invoked for assist delivery; the payload is
        // ignored on purpose (see the class KDOC).
        super.onHandleAssist(data, structure, content)
        openAssistantOnce("onHandleAssist")
    }

    /** Opens [MainActivity] once and finishes the session; a second callback is a no-op. */
    private fun openAssistantOnce(source: String) {
        if (opened) return
        opened = true
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "assistant session ($source): MainActivity opened")
        } catch (e: Exception) {
            Log.e(TAG, "assistant session ($source): failed to open MainActivity", e)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
