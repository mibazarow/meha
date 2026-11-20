package com.andharness.ru.data.osint

import kotlinx.serialization.Serializable

/** One candidate web source about a number (search result or fetched page). */

@Serializable
data class OsintSource(
    val title: String,
    val url: String,
    val snippet: String = "",
)
