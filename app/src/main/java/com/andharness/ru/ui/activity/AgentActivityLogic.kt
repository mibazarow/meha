package com.andharness.ru.ui.activity

import com.andharness.ru.domain.agent.ToolLabels
import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.undo.UndoEntry
import com.andharness.ru.domain.undo.UndoKinds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Pure, Android-free mapping from the two journals to what the screen shows.
 *
 * The audit log (`ToolAuditRepository`) and the undo journal (`UndoStore`) share no foreign key:
 * a tool handler writes an [UndoEntry] while it runs, and the dispatcher writes the audit row right
 * after the same call finishes (`ToolRegistryImpl.execute`). They are therefore linked by
 * **tool name + near-identical timestamp**: an audit row is undoable when an unused, supported undo
 * entry with the same [UndoEntry.toolName] lies within [MATCH_TOLERANCE_MS] of its `at`.
 *
 * This is deliberately conservative — a wrong match would offer an «Отменить» that reverts a
 * different action — so an ambiguous/unmatched entry is simply shown as not undoable. Kept pure so
 * ordering, the bound, the match rule and the day grouping are all JVM-testable.
 */
object AgentActivityMapper {

    /** Hard cap on report rows (the "last N" of the screen). */
    const val MAX_ITEMS = 100

    /**
     * How far an undo entry's timestamp may sit from an audit row's `at` and still be considered the
     * same action. The dispatcher records both in the same call, so the real gap is milliseconds;
     * the generous window only tolerates a slow clock/file flush, never a different tool run.
     */
    const val MATCH_TOLERANCE_MS = 5 * 60 * 1000L

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Builds the newest-first report: [audit] is bounded to [limit] rows and each row is paired with
     * its undo entry (see the class doc). Each undo entry is used at most once.
     */
    fun build(
        audit: List<ToolAuditEntry>,
        undo: List<UndoEntry>,
        limit: Int = MAX_ITEMS,
        toleranceMs: Long = MATCH_TOLERANCE_MS,
    ): List<AgentActivityItem> {
        val newestFirst = audit
            .sortedWith(compareByDescending<ToolAuditEntry> { it.at }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))

        val candidates = undo.filter { isUndoableEntry(it) }.sortedBy { it.timestamp }
        val used = BooleanArray(candidates.size)

        return newestFirst.map { entry ->
            val index = bestMatch(entry, candidates, used, toleranceMs)
            val matched = if (index >= 0) {
                used[index] = true
                candidates[index]
            } else {
                null
            }
            entry.toItem(matched)
        }
    }

    /** The undo entry closest in time to [entry] with the same tool, not yet [used], within [toleranceMs]. */
    private fun bestMatch(
        entry: ToolAuditEntry,
        candidates: List<UndoEntry>,
        used: BooleanArray,
        toleranceMs: Long,
    ): Int {
        var bestIndex = -1
        var bestDelta = Long.MAX_VALUE
        candidates.forEachIndexed { index, candidate ->
            if (used[index] || candidate.toolName != entry.tool) return@forEachIndexed
            val delta = abs(candidate.timestamp - entry.at)
            if (delta <= toleranceMs && delta < bestDelta) {
                bestDelta = delta
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * The "is undoable" decision: an entry can be undone only when it exists AND its [UndoEntry.undoKind]
     * is one of [UndoKinds.SUPPORTED]. The store only ever holds supported kinds, so this is a
     * belt-and-braces guard that keeps the UI honest about unknown/foreign kinds.
     */
    fun isUndoableEntry(entry: UndoEntry?): Boolean =
        entry != null && entry.undoKind in UndoKinds.SUPPORTED

    /** Short human line for one row, e.g. «Создаю заметку — успешно». */
    fun summaryLine(item: AgentActivityItem): String =
        "${item.title} — ${if (item.success) "успешно" else "ошибка"}"

    /** `HH:mm` in [zone]; minSdk 26 makes `java.time` available directly. */
    fun timeOfDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(at).atZone(zone).format(TIME_FORMAT)

    /** «Сегодня» / «Вчера» / `dd.MM.yyyy` for [at], relative to [now] in [zone]. */
    fun dayLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> date.format(DAY_FORMAT)
        }
    }

    /**
     * Groups already-newest-first [items] by calendar day, keeping the newest day first (Kotlin's
     * `groupBy` preserves first-encounter order). [now] is passed in so the day labels are testable.
     */
    fun groupByDay(
        items: List<AgentActivityItem>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AgentActivityGroup> =
        items.groupBy { dayLabel(it.at, now, zone) }
            .map { (label, groupItems) -> AgentActivityGroup(label = label, items = groupItems) }

    private fun ToolAuditEntry.toItem(undo: UndoEntry?) = AgentActivityItem(
        auditId = id,
        at = at,
        tool = tool,
        title = ToolLabels.label(tool),
        success = success,
        argsPreview = argsPreview,
        undo = undo,
    )
}
