package com.andharness.ru.bg

import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.RuleEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationTimeWorkerEntryPoint {
    fun automationRuleRepository(): AutomationRuleRepository
    fun automationScheduler(): AutomationScheduler
    fun ruleEngine(): RuleEngine
}
