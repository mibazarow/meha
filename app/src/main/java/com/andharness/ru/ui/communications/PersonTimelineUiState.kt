package com.andharness.ru.ui.communications

import com.andharness.ru.domain.person.Person

/**
 * State of one person's timeline screen.
 *
 * [person] is the resolved person (the screen's title); it is `null` while loading or when the
 * person id from the route no longer exists. [brief] is the `person_brief`-style summary rendered
 * by `PersonBriefComposer`; [sections] is the merged, day-grouped timeline (interactions +
 * transcript moments + linked memories/opinions).
 *
 * [message] carries an honest failure/status line only.
 */
data class PersonTimelineUiState(
    val person: Person? = null,
    val brief: String? = null,
    val sections: List<PersonTimelineSection> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
)
