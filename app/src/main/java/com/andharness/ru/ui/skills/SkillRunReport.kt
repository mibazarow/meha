package com.andharness.ru.ui.skills


/** Result of one skill run, kept for the report dialog. */
data class SkillRunReport(
    val skillName: String,
    val success: Boolean,
    val text: String,
)
