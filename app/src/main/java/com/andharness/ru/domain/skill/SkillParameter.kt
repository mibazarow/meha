package com.andharness.ru.domain.skill

import kotlinx.serialization.Serializable

/** Named, optional input of a skill (e.g. `{адрес}`, `{сумма}`). */

@Serializable
data class SkillParameter(
    val name: String,
    val type: SkillParameterType = SkillParameterType.TEXT,
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null,
    /** Allowed values for [SkillParameterType.CHOICE]; ignored for the other types. */
    val choices: List<String> = emptyList(),
)
