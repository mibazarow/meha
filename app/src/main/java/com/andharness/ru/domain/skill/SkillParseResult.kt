package com.andharness.ru.domain.skill


/** Outcome of parsing author-supplied skill JSON: a value or an honest, human (Russian) error. */
sealed interface SkillParseResult<out T> {
    data class Ok<T>(val value: T) : SkillParseResult<T>
    data class Err(val message: String) : SkillParseResult<Nothing>
}
