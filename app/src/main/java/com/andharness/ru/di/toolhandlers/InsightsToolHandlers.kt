package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.insights.InsightsAssembler
import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.audit.AuditToolsHandler
import com.andharness.ru.data.tools.calls.CallRulesToolsHandler
import com.andharness.ru.data.tools.insights.InsightsToolsHandler
import com.andharness.ru.data.tools.notifications.NotificationRulesToolsHandler
import com.andharness.ru.data.tools.notifications.NotificationTriageToolsHandler
import com.andharness.ru.data.tools.rag.RagToolsHandler
import com.andharness.ru.data.tools.sms.SmsPhotoRuleToolsHandler
import com.andharness.ru.data.tools.undo.UndoToolsHandler
import com.andharness.ru.data.tools.usage.AutomationSuggestionsToolsHandler
import com.andharness.ru.domain.undo.UndoExecutor
import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.data.usage.AutomationSuggestionEngine
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.notify.NotificationCaptureLog
import com.andharness.ru.domain.rag.RagRepository
import com.andharness.ru.domain.tools.ToolHandler

/** Audit, insights, RAG, notification-triage/rules, automation-suggestions and undo handlers. */
internal fun insightsToolHandlers(
    context: Context,
    toolAuditRepository: ToolAuditRepository,
    skillStore: SkillStore,
    notificationCaptureLog: NotificationCaptureLog,
    ragRepository: RagRepository,
    notificationRulesStore: NotificationRulesStore,
    callRulesStore: CallRulesStore,
    smsPhotoRuleStore: com.andharness.ru.data.sms.SmsPhotoRuleStore,
    automationSuggestionEngine: AutomationSuggestionEngine,
    undoStore: UndoStore,
    undoExecutor: UndoExecutor,
): List<ToolHandler> =
    (AuditToolsHandler(context, toolAuditRepository).handlers()
        + InsightsToolsHandler(
            InsightsAssembler(toolAuditRepository, skillStore, notificationCaptureLog)
        ).handlers()
        + RagToolsHandler(context, ragRepository).handlers()
        + NotificationTriageToolsHandler(
            context,
            notificationCaptureLog,
            notificationRulesStore
        ).handlers()
        + NotificationRulesToolsHandler(notificationRulesStore).handlers()
        + CallRulesToolsHandler(callRulesStore).handlers()
        + SmsPhotoRuleToolsHandler(smsPhotoRuleStore).handlers()
        + AutomationSuggestionsToolsHandler(automationSuggestionEngine).handlers()
        + UndoToolsHandler(undoStore, undoExecutor).handlers()
)
