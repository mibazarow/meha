package com.andharness.ru.data.agent

import com.andharness.ru.data.llm.LlmTimeouts
import com.andharness.ru.domain.agent.RunLifecyclePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the orchestrator's per-completion wall-clock backstop so it can never again pre-empt a
 * completion the LLM layer is legally allowed to run.
 *
 * The former `11 min` (660_000 ms) value sat BELOW the streaming data-wait budget (1024 s), so it
 * cut legally-long completions before the LLM layer gave up. The cap is now derived from
 * [LlmTimeouts.MAX_COMPLETION_SEC] plus a margin; these Android-free assertions guard that invariant.
 */
class LlmCompletionCapTest {

    @Test
    fun completionCapNeverPreEmptsTheLlmCompletionBudget() {
        val llmBudgetMs = LlmTimeouts.MAX_COMPLETION_SEC * 1_000L
        assertTrue(
            "LLM_COMPLETION_CAP_MS ($LLM_COMPLETION_CAP_MS) must be >= the LLM overall " +
                "completion budget ($llmBudgetMs ms)",
            LLM_COMPLETION_CAP_MS >= llmBudgetMs
        )
    }

    @Test
    fun completionCapStrictlyExceedsEveryDataWaitBudget() {
        val maxDataWaitMs = maxOf(
            LlmTimeouts.DATA_WAIT_TIMEOUT_SEC,
            LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC
        ) * 1_000L
        assertTrue(
            "LLM_COMPLETION_CAP_MS ($LLM_COMPLETION_CAP_MS) must strictly exceed the largest " +
                "LLM data-wait budget ($maxDataWaitMs ms)",
            LLM_COMPLETION_CAP_MS > maxDataWaitMs
        )
    }

    @Test
    fun completionCapMatchesTheRunHardCap() {
        assertEquals(
            "the per-completion backstop must stay aligned with the run-level hard cap",
            RunLifecyclePolicy.RUN_HARD_CAP_MS,
            LLM_COMPLETION_CAP_MS
        )
    }
}
