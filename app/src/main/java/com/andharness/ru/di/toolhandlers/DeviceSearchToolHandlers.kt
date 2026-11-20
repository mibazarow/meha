package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.index.DeviceSearchService
import com.andharness.ru.data.index.MediaStorePathResolver
import com.andharness.ru.data.tools.index.DeviceSearchToolHandler
import com.andharness.ru.domain.tools.ToolHandler

/** Whole-device semantic search tool (`device_search`) over the on-device index. */
internal fun deviceSearchToolHandlers(
    deviceSearchService: DeviceSearchService,
    context: Context,
): List<ToolHandler> = listOf(
    DeviceSearchToolHandler(deviceSearchService, MediaStorePathResolver(context))
)
