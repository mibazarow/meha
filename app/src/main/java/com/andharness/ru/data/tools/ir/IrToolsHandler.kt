package com.andharness.ru.data.tools.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds one [ToolHandler] per infrared tool: [ir_transmit].
 *
 * Official references (consulted before writing this code):
 * - `ConsumerIrManager.hasIrEmitter()` / `ConsumerIrManager.transmit(int carrierFrequency,
 *   int[] pattern)` (API 19+). `pattern` is the alternating on/off durations in microseconds,
 *   and only patterns shorter than 2 seconds are transmitted:
 *   https://developer.android.com/reference/android/hardware/ConsumerIrManager
 * - `ConsumerIrManager.getCarrierFrequencies()` returns the supported carrier-frequency ranges
 *   (or null if the service is unavailable): same reference page.
 * - `<uses-permission android:name="android.permission.TRANSMIT_IR" />` — a normal
 *   (install-time, non-runtime) permission. Declaring it does NOT guarantee that the device
 *   actually has an IR emitter, so `hasIrEmitter()` is always checked first:
 *   https://developer.android.com/reference/android/Manifest.permission#TRANSMIT_IR
 * - Optional hardware feature `PackageManager.FEATURE_CONSUMER_IR`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_CONSUMER_IR
 * - SDK sources cross-check: `~/Library/Android/sdk/sources/android-37.0/android/hardware/ConsumerIrManager.java`
 *
 * Honesty rules: no emitter -> honest "unavailable" result (never a fake success); a pattern
 * longer than 2 s is rejected up front because the platform would not transmit it.
 */
class IrToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(transmit())

    private fun transmit(): ToolHandler = object : ToolHandler {
        override val name = "ir_transmit"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val frequency = (arguments["frequency"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                ?: return@withContext ToolResult(
                    false,
                    "Укажите frequency — частоту несущей в герцах (целое число, например 38000)."
                )
            if (frequency <= 0) {
                return@withContext ToolResult(false, "frequency должна быть положительной (Гц), получено: $frequency.")
            }

            val rawPattern = (arguments["pattern"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (rawPattern.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Укажите pattern — чередующиеся длительности вкл/выкл в микросекундах через запятую."
                )
            }
            val pattern = ArrayList<Int>()
            for (part in rawPattern.split(',', ';', ' ', '\n')) {
                val token = part.trim()
                if (token.isEmpty()) continue
                val value = token.toIntOrNull()
                    ?: return@withContext ToolResult(
                        false,
                        "pattern должен состоять только из целых чисел (микросекунды), получено: «$token»."
                    )
                if (value <= 0) {
                    return@withContext ToolResult(
                        false,
                        "Длительности в pattern должны быть положительными (мкс), получено: $value."
                    )
                }
                pattern.add(value)
            }
            if (pattern.isEmpty()) {
                return@withContext ToolResult(false, "pattern не содержит ни одной длительности.")
            }
            val totalMicros = pattern.sumOf { it.toLong() }
            if (totalMicros > MAX_PATTERN_MICROS) {
                return@withContext ToolResult(
                    false,
                    "Шаблон длится ${totalMicros / 1000} мс — это больше лимита платформы 2000 мс: " +
                        "ConsumerIrManager передаёт только шаблоны короче 2 секунд. Сократите pattern."
                )
            }

            try {
                val ir = context.getSystemService(ConsumerIrManager::class.java)
                    ?: return@withContext ToolResult(
                        false,
                        "ИК-передатчик недоступен: на устройстве нет службы Consumer IR."
                    )
                if (!ir.hasIrEmitter()) {
                    return@withContext ToolResult(
                        false,
                        "На этом устройстве нет ИК-излучателя (hasIrEmitter() == false) — передать сигнал невозможно."
                    )
                }

                val ranges = try {
                    ir.carrierFrequencies
                } catch (_: Exception) {
                    null
                }
                if (!ranges.isNullOrEmpty() &&
                    ranges.none { frequency >= it.minFrequency && frequency <= it.maxFrequency }
                ) {
                    val supported = ranges.joinToString(", ") { "${it.minFrequency}–${it.maxFrequency} Гц" }
                    return@withContext ToolResult(
                        false,
                        "Частота $frequency Гц не поддерживается эмиттером. Доступные диапазоны: $supported."
                    )
                }

                ir.transmit(frequency, pattern.toIntArray())
                ToolResult(
                    true,
                    "ИК-сигнал передан: несущая $frequency Гц, длительностей ${pattern.size} (${totalMicros / 1000} мс)."
                )
            } catch (e: SecurityException) {
                ToolResult(
                    false,
                    "Передача ИК запрещена: проверьте разрешение TRANSMIT_IR (${e.message})."
                )
            } catch (e: IllegalArgumentException) {
                ToolResult(false, "Эмиттер отклонил параметры сигнала: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка передачи ИК-сигнала: ${e.message}")
            }
        }
    }

    private companion object {
        /** `ConsumerIrManager.transmit` only transmits patterns shorter than 2 seconds. */
        const val MAX_PATTERN_MICROS = 2_000_000L
    }
}
