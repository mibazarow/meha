package com.andharness.ru.data.tools.sms

/**
 * One raw SMS row read from `Telephony.Sms.CONTENT_URI`, used to aggregate per-thread
 * conversation summaries without relying on the `content://sms/conversations` view.
 *
 * Reference: https://developer.android.com/reference/android/provider/Telephony.Sms
 */
internal data class SmsConversationRow(
    val threadId: Long,
    val date: Long,
    val body: String,
)

/**
 * Aggregates [rows] (expected sorted newest-first, i.e. `DATE DESC`) into the output shape
 * `sms_conversations` has always produced: `thread <id> | сообщений: <count> | <latest body>`.
 *
 * The first row seen for a thread is its most recent message, so it becomes the snippet; the
 * count is the number of observed rows for that thread (all SMS messages of the thread when the
 * query is unfiltered). At most [limit] distinct threads are returned, in first-seen (newest)
 * order. Pure Kotlin with no Android runtime, so it is unit-testable on the JVM.
 */
internal fun aggregateSmsConversations(
    rows: List<SmsConversationRow>,
    limit: Int,
): List<String> {
    val byThread = LinkedHashMap<Long, Pair<Int, String>>()
    for (row in rows) {
        val existing = byThread[row.threadId]
        byThread[row.threadId] = if (existing == null) {
            1 to row.body
        } else {
            (existing.first + 1) to existing.second
        }
    }
    return byThread.entries
        .asSequence()
        .take(limit.coerceAtLeast(0))
        .map { (threadId, agg) -> "thread $threadId | сообщений: ${agg.first} | ${agg.second}" }
        .toList()
}
