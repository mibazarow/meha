package com.andharness.ru.ui.skills

import com.andharness.ru.domain.skill.Skill

/**
 * State of the «Навыки» library screen (M2, SPEC §24.2).
 *
 * [skills] is the whole library in store order; [visibleSkills] applies the search query through the
 * Android-free [SkillLibraryLogic.filter], so the same filtering is unit tested on the JVM. The
 * dialog fields ([details], [editTarget], [deleteTarget], [runTarget], [report]) are mutually
 * independent nulls — a non-null value means that dialog is open.
 */
data class SkillLibraryUiState(
    val skills: List<Skill> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    val runningSkillName: String? = null,
    val message: String? = null,
    val details: Skill? = null,
    val editTarget: Skill? = null,
    val deleteTarget: Skill? = null,
    val runTarget: Skill? = null,
    val report: SkillRunReport? = null,
) {
    /** What the list must render: the filtered library, or everything when the query is blank. */
    val visibleSkills: List<Skill> get() = SkillLibraryLogic.filter(skills, query)

    val isSearchActive: Boolean get() = query.isNotBlank()
}
