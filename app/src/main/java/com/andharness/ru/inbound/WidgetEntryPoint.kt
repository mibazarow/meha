package com.andharness.ru.inbound

import androidx.compose.runtime.getValue
import com.andharness.ru.domain.chat.ChatRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge: Glance is not a Hilt-aware component, so the repository is pulled from here. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun chatRepository(): ChatRepository
}
