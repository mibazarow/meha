package com.andharness.ru.data.memory

import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.Display
import com.andharness.ru.data.tools.sim.SimNumbersFormatter
import com.andharness.ru.data.tools.sim.SimNumbersReader
import com.andharness.ru.domain.memory.DeviceFactSpecs
import com.andharness.ru.service.AccessibilityAutomationService
import com.andharness.ru.service.NotificationRelayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the JSON payload of every cacheable device fact (update.md §12.4).
 *
 * Honesty rules: when the required permission/access is missing the collector does NOT invent a
 * value — it either returns a payload carrying an explicit `available:false` flag, or `null`
 * (fact skipped). Privacy rules (§12.5): never hardware identifiers, MAC/BSSID, precise location,
 * account names, notification content or contact data — only non-sensitive aggregates.
 *
 * References:
 * - https://developer.android.com/reference/android/os/Build
 * - https://developer.android.com/reference/android/content/pm/PackageManager
 * - https://developer.android.com/reference/android/app/role/RoleManager
 * - https://developer.android.com/reference/android/os/storage/StorageManager
 * - https://developer.android.com/reference/android/os/StatFs
 * - https://developer.android.com/reference/android/net/ConnectivityManager
 * - https://developer.android.com/reference/android/accounts/AccountManager
 * - https://developer.android.com/reference/android/provider/ContactsContract
 * - https://developer.android.com/reference/android/hardware/SensorManager
 */
@Singleton
class DeviceFactCollectors @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Produces [key], or `null` when the key is unknown or the fact is unavailable right now. */
    fun collect(key: String): CollectedFact? {
        val spec = DeviceFactSpecs.byKey(key) ?: return null
        val (payload, api) = when (key) {
            DeviceFactSpecs.DEVICE_MODEL -> deviceModel()
            DeviceFactSpecs.ANDROID_VERSION -> androidVersion()
            DeviceFactSpecs.SCREEN -> screen()
            DeviceFactSpecs.INSTALLED_APPS -> installedApps()
            DeviceFactSpecs.DEFAULT_ROLES -> defaultRoles()
            DeviceFactSpecs.HOME_LAUNCHER -> homeLauncher()
            DeviceFactSpecs.STORAGE_VOLUMES -> storageVolumes()
            DeviceFactSpecs.STORAGE_SPACE -> storageSpace()
            DeviceFactSpecs.CURRENT_NETWORK -> currentNetwork()
            DeviceFactSpecs.ACCOUNTS_COUNT -> accountsCount()
            DeviceFactSpecs.CONTACTS_COUNT -> contactsCount()
            DeviceFactSpecs.SENSORS -> sensors()
            DeviceFactSpecs.SPECIAL_ACCESS -> specialAccess()
            DeviceFactSpecs.LOCALE_TIMEZONE -> localeTimezone()
            DeviceFactSpecs.OWN_APP_VERSION -> ownAppVersion()
            DeviceFactSpecs.SIM_NUMBERS -> simNumbers()
            else -> null to null
        } ?: return null
        return CollectedFact(
            key = spec.key,
            category = spec.category,
            valueJson = payload.toString(),
            ttlMs = spec.ttlMs,
            source = SOURCE,
            sourceApi = api
        )
    }

    /** Produces every cacheable fact that is available right now (unavailable ones are skipped). */
    fun collectAll(): List<CollectedFact> = DeviceFactSpecs.keys.mapNotNull { collect(it) }

    // ---- static facts ----

    private fun deviceModel(): Pair<kotlinx.serialization.json.JsonObject, String> =
        buildJsonObject {
            put("manufacturer", Build.MANUFACTURER.orEmpty())
            put("model", Build.MODEL.orEmpty())
            put("device", Build.DEVICE.orEmpty())
            put("board", Build.BOARD.orEmpty())
            put("hardware", Build.HARDWARE.orEmpty())
            put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
        } to "android.os.Build"

    private fun androidVersion(): Pair<kotlinx.serialization.json.JsonObject, String> =
        buildJsonObject {
            put("release", Build.VERSION.RELEASE.orEmpty())
            put("sdk", Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                put("securityPatch", Build.VERSION.SECURITY_PATCH.orEmpty())
            }
            put("incremental", Build.VERSION.INCREMENTAL.orEmpty())
        } to "android.os.Build.VERSION"

    private fun screen(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val display = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            dm?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val refreshRate = runCatching { display?.refreshRate }.getOrNull()
        val payload = buildJsonObject {
            put("widthPx", metrics.widthPixels)
            put("heightPx", metrics.heightPixels)
            put("densityDpi", metrics.densityDpi)
            put("density", metrics.density.toDouble())
            if (refreshRate != null) put("refreshRateHz", refreshRate.toDouble())
        }
        return payload to "android.util.DisplayMetrics+DisplayManager"
    }

    private fun installedApps(): Pair<kotlinx.serialization.json.JsonObject, String>? = runCatching {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0).take(MAX_APP_ENTRIES)
        val payload = buildJsonObject {
            put("count", packages.size)
            putJsonArray("apps") {
                packages.forEach { info ->
                    val app = info.applicationInfo
                    addJsonObject {
                        put("package", info.packageName)
                        put("label", runCatching { app?.loadLabel(pm)?.toString() }.getOrNull().orEmpty())
                        put("system", app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                }
            }
        }
        payload to "PackageManager.getInstalledPackages"
    }.getOrNull()

    private fun defaultRoles(): Pair<kotlinx.serialization.json.JsonObject, String>? {
        val manager = roleManager() ?: return null
        val held = mutableListOf<String>()
        val payload = buildJsonObject {
            putJsonArray("roles") {
                ROLES.forEach { role ->
                    val available = runCatching { manager.isRoleAvailable(role) }.getOrDefault(false)
                    val isHeld = available && runCatching { manager.isRoleHeld(role) }.getOrDefault(false)
                    if (isHeld) held += role
                    addJsonObject {
                        put("role", role)
                        put("available", available)
                        put("held", isHeld)
                    }
                }
            }
            putJsonArray("held") { held.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        return payload to "RoleManager.isRoleHeld"
    }

    private fun homeLauncher(): Pair<kotlinx.serialization.json.JsonObject, String>? {
        val roleHeld = roleManager()?.let { manager ->
            runCatching { manager.isRoleAvailable(ROLE_HOME) && manager.isRoleHeld(ROLE_HOME) }
                .getOrDefault(false)
        }
        val homePackage = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        val payload = buildJsonObject {
            put("package", homePackage.orEmpty())
            put("roleHeld", roleHeld == true)
        }
        return payload to "RoleManager(ROLE_HOME)+PackageManager.resolveActivity"
    }

    // ---- semi-static facts ----

    private fun storageVolumes(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val sm = context.getSystemService(StorageManager::class.java)
        val volumes = runCatching { sm?.storageVolumes }.getOrNull().orEmpty()
        val payload = buildJsonObject {
            put("count", volumes.size)
            putJsonArray("volumes") {
                volumes.forEach { volume ->
                    addJsonObject {
                        put("description", volume.getDescription(context).orEmpty())
                        put("removable", volume.isRemovable)
                        put("primary", volume.isPrimary)
                        put("state", volume.state.orEmpty())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            put("uuid", volume.uuid.orEmpty())
                            put("directory", volume.directory?.absolutePath.orEmpty())
                        }
                    }
                }
            }
        }
        return payload to "StorageManager.getStorageVolumes"
    }

    private fun storageSpace(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val (free, total) = statFs(primaryStoragePath())
        val (dataFree, dataTotal) = statFs(Environment.getDataDirectory().absolutePath)
        val payload = buildJsonObject {
            put("freeBytes", free)
            put("totalBytes", total)
            put("dataFreeBytes", dataFree)
            put("dataTotalBytes", dataTotal)
        }
        return payload to "android.os.StatFs"
    }

    private fun currentNetwork(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = runCatching { network?.let { cm?.getNetworkCapabilities(it) } }.getOrNull()
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val payload = buildJsonObject {
            put("connected", caps != null)
            put("transport", transport)
            put("metered", caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        }
        return payload to "ConnectivityManager.getNetworkCapabilities"
    }

    private fun accountsCount(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val am = runCatching { AccountManager.get(context) }.getOrNull()
        val accounts = runCatching { am?.accounts?.toList() }.getOrNull()
        val payload = buildJsonObject {
            put("available", accounts != null)
            put("count", accounts?.size ?: 0)
            putJsonArray("types") {
                accounts?.map { it.type }?.distinct()?.sorted()?.forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            }
        }
        return payload to "AccountManager.getAccounts (count+types only)"
    }

    private fun contactsCount(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val count = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { it.count } ?: 0
        }.getOrNull()
        val payload = buildJsonObject {
            put("available", count != null)
            put("count", count ?: 0)
        }
        return payload to "ContactsContract.Contacts (count only)"
    }

    // ---- volatile facts ----

    private fun specialAccess(): Pair<kotlinx.serialization.json.JsonObject, String> {
        val payload = buildJsonObject {
            put("accessibility", runCatching { AccessibilityAutomationService.isEnabled() }.getOrDefault(false))
            put("notification_listener", runCatching { NotificationRelayService.isEnabled() }.getOrDefault(false))
            put("usage_access", hasUsageAccess())
            put("write_settings", runCatching { Settings.System.canWrite(context) }.getOrDefault(false))
            put("overlay", runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false))
            put("exact_alarm", canScheduleExactAlarms())
            put(
                "notification_policy",
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        ?.isNotificationPolicyAccessGranted() == true
                }.getOrDefault(false)
            )
        }
        return payload to "AppOps/Settings/AlarmManager granted-state checks"
    }

    private fun sensors(): Pair<kotlinx.serialization.json.JsonObject, String>? = runCatching {
        val sm = context.getSystemService(SensorManager::class.java)
        val list = sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        val payload = buildJsonObject {
            put("count", list.size)
            putJsonArray("sensors") {
                list.forEach { sensor ->
                    addJsonObject {
                        put("name", sensor.name.orEmpty())
                        put("type", runCatching { sensor.stringType }.getOrNull().orEmpty())
                        put("vendor", sensor.vendor.orEmpty())
                    }
                }
            }
        }
        payload to "SensorManager.getSensorList"
    }.getOrNull()

    private fun localeTimezone(): Pair<kotlinx.serialization.json.JsonObject, String> =
        buildJsonObject {
            put("locale", Locale.getDefault().toString())
            put("timezone", TimeZone.getDefault().id)
            put("uses24Hour", runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false))
        } to "Locale/TimeZone/DateFormat"

    private fun ownAppVersion(): Pair<kotlinx.serialization.json.JsonObject, String>? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        buildJsonObject {
            put("versionName", info.versionName.orEmpty())
            put("versionCode", code)
            put("package", context.packageName)
        } to "PackageManager.getPackageInfo"
    }.getOrNull()

    // ---- helpers ----

    /**
     * SIM phone number(s) — honest (BUG-057): only what the platform reports.
     *
     * Without `READ_PHONE_STATE` nothing is cached at all (the fact is skipped instead of being
     * poisoned with empty values). With the permission the payload always carries a machine-readable
     * `numberKnown` flag and the explicit «не определено» marker per subscription when the
     * operator/SIM does not expose MSISDN. Reuses the same [SimNumbersReader] as the `sim_numbers`
     * tool so the tool and the cache can never disagree.
     */
    private fun simNumbers(): Pair<kotlinx.serialization.json.JsonObject, String>? =
        when (val result = SimNumbersReader(context).read()) {
            is SimNumbersReader.Result.NoPhoneStatePermission -> null
            is SimNumbersReader.Result.Success ->
                SimNumbersFormatter.toFactJson(result.rows) to
                    "SubscriptionManager.getPhoneNumber/TelephonyManager.getLine1Number"
        }

    private fun roleManager(): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
        } else {
            null
        }

    private fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun canScheduleExactAlarms(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
    }.getOrDefault(false)

    private fun statFs(path: String): Pair<Long, Long> = runCatching {
        val stat = StatFs(path)
        stat.availableBytes to stat.totalBytes
    }.getOrDefault(0L to 0L)

    private fun primaryStoragePath(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.primaryStorageVolume?.directory?.absolutePath
        } else {
            null
        }
    }.getOrNull() ?: Environment.getExternalStorageDirectory().absolutePath

    companion object {
        private const val SOURCE = "device_facts_refresh"
        private const val MAX_APP_ENTRIES = 2000
        private const val ROLE_HOME = "android.app.role.HOME"

        private val ROLES = listOf(
            ROLE_HOME,
            "android.app.role.DIALER",
            "android.app.role.SMS",
            "android.app.role.BROWSER",
            "android.app.role.CALL_SCREENING",
            "android.app.role.ASSISTANT",
        )
    }
}
