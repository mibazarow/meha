package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.embedding.ChatTextEmbedder
import com.andharness.ru.data.embedding.LocalEmbedder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the process-wide on-device embedder from the bundled ONNX assets. [LocalEmbedder.get]
 * itself is lazy: the ~29 MB native session is created on the first [LocalEmbedder.embed] call.
 * Semantic search is on by default, so the app also warms the model up in the background at
 * startup; it is never invoked while semantic search is off. Nothing here touches the network.
 */
@Module
@InstallIn(SingletonComponent::class)
object EmbeddingModule {

    @Provides
    @Singleton
    fun provideLocalEmbedder(@ApplicationContext context: Context): LocalEmbedder =
        LocalEmbedder.get(context)

    /**
     * Exposes the shared [LocalEmbedder] behind the narrow [ChatTextEmbedder] port the chat-message
     * indexer depends on. Same singleton instance, no second model load.
     */
    @Provides
    @Singleton
    fun provideChatTextEmbedder(localEmbedder: LocalEmbedder): ChatTextEmbedder = localEmbedder
}
