package com.andharness.ru.data.tools.skill

/**
 * Machine action names shared by the pure [SkillPlanner] and the Android-bound [SkillStepExecutor].
 * Kept in one place so both halves of the engine compare exactly the same strings.
 */
internal object SkillActions {
    const val LAUNCH_APP = "launch_app"
    const val OPEN_URL = "open_url"
    const val DELAY = "delay"
    const val TAP = "tap"
    const val TYPE = "type"
    const val WAIT_FOR_TEXT = "wait_for_text"
    const val SCROLL = "scroll"
    const val KEY = "key"
    const val ASSERT_TEXT = "assert_text"
}
