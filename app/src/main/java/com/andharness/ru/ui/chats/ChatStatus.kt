package com.andharness.ru.ui.chats


/**
 * Resolved status line: the short Russian [text] plus the [kind] the Compose layer maps to a
 * leading Material icon and a state colour (D10 — state is conveyed by colour, not emoji).
 */
internal data class ChatStatus(val kind: ChatStatusKind, val text: String)
