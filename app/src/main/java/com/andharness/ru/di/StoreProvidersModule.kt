package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.clipboard.ClipboardHistoryStore
import com.andharness.ru.data.notifications.NotificationRulesStore
import com.andharness.ru.data.objective.ObjectiveStore
import com.andharness.ru.data.objective.ObjectiveWorkStore
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.data.calls.CallRulesStore
import com.andharness.ru.data.undo.UndoStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Bindings for the Android-free, JSON-file-backed stores (skills, profile, notification/call/SMS-photo
 * rules, undo journal, clipboard history, screen map). Each only needs the app's private files dir.
 */
@Module
@InstallIn(SingletonComponent::class)
object StoreProvidersModule {

    /**
     * Persistent app-skill library (M2 slice 1): one JSON file under `<filesDir>/skills`.
     * `SkillStore` itself is Android-free (it only takes a directory `File`), so its CRUD is unit
     * tested on the JVM; this binding only supplies the app's private files directory.
     */
    @Provides
    @Singleton
    fun provideSkillStore(@ApplicationContext context: Context): SkillStore =
        SkillStore(File(context.filesDir, SkillStore.DIR_NAME))

    /**
     * Always-on, cross-chat user PROFILE (shared context): one JSON file under `<filesDir>/profile`,
     * bounded to [GlobalProfileStore] rules (100 facts, 400 chars each, 1200-char prompt block).
     * `GlobalProfileStore` is Android-free (it only takes a directory `File`), so its CRUD/bounds/
     * dedupe/atomic persistence are unit tested on the JVM; this binding only supplies the app's
     * private files directory. The same singleton instance is injected into `ContextManagerImpl`
     * (prompt injection) and the `profile_*` tools, so both see one source of truth.
     */
    @Provides
    @Singleton
    fun provideGlobalProfileStore(@ApplicationContext context: Context): GlobalProfileStore =
        GlobalProfileStore(File(context.filesDir, GlobalProfileStore.DIR_NAME))

    /**
     * Cross-chat STYLE profile (form only: address, brevity, slang, profanity, emoji, punctuation).
     * One JSON file under `<filesDir>/style`, bounded by [StyleProfileStore] rules and never
     * containing raw messages. Android-free, so its logic is JVM-tested; this binding only supplies
     * the app's private files directory.
     */
    @Provides
    @Singleton
    fun provideStyleProfileStore(@ApplicationContext context: Context): StyleProfileStore =
        StyleProfileStore(File(context.filesDir, StyleProfileStore.DIR_NAME))

    /**
     * Persistent user-defined notification rules (checklist §1, «Умный инбокс уведомлений»): one JSON
     * file under `<filesDir>/notification_rules`, bounded to 200 per-app rules.
     * `NotificationRulesStore` is Android-free (it only takes a directory `File`), so its CRUD/bounds
     * are unit tested on the JVM; this binding only supplies the app's private files directory.
     */
    @Provides
    @Singleton
    fun provideNotificationRulesStore(@ApplicationContext context: Context): NotificationRulesStore =
        NotificationRulesStore(File(context.filesDir, NotificationRulesStore.DIR_NAME))

    /**
     * Persistent user-defined call/SMS auto-handling rules (checklist §1, «Авто-обработка звонков/SMS»):
     * one JSON file under `<filesDir>/call_rules`, bounded to 200 numbers per list and a 300-char reply.
     * `CallRulesStore` is Android-free (it only takes a directory `File`), so its CRUD/bounds/cooldown
     * log are unit tested on the JVM; this binding only supplies the app's private files directory.
     */
    @Provides
    @Singleton
    fun provideCallRulesStore(@ApplicationContext context: Context): CallRulesStore =
        CallRulesStore(File(context.filesDir, CallRulesStore.DIR_NAME))

    /**
     * Persistent config for the trusted-SMS photo report (checklist §1): one JSON file under
     * `<filesDir>/sms_photo_rule`, OFF by default. `SmsPhotoRuleStore` is Android-free (it only takes
     * a directory `File`), so its CRUD/bounds/cooldown log are unit tested on the JVM.
     */
    @Provides
    @Singleton
    fun provideSmsPhotoRuleStore(@ApplicationContext context: Context): com.andharness.ru.data.sms.SmsPhotoRuleStore =
        com.andharness.ru.data.sms.SmsPhotoRuleStore(
            File(context.filesDir, com.andharness.ru.data.sms.SmsPhotoRuleStore.DIR_NAME),
        )

    /**
     * Persistent journal of reversible actions (M3 «Доверие», slice 1): one JSON file under
     * `<filesDir>/undo`, bounded to the last 50 entries. `UndoStore` is Android-free (it only takes a
     * directory `File`), so its CRUD is unit tested on the JVM; this binding only supplies the app's
     * private files directory.
     */
    @Provides
    @Singleton
    fun provideUndoStore(@ApplicationContext context: Context): UndoStore =
        UndoStore(File(context.filesDir, UndoStore.DIR_NAME))

    /**
     * Persistent clipboard history (M8 «UX-мелочи»): one JSON file under `<filesDir>/clipboard`,
     * bounded to the last 50 entries. It is Android-free (it only takes a directory `File`), so its
     * CRUD/bounds/dedupe are unit tested on the JVM; this binding only supplies the app's private
     * files directory.
     */
    @Provides
    @Singleton
    fun provideClipboardHistoryStore(@ApplicationContext context: Context): ClipboardHistoryStore =
        ClipboardHistoryStore(File(context.filesDir, ClipboardHistoryStore.DIR_NAME))

    /**
     * Persistent per-app screen map (checklist §1 «Знание экранов»): one JSON file under
     * `<filesDir>/screenmap`, bounded to 50 apps / 12 screens per app / 40 elements per screen. It is
     * a CACHE of what the agent has already seen and may be stale — `ScreenMapStore` itself is
     * Android-free (it only takes a directory `File`), so its CRUD/bounds/eviction are unit tested on
     * the JVM; this binding only supplies the app's private files directory.
     */
    @Provides
    @Singleton
    fun provideScreenMapStore(@ApplicationContext context: Context): ScreenMapStore =
        ScreenMapStore(File(context.filesDir, ScreenMapStore.DIR_NAME))

    /**
     * Persistent long-running OBJECTIVES (checklist §1 «Долгие задачи с планом и чекпоинтами»): one
     * JSON file under `<filesDir>/objective`, bounded to 50 objectives (ACTIVE ones never evicted).
     * `ObjectiveStore` is Android-free (it only takes a directory `File`), so its CRUD/bounds/atomic
     * persistence/step transitions are unit tested on the JVM; this binding only supplies the app's
     * private files directory. The same singleton instance is injected into `ContextManagerImpl`
     * (prompt resume block) and the `objective_*` tools, so both see one source of truth — which is
     * what makes an objective survive an app/process restart and be resumable.
     */
    @Provides
    @Singleton
    fun provideObjectiveStore(@ApplicationContext context: Context): ObjectiveStore =
        ObjectiveStore(File(context.filesDir, ObjectiveStore.DIR_NAME))

    /**
     * Persisted background-objective worker config (checklist §2): the concurrency limit and the
     * last tick/error, in one JSON file under `<filesDir>/objective`. Android-free, so its clamping
     * and round-trip are unit tested on the JVM. The RUNNING/QUEUED sets are intentionally NOT
     * persisted (a run cannot survive a process restart).
     */
    @Provides
    @Singleton
    fun provideObjectiveWorkStore(@ApplicationContext context: Context): ObjectiveWorkStore =
        ObjectiveWorkStore(File(context.filesDir, ObjectiveWorkStore.DIR_NAME))
}
