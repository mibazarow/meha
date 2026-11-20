package com.andharness.ru.inbound

import android.content.Context
import android.content.Intent
import com.andharness.ru.MainActivity

/**
 * Shared extras and Intent builders for the four inbound entry points (update.md idea #10):
 * the share sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), «Спросить агента»
 * (`ACTION_PROCESS_TEXT`), the Quick Settings tile and the home-screen widget.
 *
 * Every entry point funnels into [MainActivity], which is then the single place that turns an
 * inbound event into navigation (see `MainActivity.handleInboundIntent`). Only the share /
 * process-text receivers touch the database first — they must copy the shared files into
 * app-private storage while the caller's URI grant is still valid — and hand over the id of the
 * chat they created.
 */
object InboundIntents {

    /** Private action that marks an Intent as an inbound-entry-point hand-off. */
    const val ACTION_INBOUND = "com.andharness.ru.action.INBOUND"

    /** Open exactly this chat (already created by [InboundIntentHandler]). */
    const val EXTRA_OPEN_CHAT_ID = "com.andharness.ru.extra.OPEN_CHAT_ID"

    /** Create a brand-new empty chat and open it (Quick Settings tile, widget «Новый чат»). */
    const val EXTRA_NEW_CHAT = "com.andharness.ru.extra.NEW_CHAT"

    /**
     * One-shot identity of an inbound event. `MainActivity` remembers the last handled key in
     * instance state, so a configuration change (rotation) or a process-death restore re-reads the
     * same launch Intent without creating a second chat.
     */
    const val EXTRA_INBOUND_KEY = "com.andharness.ru.extra.INBOUND_KEY"

    /**
     * Marks an inbound Intent as an explicit «что это? / объясни» request, so the seeded chat is
     * sent to the agent right away with an explain/analyze instruction instead of just staging the
     * shared content (see [InboundExplainPolicy]). Set by [ExplainReceiverActivity] on the Intent it
     * receives from the system before handing it to `InboundIntentHandler`.
     */
    const val EXTRA_INBOUND_EXPLAIN = "com.andharness.ru.extra.INBOUND_EXPLAIN"

    /**
     * Intent that opens [MainActivity] on a chat that does not exist yet; the host creates it.
     * Used by the Quick Settings tile and the widget's «Новый чат» action.
     * https://developer.android.com/develop/ui/views/quicksettings-tiles
     */
    fun newChat(context: Context): Intent =
        base(context)
            .putExtra(EXTRA_NEW_CHAT, true)
            .putExtra(EXTRA_INBOUND_KEY, "new:${System.nanoTime()}")

    /**
     * Intent that opens [MainActivity] directly on an already-created [chatId]. The share /
     * process-text receivers build it after they have stored the seed for that chat.
     */
    fun openChat(context: Context, chatId: Long): Intent =
        base(context)
            .putExtra(EXTRA_OPEN_CHAT_ID, chatId)
            .putExtra(EXTRA_INBOUND_KEY, "open:$chatId:${System.nanoTime()}")

    private fun base(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_INBOUND)
            // The hand-off comes from a translucent helper Activity; an explicit NEW_TASK keeps
            // MainActivity in its own (singleTask) task instead of the helper's throwaway one.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
