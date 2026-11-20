package com.andharness.ru.domain.automation

/**
 * Arms/cancels the WorkManager work backing `time` automation rules.
 *
 * Only `time` rules need an OS-scheduled wake-up; all other triggers are event-driven. WorkManager
 * delays are inexact by design, so a `time` rule fires around (not exactly at) its configured minute.
 */
interface AutomationScheduler {
    fun schedule(rule: AutomationRule)
    fun cancel(ruleId: Long)
    fun rescheduleAll(rules: List<AutomationRule>)
}
