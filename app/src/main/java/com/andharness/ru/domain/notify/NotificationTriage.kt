package com.andharness.ru.domain.notify

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.util.Locale

/**
 * Pure, offline heuristics of idea #17: from a captured notification decide **how important** it is
 * and produce a short summary.
 *
 * Design rules (update.md idea #17):
 * - urgent keywords (RU/EN) → HIGH;
 * - a missed call → HIGH;
 * - a direct message from a known contact → NORMAL (at least not LOW);
 * - group/bulk traffic → LOW;
 * - one-time codes / passwords → LOW **and** [TriageResult.secret] = true, so they are never
 *   turned into a memory note even under [MemorizeMode.ALL];
 * - repeated messages from the same sender in a short window raise the importance.
 *
 * The class is deliberately free of Android dependencies so it can be unit-tested on the JVM.
 */
object NotificationTriage {

    /** Hard cap of a summary, so a memory note can never carry a full message body. */
    const val MAX_SUMMARY = 160

    private val URL_RE = Regex("(https?://|www\\.)\\S+")
    // Code-point ranges: Java's regex engine matches a surrogate pair as one code point, so the
    // astral emoji must be addressed as \x{1F000}-\x{1FAFF} (a \uD800-\uDBFF range would never match),
    // plus the BMP symbol/dingbat ranges and the variation-selector/ZWJ joiners.
    private val EMOJI_RE = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2190}-\\x{21FF}\\x{2600}-\\x{27BF}" +
            "\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}]"
    )
    private val CODE_RE = Regex("\\b\\d{4,8}\\b")

    private val OTP_MARKERS = listOf(
        "одноразов", "код", "otp", "one-time", "one time", "verification code",
        "пароль", "password", "пин", "pin"
    )

    private val URGENT_STEMS = listOf(
        // RU
        "срочн", "важн", "немедленн", "деньг", "оплат", "платеж", "платёж", "перевод",
        "перевед", "перечисли", "заказ", "доставк", "посылк", "курьер", "встреч", "дедлайн",
        "подтверд", "позвони", "перезвони", "задолж", "долг", "штраф", "счет", "счёт",
        // EN
        "urgent", "asap", "important", "payment", "invoice", "transfer", "order",
        "delivery", "meeting", "deadline", "call me"
    )

    private val MONEY_MARKERS = listOf(
        "деньг", "оплат", "платеж", "платёж", "перевод", "перевед", "баланс", "счет", "счёт",
        "руб", "₽", "usd", "eur", "$", "payment", "invoice", "transfer", "balance"
    )

    private val MEETING_MARKERS = listOf(
        "встреч", "созвон", "звонок", "митинг", "meeting", "call at", "созвон"
    )

    private val DELIVERY_MARKERS = listOf(
        "доставк", "заказ", "посылк", "курьер", "delivery", "order", "parcel"
    )

    /**
     * Triages one event into a summary + importance. Deterministic and side-effect free.
     */
    fun triage(event: NotificationEvent): TriageResult {
        val secret = looksLikeOtp("${event.title} ${event.text}")
        val summary = summarize(event.sender, event.title, event.text)
        val reasons = ArrayList<String>(4)

        val category = categorize(event, secret)
        val importance = when {
            secret -> {
                reasons.add("одноразовый код/пароль — не запоминаем")
                TriageImportance.LOW
            }

            event.isMissedCall -> {
                reasons.add("пропущенный вызов")
                TriageImportance.HIGH
            }

            event.isGroupOrBulk -> {
                reasons.add("группа/рассылка")
                TriageImportance.LOW
            }

            else -> {
                val hit = urgentHit("${event.title} ${event.text}")
                when {
                    hit != null -> {
                        reasons.add("важный признак: $hit")
                        TriageImportance.HIGH
                    }

                    event.repeatCount >= REPEAT_THRESHOLD -> {
                        reasons.add("повторные сообщения от одного отправителя")
                        TriageImportance.HIGH
                    }

                    event.senderKnown -> {
                        reasons.add("личное сообщение от известного контакта")
                        TriageImportance.NORMAL
                    }

                    !event.sender.isNullOrBlank() -> {
                        reasons.add("личное сообщение")
                        TriageImportance.NORMAL
                    }

                    else -> TriageImportance.NORMAL
                }
            }
        }
        return TriageResult(summary, importance, category, reasons, secret)
    }

    /** True when the text carries a one-time code / password-like marker together with a number. */
    fun looksLikeOtp(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        if (OTP_MARKERS.none { lower.contains(it) }) return false
        return CODE_RE.containsMatchIn(raw)
    }

    /**
     * Builds the concise gist: links and emojis are stripped, whitespace collapsed, the sender is
     * prefixed once, and the result is capped at [MAX_SUMMARY] characters.
     */
    fun summarize(sender: String?, title: String, text: String): String {
        val body = text.trim().ifEmpty { title.trim() }
        val cleaned = clean(body)
        val prefix = sender?.trim()?.takeIf { it.isNotEmpty() && !cleaned.startsWith(it, ignoreCase = true) }
        val full = if (prefix == null) cleaned else "$prefix: $cleaned"
        return full.take(MAX_SUMMARY).trim()
    }

    /** Strips URLs and emoji and collapses whitespace. */
    fun clean(raw: String): String = collapseWhitespace(
        raw.replace(URL_RE, " ")
            .replace(EMOJI_RE, "")
    )

    /** Stable identity of a memorized gist, used by the dedupe window. */
    fun dedupeKey(source: String, summary: String): String =
        "${source.trim().lowercase(Locale.ROOT)}|${summary.trim().lowercase(Locale.ROOT)}"

    private fun urgentHit(raw: String): String? {
        val lower = raw.lowercase(Locale.ROOT)
        return URGENT_STEMS.firstOrNull { lower.contains(it) }
    }

    private fun matches(raw: String, markers: List<String>): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return markers.any { lower.contains(it) }
    }

    private fun categorize(event: NotificationEvent, secret: Boolean): TriageCategory {
        if (secret) return TriageCategory.OTP
        if (event.isMissedCall) return TriageCategory.CALL
        val haystack = "${event.title} ${event.text}"
        if (matches(haystack, MONEY_MARKERS)) return TriageCategory.MONEY
        if (matches(haystack, MEETING_MARKERS)) return TriageCategory.MEETING
        if (matches(haystack, DELIVERY_MARKERS)) return TriageCategory.DELIVERY
        // A sender that is an app name (rather than a person) is treated as bulk traffic.
        if (event.isGroupOrBulk) return TriageCategory.BULK
        if (!event.sender.isNullOrBlank() || event.senderKnown) return TriageCategory.PERSONAL
        return TriageCategory.OTHER
    }

    /** How many repeated messages from one sender in the window tip the event to HIGH. */
    const val REPEAT_THRESHOLD = 3
}
