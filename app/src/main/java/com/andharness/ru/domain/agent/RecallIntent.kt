package com.andharness.ru.domain.agent

/**
 * Detects turns where the user is asking about something from the PAST (their own facts, earlier
 * agreements, conversations or messages) and renders a short runtime nudge telling the model to
 * recall BY MEANING before answering.
 *
 * Why this exists: the agent already has `memory_search` / `message_search` (synonym- and
 * paraphrase-aware), but weaker or hasty turns answer from the visible context or from a literal
 * substring search instead of calling them. A deterministic hint on the user message makes the
 * "when should I recall?" decision explicit every time such a question appears, without spending
 * an LLM turn and without ever blocking the answer.
 *
 * Pure Kotlin (no Android deps), deliberately conservative: only explicit recall cues match, so a
 * reminder request («напомни поставить будильник») or an ordinary question never triggers it.
 */
object RecallIntent {

    /** What kind of recall the turn most likely needs. */
    enum class Scope { MEMORY, MESSAGES }

    /**
     * The runtime hint for [userText], or `null` when the turn is not a recall question.
     * [scope] only tunes which tool is named first.
     */
    fun hintFor(userText: String): String? {
        val text = userText.lowercase()
        if (text.isBlank()) return null
        val scope = when {
            MESSAGE_CUES.any { text.contains(it) } -> Scope.MESSAGES
            MEMORY_CUES.any { text.contains(it) } -> Scope.MEMORY
            else -> return null
        }
        // A reminder request ("напомни поставить будильник", "remind me to call mom") is an ACTION,
        // not a question about the past; only "напомни, что/о чём/когда" is recall.
        if (REMINDER_ACTION.any { text.contains(it) } &&
            !RECALL_AFTER_REMIND.any { text.contains(it) }
        ) {
            return null
        }
        return if (scope == Scope.MESSAGES) MESSAGES_HINT else MEMORY_HINT
    }

    private val MESSAGE_CUES = listOf(
        "переписк", "сообщени", "писал", "писала", "написал", "написала", "смс", "sms",
        "звон", "почта", "почту", "письмо", "мессенджер", "в чате", "whatsapp", "telegram",
        "телеграм", "message", "texted", "text me", "messaged", "wrote to me", "email",
        "e-mail", "conversation", "chat history",
    )

    private val MEMORY_CUES = listOf(
        "помнишь", "помните", "вспомни", "вспомните", "напомни, что", "напомни о",
        "напомни про", "напомни, когда", "напомни, как", "напомни, где",
        "что я говорил", "что я сказал", "что я просил", "что я люблю", "что я предпочитаю",
        "что мы обсуждали", "что мы говорили", "мы обсуждали", "мы говорили", "мы договаривались",
        "мы решили", "договорились", "в прошлый раз", "в прошлом", "раньше мы",
        "какие у меня", "какой у меня", "где я живу", "где я работаю", "когда у меня",
        "кто такой", "кто такая", "мой адрес", "моя дата рождения", "день рождения",
        "предпочтени", "привычк",
        "do you remember", "remember when", "remind me what", "remind me about",
        "what did i", "what did we", "we discussed", "we agreed", "last time",
        "my address", "my birthday", "who is", "who was",
    )

    /** Signals that the user asks for a future reminder/action rather than a recall. */
    private val REMINDER_ACTION = listOf(
        "будильник", "таймер", "напоминание", "через ", "завтра", "послезавтра",
        "alarm", "timer", "reminder", "in 5 minutes", "tomorrow",
    )

    /** Words after «напомни», which turn it into a recall question ("напомни, что мы решили"). */
    private val RECALL_AFTER_REMIND = listOf(
        "напомни, что", "напомни о", "напомни про", "напомни, когда", "напомни, как",
        "напомни, где", "remind me what", "remind me about",
    )

    private const val MEMORY_HINT =
        "RECALL HINT (this turn): the user is asking about something from the past. If the fact is " +
            "not explicitly present above, call memory_search with a short key phrase BEFORE " +
            "answering. For conversations/messages call message_search with semantic=true " +
            "(communication_search sweeps every channel). Answer only from what the tools return — " +
            "do not guess or claim you do not remember before the tool actually returns nothing."

    private const val MESSAGES_HINT =
        "RECALL HINT (this turn): the user is asking about past messages or conversations. Call " +
            "message_search with a short key phrase and semantic=true BEFORE answering (use " +
            "communication_search when the channel is unclear, interaction_search for one person). " +
            "Set semantic=false only for an exact quote, number, code or id. Answer only from what " +
            "the tools return — do not guess."
}
