package com.andharness.ru.ui.communications

import com.andharness.ru.domain.communications.Recording

/**
 * One row of the «Связи» list. [recording] is the tracked audio; [snippet] is the first matching
 * transcript line when the row came from a transcript search (`null` when browsing all recordings).
 */
data class CommunicationsItem(
    val recording: Recording,
    val snippet: String? = null,
)

/**
 * State of the «Связи» screen.
 *
 * [recordings] is the bounded newest-first list shown when there is no query. [results] holds the
 * recordings whose transcripts match a non-blank [query]; [visibleItems] picks whichever the screen
 * must render. Search is debounced inside [CommunicationsViewModel] so a fast typist does not fire
 * one FTS query per keystroke.
 *
 * [modelDownloaded] and [audioPermissionGranted] drive the honest empty/error states (and the
 * «Открыть настройки» affordance), while [busyRecordingIds] marks recordings with an in-flight
 * transcription so their row can show progress and disable its action.
 *
 * [message] carries an honest failure/status line only — it is cleared on every successful action.
 */
data class CommunicationsUiState(
    val recordings: List<Recording> = emptyList(),
    val query: String = "",
    val results: List<CommunicationsItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val busyRecordingIds: Set<Long> = emptySet(),
    val modelDownloaded: Boolean = false,
    val audioPermissionGranted: Boolean = true,
    val message: String? = null,
) {
    /** True while the user is filtering by [query] rather than browsing everything. */
    val isSearchActive: Boolean get() = query.isNotBlank()

    /** What the list area must show: search hits while a query is active, otherwise all recordings. */
    val visibleItems: List<CommunicationsItem>
        get() = if (isSearchActive) results else recordings.map { CommunicationsItem(it) }
}
