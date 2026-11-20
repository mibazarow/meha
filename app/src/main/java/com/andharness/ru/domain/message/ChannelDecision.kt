package com.andharness.ru.domain.message


/** Result of [MessageChannelSelector.select]. */
sealed interface ChannelDecision {

    /** A channel could be chosen; the caller may send. */
    data class Chosen(val channel: MessageChannel) : ChannelDecision

    /** No honest way to reach the person through the requested channel; send nothing. */
    data class Unavailable(val reason: String) : ChannelDecision
}
