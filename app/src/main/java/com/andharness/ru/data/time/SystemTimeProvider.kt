package com.andharness.ru.data.time

import com.andharness.ru.domain.common.TimeProvider
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** [TimeProvider] backed by the device clock. Every getter is read live, never cached. */
@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()

    override fun locale(): Locale = Locale.getDefault()
}
