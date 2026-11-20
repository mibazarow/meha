package com.andharness.ru.domain.skill

import kotlinx.serialization.Serializable

/**
 * A saved, replayable app skill.
 *
 * [id] is stable across updates; [name] is the human key an agent may address it by; [appPackage]
 * documents which app the skill is meant for (informational for now, the engine may use it to launch
 * the target). [parameters] declares the substitutable inputs, [steps] is the ordered action list.
 * [createdAt]/[updatedAt] are epoch milliseconds.
 *
 * [lastUsedAt] is the last **successful real run** (epoch millis), or `null` while the skill has never
 * been used; the idle GC ([SkillGcPolicy]) falls back to [createdAt] in that case. It is nullable with
 * a default so JSON persisted before the field existed still decodes (kotlinx.serialization uses the
 * default for a missing optional property):
 * https://kotlinlang.org/docs/serialization.html#default-values-are-not-encoded
 */

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String = "",
    val appPackage: String = "",
    val parameters: List<SkillParameter> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastUsedAt: Long? = null,
) {
    /** All `{placeholders}` referenced by the steps (text/url/assert templates). */
    fun referencedParameters(): List<String> = steps
        .flatMap { step ->
            when (step) {
                is SkillStep.Type -> SkillTemplate.placeholders(step.textTemplate)
                is SkillStep.OpenUrl -> SkillTemplate.placeholders(step.urlTemplate)
                is SkillStep.AssertText -> SkillTemplate.placeholders(step.expected)
                else -> emptyList()
            }
        }
        .distinct()
}
