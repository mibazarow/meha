package com.andharness.ru.domain.watch


/**
 * A folder watch (TZ item B4).
 *
 * @param path absolute path inside the tool root being watched.
 * @param mask `FileObserver` event mask used when the watch was started.
 * @param startAt epoch millis when the watch was created.
 */
data class FolderWatch(
    val id: Long,
    val path: String,
    val mask: Int,
    val startAt: Long
)
