package com.andharness.ru.domain.assist.overlay

/** Where a floating-assistant submit must land. */
sealed interface OverlayChatRoute {
    /**
     * An on-screen question: the one persistent topic chat «Распознавание экрана», and the turn may
     * be seeded with the current-screen context.
     */
    data object Screen : OverlayChatRoute

    /**
     * Any other request: a fresh chat created per request, and the turn must carry no screen context
     * at all.
     */
    data object NewChat : OverlayChatRoute
}

/**
 * Pure, Android-free routing of a floating-assistant request to its chat and seed text.
 *
 * The behaviour it pins down:
 *  - a request classified by [OverlayRequestClassifier] as being about the screen goes to ONE
 *    persistent chat (`topic = [SCREEN_TOPIC]`, title = [SCREEN_TITLE]`) and its seed may append the
 *    current-screen block;
 *  - every other request gets a NEW chat (the caller creates it) and [seedText] returns the bare
 *    question — [seedText] ignores any [screenBlock] handed to it, so screen content can never leak
 *    into a non-screen turn.
 *
 * Kept free of `Context`/`AccessibilityService` so the decision is unit-tested on the JVM; the
 * Android I/O (reading the screen, creating the chat, running the turn) stays in the overlay
 * hand-off.
 */
object OverlayChatRouting {

    /** Stable topic under which the persistent screen chat id is remembered (key `chat_screen`). */
    const val SCREEN_TOPIC = "screen"

    /** Title of the one persistent chat every on-screen request lands in. */
    const val SCREEN_TITLE = "Распознавание экрана"

    /** The chat this [text] belongs to. */
    fun route(text: String): OverlayChatRoute =
        if (OverlayRequestClassifier.isScreenRequest(text)) OverlayChatRoute.Screen
        else OverlayChatRoute.NewChat

    /**
     * The text a turn is started with. For a screen request it is the question followed by
     * [screenBlock] (or, when that is null/blank, just the question); for every other request the
     * bare question is returned and [screenBlock] is ignored by construction.
     */
    fun seedText(text: String, screenBlock: String?): String = when (route(text)) {
        OverlayChatRoute.Screen -> OverlayScreenContext.seedMessage(text, screenBlock)
        OverlayChatRoute.NewChat -> text.trim()
    }
}
