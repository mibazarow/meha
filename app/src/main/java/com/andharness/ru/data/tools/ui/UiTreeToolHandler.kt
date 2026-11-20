package com.andharness.ru.data.tools.ui

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ui_tree`: reads and formats the on-screen accessibility element tree. */
internal class UiTreeToolHandler(
    private val engine: UiActionEngine,
) : ToolHandler {
    override val name = "ui_tree"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = engine.serviceOrNull() ?: return@withContext engine.notEnabled()
        try {
            val root = service.rootInActiveWindow
                ?: return@withContext ToolResult(false, "Нет активного окна.")
            val elements = AccessibilityTree.collect(root, MAX_TREE_NODES)
            // Success: best-effort cache of this screen for the per-app screen map. It never
            // affects the tool result and never throws.
            engine.autoRecordScreen(service)
            if (elements.isEmpty()) ToolResult(true, "На экране нет читаемых элементов.")
            else ToolResult(true, AccessibilityTree.format(elements))
        } catch (e: CancellationException) {
            // A cancel/withTimeout must reach the run loop instead of being reported as a
            // normal tool failure (update.md bug #7) — otherwise the timeout is defeated.
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        const val MAX_TREE_NODES = 200
    }
}
