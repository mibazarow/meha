package com.andharness.ru.domain.skill

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One replayable action. The sealed hierarchy covers exactly the actions the existing UI automation
 * supports (`ui_tap`, `ui_type`, `ui_wait_for_text`, `ui_scroll`, `key_event`, `app_launch`,
 * `open_url`); `delay`/`assert_text` are pure additions for pacing and self-checking.
 *
 * `textTemplate`/`urlTemplate`/`expected` may contain `{parameter}` placeholders rendered by
 * [SkillTemplate] before the step runs.
 */

@Serializable
sealed interface SkillStep {

    @Serializable
    @SerialName("tap")
    data class Tap(val selector: SkillSelector) : SkillStep

    @Serializable
    @SerialName("type")
    data class Type(
        val selector: SkillSelector,
        val textTemplate: String,
    ) : SkillStep

    @Serializable
    @SerialName("wait_for_text")
    data class WaitForText(
        val selector: SkillSelector,
        val timeoutMs: Long = SkillStep.DEFAULT_TIMEOUT_MS,
    ) : SkillStep

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val selector: SkillSelector,
        val direction: SkillScrollDirection = SkillScrollDirection.DOWN,
        val amount: Int = 1,
    ) : SkillStep

    @Serializable
    @SerialName("key")
    data class Key(val keyName: String) : SkillStep

    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(val packageName: String) : SkillStep

    @Serializable
    @SerialName("delay")
    data class Delay(val ms: Long) : SkillStep

    @Serializable
    @SerialName("assert_text")
    data class AssertText(
        val selector: SkillSelector,
        val expected: String,
    ) : SkillStep

    @Serializable
    @SerialName("open_url")
    data class OpenUrl(val urlTemplate: String) : SkillStep

    companion object {
        /** Default wait for `wait_for_text` when the author did not set an explicit timeout. */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
