package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.dao.RecordingDao
import com.andharness.ru.data.db.dao.SpeakerDao
import com.andharness.ru.data.db.dao.SpeakerVoiceEmbeddingDao
import com.andharness.ru.data.db.dao.StoredMessageDao
import com.andharness.ru.data.db.dao.StoredMessageFtsDao
import com.andharness.ru.data.db.dao.StoredMessageVectorDao
import com.andharness.ru.data.db.dao.TranscriptFtsDao
import com.andharness.ru.data.db.dao.TranscriptSegmentDao
import com.andharness.ru.data.db.dao.TranscriptVectorDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * "Communications" DAO providers: recordings, transcript segments and their FTS/vector stores, the
 * speaker registry and its voice samples. Follows the per-domain DB module pattern (see
 * [PersonDbModule] / [MemoryDbModule]).
 *
 * Reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Module
@InstallIn(SingletonComponent::class)
object CommunicationsDbModule {

    @Provides
    @Singleton
    fun provideRecordingDao(database: AppDatabase): RecordingDao = database.recordingDao()

    @Provides
    @Singleton
    fun provideTranscriptSegmentDao(database: AppDatabase): TranscriptSegmentDao =
        database.transcriptSegmentDao()

    @Provides
    @Singleton
    fun provideTranscriptFtsDao(database: AppDatabase): TranscriptFtsDao = database.transcriptFtsDao()

    @Provides
    @Singleton
    fun provideTranscriptVectorDao(database: AppDatabase): TranscriptVectorDao =
        database.transcriptVectorDao()

    @Provides
    @Singleton
    fun provideSpeakerDao(database: AppDatabase): SpeakerDao = database.speakerDao()

    @Provides
    @Singleton
    fun provideSpeakerVoiceEmbeddingDao(database: AppDatabase): SpeakerVoiceEmbeddingDao =
        database.speakerVoiceEmbeddingDao()

    @Provides
    @Singleton
    fun provideStoredMessageDao(database: AppDatabase): StoredMessageDao = database.storedMessageDao()

    @Provides
    @Singleton
    fun provideStoredMessageFtsDao(database: AppDatabase): StoredMessageFtsDao =
        database.storedMessageFtsDao()

    @Provides
    @Singleton
    fun provideStoredMessageVectorDao(database: AppDatabase): StoredMessageVectorDao =
        database.storedMessageVectorDao()

    @Provides
    @Singleton
    fun provideChatMessageVectorDao(database: AppDatabase): ChatMessageVectorDao =
        database.chatMessageVectorDao()
}
