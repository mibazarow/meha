package com.andharness.ru.domain.device

/**
 * Provides a short, stable summary of the device (hardware + OS) that is injected into the
 * agent's system prompt so the model knows what phone it runs on.
 */
interface DeviceInfoProvider {
    fun summary(): String
}
