package com.andharness.ru.domain.memory

import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.JsonArgs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders the compact `DEVICE FACTS (обновлено …)` block injected into the system prompt (TZ §12.3).
 *
 * Only **fresh** summary facts are rendered, and only as short aggregates: model, Android version,
 * installed-app count, storage volumes, held default roles, current transport and special-access
 * flags. Full payloads stay behind `device_facts_get`, so the prompt never grows unbounded and no
 * sensitive data (§12.5) is ever shown. Pure Kotlin — unit-testable on the JVM.
 */
object DeviceFactSummary {

    /** One instruction line the agent sees next to the block (TZ §12.3). */
    const val PROMPT_INSTRUCTION =
        "Факты об устройстве — кэш; если они устарели или нужны точные данные, " +
            "вызови device_facts_refresh"

    private const val MAX_CHARS = 800

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val json = AppJson.Lenient

    /**
     * @param facts every stored fact (fresh and stale alike); stale ones are skipped.
     * @param now current epoch millis used for the freshness check and the header stamp.
     * @param zone time zone for the header stamp (injectable for deterministic tests).
     * @return the prompt block, or an empty string when no summary fact is currently fresh.
     */
    fun render(facts: List<DeviceFact>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val byKey = facts.filter { DeviceFactSpecs.byKey(it.key)?.summary == true }
            .filter { DeviceFactSpecs.isFresh(it, now) }
            .associateBy { it.key }
        if (byKey.isEmpty()) return ""

        val lines = mutableListOf<String>()
        byKey[DeviceFactSpecs.DEVICE_MODEL]?.let { fact ->
            obj(fact)?.let { o ->
                val manufacturer = o.str("manufacturer").orEmpty()
                val model = o.str("model").orEmpty()
                val device = o.str("device").orEmpty()
                val text = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
                lines += "- модель: " + buildString {
                    append(text.ifBlank { "неизвестно" })
                    if (device.isNotBlank()) append(" (device $device)")
                }
            }
        }
        byKey[DeviceFactSpecs.ANDROID_VERSION]?.let { fact ->
            obj(fact)?.let { o ->
                val release = o.str("release").orEmpty()
                val sdk = o.str("sdk").orEmpty()
                val patch = o.str("securityPatch").orEmpty()
                lines += buildString {
                    append("- Android: ").append(release.ifBlank { "?" })
                    if (sdk.isNotBlank()) append(" (API ").append(sdk).append(')')
                    if (patch.isNotBlank()) append(", патч ").append(patch)
                }
            }
        }
        byKey[DeviceFactSpecs.INSTALLED_APPS]?.let { fact ->
            obj(fact)?.str("count")?.let { lines += "- приложений: $it" }
        }
        byKey[DeviceFactSpecs.STORAGE_VOLUMES]?.let { fact ->
            obj(fact)?.get("volumes")?.let { element ->
                val volumes = (element as? JsonArray).orEmpty()
                val described = volumes.take(3).mapNotNull { entry ->
                    val o = entry as? JsonObject ?: return@mapNotNull null
                    val description = o.str("description").orEmpty()
                    val state = o.str("state").orEmpty()
                    listOf(description, state).filter { it.isNotBlank() }.joinToString(" — ")
                }
                lines += "- тома: ${volumes.size}" +
                    if (described.isNotEmpty()) " (" + described.joinToString("; ") + ")" else ""
            }
        }
        byKey[DeviceFactSpecs.DEFAULT_ROLES]?.let { fact ->
            obj(fact)?.get("held")?.let { element ->
                val roles = (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                if (roles.isNotEmpty()) lines += "- роли: " + roles.joinToString(", ") { shortRole(it) }
            }
        }
        byKey[DeviceFactSpecs.CURRENT_NETWORK]?.let { fact ->
            obj(fact)?.let { o ->
                val connected = o.str("connected") == "true"
                val transport = o.str("transport").orEmpty()
                lines += "- сеть: " + if (connected && transport.isNotBlank()) transport else "нет"
            }
        }
        byKey[DeviceFactSpecs.SPECIAL_ACCESS]?.let { fact ->
            obj(fact)?.let { o ->
                val enabled = SPECIAL_ACCESS_FLAGS.filter { o.str(it.first) == "true" }.map { it.second }
                lines += "- спец-доступы: " + enabled.ifEmpty { listOf("нет") }.joinToString(", ")
            }
        }
        if (lines.isEmpty()) return ""

        val header = "DEVICE FACTS (обновлено " + STAMP.format(Instant.ofEpochMilli(now).atZone(zone)) + ")"
        val block = buildString {
            append(header)
            lines.forEach { append('\n').append(it) }
            append('\n').append(PROMPT_INSTRUCTION)
        }
        return if (block.length <= MAX_CHARS) block else block.substring(0, MAX_CHARS).trimEnd()
    }

    private val SPECIAL_ACCESS_FLAGS = listOf(
        "accessibility" to "accessibility",
        "notification_listener" to "notification_listener",
        "usage_access" to "usage_access",
        "write_settings" to "write_settings",
        "overlay" to "overlay",
        "exact_alarm" to "exact_alarm",
        "notification_policy" to "notification_policy",
    )

    internal fun shortRole(role: String): String =
        role.removePrefix("android.app.role.").ifBlank { role }

    private fun obj(fact: DeviceFact): JsonObject? =
        runCatching { json.parseToJsonElement(fact.valueJson) as? JsonObject }.getOrNull()

    /** Reads a value's textual content (`null` when absent); blank is kept (see [JsonArgs.raw]). */
    private fun JsonObject.str(key: String): String? = JsonArgs.raw(this, key)
}
