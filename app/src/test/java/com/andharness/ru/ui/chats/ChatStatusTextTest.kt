package com.andharness.ru.ui.chats

import com.andharness.ru.domain.agent.ToolActivity
import com.andharness.ru.domain.agent.ToolActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStatusTextTest {

    @Test
    fun showsTheCompactionNoticeWhileTheContextIsBeingPrepared() {
        assertEquals(
            "Подождите, идёт сжатие контекста.",
            chatStatusText(contextCompaction = true, toolActivity = null, isStreaming = true)
        )
    }

    @Test
    fun compactionNoticeIsTransientAndYieldsToRealOutput() {
        // Once the model produces output the flag is cleared by the orchestrator: the notice must
        // disappear (nothing is ever persisted as a chat message), leaving the normal status line.
        assertNull(
            "the compaction notice must not survive once compilation finished",
            chatStatusText(contextCompaction = false, toolActivity = null, isStreaming = false)
        )
        assertEquals(
            "Меха печатает…",
            chatStatusText(contextCompaction = false, toolActivity = null, isStreaming = true)
        )
    }

    @Test
    fun compactionNoticeWinsOverToolActivity() {
        val running = ToolActivity("flashlight_set", ToolActivityState.RUNNING)
        assertEquals(
            "Подождите, идёт сжатие контекста.",
            chatStatusText(contextCompaction = true, toolActivity = running, isStreaming = true)
        )
        assertEquals(
            "Включаю фонарик",
            chatStatusText(contextCompaction = false, toolActivity = running, isStreaming = true)
        )
    }

    @Test
    fun statusIsPlainTextAndTheKindDrivesTheIconAndColour() {
        val running = ToolActivity("flashlight_set", ToolActivityState.RUNNING)
        val succeeded = ToolActivity("flashlight_set", ToolActivityState.SUCCEEDED)
        val failed = ToolActivity("flashlight_set", ToolActivityState.FAILED)

        assertEquals(ChatStatusKind.STREAMING, chatStatus(false, null, true)?.kind)
        assertEquals(ChatStatusKind.COMPACTION, chatStatus(true, running, true)?.kind)
        assertEquals(ChatStatusKind.TOOL_RUNNING, chatStatus(false, running, true)?.kind)
        assertEquals(ChatStatusKind.TOOL_SUCCEEDED, chatStatus(false, succeeded, true)?.kind)
        assertEquals(ChatStatusKind.TOOL_FAILED, chatStatus(false, failed, true)?.kind)
        assertNull(chatStatus(false, null, false))

        // The failure line is plain text: no emoji, the warning icon + error colour carry the state.
        assertEquals(
            "Не удалось: Включаю фонарик",
            chatStatus(false, failed, true)?.text
        )
    }

    @Test
    fun providerSlowHintReplacesTheTypingLineAndYieldsToRealOutput() {
        // Nothing arrived yet: the hint tells the user what to check instead of a false "печатает…".
        assertEquals(
            ChatStatusKind.PROVIDER_SLOW,
            chatStatus(
                contextCompaction = false,
                toolActivity = null,
                isStreaming = true,
                providerSlow = true,
            )?.kind
        )
        val text = chatStatusText(
            contextCompaction = false,
            toolActivity = null,
            isStreaming = true,
            providerSlow = true,
        ).orEmpty()
        assertEquals(true, text.contains("баланс"))
        assertEquals(true, text.contains("API-ключа"))

        // Any streamed output clears the flag; the normal typing line comes back.
        assertEquals(
            ChatStatusKind.STREAMING,
            chatStatus(false, null, true, providerSlow = false)?.kind
        )
    }

    @Test
    fun compactionAndToolActivityOutrankTheSlowHint() {
        val running = ToolActivity("flashlight_set", ToolActivityState.RUNNING)
        assertEquals(
            ChatStatusKind.COMPACTION,
            chatStatus(true, running, true, providerSlow = true)?.kind
        )
        assertEquals(
            ChatStatusKind.TOOL_RUNNING,
            chatStatus(false, running, true, providerSlow = true)?.kind
        )
    }

    @Test
    fun contextUsageIndicatorNeedsBothNumbersAndAPositiveBudget() {
        assertEquals("Контекст: использовано 100 / 1000", contextUsageText(100L, 1000L))
        assertNull("no measurement yet → no indicator", contextUsageText(null, 1000L))
        assertNull("no budget → no indicator", contextUsageText(100L, null))
        assertNull("a zero budget must not render a division-by-nothing indicator", contextUsageText(100L, 0L))
    }

    @Test
    fun reasoningShowsThinkingInsteadOfTyping() {
        val status = chatStatus(
            contextCompaction = false,
            toolActivity = null,
            isStreaming = true,
            providerThinking = true,
        )
        assertEquals(ChatStatusKind.THINKING, status?.kind)
        assertEquals("Меха думает…", status?.text)

        // Once visible output starts the thinking flag is cleared by the orchestrator.
        assertEquals(
            ChatStatusKind.STREAMING,
            chatStatus(false, null, true, providerThinking = false)?.kind
        )
    }

    @Test
    fun thinkingOutranksTheSlowHintButYieldsToToolsAndCompaction() {
        val running = ToolActivity("flashlight_set", ToolActivityState.RUNNING)
        assertEquals(
            ChatStatusKind.THINKING,
            chatStatus(false, null, true, providerThinking = true, providerSlow = true)?.kind
        )
        assertEquals(
            ChatStatusKind.TOOL_RUNNING,
            chatStatus(false, running, true, providerThinking = true)?.kind
        )
        assertEquals(
            ChatStatusKind.COMPACTION,
            chatStatus(true, running, true, providerThinking = true)?.kind
        )
    }
}
