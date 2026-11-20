package com.andharness.ru.data.notify

import android.util.Log
import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.notifications.NotificationRules
import com.andharness.ru.domain.notifications.RuleImportance
import com.andharness.ru.domain.notify.CapturedNotification
import com.andharness.ru.domain.notify.MemorizeMode
import com.andharness.ru.domain.notify.MemoryPolicy
import com.andharness.ru.domain.notify.NotificationCaptureLog
import com.andharness.ru.domain.notify.NotificationEvent
import com.andharness.ru.domain.notify.NotificationTriage
import com.andharness.ru.domain.notify.RecentNoteDedup
import com.andharness.ru.domain.notify.TriageImportance
import com.andharness.ru.domain.notify.TriageResult
import com.andharness.ru.domain.person.IdentityType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates idea #17: triage one captured notification, keep its **bounded summary** in the
 * in-memory capture log and, when it is important enough, turn it into a short long-term memory
 * note that links back to the person and the source app.
 *
 * Triage always runs; whether an event is captured at all is decided upstream (notification access
 * + the messaging-index opt-in). Gates, in this order (all enforced here, in one place):
 * 1. the capture log keeps only the summary (never the body);
 * 2. the fixed [MemorizeMode.ALL] policy + [MemoryPolicy] decide whether a note is written
 *    (HIGH and NORMAL are memorized, LOW is not); one-time codes/passwords are never memorized;
 * 3. an identical gist from the same source inside the dedupe window is skipped.
 *
 * All methods are suspend and never log message text.
 */
@Singleton
class NotificationTriageEngine @Inject constructor(
    private val assistRepository: AssistRepository,
    private val captureLog: NotificationCaptureLog,
    private val personIndexer: PersonIndexer,
    private val rulesStore: NotificationRulesStore,
) {

    private val dedup = RecentNoteDedup(DEDUPE_WINDOW_MS)

    /** Outcome of one triage, returned to callers and to the debug simulation tool. */
    data class Outcome(
        val result: TriageResult?,
        val captured: Boolean,
        val memorized: Boolean,
        val noteId: Long?,
        val note: String?,
        val skipped: String?,
    )

    /**
     * Triages [event] and, when allowed, memorizes the summary. Never throws: a failing memory write
     * degrades to "captured but not memorized" with the reason kept for honest reporting.
     */
    suspend fun onEvent(event: NotificationEvent): Outcome =
        finish(event, triageAndMaybeMemorize(event))

    /**
     * Content-free trace of one triage: package, importance, category and whether a note was written.
     * The summary, sender and message text are deliberately never logged.
     */
    private fun finish(event: NotificationEvent, outcome: Outcome): Outcome {
        Log.i(
            TAG,
            "triage app=${event.app} importance=${outcome.result?.importance} " +
                "category=${outcome.result?.category} captured=${outcome.captured} " +
                "memorized=${outcome.memorized} note=${outcome.noteId} reason=${outcome.skipped ?: "-"}"
        )
        return outcome
    }

    private suspend fun triageAndMaybeMemorize(event: NotificationEvent): Outcome {
        val at = if (event.at > 0L) event.at else System.currentTimeMillis()
        val repeatCount = captureLog.countFrom(event.app, event.sender, at - REPEAT_WINDOW_MS) + 1
        val personId = try {
            resolvePerson(event)
        } catch (e: Exception) {
            null
        }
        val enriched = event.copy(
            at = at,
            repeatCount = repeatCount,
            senderKnown = event.senderKnown || personId != null,
        )

        // User rules (checklist §1). Defaults are a strict no-op, so an empty rule set changes nothing.
        val rules = rulesStore.current()
        val decision = NotificationRules.classify(enriched.app, rules)
        if (decision == RuleImportance.MUTE) {
            // Not surfaced/forwarded by Меха: no digest entry, no memory note. The real system
            // notification is deliberately left untouched (the listener only observes).
            return Outcome(
                result = null,
                captured = false,
                memorized = false,
                noteId = null,
                note = null,
                skipped = "правило приложения: без уведомлений",
            )
        }
        var result = NotificationTriage.triage(enriched)
        if (decision == RuleImportance.IMPORTANT && !result.secret) {
            result = result.copy(
                importance = TriageImportance.HIGH,
                reasons = result.reasons + "правило пользователя: важное приложение",
            )
        }
        captureLog.add(
            CapturedNotification(
                app = enriched.app,
                sender = enriched.sender,
                summary = result.summary,
                importance = result.importance,
                category = result.category,
                reasons = result.reasons,
                at = at,
            )
        )

        // Quiet hours make Меха silent: the event is still captured (so the digest can show it later),
        // but it is not turned into a proactive memory note. Important events may pass when allowed.
        val quiet = NotificationRules.isQuiet(at, rules.quietHours)
        if (quiet && !(rules.quietAllowImportant && result.importance == TriageImportance.HIGH)) {
            return Outcome(
                result,
                captured = true,
                memorized = false,
                noteId = null,
                note = null,
                skipped = "тихие часы — не запоминаем",
            )
        }

        if (!MemoryPolicy.shouldMemorize(result, MemorizeMode.ALL)) {
            return Outcome(
                result,
                captured = true,
                memorized = false,
                noteId = null,
                note = null,
                skipped = if (result.secret) "одноразовый код — не запоминаем" else "низкая важность — не запоминаем"
            )
        }
        if (dedup.isDuplicate(event.app, result.summary, at)) {
            return Outcome(result, captured = true, memorized = false, noteId = null, note = null, skipped = "дубликат в окне")
        }
        val note = buildNote(enriched, result, personId)
        return try {
            val id = assistRepository.saveMemory(
                text = note,
                tags = buildTags(enriched, result),
                importance = MemoryPolicy.memoryImportance(result),
            )
            dedup.remember(event.app, result.summary, at)
            Outcome(result, captured = true, memorized = true, noteId = id, note = note, skipped = null)
        } catch (e: Exception) {
            Outcome(result, captured = true, memorized = false, noteId = null, note = null, skipped = "ошибка записи: ${e.message}")
        }
    }

    /** The optional link to the personal base: a known messenger handle first, then a phone handle. */
    private suspend fun resolvePerson(event: NotificationEvent): Long? {
        val sender = event.sender?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        personIndexer.personIdFor(IdentityType.MESSENGER, sender, event.app)?.let { return it }
        return personIndexer.personIdFor(IdentityType.PHONE, sender)
    }

    private fun buildNote(event: NotificationEvent, result: TriageResult, personId: Long?): String {
        val app = event.app.substringAfterLast('.').ifBlank { event.app }
        val link = personId?.let { "персона #$it" } ?: event.sender?.trim()?.takeIf { it.isNotEmpty() }
        val tail = listOfNotNull(app, link).joinToString(", ")
        return if (tail.isEmpty()) result.summary else "${result.summary} ($tail)"
    }

    private fun buildTags(event: NotificationEvent, result: TriageResult): String {
        val parts = ArrayList<String>(4)
        parts.add("уведомление")
        parts.add(event.app)
        event.sender?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add(result.category.name.lowercase())
        if (result.importance == TriageImportance.HIGH) parts.add("важное")
        return parts.joinToString(",")
    }

    private companion object {
        const val TAG = "Andharness"

        /** Same source + same summary inside this window is not memorized twice. */
        const val DEDUPE_WINDOW_MS = 30L * 60L * 1000L

        /** Window used to count repeated messages from one sender. */
        const val REPEAT_WINDOW_MS = 15L * 60L * 1000L
    }
}
