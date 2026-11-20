package com.andharness.ru.data.person

import android.content.Context
import com.andharness.ru.domain.messages.StoredMessagesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge for components the framework instantiates itself — the SMS `BroadcastReceiver`, the
 * notification listener service and the backfill `Worker` — so they can reach the personal-base
 * ingester without a custom factory.
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PersonIngestEntryPoint {
    fun personIndexer(): PersonIndexer
    fun messagingIndexConfigStore(): MessagingIndexConfigStore
    fun personBackfillStore(): PersonBackfillStore
    fun storedMessagesRepository(): StoredMessagesRepository
}

/** Convenience accessor used by the receiver/service/worker. */
fun personIngestEntryPoint(context: Context): PersonIngestEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, PersonIngestEntryPoint::class.java)
