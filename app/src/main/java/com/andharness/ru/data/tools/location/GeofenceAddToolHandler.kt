package com.andharness.ru.data.tools.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * `geofence_add`: registers a geofence with Play Services and records it locally.
 *
 * Geofencing reference (`GeofencingClient`, `GeofencingRequest`, `Geofence`) —
 * https://developer.android.com/develop/sensors-and-location/location/geofencing
 */
internal class GeofenceAddToolHandler(
    private val context: Context,
    private val permissions: LocationPermissions,
) : ToolHandler {

    override val name = "geofence_add"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val latitude = arguments.str("latitude")?.toDoubleOrNull()
            ?: return@withContext ToolResult(false, "Укажите latitude — широту центра геозоны (число, -90..90).")
        val longitude = arguments.str("longitude")?.toDoubleOrNull()
            ?: return@withContext ToolResult(false, "Укажите longitude — долготу центра геозоны (число, -180..180).")
        val radiusM = arguments.str("radius_m")?.toFloatOrNull()
            ?: return@withContext ToolResult(false, "Укажите radius_m — радиус геозоны в метрах (число больше 0).")
        if (latitude !in -90.0..90.0) {
            return@withContext ToolResult(false, "latitude должно быть в диапазоне -90..90.")
        }
        if (longitude !in -180.0..180.0) {
            return@withContext ToolResult(false, "longitude должно быть в диапазоне -180..180.")
        }
        if (radiusM <= 0f) {
            return@withContext ToolResult(false, "radius_m должно быть больше 0.")
        }
        val expirationRaw = arguments.str("expiration_hours")
        val expirationHours = expirationRaw?.toIntOrNull()
        if (expirationRaw != null && expirationHours == null) {
            return@withContext ToolResult(false, "expiration_hours должно быть целым числом часов.")
        }
        if (expirationHours != null && expirationHours <= 0) {
            return@withContext ToolResult(false, "expiration_hours должно быть больше 0 (или не задано для бессрочной геозоны).")
        }
        val dwell = arguments.str("dwell")?.toBooleanStrictOrNull() ?: false

        // Both permissions are required for geofencing: FINE to evaluate the fence and
        // BACKGROUND so transitions keep firing while the app is not in the foreground.
        // https://developer.android.com/develop/sensors-and-location/location/geofencing
        if (!permissions.fineLocationGranted()) {
            return@withContext ToolResult(
                false,
                "Для геозон нужно разрешение ACCESS_FINE_LOCATION — оно не выдано.",
            )
        }
        if (!permissions.backgroundLocationGranted()) {
            return@withContext ToolResult(
                false,
                "Для геозон нужно разрешение «Местоположение всегда» (ACCESS_BACKGROUND_LOCATION) — " +
                    "оно не выдано, а без него геозоны не срабатывают в фоне. Выдайте его в настройках: " +
                    "Приложения → Меха → Разрешения → Местоположение → «Всегда разрешать».",
            )
        }

        val store = GeofenceStore(context)
        val id = arguments.str("id")
            ?: "geo_" + UUID.randomUUID().toString().replace("-", "").take(10)
        if (store.exists(id)) {
            return@withContext ToolResult(
                false,
                "Геозона с id «$id» уже существует. Удалите её (geofence_remove) или укажите другой id.",
            )
        }
        if (store.count() >= GeofenceStore.MAX_GEOFENCES) {
            return@withContext ToolResult(
                false,
                "Достигнут лимит геозон (${GeofenceStore.MAX_GEOFENCES}). Удалите ненужные: geofence_remove.",
            )
        }

        val transitionTypes = Geofence.GEOFENCE_TRANSITION_ENTER or
            Geofence.GEOFENCE_TRANSITION_EXIT or
            (if (dwell) Geofence.GEOFENCE_TRANSITION_DWELL else 0)
        val builder = Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radiusM)
            .setTransitionTypes(transitionTypes)
            .setExpirationDuration(
                if (expirationHours != null) {
                    expirationHours.toLong() * 3_600_000L
                } else {
                    Geofence.NEVER_EXPIRE
                },
            )
        if (dwell) builder.setLoiteringDelay(LOITERING_DELAY_MS)

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT,
            )
            .addGeofence(builder.build())
            .build()

        // The PendingIntent MUST be mutable so the Play Services process can attach the
        // geofencing extras (transition type, triggering geofences, location) when it sends the
        // broadcast. FLAG_MUTABLE exists from Android 12 (API 31); below that no flag is needed.
        // https://developer.android.com/develop/sensors-and-location/location/geofencing
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            GEOFENCE_PENDING_INTENT_REQUEST_CODE,
            Intent(context, GeofenceReceiver::class.java).setAction(ACTION_GEOFENCE_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }),
        )

        val client = try {
            LocationServices.getGeofencingClient(context)
        } catch (e: Exception) {
            return@withContext ToolResult(
                false,
                "Службы Google Play недоступны — геозоны зарегистрировать нельзя: ${e.message}",
            )
        }
        val result = try {
            GeofenceTaskSupport.awaitTask(client.addGeofences(request, pendingIntent))
        } catch (e: SecurityException) {
            return@withContext ToolResult(
                false,
                "Система отклонила геозону из-за разрешений: ${e.message}",
            )
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Не удалось зарегистрировать геозону: ${e.message}")
        }
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "неизвестная ошибка Play Services"
            return@withContext ToolResult(false, "Не удалось зарегистрировать геозону: $reason")
        }

        store.add(
            GeofenceRecord(
                id = id,
                latitude = latitude,
                longitude = longitude,
                radiusM = radiusM,
                expirationHours = expirationHours,
                dwell = dwell,
                addedAtMs = System.currentTimeMillis(),
            ),
        )
        ToolResult(
            true,
            "Геозона «$id» добавлена: центр ($latitude, $longitude), радиус ${radiusM}м, " +
                "события ${if (dwell) "вход/выход/пребывание" else "вход/выход"}, " +
                (expirationHours?.let { "истекает через $it ч" } ?: "бессрочная") +
                ". При переходе придёт уведомление.",
        )
    }

    private companion object {
        private const val LOITERING_DELAY_MS = 60_000
        private const val GEOFENCE_PENDING_INTENT_REQUEST_CODE = 0
        private const val ACTION_GEOFENCE_TRANSITION = "com.andharness.ru.action.GEOFENCE_TRANSITION"
    }
}
