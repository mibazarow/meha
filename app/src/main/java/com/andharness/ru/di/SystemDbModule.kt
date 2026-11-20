package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.AutomationEventDao
import com.andharness.ru.data.db.dao.AutomationRuleDao
import com.andharness.ru.data.db.dao.CrashLogDao
import com.andharness.ru.data.db.dao.RestartStateDao
import com.andharness.ru.data.db.dao.SshServerDao
import com.andharness.ru.data.db.dao.ToolAuditDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Automation / crash / SSH / audit DAO providers. */
@Module
@InstallIn(SingletonComponent::class)
object SystemDbModule {

    @Provides
    fun provideAutomationRuleDao(database: AppDatabase): AutomationRuleDao = database.automationRuleDao()

    @Provides
    fun provideAutomationEventDao(database: AppDatabase): AutomationEventDao = database.automationEventDao()

    @Provides
    fun provideCrashLogDao(database: AppDatabase): CrashLogDao = database.crashLogDao()

    @Provides
    fun provideRestartStateDao(database: AppDatabase): RestartStateDao = database.restartStateDao()

    @Provides
    fun provideSshServerDao(database: AppDatabase): SshServerDao = database.sshServerDao()

    @Provides
    fun provideToolAuditDao(database: AppDatabase): ToolAuditDao = database.toolAuditDao()
}
