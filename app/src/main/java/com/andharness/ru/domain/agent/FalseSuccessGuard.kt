package com.andharness.ru.domain.agent

/**
 * Deterministic, phrase-only guard against the model claiming it performed an action when it did
 * not actually do so (the "false success" failure: the agent says «создал задачу», but no tool was
 * ever called, or the last tool call returned an error).
 *
 * Pure Kotlin (no Android / Room / coroutines), so it is trivially unit-testable on the JVM and can
 * never throw or block. BUG-059 adds [shouldCorrectDestructive] for delete/clear/trash claims: they
 * additionally require confirming evidence (a destructive tool call that returned success, i.e. one
 * whose handler re-checked the effect). [AgentOrchestratorImpl] uses [shouldCorrect] on a FINAL assistant message:
 * when it returns true, the turn is NOT accepted as final — one corrective system message is fed
 * back and ONE extra model turn is run (bounded to a single correction per user turn).
 */
object FalseSuccessGuard {

    /**
     * Corrective system message appended once when a false success is detected. Mirrors the hard
     * prompt rule («ПРАВИЛА ВЫПОЛНЕНИЯ») but at the exact moment the model violated it.
     */
    const val CORRECTION_PROMPT: String =
        "СТОП. Ты сообщил о выполнении, но инструмент не вызывался (или вернул ошибку). " +
            "Либо вызови нужный инструмент и доложи по его результату, либо честно скажи, что не сделал."

    /**
     * Honest suffix appended to the final message when, even after the single correction, the model
     * still claims success without a confirming tool result. Least-intrusive choice: the model's own
     * text is kept verbatim and only a clear honesty note is added, so the user is never misled.
     */
    const val UNVERIFIED_SUCCESS_NOTE: String =
        "Внимание: инструмент не подтвердил успешное выполнение — действие могло быть НЕ выполнено. " +
            "Проверь результат сам или переформулируй запрос."

    /**
     * Corrective system message appended once when the reply claims a DESTRUCTIVE/CHANGING action
     * (delete / clear / trash) but the run holds no confirming evidence (BUG-059). The model is told
     * to actually re-query the state and, if nothing changed, to say so honestly.
     */
    const val DESTRUCTIVE_CORRECTION_PROMPT: String =
        "СТОП. Ты сообщил об удалении/очистке, но выполнение НЕ подтверждено результатом инструмента. " +
            "Проверь результат фактически: пере-запроси состояние (список/количество/наличие объекта). " +
            "Если не удалилось — честно скажи, что действие НЕ выполнено, и не пиши «готово»."

    /**
     * Tool names known to destroy or move data. After BUG-059 every one of these handlers must
     * return `success=true` ONLY when a re-check confirmed the effect, so a single successful call
     * to any of them counts as confirming evidence. Kept as an explicit list for the audit; the
     * runtime classifier [isDestructiveTool] also matches by name pattern so a new `*_delete` tool
     * is covered without edits here.
     */
    val DESTRUCTIVE_TOOL_NAMES: Set<String> = setOf(
        "contact_delete", "note_delete", "memory_delete", "todo_delete",
        "schedule_delete", "calendar_delete", "files_delete", "files_trash",
        "files_trash_empty",
    )

    /**
     * Destructive-claim roots: wording that asserts a delete/clear actually happened. Kept
     * deliberately narrow (accomplished-past forms) so a plan («удалю», «нужно удалить») does not
     * match. Substring-based and case-insensitive.
     */
    private val DESTRUCTIVE_CLAIM_ROOTS = listOf(
        "удалил", "удалена", "удалены", "удалён", "удален", "удалено",
        "очистил", "очищена", "очищено", "очищены",
        "стёр", "стерт", "стёрт", "стер ", "стёр ",
        "в корзин",
    )

    /**
     * True when [toolName] is a data-destroying/changing tool. Matches the explicit list plus a
     * conservative name pattern, so a destructive claim can be checked against confirming evidence.
     */
    fun isDestructiveTool(toolName: String): Boolean {
        val n = toolName.lowercase()
        return n in DESTRUCTIVE_TOOL_NAMES ||
            n.contains("delete") || n.contains("trash") ||
            n.contains("_remove") || n.contains("_clear") || n.contains("_empty")
    }

    /** True when [assistantText] claims a delete/clear/trash actually happened. */
    fun claimsDestructiveDone(assistantText: String): Boolean {
        if (assistantText.isBlank()) return false
        val lower = assistantText.lowercase()
        return DESTRUCTIVE_CLAIM_ROOTS.any { lower.contains(it) }
    }

    /**
     * BUG-059 core predicate: a reply that claims a destructive action was done must be rejected
     * (and corrected once) unless the run holds confirming evidence — a destructive tool that
     * returned `success=true`, which (by the handler contract established for BUG-059) already
     * means the effect was re-checked. A successful read that merely preceded the claim is NOT
     * evidence.
     */
    fun shouldCorrectDestructive(
        assistantText: String,
        destructiveToolConfirmed: Boolean
    ): Boolean = claimsDestructiveDone(assistantText) && !destructiveToolConfirmed

    /**
     * Action roots shared by requests and success claims. All matching is case-insensitive and
     * substring-based (the language is morphologically rich, so roots work better than whole words).
     */
    private val ACTION_ROOTS = listOf(
        "созда", "добав", "поставь", "постав", "установ", "удали", "удал",
        "переимену", "отправ", "позвон", "заархив", "архив", "запомни", "настрой",
        "напомн", "включи", "выключи", "проверяй", "каждый час", "по расписанию",
        "скопиру", "перемест", "собер", "заброниру", "закаж", "оплат"
    )

    /**
     * Success-claim roots: words a false-success answer typically contains («готово», «сделал»,
     * «создана», «успешно», ...). Substring-based and case-insensitive.
     */
    private val SUCCESS_ROOTS = listOf(
        "созда", "создана", "создал", "добав", "поставил", "установил", "удалил",
        "переименова", "отправил", "позвонил", "заархив", "запомнил", "настроил",
        "напомн", "включил", "готово", "выполнено", "сделано", "сделал",
        "сохранён", "успешно"
    )

    /**
     * True when [userText] reads like a request to perform an action (imperative / action noun).
     * Best-effort heuristic only — a false positive is harmless unless the reply also claims success.
     */
    fun looksLikeActionRequest(userText: String): Boolean {
        if (userText.isBlank()) return false
        val lower = userText.lowercase()
        return ACTION_ROOTS.any { lower.contains(it) }
    }

    /**
     * True when [assistantText] claims the action was completed. Best-effort heuristic; word roots
     * such as «созда» also match the infinitive, which is intentional — in a final answer that reads
     * as a completion claim rather than a plan.
     */
    fun claimsActionDone(assistantText: String): Boolean {
        if (assistantText.isBlank()) return false
        val lower = assistantText.lowercase()
        return SUCCESS_ROOTS.any { lower.contains(it) }
    }

    /**
     * The core predicate: a final assistant message must be rejected (and corrected once) when
     * either
     *  - the turn produced NO tool call at all, the user asked for an action, and the reply claims
     *    success (nothing could have happened); or
     *  - the turn DID call tools, the LAST tool result was an error, and the reply still claims
     *    success (the outcome contradicts the tool output).
     *
     * [toolCallsInTurn] is the number of tool invocations executed during the whole user turn;
     * [lastToolErrored] tells whether the most recent tool result reported a failure.
     */
    fun shouldCorrect(
        userText: String,
        assistantText: String,
        toolCallsInTurn: Int,
        lastToolErrored: Boolean
    ): Boolean {
        if (!claimsActionDone(assistantText)) return false
        return if (toolCallsInTurn == 0) {
            looksLikeActionRequest(userText)
        } else {
            lastToolErrored
        }
    }
}
