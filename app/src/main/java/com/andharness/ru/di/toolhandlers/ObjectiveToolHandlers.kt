package com.andharness.ru.di.toolhandlers

import com.andharness.ru.data.objective.ObjectiveRunCoordinator
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.tools.objective.ObjectiveToolsHandler
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.tools.ToolHandler

/** Long-running objective tools (checklist §1): plan/execute/checkpoint/resume across sessions. */
internal fun objectiveToolHandlers(
    objectiveStore: ObjectiveStore,
    currentChatProvider: CurrentChatProvider,
    objectiveCoordinator: ObjectiveRunCoordinator
): List<ToolHandler> =
    ObjectiveToolsHandler(objectiveStore, currentChatProvider, objectiveCoordinator).handlers()
