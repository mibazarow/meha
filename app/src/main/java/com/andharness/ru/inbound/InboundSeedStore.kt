package com.andharness.ru.inbound

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory hand-off between the inbound entry points and the `ChatViewModel` of the chat they
 * created. The chat is created BEFORE the app navigates to it, so the ViewModel looks its seed up
 * by chat id when it is constructed; [take] consumes it exactly once (a second ViewModel for the
 * same chat — e.g. after process death — simply finds nothing and shows a normal empty chat).
 */
@Singleton
class InboundSeedStore @Inject constructor() {

    private val seeds = ConcurrentHashMap<Long, InboundChatSeed>()

    fun put(chatId: Long, seed: InboundChatSeed) {
        seeds[chatId] = seed
    }

    fun take(chatId: Long): InboundChatSeed? = seeds.remove(chatId)
}
