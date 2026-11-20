package com.andharness.ru.di

import com.andharness.ru.data.automation.AutomationEventRepositoryImpl
import com.andharness.ru.data.automation.AutomationRuleRepositoryImpl
import com.andharness.ru.data.automation.AutomationSchedulerImpl
import com.andharness.ru.data.automation.RuleEngineImpl
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.RuleEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires the automation engine (update.md §11.6): rule storage, the time scheduler and the engine. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationModule {

    @Binds
    @Singleton
    abstract fun bindAutomationRuleRepository(impl: AutomationRuleRepositoryImpl): AutomationRuleRepository

    @Binds
    @Singleton
    abstract fun bindAutomationEventRepository(
        impl: AutomationEventRepositoryImpl
    ): AutomationEventRepository

    @Binds
    @Singleton
    abstract fun bindAutomationScheduler(impl: AutomationSchedulerImpl): AutomationScheduler

    @Binds
    @Singleton
    abstract fun bindRuleEngine(impl: RuleEngineImpl): RuleEngine
}
