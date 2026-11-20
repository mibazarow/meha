package com.andharness.ru.data.tools.sms

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Builds one [ToolHandler] per sms tool: `sms_list`, `sms_send`, `sms_thread`, `sms_conversations`,
 * `subscription_list`, `sms_read_state`, `sms_send_on_sim`, `mms_send`, `sms_delete`, in their
 * original registration order. This class is a thin coordinator: each tool lives in its own
 * `<Tool>Handler` class and the shared SmsManager / SENT-broadcast plumbing is in [SmsSupport].
 */
class SmsToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> {
        val support = SmsSupport(context)
        return listOf(
            SmsListToolHandler(context),
            SmsSendToolHandler(context, support),
            SmsThreadToolHandler(context),
            SmsConversationsToolHandler(context),
            SubscriptionListToolHandler(context),
            SmsReadStateToolHandler(context),
            SmsSendOnSimToolHandler(support),
            MmsSendToolHandler(context, support),
            SmsDeleteToolHandler(context),
        )
    }
}
