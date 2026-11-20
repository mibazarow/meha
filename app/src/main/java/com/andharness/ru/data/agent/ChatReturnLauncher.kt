package com.andharness.ru.data.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.MainActivity
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.BackgroundChatReturnPolicy
import com.andharness.ru.domain.ui.ActiveChatTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the ORIGINATING chat back to the front when a user-initiated agent turn finishes while
 * Меха is not visible (the user was left in another app by a UI-automation step / `app_launch`),
 * and falls back to the chat-targeted result notification when that is not possible.
 *
 * Why a background activity start is attempted at all: Меха holds exemptions from the Android 10+
 * background-activity-launch (BAL) restrictions here — a system-bound `AccessibilityService`
 * (`service.AccessibilityAutomationService`, enabled for UI automation) and the user-granted
 * `SYSTEM_ALERT_WINDOW` permission (the floating assistant). Both are explicit BAL exemptions:
 * https://developer.android.com/guide/components/activities/secure-bal
 *
 * Note: a running FOREGROUND SERVICE is NOT an exemption for starting activities — an app running a
 * foreground service is still considered to be in the background for BAL.
 * https://developer.android.com/guide/components/activities/background-starts
 *
 * Failure is never silent: when the launch is refused, [NotificationHelper.postDoneNotification]
 * posts the same chat deep-link the completion path already uses, so a tap still lands the user in
 * the chat where the agent's final report is the last message. The launch itself is best-effort and
 * must never crash the agent (high-caution area) — hence the broad catch.
 */
@Singleton
class ChatReturnLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: NotificationHelper,
) {

    /**
     * If [userInitiated] and the app is NOT in the foreground and [chatId] is real, starts
     * [MainActivity] on that chat with `EXTRA_CHAT_ID` (the same extra a notification tap uses, so
     * `MainActivity.handleDeepLink` navigates to it). Returns true when the Activity start was
     * accepted by the platform; false when it was skipped (foreground / background run / no chat) or
     * refused. On refusal the result notification is posted as the fallback.
     */
    fun returnToOriginatingChatIfAway(chatId: Long?, userInitiated: Boolean): Boolean {
        if (!BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = userInitiated,
                appInForeground = ActiveChatTracker.isAppForeground,
                chatId = chatId,
            )
        ) {
            return false
        }
        val target = chatId ?: return false
        val returned = try {
            context.startActivity(openChatIntent(target))
            true
        } catch (e: Exception) {
            // The platform can refuse a BAL even with the exemptions above; some builds throw, AOSP
            // silently logs "Background activity launch blocked". Never let that crash the agent.
            Log.w(TAG, "Background return to chat $target was refused: ${e.message}")
            false
        }
        if (!returned) {
            // Fallback (requirement: never fail silently). postDoneNotification returns false when
            // POST_NOTIFICATIONS is denied; there is nothing more we can do then, but we do not
            // pretend the user was returned.
            notifications.postDoneNotification(text = FALLBACK_TEXT, chatId = target)
        }
        return returned
    }

    /**
     * Explicit `Intent` to [MainActivity] carrying `EXTRA_CHAT_ID`, exactly like
     * [NotificationHelper.contentIntent], so the existing deep-link path in
     * `MainActivity.handleDeepLink` opens the chat (it verifies the chat still exists and falls
     * back honestly when it was deleted).
     *
     * Flags: `NEW_TASK` because the context is the Application; `CLEAR_TOP` + `SINGLE_TOP` so an
     * already-running `launchMode="singleTask"` instance receives the Intent through `onNewIntent`
     * instead of being finished and recreated (the repo's notification deep link avoids a recreation
     * for the same reason).
     * https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_NEW_TASK
     * https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_CLEAR_TOP
     * https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent)
     */
    private fun openChatIntent(chatId: Long): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationHelper.EXTRA_CHAT_ID, chatId)
        }

    private companion object {
        const val TAG = "Andharness"
        const val FALLBACK_TEXT = "Задача завершена — откройте чат, чтобы увидеть отчёт."
    }
}
