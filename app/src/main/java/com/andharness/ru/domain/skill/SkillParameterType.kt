package com.andharness.ru.domain.skill

import kotlinx.serialization.Serializable

/**
 * Data model of a reusable, parameterized **app skill** (M2, SPEC §24.2): "record once, replay with
 * parameters". It is deliberately Android-free and only depends on kotlinx.serialization, so the
 * whole model (and its codec in [SkillCodec]) is exercised on the JVM.
 *
 * A skill is human-authored/persisted JSON: the agent creates it through the `skill_save` tool and a
 * later slice (the run engine) will resolve [SkillSelector]s against the accessibility tree and
 * substitute `{parameter}` placeholders via [SkillTemplate].
 *
 * kotlinx.serialization sealed-class polymorphism:
 * https://kotlinlang.org/docs/serialization.html#sealed-classes
 */

/** Value type of a [SkillParameter]. The engine will coerce the caller's string to it. */

@Serializable
enum class SkillParameterType {
    TEXT,
    NUMBER,
    CHOICE,
}
