package com.andharness.ru.data.llm

/**
 * Actionable, user-facing hints attached to provider failures.
 *
 * A bare `Ошибка API (401)` tells the user nothing they can act on. The hints below turn the most
 * common provider failures into concrete checks — balance/limits, key validity, provider
 * availability — so a stalled or rejected request ends with "what to try next" instead of a
 * dead end. Pure and Android-free so the mapping is unit-tested on the JVM.
 */
internal object LlmErrorHints {

    const val KEY_HINT =
        "Проверьте API-ключ: возможно, он истёк, отозван или введён не полностью."
    const val BALANCE_HINT =
        "Проверьте баланс и лимиты у провайдера: средства могли закончиться или квота — исчерпаться."
    const val LIMIT_HINT =
        "Провайдер ограничил запросы (частота или квота). Проверьте баланс и лимиты, затем повторите."
    const val PROVIDER_HINT =
        "Провайдер недоступен или перегружен. Проверьте баланс/лимиты и статус сервиса, затем повторите."
    const val TIMEOUT_HINT =
        "Провайдер долго не отвечает. Проверьте баланс и лимиты, срок действия API-ключа и " +
            "доступность сервиса — или переключитесь на другого провайдера и повторите запрос."
    const val CONNECTION_HINT =
        "Проверьте подключение к интернету и доступность адреса провайдера."

    /**
     * Hint for a non-2xx HTTP response, or `null` when the status is not actionable (the raw
     * provider message is then shown alone).
     */
    fun forHttpStatus(code: Int, body: String): String? = when {
        code == 401 || code == 403 -> KEY_HINT
        code == 402 -> BALANCE_HINT
        code == 429 -> LIMIT_HINT
        code == 400 && mentionsBilling(body) -> BALANCE_HINT
        code >= 500 -> PROVIDER_HINT
        else -> null
    }

    /** Appends [hint] to [message] on its own paragraph; a null hint leaves the message untouched. */
    fun withHint(message: String, hint: String?): String =
        if (hint.isNullOrBlank()) message else "$message\n\n$hint"

    /**
     * Some providers answer 400/403 with a billing/quota sentence instead of a dedicated status.
     * Keywords are matched case-insensitively; the list is intentionally narrow to avoid false
     * positives on unrelated validation errors.
     */
    private fun mentionsBilling(body: String): Boolean {
        val lower = body.lowercase()
        return BILLING_KEYWORDS.any { lower.contains(it) }
    }

    private val BILLING_KEYWORDS = listOf(
        "insufficient",
        "balance",
        "quota",
        "credit",
        "billing",
        "payment",
        "no money",
        "limit exceeded",
        "exceeded your current",
        "недостаточно средств",
        "баланс",
        "квота",
        "лимит",
    )
}
