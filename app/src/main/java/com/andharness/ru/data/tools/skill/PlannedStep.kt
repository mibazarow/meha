package com.andharness.ru.data.tools.skill

import com.andharness.ru.domain.ui.UiSelector

/** One fully-resolved step of a [SkillPlan]. */
data class PlannedStep(
    /** 1-based step number, for honest per-step reporting. */
    val index: Int,
    /** Machine action name (`launch_app`, `tap`, ...). */
    val action: String,
    /** Human (Russian) one-line description used by the dry-run report. */
    val summary: String,
    val selector: UiSelector? = null,
    /** Rendered text/url/expected value, when the step has one. */
    val payload: String? = null,
    val packageName: String? = null,
    val keyName: String? = null,
    val direction: String? = null,
    val amount: Int = 0,
    val ms: Long = 0L,
    val timeoutMs: Long = 0L,
    /** False for steps that need no accessibility service (`launch_app`, `delay`, `open_url`). */
    val needsAccessibility: Boolean = true,
    /** True only for the BUG-032 startup `launch_app` the planner prepends (never stored). */
    val implicit: Boolean = false,
    /** Non-null when this step cannot be executed (missing parameter, empty selector, ...). */
    val issue: String? = null,
)
