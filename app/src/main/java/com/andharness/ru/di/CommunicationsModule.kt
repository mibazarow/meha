package com.andharness.ru.di

import com.andharness.ru.data.communications.RecordingsRepositoryImpl
import com.andharness.ru.data.communications.SpeakerRepositoryImpl
import com.andharness.ru.data.communications.TranscriptRepositoryImpl
import com.andharness.ru.data.messages.MessageSearchServiceImpl
import com.andharness.ru.data.messages.StoredMessagesRepositoryImpl
import com.andharness.ru.data.messages.LocalMessageQueryEmbedder
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.StoredMessagesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the "Communications" repositories (recordings, transcripts, speakers) to their Room-backed
 * implementations. Follows the per-domain module pattern (see [PersonModule] / [MemoryDbModule]).
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommunicationsModule {

    @Binds
    @Singleton
    abstract fun bindRecordingsRepository(impl: RecordingsRepositoryImpl): RecordingsRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptRepository(impl: TranscriptRepositoryImpl): TranscriptRepository

    @Binds
    @Singleton
    abstract fun bindSpeakerRepository(impl: SpeakerRepositoryImpl): SpeakerRepository

    @Binds
    @Singleton
    abstract fun bindStoredMessagesRepository(
        impl: StoredMessagesRepositoryImpl
    ): StoredMessagesRepository

    @Binds
    @Singleton
    abstract fun bindMessageSearch(impl: MessageSearchServiceImpl): MessageSearch

    @Binds
    @Singleton
    abstract fun bindMessageQueryEmbedder(
        impl: LocalMessageQueryEmbedder
    ): MessageQueryEmbedder
}
