package com.andharness.ru.data.tools.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens Google Play `market://` URIs with a web fallback, shared by [app_store_open] and
 * [app_store_search]. Implicit `ACTION_VIEW` intents must carry `FLAG_ACTIVITY_NEW_TASK` when
 * launched from a non-Activity context, and an unhandled implicit intent throws
 * [ActivityNotFoundException].
 *
 * References:
 * - https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * - https://developer.android.com/guide/components/intents-common
 * - https://developer.android.com/reference/android/content/ActivityNotFoundException
 */
internal class AppStoreLauncher(private val context: Context) {

    /** @return `true` when the Play Store app handled the URI, `false` when the web fallback was used. */
    fun open(market: Uri, web: Uri): Boolean {
        val marketIntent = Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(marketIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Play Store not installed/handling — use the browser fallback below.
        }
        val webIntent = Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(webIntent)
        return false
    }

    /** Honest result text: the user still installs manually. */
    fun message(target: String, viaMarket: Boolean): String {
        val where = if (viaMarket) {
            "Открыл $target в Google Play."
        } else {
            "Google Play не установлен — открыл $target в браузере (веб-версия play.google.com)."
        }
        return "$where Установить приложение молча агент не может: нажмите «Установить» сами, " +
            "а затем проверьте результат через app_is_installed."
    }
}
