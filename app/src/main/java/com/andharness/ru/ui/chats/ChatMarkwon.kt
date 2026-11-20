package com.andharness.ru.ui.chats

import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.Browser
import android.util.Log
import android.view.View
import androidx.annotation.ColorInt
import com.andharness.ru.domain.chat.LinkIntent
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * The single Markwon configuration for the chat transcript (built once per screen in `ChatScreen`,
 * shared by every bubble — §18/§19).
 *
 * - [LinkifyPlugin] auto-links bare URLs, e-mails and phone numbers in plain text. It hooks the
 *   core text listener and re-uses the core `Link` span factory, so auto-linked URLs get the same
 *   colour/underline and the same resolver as Markdown links
 *   (https://noties.io/Markwon/docs/v4/linkify/).
 * - The theme sets the link colour to a fixed readable blue (the caller passes
 *   `ChatLinkLight`/`ChatLinkDark`) and keeps the underline on
 *   (`MarkwonTheme.Builder#linkColor` / `#isLinkUnderlined`, underline default true since 4.5.0 —
 *   https://noties.io/Markwon/docs/v4/core/theme.html#link-color).
 * - [SafeLinkResolver] replaces the default resolver so every tap goes through the
 *   [LinkIntent] scheme allow-list before an Activity is started
 *   (https://noties.io/Markwon/docs/v4/core/configuration.html#linkresolver).
 */
fun buildChatMarkwon(context: Context, @ColorInt linkColorArgb: Int): Markwon =
    Markwon.builder(context)
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .linkColor(linkColorArgb)
                    .isLinkUnderlined(true)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver(SafeLinkResolver)
            }
        })
        .build()

/**
 * Opens a tapped link through `Intent(ACTION_VIEW)` only when [LinkIntent] accepts the scheme, and
 * never crashes the transcript: a missing handler (`ActivityNotFoundException`, e.g. `mailto:` with
 * no mail app) or any other runtime failure (malformed URI, `SecurityException`) is logged and
 * swallowed. Mirrors Markwon's own default resolver, which also silently ignores unresolvable
 * links (https://noties.io/Markwon/docs/v4/core/configuration.html#linkresolver,
 * https://developer.android.com/reference/android/content/Intent#ACTION_VIEW).
 * `Browser.EXTRA_APPLICATION_ID` hints the browser to reuse its existing task —
 * https://developer.android.com/reference/android/provider/Browser#EXTRA_APPLICATION_ID
 */
object SafeLinkResolver : LinkResolver {

    private const val TAG = "SafeLinkResolver"

    override fun resolve(view: View, link: String) {
        val intent = LinkIntent.buildViewIntent(link) ?: return
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, view.context.packageName)
        try {
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for link: $link")
        } catch (e: RuntimeException) {
            // Malformed URI / SecurityException / bad component — a chat bubble must never crash.
            Log.w(TAG, "failed to open link: $link", e)
        }
    }
}
