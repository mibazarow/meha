package com.andharness.ru

import android.app.Application
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.WorkManager
import com.andharness.ru.bg.OfflineActionWorker
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.data.automation.AutomationTriggerManager
import com.andharness.ru.data.chat.ChatMessageVectorIndexer
import com.andharness.ru.data.communications.AudioRetentionScheduler
import com.andharness.ru.data.communications.CommunicationsAutomationScheduler
import com.andharness.ru.data.crash.CrashGuard
import com.andharness.ru.data.embedding.EmbedderDownloadWorker
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.data.index.DeviceDocumentIndexScheduler
import com.andharness.ru.data.index.DeviceImageIndexScheduler
import com.andharness.ru.data.index.DeviceImageObserver
import com.andharness.ru.data.memory.DeviceFactsInvalidationManager
import com.andharness.ru.data.memory.MemoryEmbeddingScheduler
import com.andharness.ru.data.memory.MemoryEntityRepository
import com.andharness.ru.data.memory.MemoryGcScheduler
import com.andharness.ru.data.memory.MemoryPersonRepository
import com.andharness.ru.data.memory.MemoryVectorRepository
import com.andharness.ru.data.messages.StoredMessageEmbeddingScheduler
import com.andharness.ru.data.offline.OfflineActionQueue
import com.andharness.ru.data.objective.ObjectiveWorkersScheduler
import com.andharness.ru.data.prefs.LicenseStore
import com.andharness.ru.data.prefs.LocalModelCleanupPreferences
import com.andharness.ru.data.schedule.ScheduleCatchUp
import com.andharness.ru.data.skill.SkillGcRunner
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.update.InAppUpdater
import com.andharness.ru.di.PdfModule
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.locale.AppLocale
import com.andharness.ru.domain.localserver.LocalModelLeftoverCleaner
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.inbound.AndharnessWidget
import com.andharness.ru.service.OverlayAssistantService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class AndharnessApplication : Application() {

    /**
     * Registers the runtime automation triggers (power/screen/network) and arms the persisted
     * `time` rules for as long as this process is alive (update.md §11.6). Idempotent.
     */
    @Inject
    lateinit var automationTriggerManager: AutomationTriggerManager

    /**
     * Registers the device-fact invalidation hooks (package/media/network/storage) and schedules the
     * periodic sweep (update.md §12.2). Idempotent.
     */
    @Inject
    lateinit var deviceFactsInvalidationManager: DeviceFactsInvalidationManager

    /**
     * Schedules the daily, power-guarded GC of stale low-value memories.
     * Idempotent.
     */
    @Inject
    lateinit var memoryGcScheduler: MemoryGcScheduler

    /**
     * Schedules the periodic, power-guarded embedding of new/stale memories for semantic recall
     * A no-op at runtime while no embedding model is configured. Idempotent.
     */
    @Inject
    lateinit var memoryEmbeddingScheduler: MemoryEmbeddingScheduler

    /**
     * Schedules the periodic, power-guarded embedding of new/stale captured messages (SMS + messenger
     * text) for hybrid `message_search`. A no-op at runtime while semantic search is off. Idempotent.
     */
    @Inject
    lateinit var storedMessageEmbeddingScheduler: StoredMessageEmbeddingScheduler

    /**
     * Installs the crash-protection uncaught-exception handler and the "restart after crash"
     * scheduler (update.md §1.2). Must be installed as early as possible. Idempotent.
     */
    @Inject
    lateinit var crashGuard: CrashGuard

    @Inject
    lateinit var chatRepository: ChatRepository

    /**
     * Backfills `memory ↔ person` graph edges for memories stored before the linker existed (or
     * before a person became known). Bounded per launch; best-effort. Idempotent.
     */
    @Inject
    lateinit var memoryPersonRepository: MemoryPersonRepository

    /**
     * Backfills `memory ↔ entity` graph edges (topics/projects/places) for memories stored before the
     * extractor existed. Bounded per launch; best-effort. Idempotent.
     */
    @Inject
    lateinit var memoryEntityRepository: MemoryEntityRepository

    /**
     * Backfills embedding vectors for memories stored before save-time embedding existed (or whose
     * vector is stale for the bundled model). Bounded per launch; best-effort. Idempotent.
     */
    @Inject
    lateinit var memoryVectorRepository: MemoryVectorRepository

    /**
     * Gives the app's OWN chat messages (`user`/`assistant`) an on-device semantic index. New
     * messages are indexed on write; this is also used for the bounded startup backfill of messages
     * stored before the index existed. Best-effort. Idempotent.
     */
    @Inject
    lateinit var chatMessageVectorIndexer: ChatMessageVectorIndexer

    /**
     * Bundled offline embedder for semantic memory search. Warmed up in the background at startup
     * when semantic search is on, so the first recall does not pay the ~29 MB model load.
     */
    @Inject
    lateinit var localEmbedder: LocalEmbedder

    /** Persisted settings; the warm-up runs only when semantic search is enabled. */
    @Inject
    lateinit var settingsStore: LlmSettingsStore

    /**
     * Fires scheduled tasks/reminders whose due time passed while the device was off/dozing (or after
     * a dropped WorkManager delivery). Bounded per start, idempotent, power-guarded. Idempotent.
     */
    @Inject
    lateinit var scheduleCatchUp: ScheduleCatchUp

    /**
     * Bounded retention for the append-only tool-call audit log (newest 200 rows / 30 days),
     * enforced once per app start. Cheap DB deletes; best-effort. Idempotent.
     */
    @Inject
    lateinit var toolAuditRepository: ToolAuditRepository

    /**
     * In-app updater: checks the update manifest at most once every 12 hours, downloads and verifies
     * the APK, then hands it to the system installer (always with the user's consent).
     */
    @Inject
    lateinit var inAppUpdater: InAppUpdater

    /**
     * Persisted «Плавающий помощник» switch. It survives a process kill, but the bubble's
     * foreground service does not, so the flag is reconciled with reality at every process start
     * (BUG-030).
     */
    @Inject
    lateinit var overlayAssistantStore: OverlayAssistantStore

    /** EULA gate, re-checked here because a bumped agreement must also suppress the bubble. */
    @Inject
    lateinit var licenseStore: LicenseStore

    /**
     * One-shot flag guarding the reclaim of orphaned on-device local-chat-model files (`models/
     * meha-qwen3vl-v2-*`, `slots/`) left behind by the removed feature. Ensures the cleanup runs at
     * most once per install.
     */
    @Inject
    lateinit var localModelCleanupPreferences: LocalModelCleanupPreferences

    /**
     * Persistent app-skill library. The idle-skill GC (skills unused for > 20 days) runs once per
     * app start on [applicationScope]; best-effort and bounded by `SkillCodec.MAX_SKILLS`.
     */
    @Inject
    lateinit var skillStore: SkillStore

    /**
     * Persisted offline action queue (§2 «Очередь действий offline»). Actions that failed for lack
     * of network are retried by [OfflineActionWorker]; if the queue already holds actions at
     * startup, the connected-only drain worker is (re)armed so a retry armed before a process
     * restart is not lost. Idempotent (ExistingWorkPolicy.KEEP).
     */
    @Inject
    lateinit var offlineActionQueue: OfflineActionQueue

    /**
     * Arms the periodic background objective runner (checklist §2 «Параллельные задачи/фоновые
     * цели»). Idempotent, network-free and battery-not-low-constrained; the coordinator it wakes
     * applies the power guard / concurrency limit. OFF unless objectives exist.
     */
    @Inject
    lateinit var objectiveWorkersScheduler: ObjectiveWorkersScheduler

    /**
     * Arms the periodic background processing of call recordings (folder scan + bounded
     * transcription under the mode/power/daily-budget gate). Idempotent, battery-not-low-constrained.
     */
    @Inject
    lateinit var communicationsAutomationScheduler: CommunicationsAutomationScheduler

    /**
     * Arms the daily, power-guarded audio retention GC (keepAudioDays + audioCacheLimitMb;
     * transcripts and metadata are never touched). Idempotent.
     */
    @Inject
    lateinit var audioRetentionScheduler: AudioRetentionScheduler

    /**
     * Arms the whole-device IMAGE indexer: a one-shot full pass + a periodic incremental pass
     * (hardcoded, battery-not-low). A no-op at runtime while the embedder artifacts are not
     * downloaded. Idempotent.
     */
    @Inject
    lateinit var deviceImageIndexScheduler: DeviceImageIndexScheduler

    /**
     * Realtime trigger for the image indexer: registers a MediaStore `ContentObserver` so gallery
     * changes enqueue a prompt incremental pass. Idempotent, process-lifetime.
     */
    @Inject
    lateinit var deviceImageObserver: DeviceImageObserver

    /**
     * Arms the whole-device DOCUMENT indexer: a one-shot full pass + a periodic incremental pass
     * (hardcoded, battery-not-low). A no-op at runtime while the embedder artifacts are not
     * downloaded or shared-storage document access is not granted. Idempotent; serialised with the
     * image pass by the shared `DeviceIndexLock`.
     */
    @Inject
    lateinit var deviceDocumentIndexScheduler: DeviceDocumentIndexScheduler

    /**
     * Guarantees PdfBox-Android's resource loader is initialized before ANY PdfBox API is touched,
     * on EVERY path. The PDF tool handlers already inject this marker, but the background document
     * indexer (`DeviceDocumentIndexer` → `DocumentTextSource` → `DocumentTextExtractor.extractPdf`)
     * did not, so on a cold process a PDF in an indexing pass failed with
     * `ExceptionInInitializerError → IOException: GlyphList
     * 'com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt' not found`
     * because `PDFBoxResourceLoader.init` had never run.
     *
     * Injecting this `@Singleton` marker here makes Hilt construct it — and therefore call
     * `PDFBoxResourceLoader.init(applicationContext)` — once at startup, whichever path runs first.
     * It is idempotent by binding: the provider is `@Singleton`, so init runs exactly once per
     * process and the existing tool-path behavior is unchanged.
     * https://github.com/TomRoush/PdfBox-Android
     */
    @Inject
    lateinit var pdfBoxInitializer: PdfModule.PdfBoxInitializer

    /** Fire-and-forget scope for startup reconciliation that must not block `onCreate`. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Per-app language (RU/EN): apply the persisted language BEFORE any Activity is created, so
        // the very first frame already renders it. On a first launch nothing is set yet, so the
        // stored default (Russian) is applied here — this is the documented "call
        // setApplicationLocales the first time your app is run" corner case. Runs on the main thread
        // (Application.onCreate) and is a no-op once a locale is already set.
        // https://developer.android.com/guide/topics/resources/app-languages
        runCatching { AppLocale.ensureDefault(settingsStore.currentLanguage()) }
            .onFailure { Log.w(TAG, "Applying the per-app locale failed: ${it.message}") }
        // Force construction of the @Singleton PdfBox initializer (which calls
        // PDFBoxResourceLoader.init) before any PdfBox API is used on any path — the PDF tool
        // handlers and, crucially, the background document indexer. See the field's KDoc.
        @Suppress("UNUSED_EXPRESSION")
        pdfBoxInitializer
        crashGuard.install()
        reconcileOverlayAssistant()
        automationTriggerManager.start()
        deviceFactsInvalidationManager.start()
        memoryGcScheduler.start()
        memoryEmbeddingScheduler.start()
        storedMessageEmbeddingScheduler.start()
        objectiveWorkersScheduler.start()
        communicationsAutomationScheduler.start()
        audioRetentionScheduler.start()
        deviceImageIndexScheduler.start()
        deviceImageObserver.start()
        deviceDocumentIndexScheduler.start()
        // Auto-download the EmbeddingGemma 2 embedder artifacts used by whole-device semantic search.
        // The index schedulers above defer/retry until these files exist, so this is what kicks the
        // model fetch off. Enqueued with an UNMETERED constraint and ExistingWorkPolicy.KEEP, so it is
        // idempotent across every app start, non-blocking, and never runs on metered data; offline it
        // simply stays queued. There is no user toggle by design. Best-effort — a scheduling failure
        // must never break startup.
        runCatching { EmbedderDownloadWorker.enqueue(this) }
            .onFailure { Log.w(TAG, "Enqueuing the embedder download failed: ${it.message}") }
        // One-time cleanup of the removed digest feature. The WorkManager work an earlier build
        // enqueued survives an in-place update (app data is preserved), so it must be cancelled here;
        // otherwise WorkManager keeps trying to run worker classes that no longer exist in the APK.
        // Idempotent and cheap on every start.
        runCatching {
            val workManager = WorkManager.getInstance(this)
            workManager.cancelUniqueWork(LEGACY_DAILY_DIGEST_MORNING)
            workManager.cancelUniqueWork(LEGACY_DAILY_DIGEST_EVENING)
            workManager.cancelUniqueWork(LEGACY_DAY_DIGEST_PERIODIC)
        }.onFailure { Log.w(TAG, "Cancelling legacy digest work failed: ${it.message}") }
        // One-time reclaim of the ORPHANED files of the removed on-device local chat model. The
        // feature is gone, but an in-place update preserves app data, so its weights/projector
        // (`models/meha-qwen3vl-v2-*.gguf`) and KV slots (`slots/`) linger. Deleted once, off the
        // main thread, best-effort. The device-search embedder (`models/embedder/**`) and the speech
        // models are NOT touched. The flag is set AFTER the attempt so a transient failure does not
        // retry forever. `Context.getFilesDir()` —
        // https://developer.android.com/reference/android/content/Context#getFilesDir()
        applicationScope.launch {
            if (localModelCleanupPreferences.isCleanupDone()) return@launch
            runCatching {
                val filesDir = filesDir
                val modelsDir = File(filesDir, LocalModelLeftoverCleaner.MODELS_DIR)
                val removed = LocalModelLeftoverCleaner.clean(
                    filesDir = filesDir,
                    modelsDir = modelsDir,
                    delete = { it.deleteRecursively() },
                )
                localModelCleanupPreferences.setCleanupDone()
                Log.i(TAG, "Reclaimed $removed orphaned local-model path(s)")
            }.onFailure { Log.w(TAG, "Local-model orphan cleanup failed: ${it.message}") }
        }
        // Offline queue (§2): an action queued before a process restart would otherwise wait for the
        // next enqueue. Re-arm the connected-only drain worker whenever the queue already has items.
        // Best-effort — a scheduling failure must never break startup.
        runCatching {
            if (offlineActionQueue.count() > 0) {
                OfflineActionWorker.enqueue(this)
                Log.i(TAG, "Offline queue has ${offlineActionQueue.count()} pending action(s); drain armed")
            }
        }.onFailure { Log.w(TAG, "Arming the offline queue drain failed: ${it.message}") }
        // Startup reconciliation: a live stream cannot cross a process restart, so any persisted
        // `isStreaming=1` row is stale and must be reset (otherwise a finished message keeps showing
        // a streaming cursor / a "stop" affordance on the next open).
        applicationScope.launch {
            runCatching { chatRepository.clearStaleStreaming() }
                .onSuccess {
                    if (it > 0) Log.i(TAG, "Cleared $it stale isStreaming row(s) at startup")
                }
                .onFailure { Log.w(TAG, "clearStaleStreaming failed: ${it.message}") }
        }
        // Home-screen widget freshness: `provideGlance` is not restarted by `updateAll`, so the widget
        // composition observes `observeChats()` itself while its session is alive, and this loop wakes
        // it (and reloads its initial data) whenever the app process is running. Bounded and
        // loop-safe: `distinctUntilChanged` drops no-op emissions, `sample` caps the render rate at
        // one per interval (a burst of chat updates cannot trigger a render storm), and rendering only
        // reads the DB — it never writes a chat, so this cannot feed itself. `catch` and `runCatching`
        // keep one failure (the feed erroring, or a single failed render) from cancelling the observer.
        applicationScope.launch {
            chatRepository.observeChats()
                .distinctUntilChanged()
                .sample(WIDGET_REFRESH_INTERVAL_MS)
                .catch { Log.w(TAG, "Widget chat observer failed", it) }
                .collect {
                    runCatching { AndharnessWidget().updateAll(this@AndharnessApplication) }
                        .onFailure { Log.w(TAG, "Widget refresh failed", it) }
                }
        }
        // Startup backfill: memories stored before the linker existed (or before a person became
        // known) have no `memory_persons` edges yet; link a bounded batch so graph recall can find
        // them. Fire-and-forget and best-effort — it must never block or break startup.
        applicationScope.launch {
            runCatching { memoryPersonRepository.linkMissing(MEMORY_PERSON_BACKFILL_BATCH) }
                .onSuccess {
                    if (it > 0) Log.i(TAG, "Backfilled $it memory↔person link(s) at startup")
                }
                .onFailure { Log.w(TAG, "memoryPersonRepository.linkMissing failed: ${it.message}") }
        }
        // Startup backfill: memories stored before the generic entity extractor existed have no
        // `entities` / `memory_entities` edges yet; link a bounded batch so graph recall can reach
        // them by topic/project/place. Fire-and-forget and best-effort — never blocks startup.
        applicationScope.launch {
            runCatching { memoryEntityRepository.linkMissing(MEMORY_ENTITY_BACKFILL_BATCH) }
                .onSuccess {
                    if (it > 0) Log.i(TAG, "Backfilled $it memory↔entity link(s) at startup")
                }
                .onFailure { Log.w(TAG, "memoryEntityRepository.linkMissing failed: ${it.message}") }
        }
        // Startup catch-up: a scheduled task/reminder whose due time passed while the process was not
        // alive (device off/dozing, dropped WorkManager delivery) must not be silently lost. Fire the
        // bounded batch now, reusing the existing scheduler/worker. Best-effort — it must never block
        // or break startup.
        applicationScope.launch {
            runCatching { scheduleCatchUp.reconcile() }
                .onSuccess {
                    if (it > 0) Log.i(TAG, "Caught up $it missed scheduled run(s) at startup")
                }
                .onFailure { Log.w(TAG, "scheduleCatchUp.reconcile failed: ${it.message}") }
        }
        // Bounded retention for the append-only tool-call audit log: every tool dispatch appends a
        // row, so without a prune the table would grow forever. Enforced once at startup on the IO
        // scope; both deletes are indexed and cheap. Best-effort — must never block or break startup.
        applicationScope.launch {
            runCatching { toolAuditRepository.prune() }
                .onFailure { Log.w(TAG, "Pruning the tool audit log failed: ${it.message}") }
        }
        // Idle-skill GC: a skill that has not been successfully run for more than 20 days
        // (SkillGcPolicy.MAX_IDLE_MS) is dead weight, so it is deleted here, once per app start.
        // Fire-and-forget and best-effort — an empty library deletes nothing, and a failed GC must
        // never block or break startup.
        applicationScope.launch {
            runCatching { SkillGcRunner(skillStore).run() }
                .onSuccess {
                    if (it > 0) Log.i(TAG, "Skill GC deleted $it idle skill(s) (unused > 20 days)")
                }
                .onFailure { Log.w(TAG, "Skill GC failed: ${it.message}") }
        }
        // Startup update check. Best-effort and cached for 12h, so at most one manifest fetch per
        // half-day. `check()` never throws and never blocks `onCreate`; a failed check is logged only.
        applicationScope.launch {
            runCatching { inAppUpdater.check() }
                .onFailure { Log.w(TAG, "Update check failed: ${it.message}") }
        }
        // Warm-up: with semantic search on by default the first recall would otherwise pay the
        // ~29 MB model load on the hot path. Fire-and-forget on the IO scope, skipped entirely when
        // the user has switched semantic search off. Never throws and never blocks `onCreate`.
        applicationScope.launch {
            val enabled = runCatching { settingsStore.config.first().semanticSearchEnabled }
                .onFailure { Log.w(TAG, "Reading settings for embedding warm-up failed: ${it.message}") }
                .getOrDefault(false)
            if (enabled) {
                Log.i(TAG, "Warming up the on-device embedding model in the background")
                localEmbedder.warmUp()
                // Backfill: memories saved before save-time embedding existed have no vector yet, so
                // semantic recall cannot see them until the periodic worker runs. Embed a bounded
                // batch now, reusing the model just warmed up. Best-effort — never breaks startup.
                runCatching { memoryVectorRepository.embedMissing(MEMORY_VECTOR_BACKFILL_BATCH) }
                    .onSuccess {
                        if (it > 0) Log.i(TAG, "Backfilled $it memory embedding vector(s) at startup")
                    }
                    .onFailure { Log.w(TAG, "memoryVectorRepository.embedMissing failed: ${it.message}") }
                // Backfill: chat messages stored before `chat_message_vectors` existed have no vector
                // yet; embed a bounded batch now, reusing the model just warmed up, so the app's own
                // chats become semantically searchable. Best-effort — never breaks startup.
                runCatching { chatMessageVectorIndexer.backfill(CHAT_MESSAGE_BACKFILL_BATCHES) }
                    .onSuccess {
                        if (it > 0) Log.i(TAG, "Backfilled $it chat message embedding vector(s) at startup")
                    }
                    .onFailure { Log.w(TAG, "chatMessageVectorIndexer.backfill failed: ${it.message}") }
            }
        }
    }

    /**
     * BUG-030 fix: the floating-assistant switch is persisted, but its foreground service is not —
     * a process kill (crash, low memory) leaves the switch ON with no bubble on screen. Every
     * process start re-applies the gate through [OverlayAssistantService.reconcile], which restarts
     * the service when the flag is ON and all approvals hold, or clears the stale flag otherwise.
     *
     * `OverlayAssistantService.start()` is idempotent — a still-running instance just re-shows its
     * existing bubble — so calling it here cannot create duplicates. Starting a foreground service
     * from the background is allowed for this case because the app holds `SYSTEM_ALERT_WINDOW`, which
     * is one of the documented exemptions; the service itself also starts its foreground state first.
     * https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
     *
     * Best-effort: a failure here must never break app startup.
     */
    private fun reconcileOverlayAssistant() {
        try {
            OverlayAssistantService.reconcile(this, overlayAssistantStore, licenseStore)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay assistant startup reconciliation failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Andharness"

        // Legacy unique WorkManager names of the removed digest feature, cancelled once in onCreate.
        private const val LEGACY_DAILY_DIGEST_MORNING = "andharness_daily_digest_morning"
        private const val LEGACY_DAILY_DIGEST_EVENING = "andharness_daily_digest_evening"
        private const val LEGACY_DAY_DIGEST_PERIODIC = "andharness_day_digest_periodic"

        /** Max memories whose `memory ↔ person` links are backfilled per app start. */
        const val MEMORY_PERSON_BACKFILL_BATCH = 200

        /** Max memories whose `memory ↔ entity` links are backfilled per app start. */
        const val MEMORY_ENTITY_BACKFILL_BATCH = 200

        /** Max memories whose embedding vectors are backfilled per app start. */
        const val MEMORY_VECTOR_BACKFILL_BATCH = 50

        /**
         * Max backfill passes for chat-message embeddings per app start, each up to
         * [ChatMessageVectorIndexer.BACKFILL_BATCH_SIZE] messages (bounded, best-effort).
         */
        const val CHAT_MESSAGE_BACKFILL_BATCHES = 16

        /**
         * How often the home-screen widget may be re-rendered after a chat change. Caps the render
         * rate at one update per interval (burst-safe) while still feeling live.
         */
        const val WIDGET_REFRESH_INTERVAL_MS = 1_500L
    }
}
