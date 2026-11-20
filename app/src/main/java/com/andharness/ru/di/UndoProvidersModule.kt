package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.data.undo.UndoActions
import com.andharness.ru.domain.undo.UndoExecutor
import com.andharness.ru.domain.undo.UndoRecorder
import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.domain.assist.AssistRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Undo journal surfaces: the write recorder and the inverse-dispatch executor. */
@Module
@InstallIn(SingletonComponent::class)
object UndoProvidersModule {

    /** The journal as the narrow [UndoRecorder] write surface handlers depend on. */
    @Provides
    @Singleton
    fun provideUndoRecorder(undoStore: UndoStore): UndoRecorder = undoStore

    /**
     * Inverse dispatch table. Only safe, well-defined reversals are registered (see [UndoActions]);
     * actions with no exact inverse are never recorded, so `undo_last` stays honest.
     */
    @Provides
    @Singleton
    fun provideUndoExecutor(
        @ApplicationContext context: Context,
        assistRepository: AssistRepository,
        scheduledTaskActions: ScheduledTaskActions,
    ): UndoExecutor = UndoExecutor(
        UndoActions.android(context, assistRepository, scheduledTaskActions),
    )
}
