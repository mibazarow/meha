package com.andharness.ru.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.assist.OverlayAgentRunner
import com.andharness.ru.data.chat.TopicChatRouter
import com.andharness.ru.data.chat.TopicChatStore
import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.di.DatabaseModule
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.assist.overlay.OverlayChatRouting
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.retrieval.TextRetriever
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.security.DatabaseEncryptionPlanner
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolRegistry
import com.andharness.ru.domain.ui.ActiveChatTracker
import com.andharness.ru.inbound.InboundIntents
import com.andharness.ru.service.OverlayAssistantService
import com.andharness.ru.service.overlay.OverlayAgentHandoff
import com.andharness.ru.service.overlay.OverlayScreenContextReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale

/**
 * Debug-build-only ADB smoke harness. It exists so app tools can be exercised on a real device
 * without an LLM:
 *
 * ```
 * adb shell am broadcast -a com.andharness.ru.DEBUG_TOOL \
 *   --es tool <tool_name> --es args '{"...":"..."}'
 * adb logcat -d -t 200 | grep ANDHARNESS_SMOKE
 * ```
 *
 * Safety: this file lives in `src/debug`, so it is never part of a release APK. The receiver is
 * exported but protected by `android:permission="android.permission.DUMP"` (a signature|privileged
 * permission that ordinary co-installed apps do not hold, while the ADB `shell` user does), so the
 * `adb shell am broadcast -n com.andharness.ru/.debug.DebugToolReceiver …` workflow keeps working and
 * third-party apps are blocked. It FORWARDS a request to [ToolRegistry] (`execute`) for any name in
 * [ALLOWED_TOOLS], and that list mirrors the full tool catalog (`DefaultTools.kt`): it therefore
 * INCLUDES destructive tools such as `files_delete`, `ssh_exec`, `app_uninstall`, `sms_send` and
 * `settings_system_set`. So the bridge can run destructive tools — exercise it only on a disposable
 * test device. Results are written to logcat under [TAG] and to the `debug_tool_bridge` preferences
 * file so they can be read back with `adb shell run-as`.
 */
class DebugToolReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_UI_STATE) {
            respond(
                context,
                buildString {
                    appendLine("UI_STATE")
                    appendLine("isAppForeground=${ActiveChatTracker.isAppForeground}")
                    appendLine("activeChatId=${ActiveChatTracker.activeChatId}")
                }.trim(),
            )
            return
        }

        if (intent.action == ACTION_DB_COUNTS) {
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val text = try {
                    "DB_COUNTS\n" + describeDatabase(appContext)
                } catch (t: Throwable) {
                    "exception=${t::class.java.name}: ${t.message}"
                }
                respond(appContext, text)
                pending.finish()
            }
            return
        }

        if (intent.action == ACTION_SEMANTIC_PROBE) {
            val pending = goAsync()
            val appContext = context.applicationContext
            val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val text = try {
                    if (query.isEmpty()) {
                        "SEMANTIC_PROBE\nquery пуст"
                    } else {
                        semanticProbe(appContext, query)
                    }
                } catch (t: Throwable) {
                    "SEMANTIC_PROBE\nexception=${t::class.java.name}: ${t.message}"
                }
                respond(appContext, text)
                pending.finish()
            }
            return
        }

        if (intent.action == ACTION_SEED_CHAT) {
            val pending = goAsync()
            val appContext = context.applicationContext
            val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
                .ifEmpty { DEFAULT_SEED_TITLE }
            // The exact user message: deliberately NOT trimmed (share path prefixes are not added).
            val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
            val autoRun = intent.getBooleanExtra(EXTRA_AUTO_RUN, true)
            val open = intent.getBooleanExtra(EXTRA_OPEN, true)
            // Debug-only: run this seeded turn as a BACKGROUND run, so the interactive-vs-background
            // concurrency guarantee can be exercised end-to-end on a device.
            val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                // Create the chat and ACK immediately. A full agent turn can take minutes, far past
                // the ~10s a goAsync broadcast may stay open, so it must NOT run in this pending
                // path (that was the ANR): the turn starts only after pending.finish() below.
                // https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
                if (prompt.isBlank()) {
                    respond(appContext, "SEED_ERROR: пустой extra «$EXTRA_PROMPT»")
                    pending.finish()
                    return@launch
                }
                val chatId = try {
                    EntryPointAccessors
                        .fromApplication(appContext, DebugSeedEntryPoint::class.java)
                        .chatRepository()
                        .createChat(title)
                } catch (t: Throwable) {
                    respond(appContext, "SEED_ERROR exception=${t::class.java.name}: ${t.message}")
                    pending.finish()
                    return@launch
                }
                respond(appContext, "SEED_ACCEPTED chatId=$chatId")
                pending.finish()
                runSeededChat(appContext, chatId, title, prompt, autoRun, open, background)
            }
            return
        }

        if (intent.action == ACTION_OVERLAY_ASK) {
            val pending = goAsync()
            val appContext = context.applicationContext
            val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
                .ifEmpty { DEFAULT_OVERLAY_PROMPT }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val text = try {
                    overlayAsk(appContext, prompt)
                } catch (t: Throwable) {
                    "OVERLAY_ASK exception=${t::class.java.name}: ${t.message}"
                }
                respond(appContext, text)
                pending.finish()
            }
            return
        }

        if (intent.action == ACTION_OVERLAY_STATE) {
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val text = try {
                    overlayState(appContext)
                } catch (t: Throwable) {
                    "OVERLAY_STATE exception=${t::class.java.name}: ${t.message}"
                }
                respond(appContext, text)
                pending.finish()
            }
            return
        }

        if (intent.action == ACTION_LLM_STATUS) {
            val pending = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val text = try {
                    llmStatus(appContext)
                } catch (t: Throwable) {
                    "LLM_STATUS exception=${t::class.java.name}: ${t.message}"
                }
                respond(appContext, text)
                pending.finish()
            }
            return
        }

        val tool = intent.getStringExtra(EXTRA_TOOL)?.trim().orEmpty()
        val args = intent.getStringExtra(EXTRA_ARGS)?.trim().orEmpty().ifEmpty { "{}" }
        if (tool.isEmpty()) return
        if (tool !in ALLOWED_TOOLS) {
            respond(context, "REFUSED: инструмент «$tool» вне дымового whitelist ($ALLOWED_TOOLS).")
            return
        }
        // The ordered broadcast must be RELEASED before the tool runs. A tool that awaits its own
        // broadcast (e.g. the SMS SENT result, delivered by the telephony uid 1001) would otherwise
        // stay PENDING behind an ACTIVE DEBUG_TOOL in the per-process broadcast queue for the whole
        // tool run and report a false timeout. So the pending result is finished promptly — the same
        // pattern as ACTION_SEED_CHAT — and the tool runs off the broadcast afterwards; its result is
        // still reported through [respond] (logcat [TAG] + the bridge prefs).
        // https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
        val pending = goAsync()
        pending.finish()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val text = try {
                val registry = EntryPointAccessors
                    .fromApplication(appContext, DebugToolEntryPoint::class.java)
                    .toolRegistry()
                val result = registry.execute(tool, args)
                "success=${result.success}\n${result.output}"
            } catch (t: Throwable) {
                "exception=${t::class.java.name}: ${t.message}"
            }
            respond(appContext, "TOOL=$tool\n$text")
        }
    }

    /**
     * On-device evidence for the SQLCipher smoke (update.md idea #7): the database file header (to
     * prove it is no longer plaintext SQLite), the schema version and exact per-table row counts
     * read through Room — the only reader that can decrypt the file.
     */
    private fun describeDatabase(context: Context): String {
        val database = EntryPointAccessors
            .fromApplication(context, DebugDbEntryPoint::class.java)
            .appDatabase()
        val file = context.getDatabasePath(DatabaseModule.DATABASE_NAME)
        val header = DatabaseEncryptionPlanner.readHeader(file)
        val sqlite = database.openHelper.writableDatabase
        val version = sqlite.query("PRAGMA user_version").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name"
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        val counts = tables.joinToString("\n") { table ->
            val count = sqlite.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
            "$table=$count"
        }
        val headerHex = header?.joinToString("") { "%02x".format(it.toInt() and 0xFF) } ?: "(нет файла)"
        return buildString {
            appendLine("file=${file.name} sizeBytes=${file.length()}")
            appendLine("headerHex=$headerHex")
            appendLine("plaintextSqlite=${DatabaseEncryptionPlanner.isPlaintextSqliteHeader(header)}")
            appendLine("userVersion=$version")
            append(counts)
        }
    }

    /**
     * On-device evidence for tuning `SEMANTIC_RECALL_MIN_COSINE`: embeds [query] with the same bundled
     * model recall uses, then reports the ACTUAL cosine against every stored memory vector, best first.
     * Read-only: it never writes a vector or a memory. Bounded to [PROBE_SCAN_LIMIT] memories and
     * [PROBE_MAX_LINES] printed lines. Never throws for ordinary failures (returns an honest line).
     */
    private suspend fun semanticProbe(context: Context, query: String): String {
        val entryPoint = EntryPointAccessors
            .fromApplication(context, DebugSemanticEntryPoint::class.java)
        val queryVector = entryPoint.localEmbedder().embed(listOf(query)).getOrNull()?.firstOrNull()
        if (queryVector == null || queryVector.isEmpty()) {
            return "SEMANTIC_PROBE\nquery=$query\nошибка: не удалось получить эмбеддинг запроса"
        }
        val memories = entryPoint.assistDao().listNotes(PROBE_KIND_MEMORY, PROBE_SCAN_LIMIT)
        val stored = entryPoint.memoryVectorDao().byIds(memories.map { it.id })
            .associateBy { it.memoryId }
        val scored = memories.mapNotNull { memory ->
            val vector = stored[memory.id]?.let { VectorCodec.decode(it.vector) }
            if (vector == null || vector.isEmpty()) return@mapNotNull null
            Scored(memory.id, TextRetriever.cosine(queryVector, vector), memory.text)
        }.sortedByDescending { it.cosine }

        val max = scored.maxOfOrNull { it.cosine } ?: 0.0
        val mean = if (scored.isEmpty()) 0.0 else scored.sumOf { it.cosine } / scored.size
        return buildString {
            appendLine("SEMANTIC_PROBE")
            appendLine("query=$query")
            appendLine("vectors=${scored.size}")
            scored.take(PROBE_MAX_LINES).forEach { hit ->
                val preview = hit.text.trim().replace('\n', ' ').take(PREVIEW_CHARS)
                appendLine("#${hit.id} cos=${String.format(Locale.US, "%.3f", hit.cosine)} | $preview")
            }
            append("max=${String.format(Locale.US, "%.3f", max)} mean=${String.format(Locale.US, "%.3f", mean)}")
        }
    }

    /**
     * Debug-only showcase seeder, run OFF the broadcast once the chat has been created and the
     * broadcast finished (see [ACTION_SEED_CHAT]). Seeds [chatId] with the exact [prompt] and
     * (unless [autoRun] is off) runs a complete agent turn, waiting for it to finish. The turn is
     * bounded by [RUN_TIMEOUT_MS] so a hung LLM can never block it forever; on timeout the run's own
     * `finally` still finalizes it and the result is reported honestly. Then, optionally, the chat is
     * brought to the foreground via the same [InboundIntents.openChat] path the inbound entry points
     * use. Never throws: the long turn is the whole point of running it here, away from the
     * receiver's ~10s limit.
     */
    private suspend fun runSeededChat(
        context: Context,
        chatId: Long,
        title: String,
        prompt: String,
        autoRun: Boolean,
        open: Boolean,
        background: Boolean,
    ) {
        val entryPoint = EntryPointAccessors
            .fromApplication(context, DebugSeedEntryPoint::class.java)
        val repository = entryPoint.chatRepository()
        val orchestrator = entryPoint.agentOrchestrator()

        var runSummary = "autoRun=false"
        if (autoRun) {
            // A hung model must not hold the run: cancel via timeout and report it as such.
            val outcome = withTimeoutOrNull(RUN_TIMEOUT_MS) {
                orchestrator.run(
                    chatId,
                    prompt,
                    priority = if (background) AgentRunPriority.BACKGROUND
                    else AgentRunPriority.INTERACTIVE,
                )
            }
            runSummary = when (outcome) {
                null -> "timeout>${RUN_TIMEOUT_MS / 1000}s"
                AgentRunOutcome.BUSY -> "busy"
                AgentRunOutcome.STARTED -> "started"
            }
        }

        // `AgentOrchestrator.run` persists the user message itself (exactly `prompt`, no prefix). Only
        // add it when the run did not (autoRun off / BUSY / timed out before persisting), so the chat
        // ends up with exactly ONE user bubble whose text is exactly `prompt`.
        var messages = repository.observeMessages(chatId).first()
        if (messages.none { it.role == "user" && it.content == prompt }) {
            repository.addMessage(chatId, "user", prompt)
            messages = repository.observeMessages(chatId).first()
        }

        val assistantMessages = messages.filter { it.role == "assistant" }
        val assistantLen = assistantMessages.lastOrNull()?.content?.length ?: 0

        var opened = false
        if (open) {
            opened = runCatching {
                context.startActivity(InboundIntents.openChat(context, chatId))
                true
            }.getOrDefault(false)
        }

        Log.i(SEED_LOG_TAG, "SEED_DONE chatId=$chatId asstLen=$assistantLen run=$runSummary open=$opened")
        // The chat is created and the turn is done: report the final summary under the smoke tag.
        // The immediate `SEED_ACCEPTED` was already written before the broadcast finished.
        Log.i(
            TAG,
            buildString {
                appendLine("SEED_DONE chatId=$chatId")
                appendLine("title=$title")
                appendLine("run=$runSummary")
                appendLine("assistantMessages=${assistantMessages.size} lastAssistantChars=$assistantLen")
                append("openRequested=$open opened=$opened")
            },
        )
    }

    /**
     * Debug-only driver for the real floating-assistant hand-off path: builds [OverlayAgentHandoff]
     * exactly as the overlay Service does and sends one [prompt] through it. An on-screen prompt goes
     * to the ONE persistent «Распознавание экрана» chat (remembered under `chat_screen`); an
     * unrelated prompt creates a NEW chat and reports its fresh id with `storedPref=-1` (no screen
     * chat touched).
     */
    private suspend fun overlayAsk(context: Context, prompt: String): String {
        val entryPoint = EntryPointAccessors
            .fromApplication(context, DebugOverlayEntryPoint::class.java)
        val handoff = OverlayAgentHandoff(
            context = context,
            topicChatRouter = entryPoint.topicChatRouter(),
            overlayAgentRunner = entryPoint.overlayAgentRunner(),
            screenContextReader = OverlayScreenContextReader(context.packageName),
            chatRepository = entryPoint.chatRepository(),
            overlayChatTitle = OverlayAssistantService.CHAT_TITLE,
        )
        val chatId = handoff.startTurn(prompt)
        val stored = overlayStoredChatId(context)
        return "OVERLAY_ASK chatId=$chatId storedPref=$stored"
    }

    /**
     * Debug-only read-back of the persistent screen chat: the remembered id (`chat_screen`), its
     * title and how many messages (and user messages) it holds — the observable evidence that
     * on-screen asks appended to the one «Распознавание экрана» chat.
     */
    private suspend fun overlayState(context: Context): String {
        val repository = EntryPointAccessors
            .fromApplication(context, DebugOverlayEntryPoint::class.java)
            .chatRepository()
        val stored = overlayStoredChatId(context)
        val chat = if (stored > 0) repository.observeChat(stored).first() else null
        val messages = if (stored > 0) repository.observeMessages(stored).first() else emptyList()
        return buildString {
            appendLine("OVERLAY_STATE storedPref=$stored")
            appendLine("title=${chat?.title ?: "(нет чата)"}")
            appendLine("messages=${messages.size}")
            append("userMessages=${messages.count { it.role == "user" }}")
        }
    }

    private fun overlayStoredChatId(context: Context): Long =
        context.getSharedPreferences(TopicChatStore.PREFS_FILE, Context.MODE_PRIVATE)
            .getLong(TopicChatStore.keyFor(OverlayChatRouting.SCREEN_TOPIC), -1L)

    /**
     * Debug-only LLM configuration probe. Prints whether a provider is configured, the endpoint HOST
     * only (scheme://host[:port] — the path is never printed), the model name and a MASKED API-key
     * status (only the length and last 4 chars). The full key is never read into the report.
     */
    private suspend fun llmStatus(context: Context): String {
        val store = EntryPointAccessors
            .fromApplication(context, DebugLlmStatusEntryPoint::class.java)
            .llmSettingsStore()
        val configured = store.isConfigured().first()
        val settings = store.config.first()
        val key = runCatching { store.getApiKey() }.getOrNull()
        val keyStatus = if (key.isNullOrEmpty()) {
            "нет (keyLen=0)"
        } else {
            "присутствует keyLen=${key.length} last4=${key.takeLast(4)}"
        }
        return buildString {
            appendLine("LLM_STATUS")
            appendLine("isConfigured=$configured")
            appendLine("endpointHost=${endpointHost(settings.endpoint)}")
            appendLine("model=${settings.model.ifBlank { "(не задана)" }}")
            append("apiKey=$keyStatus")
        }
    }

    /**
     * Reduces an endpoint URL to `scheme://host[:port]`. Deliberately drops the path, query and
     * user-info so a full endpoint (which may embed a key) is never echoed. Returns a placeholder
     * when the value is blank or cannot be parsed.
     */
    private fun endpointHost(endpoint: String): String {
        val raw = endpoint.trim()
        if (raw.isEmpty()) return "(не задан)"
        val parsed = runCatching { URI(raw) }.getOrNull()?.takeIf { it.host != null }
            ?: runCatching { URI("https://$raw") }.getOrNull()?.takeIf { it.host != null }
            ?: return "(хост не распознан)"
        return buildString {
            append(parsed.scheme ?: "https")
            append("://")
            append(parsed.host)
            if (parsed.port != -1) append(":").append(parsed.port)
        }
    }

    /** One memory's measured query cosine, kept alongside its text for the ordered report. */
    private data class Scored(val id: Long, val cosine: Double, val text: String)

    private fun respond(context: Context, text: String) {
        Log.i(TAG, text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, text)
            .commit()
    }

    companion object {
        const val TAG = "ANDHARNESS_SMOKE"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_ARGS = "args"

        /** Debug-only action that reports the encrypted database's header, version and row counts. */
        const val ACTION_DB_COUNTS = "com.andharness.ru.DEBUG_DB_COUNTS"

        /**
         * Debug-only, read-only report of [ActiveChatTracker] (update.md bug #5 evidence): proves
         * on-device which chat the UI considers open and whether the process is foregrounded, so
         * the notification gate can be exercised deterministically without an LLM.
         */
        const val ACTION_UI_STATE = "com.andharness.ru.DEBUG_UI_STATE"

        /**
         * Debug-only cosine probe: embeds the `query` extra and reports the actual cosine against
         * every stored memory vector, so `SEMANTIC_RECALL_MIN_COSINE` can be tuned from evidence. The
         * report is written like any other action (logcat under [TAG] + the bridge prefs). Read-only.
         */
        const val ACTION_SEMANTIC_PROBE = "com.andharness.ru.DEBUG_SEMANTIC_PROBE"

        /** Text extra carrying the probe query for [ACTION_SEMANTIC_PROBE]. */
        const val EXTRA_QUERY = "query"

        /**
         * Debug-only showcase seeder: creates a real chat, seeds the exact `prompt` and (by default)
         * runs a full agent turn, then opens it. The broadcast is acknowledged immediately with
         * `SEED_ACCEPTED chatId=…` and finished; the turn then runs in the background and logs the
         * `SEED_DONE` marker under [SEED_LOG_TAG]. Read the result under [TAG] / the bridge prefs.
         */
        const val ACTION_SEED_CHAT = "com.andharness.ru.DEBUG_SEED_CHAT"

        /** Optional display title for the seeded chat (default [DEFAULT_SEED_TITLE]). */
        const val EXTRA_TITLE = "title"

        /** Required exact user message for [ACTION_SEED_CHAT] (Cyrillic allowed, not prefixed). */
        const val EXTRA_PROMPT = "prompt"

        /** Optional `autoRun` (default true): run one complete agent turn before opening the chat. */
        const val EXTRA_AUTO_RUN = "autoRun"

        /** Optional `open` (default true): bring the seeded chat to the foreground afterwards. */
        const val EXTRA_OPEN = "open"

        /** Optional `background` (default false): run the seeded turn as a BACKGROUND run. */
        const val EXTRA_BACKGROUND = "background"

        /**
         * Debug-only LLM configuration probe: prints whether a provider is configured, the endpoint
         * HOST only and a masked API-key status (never the key itself).
         */
        const val ACTION_LLM_STATUS = "com.andharness.ru.DEBUG_LLM_STATUS"

        /**
         * Debug-only driver for the real floating-assistant hand-off. Takes an optional [EXTRA_PROMPT]
         * and reports `OVERLAY_ASK chatId=… storedPref=…`. An on-screen prompt reuses the one
         * persistent «Распознавание экрана» chat (`storedPref` = `chat_screen`); an unrelated prompt
         * creates a NEW chat each time (`storedPref=-1`).
         */
        const val ACTION_OVERLAY_ASK = "com.andharness.ru.DEBUG_OVERLAY_ASK"

        /** Debug-only read-back of the persistent screen chat (id, title, message counts). */
        const val ACTION_OVERLAY_STATE = "com.andharness.ru.DEBUG_OVERLAY_STATE"

        /** Default prompt for [ACTION_OVERLAY_ASK] when none is supplied (an on-screen request). */
        private const val DEFAULT_OVERLAY_PROMPT = "что на экране?"

        /** Default title for a seeded showcase chat. */
        private const val DEFAULT_SEED_TITLE = "Демо-чат"

        /** Wall-clock cap for one seeded agent turn; on timeout the run is cancelled and reported. */
        private const val RUN_TIMEOUT_MS = 120_000L

        /** Dedicated logcat tag for the seed completion marker, kept apart from [TAG]. */
        const val SEED_LOG_TAG = "ANDHARNESS_SEED"

        private const val PREFS = "debug_tool_bridge"
        private const val KEY_LAST = "last_result"

        /** Text kind of the rows the probe scores (`notes.kind`). */
        private const val PROBE_KIND_MEMORY = "memory"

        /** Bounded scan of memories to compare against, matching recall's candidate order of magnitude. */
        private const val PROBE_SCAN_LIMIT = 500

        /** Cap on printed per-memory lines, so the report stays readable in logcat/prefs. */
        private const val PROBE_MAX_LINES = 20

        /** Characters of memory text shown per line. */
        private const val PREVIEW_CHARS = 60
        // Debug-only smoke allowlist: every tool in DefaultTools.kt (generated list).
        private val ALLOWED_TOOLS = setOf(
            "alarm_exact_cancel", "alarm_exact_list", "alarm_exact_set", "alarm_set", "app_activities", "app_backup_apk", "app_data_usage", "app_deep_link",
            "app_details", "app_install_apk", "app_is_installed", "app_launch", "app_list", "app_notification_settings_open", "app_ops_self", "app_package_info", "app_permissions", "app_receivers", "app_services",
            "app_store_open", "app_store_search", "app_uninstall", "app_usage", "audio_capture_app", "audio_mode_set", "audio_play", "audio_record",
            "audio_stop", "audio_stream_analysis", "audit_export", "auto_number_chat_status", "auto_number_chat_toggle", "autofill_profile_get", "autofill_profile_set", "automation_rule_create",
            "automation_rule_delete", "automation_rule_list", "automation_rule_toggle", "automation_suggestions", "automation_suggestions_simulate", "battery_detail", "battery_optimization_request", "battery_optimization_status",
            "battery_prediction", "ble_connect", "ble_scan", "blocked_number_ui", "bluetooth_devices", "bluetooth_set", "brightness_get", "brightness_set",
            "calendar_accounts_list", "calendar_attendees_set", "calendar_create", "calendar_delete", "calendar_device_events", "calendar_events", "calendar_instances", "calendar_list",
            "calendar_recurring_create", "calendar_reminders_set", "calendar_update", "call_log", "call_log_delete", "call_observe_status", "call_rules_list", "call_rules_set", "call_screening_role_request", "call_start", "camera_burst", "camera_capture",
            "camera_list", "camera_manual", "camera_record_stop", "camera_video_record", "camera_zoom", "chat_clear_messages", "chat_create", "chat_delete", "chat_export", "chat_list", "chat_pin", "chat_recall", "chat_rename", "chat_search", "clipboard_get", "clipboard_history_clear", "clipboard_history_get", "clipboard_history_list", "clipboard_set", "commitments_extract", "communication_search", "compose_message",
            "contact_batch_upsert", "contact_birthdays", "contact_create", "contact_delete", "contact_export_vcf", "contact_get", "contact_group_assign", "contact_group_create", "contact_group_delete", "contact_group_rename", "contact_groups_list",
            "contact_import_vcf", "contact_lookup_by_number", "contact_merge", "contact_photo_clear",
            "contact_photo_get", "contact_photo_set", "contact_search", "contact_split", "contact_star_set", "contact_update", "crash_log_clear", "crash_log_list",
            "device_admin_enable", "device_facts_forget", "device_facts_get", "device_facts_list", "device_facts_refresh", "device_idle_get", "device_info", "device_lock", "device_search",
            "dial", "dialer_role_request", "dnd_set", "dnd_status", "dns_lookup", "doc_create", "doc_edit", "doc_info",
            "doc_read", "docx_append", "docx_replace", "download_file", "esim_status", "events_recent", "exif_read", "exif_write", "exit_reasons", "fgs_start", "fgs_stop",
            "files_7z_create", "files_7z_extract", "files_append", "files_copy", "files_create_dir", "files_delete", "files_dir_size", "files_duplicates",
            "files_grep", "files_gunzip", "files_gzip", "files_hash", "files_list", "files_move", "files_open", "files_rar_extract",
            "files_read", "files_rename", "files_search", "files_stat", "files_tar_create", "files_tar_extract", "files_trash", "files_trash_empty",
            "files_trash_list", "files_trash_restore", "files_unzip", "files_write", "files_zip", "files_zip_list", "flashlight_set", "folder_watch_clear",
            "folder_watch_events", "folder_watch_list", "folder_watch_start", "folder_watch_stop", "font_scale_set", "geofence_add", "geofence_list", "geofence_remove", "health_sensors_list",
            "hotspot_local_start", "hotspot_local_stop", "http_request",             "image_convert", "image_resize", "image_search", "image_thumbnail", "insights_list", "intent_open", "intent_send_to",
            "interaction_search", "internet_panel_open", "ir_transmit", "job_cancel", "job_list", "job_schedule", "keep_alive_start", "keep_alive_stop", "key_event",
            "locale_app_set", "location_get", "location_last_known", "location_settings_open", "location_updates_get", "location_updates_start",
            "location_updates_stop", "mcp_server_add", "mcp_server_list", "mcp_server_remove", "mcp_server_test", "media_audio_list", "media_control_other", "media_delete", "media_downloads_list", "media_favorite_set", "media_frame_extract", "media_metadata",
            "media_request_write", "media_scan", "media_open", "media_sessions_list", "media_share", "media_trash", "media_video_list", "memory_delete", "memory_list", "memory_save",
            "memory_search", "message_search", "messages_summarize", "messaging_index_app_add", "messaging_index_app_remove", "messaging_index_config", "messenger_voice_capture", "mms_send", "network_data_usage", "network_detail", "network_info", "note_create", "note_delete",
            "note_list", "notification_channels_create", "notification_channels_list", "notification_clear_all", "notification_dismiss", "notification_extras", "notification_list", "notification_open", "notification_post",
            "notification_reply", "notification_rules_list", "notification_rules_set", "notification_settings_open", "notification_snooze", "notification_triage_simulate", "notifications_digest", "nsd_discover", "number_osint", "number_profile", "ocr_image",
            "open_settings", "open_url", "opinion_list", "opinion_set", "pdf_create", "pdf_from_markdown", "pdf_from_template", "pdf_from_template_file", "pdf_merge", "pdf_ocr", "pdf_split", "person_alias_set", "person_brief",
            "objective_cancel", "objective_create", "objective_finish", "objective_get", "objective_list", "objective_next", "objective_pause", "objective_resume", "objective_step_update", "objective_run", "objective_stop", "objective_workers_status", "objective_concurrency_set",
            "person_find", "person_get", "person_link", "person_merge", "photos_delete", "photos_list", "ping", "power_guard_status",
            "power_saver_get", "print_file", "profile_forget", "profile_list", "profile_set", "rag_index", "rag_query", "recording_import", "recording_transcribe", "recordings_list", "recordings_scan", "remember_screen", "reminder_set", "repo_apply_patch", "repo_diff", "repo_log",
            "repo_run", "repo_status", "ringer_set", "role_hold", "role_request", "role_status", "rotation_set", "saf_create_document",
            "saf_delete", "saf_grant_remove", "saf_grant_test", "saf_grants_list", "saf_list", "saf_mkdir", "saf_open_document", "saf_pick_tree",
            "saf_read", "saf_write", "schedule_create", "schedule_delete", "schedule_list", "schedule_message", "schedule_message_cancel", "schedule_message_list", "schedule_toggle", "screen_capture",
            "screen_capture_region", "screen_map_forget", "screen_map_get", "screen_map_list", "screen_record_start", "screen_record_status", "screen_record_stop", "screen_state_get", "screen_timeout_set", "screen_understand", "self_config",
            "self_diagnostics", "selftest_run", "sensor_list", "sensor_read", "sensor_subscribe", "sensor_trigger_subscribe", "sensor_unsubscribe", "settings_get",
            "settings_global_get", "settings_known_keys", "settings_secure_get", "settings_system_get", "settings_system_set", "settings_write_access_request", "share_file", "share_files",
            "share_text", "shortcut_pin", "sim_numbers", "sim_set_default_sms", "sim_slots_list", "skill_delete", "skill_export", "skill_get", "skill_import", "skill_list", "skill_record_start", "skill_record_stop", "skill_run", "skill_save", "sms_conversations", "sms_delete", "sms_list", "sms_photo_rule_get", "sms_photo_rule_set", "sms_read_state", "sms_send", "sms_send_on_sim", "sms_thread",
            "speaker_name_assign", "speaker_registry_forget", "speaker_registry_list",
            "speech_to_text", "ssh_exec", "ssh_server_add", "ssh_server_list", "ssh_server_remove", "ssh_server_rename", "ssh_server_update", "ssh_sftp_get", "ssh_sftp_list", "ssh_sftp_put",
            "storage_volumes", "subscription_list", "telephony_info", "text_to_speech", "thermal_status", "timer_set", "todo_add", "todo_delete",
            "todo_done", "todo_list", "tool_search", "transcript_get", "transcript_search", "translate_chat", "translate_screen", "translate_text", "trusted_source_add", "trusted_source_list", "trusted_source_remove", "ui_accessibility_open_settings", "ui_accessibility_status", "ui_copy", "ui_cut", "ui_find", "ui_gesture_multi",
            "ui_long_press", "ui_macro_delete", "ui_macro_list", "ui_macro_run", "ui_macro_start", "ui_macro_stop", "ui_paste", "ui_scroll", "ui_scroll_until",
            "ui_select_all", "ui_swipe", "ui_tap", "ui_tree", "ui_trigger_subscribe", "ui_type", "ui_wait_for_text", "undo_last", "undo_list", "unknown_numbers_list", "usb_info",
            "usb_list", "usb_request_permission", "ussd_request", "vibrate", "vision_check", "vision_status", "volume_get", "volume_set",
            "vpn_prepare", "vpn_status", "wallpaper_set", "web_back", "web_click", "web_download", "web_eval_js", "web_extract",
            "web_fetch", "web_find", "web_forward", "web_navigate", "web_open", "web_readable", "web_reload", "web_screenshot",
            "web_scroll", "web_scroll_until", "web_search", "web_session_load", "web_session_save", "web_set_user_agent",
            "web_submit", "web_tabs_close", "web_tabs_list", "web_tabs_new", "web_tabs_switch", "web_type", "web_wait", "widget_pin",
            "wifi_connect_specifier", "wifi_direct_connect", "wifi_direct_disconnect", "wifi_direct_discover", "wifi_info", "wifi_rtt_range", "wifi_scan", "wifi_set",
            "wifi_suggest_add", "xlsx_set_cell", "xlsx_set_cells",
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugToolEntryPoint {
    fun toolRegistry(): ToolRegistry
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugDbEntryPoint {
    fun appDatabase(): AppDatabase
}

/** Debug-only access to the embedder and memory tables used by the cosine probe. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugSemanticEntryPoint {
    fun localEmbedder(): LocalEmbedder
    fun assistDao(): AssistDao
    fun memoryVectorDao(): MemoryVectorDao
}

/** Debug-only access to the chat/agent APIs used by [DebugToolReceiver.ACTION_SEED_CHAT]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugSeedEntryPoint {
    fun chatRepository(): ChatRepository
    fun agentOrchestrator(): AgentOrchestrator
}

/** Debug-only access to the persistent-chat router and runner used by the overlay hand-off probe. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugOverlayEntryPoint {
    fun topicChatRouter(): TopicChatRouter
    fun overlayAgentRunner(): OverlayAgentRunner
    fun chatRepository(): ChatRepository
}

/** Debug-only access to LLM settings used by [DebugToolReceiver.ACTION_LLM_STATUS]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugLlmStatusEntryPoint {
    fun llmSettingsStore(): LlmSettingsStore
}
