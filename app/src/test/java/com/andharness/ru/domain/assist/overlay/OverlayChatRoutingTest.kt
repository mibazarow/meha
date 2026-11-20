package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [OverlayChatRouting] — where a floating-assistant request lands.
 *
 * Screen requests must go to the one persistent «Распознавание экрана» chat; everything else must
 * get a new chat, and its seed text must be the bare question: [OverlayChatRouting.seedText] ignores
 * any screen block for a non-screen request, so screen content can never leak into an unrelated
 * chat.
 */
class OverlayChatRoutingTest {

    @Test
    fun screenRequestRoutesToThePersistentScreenChat() {
        assertEquals(OverlayChatRoute.Screen, OverlayChatRouting.route("что на экране?"))
        assertEquals(OverlayChatRoute.Screen, OverlayChatRouting.route("read this"))
    }

    @Test
    fun unrelatedRequestRoutesToANewChat() {
        assertEquals(OverlayChatRoute.NewChat, OverlayChatRouting.route("поставь будильник на 7 утра"))
        assertEquals(OverlayChatRoute.NewChat, OverlayChatRouting.route("hello world"))
        assertEquals(OverlayChatRoute.NewChat, OverlayChatRouting.route(""))
    }

    @Test
    fun screenChatTopicAndTitleAreStable() {
        assertEquals("screen", OverlayChatRouting.SCREEN_TOPIC)
        assertEquals("Распознавание экрана", OverlayChatRouting.SCREEN_TITLE)
    }

    @Test
    fun screenSeedTextAppendsTheScreenBlock() {
        val block = "=== Контекст текущего экрана ===\nПриложение: com.example"
        val seed = OverlayChatRouting.seedText("что на экране?", block)
        assertEquals("что на экране?\n\n$block", seed)
    }

    @Test
    fun screenSeedTextWithoutBlockIsJustTheQuestion() {
        assertEquals("что на экране?", OverlayChatRouting.seedText("  что на экране?  ", null))
    }

    @Test
    fun nonScreenSeedTextNeverCarriesScreenContent() {
        // Even when a screen block is available, a non-screen request's seed is the bare question.
        val block = "=== Контекст текущего экрана ===\nПриложение: com.example"
        assertEquals("поставь будильник", OverlayChatRouting.seedText("  поставь будильник  ", block))
        assertEquals("hello world", OverlayChatRouting.seedText("hello world", block))
    }
}
