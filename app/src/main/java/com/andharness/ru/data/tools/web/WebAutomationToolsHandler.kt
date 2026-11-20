package com.andharness.ru.data.tools.web

import android.content.Context
import com.andharness.ru.bg.WebEngineService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Entry point that wires the `web_*` tools into the tool registry (see `ToolBindingsModule`).
 *
 * The actual browsing engine is [WebEngine], a long-lived offscreen [android.webkit.WebView] session.
 * It is **hosted by [WebEngineService]** — a foreground service (`dataSync`) that keeps the process
 * alive while the agent browses, so the session survives the app being backgrounded. This class only
 * forwards each tool call to the hosted engine and falls back to its own in-process engine when the
 * OS refuses to start the service (e.g. the Android 12+ background-start restriction).
 *
 * Behaviour of every `web_*` tool is unchanged: same names, arguments, messages and session model —
 * only the host of the engine differs.
 *
 * Hosting references:
 * - Foreground services: https://developer.android.com/develop/background-work/services/fgs
 * - FGS types (Android 14+): https://developer.android.com/develop/background-work/services/fgs/service-types
 */
class WebAutomationToolsHandler(
    context: Context,
    private val okHttpClient: OkHttpClient? = null,
) {

    private val appContext = context.applicationContext

    /**
     * Engine used while the foreground service has not (yet) hosted one. Constructing it is cheap —
     * no [android.webkit.WebView] is created until a tool actually runs on the main thread.
     */
    private val inProcessEngine = WebEngine(appContext, okHttpClient)

    /**
     * One delegating [ToolHandler] per `web_*` tool (same names, same order). The engine is resolved
     * at **execute** time, never at construction/DI time, so that starting the hosting foreground
     * service happens only when a web tool is actually used.
     */
    fun handlers(): List<ToolHandler> = inProcessEngine.handlerNames().map { toolName ->
        object : ToolHandler {
            override val name = toolName

            override suspend fun execute(arguments: JsonObject): ToolResult {
                val engine = resolveEngine() ?: inProcessEngine
                val handler = engine.handler(toolName) ?: inProcessEngine.handler(toolName)
                return handler?.execute(arguments)
                    ?: ToolResult(false, "Инструмент «$toolName» недоступен.")
            }
        }
    }

    /**
     * Returns the engine hosted by [WebEngineService], starting the service if needed and waiting at
     * most [ENGINE_START_TIMEOUT_MS]. Returns null when the OS refuses to start the service or it
     * does not publish an engine in time — the caller then keeps using [inProcessEngine], so the
     * tools keep working exactly as before the service existed.
     */
    private suspend fun resolveEngine(): WebEngine? {
        WebEngineService.engine()?.let { return it }
        if (!WebEngineService.start(appContext)) return null
        return WebEngineService.awaitEngine(ENGINE_START_TIMEOUT_MS)
    }

    private companion object {
        /** Bounded wait for the service to host the engine before falling back in-process. */
        private const val ENGINE_START_TIMEOUT_MS = 5_000L
    }
}
