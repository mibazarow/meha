package com.andharness.ru.service.overlay

import android.content.Context
import android.util.Log
import com.andharness.ru.data.assist.OverlayAgentRunner
import com.andharness.ru.data.chat.TopicChatRouter
import com.andharness.ru.domain.assist.overlay.OverlayChatRoute
import com.andharness.ru.domain.assist.overlay.OverlayChatRouting
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.inbound.InboundIntents

/**
 * Hand-off to the existing agent entry point for the floating assistant, through the same
 * [com.andharness.ru.domain.agent.AgentOrchestrator.run] entry point scheduled tasks use, so it does
 * NOT depend on an Activity being visible (BUG-041).
 *
 * Where a submit lands, and whether the screen is read, is decided by the pure
 * [OverlayChatRouting] / [com.andharness.ru.domain.assist.overlay.OverlayRequestClassifier]:
 *  - a SCREEN request («что на экране», «прочитай/переведи это», …) goes to the ONE persistent
 *    «Распознавание экрана» chat, remembered by [TopicChatRouter.ensure] under the `screen` topic
 *    (preference file `andharness_notif_chats`, key `chat_screen`), and only this branch reads the
 *    current screen and seeds the turn with its context block;
 *  - every OTHER request gets a NEW chat created per request ([ChatRepository.createChat]) and the
 *    turn is seeded with the bare question — the screen is never read and no screen context or
 *    screen instruction is injected.
 *
 * The user message itself is persisted by the orchestrator's turn (see
 * `AgentOrchestratorImpl.initialize`), so this hand-off only creates/ensures the chat and starts the
 * run — appending it here would duplicate the bubble.
 *
 * The previous implementation parked the question in the in-memory seed store and relied on
 * `MainActivity` being launched to hand it to the `ChatViewModel` that consumes seeds. With no
 * Activity in the foreground a background activity launch is not guaranteed
 * (https://developer.android.com/guide/components/activities/secure-bal), so the seed was never
 * consumed and the agent never ran. Now the turn starts in the process itself, and bringing the chat
 * on screen is only a best-effort convenience ([openChat]).
 */
internal class OverlayAgentHandoff(
    private val context: Context,
    private val topicChatRouter: TopicChatRouter,
    private val overlayAgentRunner: OverlayAgentRunner,
    private val screenContextReader: OverlayScreenContextReader,
    private val chatRepository: ChatRepository,
    private val overlayChatTitle: String,
) {

    /**
     * Routes [question] and starts its turn. Returns the chat id it landed in. Throws when the
     * screen chat could not be ensured, so the Service can tell the user the request was not sent
     * instead of opening a chat that does not exist.
     *
     * Runs on an application-lifetime scope, so closing the bubble cannot cancel an answer that is
     * already being produced. The question is persisted into the chat by the orchestrator itself,
     * which is what makes it durable across process death.
     */
    suspend fun startTurn(question: String): Long = when (OverlayChatRouting.route(question)) {
        OverlayChatRoute.Screen -> startScreenTurn(question)
        OverlayChatRoute.NewChat -> startNewChatTurn(question)
    }

    /**
     * On-screen question: the persistent screen chat, seeded with the CURRENT screen context. The
     * read (and its honest "unavailable" note) happens only here.
     */
    private suspend fun startScreenTurn(question: String): Long {
        val block = screenContextReader.readBlock(contextAtOpen = null)
        val seedText = OverlayChatRouting.seedText(question, block)
        val chatId = topicChatRouter.ensure(
            OverlayChatRouting.SCREEN_TOPIC,
            OverlayChatRouting.SCREEN_TITLE,
        )
        check(chatId != TopicChatRouter.NO_CHAT_ID) {
            "не удалось открыть постоянный чат «${OverlayChatRouting.SCREEN_TITLE}»"
        }
        overlayAgentRunner.runOnce(chatId, seedText)
        return chatId
    }

    /**
     * Any other request: a fresh chat per request, seeded with the bare question. No screen read, no
     * screen context — [OverlayChatRouting.seedText] returns the question untouched.
     */
    private suspend fun startNewChatTurn(question: String): Long {
        val chatId = chatRepository.createChat(overlayChatTitle)
        overlayAgentRunner.runOnce(chatId, OverlayChatRouting.seedText(question, screenBlock = null))
        return chatId
    }

    /**
     * Brings the persistent chat on screen when the platform allows it. Background activity launches
     * are restricted since Android 10 and tightened in Android 15 («Secured background activity
     * launches»: non-visible windows no longer count, and `Intent`-based launches need an explicit
     * opt-in). `SYSTEM_ALERT_WINDOW` is a documented exemption while the overlay window is visible,
     * but the submit must never *depend* on it — hence a blocked/failed launch is logged and
     * otherwise ignored, because the chat already exists and the turn already runs.
     * https://developer.android.com/guide/components/activities/secure-bal
     * https://developer.android.com/about/versions/15/behavior-changes-15
     */
    fun openChat(chatId: Long) {
        try {
            context.startActivity(InboundIntents.openChat(context, chatId))
        } catch (e: Exception) {
            Log.w(TAG, "opening the chat from the overlay was blocked: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "OverlayAssistant"
    }
}
