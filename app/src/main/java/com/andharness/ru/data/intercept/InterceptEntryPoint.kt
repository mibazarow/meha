package com.andharness.ru.data.intercept

import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.core.WatchdogConfigStore
import com.andharness.ru.domain.automation.RuleEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import android.content.Context

/**
 * Hilt bridge for components that are NOT Hilt-managed by the framework: a
 * `CallScreeningService`, an SMS `BroadcastReceiver` and the call-log `Worker` all pull their
 * dependencies through this entry point instead of relying on a custom factory.
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InterceptEntryPoint {
    fun numberChatManager(): NumberChatManager
    fun numberChatStore(): NumberChatStore
    fun watchdogConfigStore(): WatchdogConfigStore

    /** Automation engine, so the SMS receiver can fire `sms` trigger rules (BUG-006). */
    fun ruleEngine(): RuleEngine

    /** Trusted-SMS photo report (checklist §1): capture + describe + reply for trusted numbers. */
    fun smsPhotoReporter(): SmsPhotoReporter

    /** Stored call/SMS auto-handling rules (checklist §1): the call screener reads them here. */
    fun callRulesStore(): CallRulesStore

    /** Applies the call/SMS rules: auto-reply SMS and the call-back reminder (checklist §1). */
    fun callSmsRuleApplier(): CallSmsRuleApplier
}

/** Convenience accessor used by the service/receiver/worker. */
fun interceptEntryPoint(context: Context): InterceptEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, InterceptEntryPoint::class.java)
