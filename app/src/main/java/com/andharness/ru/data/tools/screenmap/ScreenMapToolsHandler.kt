package com.andharness.ru.data.tools.screenmap

import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.screenmap.ScreenMapRender
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Tools over the persisted per-app screen map ([ScreenMapStore]):
 *
 * - `screen_map_get`    — one app's remembered screens, or a summary of all known apps;
 * - `screen_map_list`   — read-only summary (apps + screen counts + last seen);
 * - `screen_map_forget` — clears one app's map, or the whole map.
 *
 * The map is a CACHE of what the agent has already seen, so every rendering ends with an honest
 * reminder that it may be stale and the live screen must be verified with `ui_tree` before acting.
 * Reads run on [Dispatchers.IO] (file access) and never throw; a failure is reported honestly.
 */
class ScreenMapToolsHandler(private val store: ScreenMapStore) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "screen_map_get"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val appPackage = arguments.str("app_package")?.trim()?.takeIf { it.isNotBlank() }
                    val text = if (appPackage == null) {
                        ScreenMapRender.summary(store.list(), now)
                    } else {
                        ScreenMapRender.app(store.get(appPackage), appPackage, now)
                    }
                    ToolResult(true, text)
                }.getOrElse { ToolResult(false, "Ошибка карты экранов: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "screen_map_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching {
                    ToolResult(true, ScreenMapRender.summary(store.list(), System.currentTimeMillis()))
                }.getOrElse { ToolResult(false, "Ошибка карты экранов: ${it.message}") }
            }
        },
        object : ToolHandler {
            override val name = "screen_map_forget"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                runCatching {
                    val appPackage = arguments.str("app_package")?.trim()?.takeIf { it.isNotBlank() }
                    val removed = store.forget(appPackage)
                    ToolResult(true, ScreenMapRender.forget(removed, appPackage))
                }.getOrElse { ToolResult(false, "Ошибка карты экранов: ${it.message}") }
            }
        },
    )
}
