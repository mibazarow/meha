package com.andharness.ru.domain.memory

/**
 * The authoritative catalogue of cacheable device facts (update.md §12.4).
 *
 * This object is pure Kotlin (no Android imports) so the freshness/summary rules stay unit-testable
 * on the JVM. Privacy note (§12.5): only non-sensitive aggregates are cached — never hardware
 * identifiers, MAC/BSSID, precise location, account names, notification content or contact data.
 */
object DeviceFactSpecs {

    /** Never expires: the fact is only invalidated by an explicit event (`BOOT_COMPLETED`, ...). */
    const val TTL_INFINITE = 0L
    const val TTL_5_MIN = 5 * 60 * 1000L
    const val TTL_15_MIN = 15 * 60 * 1000L
    const val TTL_1_H = 60 * 60 * 1000L
    const val TTL_24_H = 24 * 60 * 60 * 1000L

    // ---- keys ----
    const val DEVICE_MODEL = "device_model"
    const val ANDROID_VERSION = "android_version"
    const val SCREEN = "screen"
    const val INSTALLED_APPS = "installed_apps"
    const val DEFAULT_ROLES = "default_roles"
    const val HOME_LAUNCHER = "home_launcher"
    const val STORAGE_VOLUMES = "storage_volumes"
    const val STORAGE_SPACE = "storage_space"
    const val CURRENT_NETWORK = "current_network"
    const val ACCOUNTS_COUNT = "accounts_count"
    const val CONTACTS_COUNT = "contacts_count"
    const val SENSORS = "sensors"
    const val SPECIAL_ACCESS = "special_access"
    const val LOCALE_TIMEZONE = "locale_timezone"
    const val OWN_APP_VERSION = "own_app_version"
    const val SIM_NUMBERS = "sim_numbers"

    // ---- categories ----
    const val CATEGORY_DEVICE = "device"
    const val CATEGORY_APPS = "apps"
    const val CATEGORY_STORAGE = "storage"
    const val CATEGORY_NETWORK = "network"
    const val CATEGORY_ACCOUNTS = "accounts"
    const val CATEGORY_SENSORS = "sensors"
    const val CATEGORY_ACCESS = "access"
    const val CATEGORY_LOCALE = "locale"

    val all: List<DeviceFactSpec> = listOf(
        DeviceFactSpec(DEVICE_MODEL, CATEGORY_DEVICE, TTL_INFINITE, summary = true),
        DeviceFactSpec(ANDROID_VERSION, CATEGORY_DEVICE, TTL_INFINITE, summary = true),
        DeviceFactSpec(SCREEN, CATEGORY_DEVICE, TTL_INFINITE, summary = false),
        DeviceFactSpec(OWN_APP_VERSION, CATEGORY_DEVICE, TTL_INFINITE, summary = false),
        DeviceFactSpec(INSTALLED_APPS, CATEGORY_APPS, TTL_24_H, summary = true),
        DeviceFactSpec(DEFAULT_ROLES, CATEGORY_APPS, TTL_24_H, summary = true),
        DeviceFactSpec(HOME_LAUNCHER, CATEGORY_APPS, TTL_24_H, summary = false),
        DeviceFactSpec(STORAGE_VOLUMES, CATEGORY_STORAGE, TTL_1_H, summary = true),
        DeviceFactSpec(STORAGE_SPACE, CATEGORY_STORAGE, TTL_15_MIN, summary = false),
        DeviceFactSpec(CURRENT_NETWORK, CATEGORY_NETWORK, TTL_5_MIN, summary = true),
        DeviceFactSpec(ACCOUNTS_COUNT, CATEGORY_ACCOUNTS, TTL_24_H, summary = false),
        DeviceFactSpec(CONTACTS_COUNT, CATEGORY_ACCOUNTS, TTL_24_H, summary = false),
        DeviceFactSpec(SENSORS, CATEGORY_SENSORS, TTL_INFINITE, summary = false),
        DeviceFactSpec(SPECIAL_ACCESS, CATEGORY_ACCESS, TTL_5_MIN, summary = true),
        DeviceFactSpec(LOCALE_TIMEZONE, CATEGORY_LOCALE, TTL_INFINITE, summary = false),
        // SIM/MSISDN rarely changes; re-collected on boot/update and reachable via sim_numbers.
        // Deliberately NOT a prompt-summary fact: it is stored so the agent can read it on demand
        // (device_facts_get/refresh) without growing the prompt block.
        DeviceFactSpec(SIM_NUMBERS, CATEGORY_NETWORK, TTL_1_H, summary = false),
    )

    fun byKey(key: String): DeviceFactSpec? = all.firstOrNull { it.key == key }

    fun isCacheable(key: String): Boolean = byKey(key) != null

    val keys: List<String> get() = all.map { it.key }

    /** Whether [fact] may be reused at [now]: not invalidated and within its TTL. */
    fun isFresh(fact: DeviceFact, now: Long): Boolean =
        !fact.invalidated && (fact.ttlMs == TTL_INFINITE || now - fact.updatedAt <= fact.ttlMs)
}
