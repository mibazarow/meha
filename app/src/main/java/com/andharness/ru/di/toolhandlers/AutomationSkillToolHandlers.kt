package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.attachments.AttachmentStore
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.alarm.AlarmToolsHandler
import com.andharness.ru.data.tools.automation.AutomationToolsHandler
import com.andharness.ru.data.tools.screen.ScreenToolsHandler
import com.andharness.ru.data.tools.screenmap.ScreenMapToolsHandler
import com.andharness.ru.data.tools.skill.SkillRecordEngine
import com.andharness.ru.data.tools.skill.SkillRunEngine
import com.andharness.ru.data.tools.skill.SkillToolsHandler
import com.andharness.ru.data.tools.skill.SkillTransferToolsHandler
import com.andharness.ru.data.tools.ui.ScreenUnderstandToolsHandler
import com.andharness.ru.data.tools.ui.UiAutomationToolsHandler
import com.andharness.ru.data.vision.OcrRecognizer
import com.andharness.ru.data.vision.VisionCapability
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.automation.AutomationEventRepository
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import dagger.Lazy

/** Alarm/automation, UI-automation, screen-map and skill tool handlers. */
internal fun automationSkillToolHandlers(
    context: Context,
    exactAlarmRepository: ExactAlarmRepository,
    exactAlarmScheduler: com.andharness.ru.data.alarm.ExactAlarmScheduler,
    currentChatProvider: CurrentChatProvider,
    automationRuleRepository: AutomationRuleRepository,
    automationEventRepository: AutomationEventRepository,
    automationScheduler: AutomationScheduler,
    automationTriggerManager: Lazy<AutomationTriggerManager>,
    screenMapStore: ScreenMapStore,
    skillStore: SkillStore,
    visionCapability: VisionCapability,
    ocrRecognizer: OcrRecognizer,
    attachmentStore: AttachmentStore,
    uiInteractionBridge: UiInteractionBridge,
    assistRepository: AssistRepository,
    personRepository: PersonRepository,
    personIdentityRepository: PersonIdentityRepository,
    personAliasRepository: PersonAliasRepository,
    interactionRepository: InteractionRepository,
): List<ToolHandler> =
    (AlarmToolsHandler(exactAlarmRepository, exactAlarmScheduler, currentChatProvider).handlers()
        + AutomationToolsHandler(context, automationRuleRepository, automationEventRepository, automationScheduler, automationTriggerManager).handlers()
        + UiAutomationToolsHandler(
            context,
            screenMapStore,
            assistRepository,
            personRepository,
            personIdentityRepository,
            personAliasRepository,
            interactionRepository,
        ).handlers()
        + ScreenMapToolsHandler(screenMapStore).handlers()
        + SkillToolsHandler(skillStore).handlers()
        + listOf(SkillRunEngine(context, skillStore).handler())
        + SkillRecordEngine(skillStore).handlers()
        + SkillTransferToolsHandler(skillStore).handlers()
        + ScreenUnderstandToolsHandler(context, visionCapability, ocrRecognizer, attachmentStore).handlers()
        + ScreenToolsHandler(context, uiInteractionBridge).handlers()
)
