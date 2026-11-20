package com.andharness.ru.domain.watch


/**
 * A single observed event belonging to a [FolderWatch].
 *
 * @param event raw `FileObserver` event constant.
 * @param name name of the affected file/folder, relative to the watched directory.
 * @param timestamp epoch millis when the event was observed.
 */
data class FolderWatchEvent(
    val id: Long,
    val watchId: Long,
    val event: Int,
    val name: String,
    val timestamp: Long
)
