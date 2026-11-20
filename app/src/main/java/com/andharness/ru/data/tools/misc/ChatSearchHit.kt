package com.andharness.ru.data.tools.misc


/** One matching chat with its (bounded) matching snippets; [titleMatch] is a title hit. */
internal data class ChatSearchHit(
    val id: Long,
    val title: String,
    val titleMatch: Boolean,
    val snippets: List<ChatSearchSnippet>,
)
