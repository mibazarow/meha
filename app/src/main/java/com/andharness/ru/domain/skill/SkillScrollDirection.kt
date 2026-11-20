package com.andharness.ru.domain.skill

import kotlinx.serialization.Serializable

/** Direction of a [SkillStep.Scroll]. */

@Serializable
enum class SkillScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}
