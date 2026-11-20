package com.andharness.ru.data.objective

import com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy
import kotlinx.serialization.Serializable

/** Persisted worker config; bounded/defaulted so a corrupt or partial file can never break startup. */
@Serializable
data class ObjectiveWorkConfig(
    val maxConcurrency: Int = ObjectiveConcurrencyPolicy.DEFAULT,
    val lastTickAt: Long = 0L,
    val lastError: String? = null
)
