package com.andharness.ru.domain.calls

/**
 * Pure, Android-free construction of the `call_log` query filters. Keeping the decision logic out of
 * the handler makes every filter combination JVM-testable, including the "no filters → default
 * behaviour" case.
 *
 * The `type` argument is normalised to one of [TYPE_INCOMING], [TYPE_OUTGOING], [TYPE_MISSED] or
 * [TYPE_REJECTED]; the handler maps that name to the platform constant `CallLog.Calls.*_TYPE`
 * (`INCOMING_TYPE`, `OUTGOING_TYPE`, `MISSED_TYPE`, `REJECTED_TYPE`):
 * https://developer.android.com/reference/android/provider/CallLog.Calls
 *
 * `days` becomes a `DATE >= ?` clause against `CallLog.Calls.DATE` ("the date the call occurred, in
 * milliseconds since the epoch"):
 * https://developer.android.com/reference/android/provider/CallLog.Calls#DATE
 */
object CallLogFilter {

    /** User-facing call-type names accepted by the `type` argument. */
    const val TYPE_INCOMING = "incoming"
    const val TYPE_OUTGOING = "outgoing"
    const val TYPE_MISSED = "missed"
    const val TYPE_REJECTED = "rejected"

    /** One day in milliseconds, matching `CallLog.Calls.DATE`'s epoch-milliseconds unit. */
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** A SQL selection clause plus its arguments; [clause] is null when no filter applies. */
    data class Selection(val clause: String?, val args: List<String>)

    /**
     * Normalises the `type` argument to a known name (case-insensitive, trimmed), or `null` when it
     * is blank/unknown. A `null` return for a non-blank input is what the handler reports as an
     * invalid type instead of silently ignoring it.
     */
    fun typeName(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        TYPE_INCOMING, "1" -> TYPE_INCOMING
        TYPE_OUTGOING, "2" -> TYPE_OUTGOING
        TYPE_MISSED, "3" -> TYPE_MISSED
        TYPE_REJECTED, "5" -> TYPE_REJECTED
        else -> null
    }

    /**
     * Builds the AND-combined SQL selection for the provided filters. [callType] is a resolved
     * platform type constant (or null); [days] is a positive "last N days" window (or null). When
     * neither is set the result is [Selection]`(null, emptyList())`, which preserves the tool's
     * original "most recent rows" behaviour.
     */
    fun build(callType: Int?, days: Int?, nowMillis: Long): Selection {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (callType != null) {
            clauses.add("$TYPE_COLUMN = ?")
            args.add(callType.toString())
        }
        if (days != null && days > 0) {
            clauses.add("$DATE_COLUMN >= ?")
            args.add((nowMillis - days.toLong() * MILLIS_PER_DAY).toString())
        }
        return Selection(
            clause = if (clauses.isEmpty()) null else clauses.joinToString(" AND "),
            args = args,
        )
    }

    /**
     * `true` when the stored row [stored] matches the `number` filter after digit-based normalisation
     * ([CallLogNumberMatcher]); a blank filter matches nothing extra because the handler only calls
     * this with a non-blank filter. Delegating keeps the same subscriber matching rule used by
     * `call_log_delete`.
     */
    fun matchesNumber(filter: String?, stored: String?): Boolean {
        val wanted = filter?.trim().orEmpty()
        if (wanted.isEmpty()) return true
        return CallLogNumberMatcher.matches(wanted, stored)
    }

    /** Mirrors the `CallLog.Calls` column names without depending on `android.provider`. */
    private const val TYPE_COLUMN = "type"
    private const val DATE_COLUMN = "date"
}
