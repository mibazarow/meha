package com.andharness.ru.data.tools.skill

/** Result of [SkillPlanner.planFromJson]: a plan or an honest (Russian) parsing error. */
sealed interface PlanResult {
    data class Ok(val plan: SkillPlan) : PlanResult
    data class Err(val message: String) : PlanResult
}
