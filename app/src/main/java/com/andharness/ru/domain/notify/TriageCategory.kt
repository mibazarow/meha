package com.andharness.ru.domain.notify


/** Coarse bucket of a captured notification, used for the digest and the memory tags. */
enum class TriageCategory { OTP, CALL, MONEY, MEETING, DELIVERY, PERSONAL, GROUP, BULK, OTHER }
