package com.andharness.ru.domain.automation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure parsers and decision logic for the "deep" automation triggers of update.md idea #18
 * (`calendar_event`, `ble_presence`, `sound_event`, `geo_distance` and `wifi_open`) plus the
 * `sms` trigger added for BUG-006.
 *
 * Kept free of Android APIs so every rule can be unit-tested on the JVM. The Android layer only
 * feeds real observations in ([AutomationEvent]) and asks these specs whether an event fires.
 *
 * Config grammar (one `trigger_config` string, `key=value` pairs separated by `;`; a single bare
 * value is accepted as a shorthand):
 * - `calendar_event`: `title_contains=<подстрока>; minutes_before=<0..1440>` (default 5; blank =
 *   любое событие). Requires READ_CALENDAR.
 * - `ble_presence`: `address=<MAC>` **или** `name_contains=<подстрока>; rssi_threshold=<-100..0>;
 *   timeout_sec=<1..600>` (defaults -70 и 15).
 * - `sound_event`: `level_threshold=<-90..0 dBFS>; sustain_ms=<100..60000>` (defaults -20 и 500).
 * - `geo_distance`: `distance_m=<10..1000000 метров>; min_interval_sec=<60..86400>` (distance_m
 *   обязателен, min_interval_sec по умолчанию 600; числовой bare-шорткат трактуется как
 *   distance_m). Сравнивает текущую локацию с последней сохранённой точкой; нужен
 *   ACCESS_FINE_LOCATION и живой процесс приложения (см. `GeoDistanceMonitor`).
 * - `wifi_open`: `ssid_contains=<подстрока>; min_interval_sec=<60..86400>` (обе опции
 *   необязательны: без `ssid_contains` — любая открытая сеть, min_interval_sec по умолчанию 600;
 *   текстовый bare-шорткат трактуется как ssid_contains, числовой — как min_interval_sec).
 *   Срабатывает на видимую ОТКРЫТУЮ (без шифрования) Wi-Fi сеть; нужен NEARBY_WIFI_DEVICES
 *   (API 31+) или ACCESS_FINE_LOCATION и живой процесс приложения (см. `OpenWifiMonitor`).
 * - `sms`: `from=<подстрока отправителя>; text_contains=<подстрока текста>` (оба фильтра
 *   необязательны и сравниваются без учёта регистра; пусто = любое входящее SMS; текстовый
 *   bare-шорткат трактуется как `text_contains`). Источник — приёмник `SmsReceiver`
 *   (`android.provider.Telephony.SMS_RECEIVED`), поэтому нужен RECEIVE_SMS.
 * - `heart_rate_threshold`: `above=<20..250 bpm>` **или** `below=<20..250 bpm>` (ровно одно,
 *   строгое сравнение) и необязательный `dwell_sec=<0..3600>` (0 по умолчанию = срабатывает
 *   сразу). Нужен датчик TYPE_HEART_RATE и разрешение BODY_SENSORS.
 * - `steps_reached`: `target=<1..1000000 шагов>` — дельта накопительного TYPE_STEP_COUNTER от
 *   базовой линии, зафиксированной в момент подписки. Нужен ACTIVITY_RECOGNITION.
 * - `no_movement`: `minutes=<1..1440>` — отсутствие значимого движения; источник —
 *   TYPE_SIGNIFICANT_MOTION либо акселерометр (какой доступен).
 * - `sleep_window`: `start=23:00; end=07:00` — ЧЕСТНАЯ ЭВРИСТИКА (окно времени + экран выключен
 *   + телефон неподвижен). В Android нет публичного датчика фаз сна, поэтому это НЕ измерение сна.
 */
object TriggerConfigs {

    /** Separator used for key=value pairs; `|` is already taken by the `notify` action. */
    private const val PAIR_SEPARATOR = ';'
    private const val KEY_VALUE_SEPARATOR = '='

    /** Pseudo-key under which a single bare (un-prefixed) value is stored. */
    private const val BARE_KEY = "_"

    const val DEFAULT_MINUTES_BEFORE = 5
    const val MAX_MINUTES_BEFORE = 1440

    const val DEFAULT_RSSI_THRESHOLD = -70
    const val DEFAULT_BLE_TIMEOUT_SEC = 15
    const val MAX_BLE_TIMEOUT_SEC = 600

    const val DEFAULT_LEVEL_DBFS = -20.0
    const val DEFAULT_SUSTAIN_MS = 500L
    const val MIN_SUSTAIN_MS = 100L
    const val MAX_SUSTAIN_MS = 60_000L

    const val MIN_GEO_DISTANCE_M = 10.0
    const val MAX_GEO_DISTANCE_M = 1_000_000.0
    const val DEFAULT_GEO_MIN_INTERVAL_SEC = 600
    const val MIN_GEO_INTERVAL_SEC = 60
    const val MAX_GEO_INTERVAL_SEC = 86_400

    const val DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC = 600
    const val MIN_OPEN_WIFI_INTERVAL_SEC = 60
    const val MAX_OPEN_WIFI_INTERVAL_SEC = 86_400

    /**
     * Capability tokens that mark a network as PROTECTED, i.e. not open. `ScanResult.capabilities`
     * "describes the authentication, key management, and encryption schemes supported by the access
     * point" (https://developer.android.com/reference/android/net/wifi/ScanResult#capabilities);
     * an unsecured network carries none of them (it is typically just `[ESS]`). `OWE` is listed
     * because Enhanced Open is encrypted (opportunistic), so it must not count as open either.
     */
    private val SECURITY_TOKENS = listOf(
        "WPA", "RSN", "SAE", "EAP", "WEP", "PSK", "OWE", "WAPI", "GCMP", "CCMP", "TKIP"
    )

    /** Mean Earth radius in metres, the standard value for the Haversine great-circle distance. */
    const val EARTH_RADIUS_M = 6_371_000.0

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    // ------------------------------------------------------------------ calendar_event

    /**
     * `calendar_event` config. [minutesBefore] is how long before the event start the rule fires;
     * the firing window also tolerates a late check (the WorkManager check is inexact), see [isDue].
     */
    data class CalendarSpec(val titleContains: String?, val minutesBefore: Int) {

        /** True when the event title passes the optional `title_contains` filter. */
        fun matchesTitle(title: String?): Boolean {
            val needle = titleContains ?: return true
            return title?.contains(needle, ignoreCase = true) == true
        }

        /**
         * True when `now` is inside the firing window: from `start - minutesBefore` up to
         * `start + GRACE_MS`. The trailing grace keeps a rule from being missed when the periodic
         * calendar check runs a few minutes late (WorkManager is inexact by design).
         */
        fun isDue(eventStartMs: Long, nowMs: Long): Boolean {
            val fireAt = eventStartMs - minutesBefore * 60_000L
            return nowMs >= fireAt && nowMs <= eventStartMs + CALENDAR_GRACE_MS
        }
    }

    const val CALENDAR_GRACE_MS = 10 * 60_000L

    fun parseCalendar(config: String): CalendarSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return CalendarSpec(null, DEFAULT_MINUTES_BEFORE)
        val params = parseParams(raw)
        var title = params["title_contains"] ?: params["title"]
        var minutesRaw = params["minutes_before"] ?: params["minutes"] ?: params["min"]
        val bare = params[BARE_KEY]
        if (bare != null && title == null && minutesRaw == null) {
            if (bare.toIntOrNull() != null) minutesRaw = bare else title = bare
        }
        val minutes = if (minutesRaw == null) {
            DEFAULT_MINUTES_BEFORE
        } else {
            minutesRaw.toIntOrNull() ?: return null
        }
        if (minutes !in 0..MAX_MINUTES_BEFORE) return null
        return CalendarSpec(title?.trim()?.takeIf { it.isNotEmpty() }, minutes)
    }

    // ------------------------------------------------------------------ ble_presence

    /**
     * `ble_presence` config: the device must satisfy the address/name filter (at least one is
     * required) and be seen at or above [rssiThreshold] within [timeoutSec].
     *
     * [rssiThreshold] is compared inclusively (`seenRssi >= rssiThreshold`). Real BLE RSSI is
     * reported in dBm over roughly `-100..0` (the `-127` value is the "no signal / not available"
     * sentinel, not a measurable level), so the parser accepts only `-100..0`. Anything below
     * `-100` would be a sentinel and therefore effectively always true — the same class of bug as
     * the `sound_event` silence floor.
     */
    data class BlePresenceSpec(
        val address: String?,
        val nameContains: String?,
        val rssiThreshold: Int,
        val timeoutSec: Int
    ) {
        fun matches(seenAddress: String?, seenName: String?, seenRssi: Int?): Boolean {
            if (seenRssi == null || seenRssi < rssiThreshold) return false
            val addressOk = address == null || seenAddress.equals(address, ignoreCase = true)
            val nameOk = nameContains == null ||
                (seenName != null && seenName.contains(nameContains, ignoreCase = true))
            return addressOk && nameOk
        }
    }

    fun parseBlePresence(config: String): BlePresenceSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var address = params["address"] ?: params["mac"]
        var name = params["name_contains"] ?: params["name"]
        val bare = params[BARE_KEY]
        if (bare != null && address == null && name == null) {
            if (MAC_REGEX.matches(bare)) address = bare else name = bare
        }
        address = address?.trim()?.takeIf { it.isNotEmpty() }
        name = name?.trim()?.takeIf { it.isNotEmpty() }
        if (address == null && name == null) return null

        val rssiRaw = params["rssi_threshold"] ?: params["rssi"]
        val rssi = if (rssiRaw == null) {
            DEFAULT_RSSI_THRESHOLD
        } else {
            rssiRaw.toIntOrNull() ?: return null
        }
        if (rssi !in -100..0) return null

        val timeoutRaw = params["timeout_sec"] ?: params["timeout"]
        val timeout = if (timeoutRaw == null) {
            DEFAULT_BLE_TIMEOUT_SEC
        } else {
            timeoutRaw.toIntOrNull() ?: return null
        }
        if (timeout !in 1..MAX_BLE_TIMEOUT_SEC) return null

        return BlePresenceSpec(address, name, rssi, timeout)
    }

    // ------------------------------------------------------------------ sound_event

    /**
     * `sound_event` config: a "loud event" is the microphone RMS level reaching at least
     * [levelThresholdDbfs] and staying there for [sustainMs] — a spike shorter than that is ignored.
     *
     * [levelThresholdDbfs] is compared inclusively (`levelDbfs >= levelThresholdDbfs`). dBFS is dB
     * relative to digital full scale (0 dBFS = the maximum representable PCM amplitude; the 16-bit
     * signed full scale is ±32768 — see https://en.wikipedia.org/wiki/DBFS and
     * android.media.AudioFormat / `ENCODING_PCM_16BIT`).
     *
     * The parser accepts only `-90.0..0.0`. Rationale: [SoundEventMonitor] computes
     * `levelDbfs = 20*log10(rms/32768)` for `rms > 0` and falls back to the digital-silence floor
     * `MIN_DBFS = -100.0` for pure silence. A threshold at or below `-90` is therefore effectively
     * always true (silence yields exactly `-100.0`, and a real phone-mic noise floor in a quiet
     * room sits well above `-90` dBFS), which would make the rule fire non-stop. The runtime
     * comparison stays `>=`; only the accepted input range is narrowed.
     */
    data class SoundEventSpec(val levelThresholdDbfs: Double, val sustainMs: Long) {
        fun triggers(levelDbfs: Double): Boolean = levelDbfs >= levelThresholdDbfs
    }

    fun parseSoundEvent(config: String): SoundEventSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return SoundEventSpec(DEFAULT_LEVEL_DBFS, DEFAULT_SUSTAIN_MS)
        val params = parseParams(raw)
        var levelRaw = params["level_threshold"] ?: params["level"] ?: params["db"]
        val sustainRaw = params["sustain_ms"] ?: params["sustain"]
        val bare = params[BARE_KEY]
        if (bare != null && levelRaw == null && sustainRaw == null && bare.toDoubleOrNull() != null) {
            levelRaw = bare
        }
        val level = if (levelRaw == null) {
            DEFAULT_LEVEL_DBFS
        } else {
            levelRaw.toDoubleOrNull() ?: return null
        }
        if (level !in -90.0..0.0) return null
        val sustain = if (sustainRaw == null) {
            DEFAULT_SUSTAIN_MS
        } else {
            sustainRaw.toLongOrNull() ?: return null
        }
        if (sustain !in MIN_SUSTAIN_MS..MAX_SUSTAIN_MS) return null
        return SoundEventSpec(level, sustain)
    }

    // ------------------------------------------------------------------ geo_distance

    /**
     * `geo_distance` config: the rule fires once the phone has moved at least [distanceM] metres away
     * from the last stored point, but no more often than [minIntervalSec]. [distanceM] is required;
     * a missing [minIntervalSec] falls back to [DEFAULT_GEO_MIN_INTERVAL_SEC].
     *
     * Units: metres only (no km convenience, to keep the grammar unambiguous). The distance is the
     * great-circle (Haversine) distance, see [distanceMeters].
     */
    data class GeoDistanceSpec(val distanceM: Double, val minIntervalSec: Int) {

        /** Inclusive comparison: exactly [distanceM] metres already counts as "moved". */
        fun triggers(distanceMeters: Double): Boolean = distanceMeters >= distanceM
    }

    /**
     * Parses `geo_distance` config. `distance_m` is required (a bare numeric value is a shorthand for
     * it); `min_interval_sec` is optional and defaults to [DEFAULT_GEO_MIN_INTERVAL_SEC]. Returns
     * null for a missing/blank/non-numeric/out-of-range value so the caller can report it honestly.
     */
    fun parseGeoDistance(config: String): GeoDistanceSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var distanceRaw = params["distance_m"] ?: params["distance"]
        val intervalRaw = params["min_interval_sec"] ?: params["min_interval"]
        val bare = params[BARE_KEY]
        if (bare != null && distanceRaw == null && bare.toDoubleOrNull() != null) {
            distanceRaw = bare
        }
        val distance = distanceRaw?.toDoubleOrNull() ?: return null
        if (!distance.isFinite() || distance !in MIN_GEO_DISTANCE_M..MAX_GEO_DISTANCE_M) return null

        val interval = if (intervalRaw == null) {
            DEFAULT_GEO_MIN_INTERVAL_SEC
        } else {
            intervalRaw.toIntOrNull() ?: return null
        }
        if (interval !in MIN_GEO_INTERVAL_SEC..MAX_GEO_INTERVAL_SEC) return null

        return GeoDistanceSpec(distance, interval)
    }

    /**
     * Great-circle distance in metres between two WGS-84 points via the Haversine formula
     * (a = sin²(Δφ/2) + cosφ1·cosφ2·sin²(Δλ/2); c = 2·atan2(√a, √(1−a)); d = R·c), using the mean
     * Earth radius [EARTH_RADIUS_M]. Accurate to ~0.5% for the short distances a rule here cares
     * about, which is well inside the GPS accuracy the monitor already filters on.
     *
     * Reference: https://en.wikipedia.org/wiki/Haversine_formula
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = (sin(deltaPhi / 2).pow(2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Formats a metre distance for user-facing text / `{value}` ("300" not "300.0"; else one decimal). */
    fun formatDistance(distanceM: Double): String =
        if (distanceM % 1.0 == 0.0) distanceM.toLong().toString() else "%.1f".format(distanceM)

    // ------------------------------------------------------------------ wifi_open

    /**
     * One visible Wi-Fi network as the pure layer sees it — an Android-free projection of
     * `android.net.wifi.ScanResult` (`SSID`, `BSSID`, `capabilities`), so the OPEN/dedupe decision
     * stays unit-testable on the JVM.
     */
    data class OpenWifiObservation(val ssid: String, val bssid: String, val capabilities: String?)

    /**
     * `wifi_open` config: fire when an OPEN (unsecured) network matching [ssidContains] becomes
     * visible, at most once per [minIntervalSec] per rule.
     *
     * [ssidContains] is optional (null = any SSID); the match is a case-insensitive substring and a
     * blank/hidden SSID never matches. [minIntervalSec] defaults to
     * [DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC].
     */
    data class OpenWifiSpec(val ssidContains: String?, val minIntervalSec: Int) {

        /** True when a real (non-blank) SSID passes the optional `ssid_contains` filter. */
        fun matchesSsid(ssid: String?): Boolean {
            val value = ssid?.trim().orEmpty()
            if (value.isEmpty()) return false
            val needle = ssidContains ?: return true
            return value.contains(needle, ignoreCase = true)
        }
    }

    /**
     * The pure OPEN-detection heuristic: a capability string with NO security token (see
     * [SECURITY_TOKENS]) is unsecured. `[ESS]` / `[IBSS]` → open; `[WPA2-PSK-CCMP][ESS]`,
     * `[WPA3-SAE]`, `[RSN-OWE-CCMP]`, `[WEP]` → protected.
     *
     * A null or blank capability string is treated as **not** open: the driver did not report the
     * scheme, so claiming "open" would be a guess. Most drivers report `[ESS]` for open networks,
     * so this stays a rare edge case rather than a missed one.
     */
    fun isOpenNetwork(capabilities: String?): Boolean {
        if (capabilities.isNullOrBlank()) return false
        val upper = capabilities.uppercase()
        return SECURITY_TOKENS.none { upper.contains(it) }
    }

    /**
     * Parses `wifi_open` config. `ssid_contains` and `min_interval_sec` are both optional; a blank
     * config means "any open network, default interval". A bare non-numeric value is a shorthand for
     * `ssid_contains`, a bare numeric value for `min_interval_sec` (like the other configs). Returns
     * null for a non-numeric/out-of-range interval so the caller can reject the rule honestly.
     */
    fun parseOpenWifi(config: String): OpenWifiSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return OpenWifiSpec(null, DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC)
        val params = parseParams(raw)
        var ssid = params["ssid_contains"] ?: params["ssid"]
        var intervalRaw = params["min_interval_sec"] ?: params["min_interval"]
        val bare = params[BARE_KEY]
        if (bare != null && ssid == null && intervalRaw == null) {
            if (bare.toIntOrNull() != null) intervalRaw = bare else ssid = bare
        }
        ssid = ssid?.trim()?.takeIf { it.isNotEmpty() }

        val interval = if (intervalRaw == null) {
            DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC
        } else {
            intervalRaw.toIntOrNull() ?: return null
        }
        if (interval !in MIN_OPEN_WIFI_INTERVAL_SEC..MAX_OPEN_WIFI_INTERVAL_SEC) return null

        return OpenWifiSpec(ssid, interval)
    }

    /**
     * Pure dedupe/selection step: the open observations that should be reported for one rule —
     * open networks whose SSID passes [spec], with a real BSSID, at most one entry per BSSID, and
     * excluding BSSIDs already reported for this rule (`alreadyReportedBssids`, compared
     * case-insensitively). The caller decides when to re-fire via [openWifiDebounceElapsed].
     */
    fun openNetworksToReport(
        results: List<OpenWifiObservation>,
        spec: OpenWifiSpec,
        alreadyReportedBssids: Set<String>
    ): List<OpenWifiObservation> {
        val reported = alreadyReportedBssids.mapTo(HashSet()) { it.lowercase() }
        return results
            .filter { isOpenNetwork(it.capabilities) && spec.matchesSsid(it.ssid) && it.bssid.isNotBlank() }
            .distinctBy { it.bssid.lowercase() }
            .filter { it.bssid.lowercase() !in reported }
    }

    /**
     * The per-rule debounce decision: true when the rule has never fired or at least
     * [minIntervalSec] have elapsed since its last firing. Kept pure so the gate is JVM-testable.
     */
    fun openWifiDebounceElapsed(nowMs: Long, lastFiredAtMs: Long?, minIntervalSec: Int): Boolean =
        lastFiredAtMs == null || nowMs - lastFiredAtMs >= minIntervalSec * 1000L

    // ------------------------------------------------------------------ sms

    /**
     * `sms` config (BUG-006): fire on an incoming SMS whose sender and/or body match. Both filters
     * are optional — a blank config fires on ANY incoming SMS — and are plain case-insensitive
     * substrings: [fromContains] is matched against the originating address / alphanumeric sender
     * id, [textContains] against the message body. Matching is a substring compare on the raw
     * strings (no phone-number normalisation), so a short number prefix or an operator name both
     * work; `null` means "no filter on this field".
     */
    data class SmsSpec(val fromContains: String?, val textContains: String?) {

        fun matches(sender: String?, body: String?): Boolean {
            val senderOk = fromContains == null ||
                sender?.contains(fromContains, ignoreCase = true) == true
            val textOk = textContains == null ||
                body?.contains(textContains, ignoreCase = true) == true
            return senderOk && textOk
        }
    }

    /**
     * Parses `sms` config. Both filters are optional, so this never fails and always returns a spec
     * (a malformed/unknown-key-only config degrades to "any SMS" instead of blocking the rule).
     * Aliases: `from` / `sender` / `number` for the sender, `text_contains` / `text` / `contains`
     * for the body; a bare value is the body filter.
     */
    fun parseSms(config: String): SmsSpec {
        val raw = config.trim()
        if (raw.isEmpty()) return SmsSpec(null, null)
        val params = parseParams(raw)
        val from = params["from"] ?: params["sender"] ?: params["number"]
        var text = params["text_contains"] ?: params["text"] ?: params["contains"]
        val bare = params[BARE_KEY]
        if (bare != null && from == null && text == null) text = bare
        return SmsSpec(
            fromContains = from?.trim()?.takeIf { it.isNotEmpty() },
            textContains = text?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------ health_triggers

    /**
     * Health-relevant sensor kinds the pure layer reasons about. Kept Android-free (no `Sensor.TYPE_*`)
     * so the availability decision is unit-testable on the JVM; `HealthSensorMonitor` maps the real
     * `SensorManager` sensors onto these kinds.
     */
    enum class HealthSensorKind { HEART_RATE, STEP_COUNTER, SIGNIFICANT_MOTION, ACCELEROMETER, SPO2 }

    /** Short Russian name of a [HealthSensorKind], used in honest error/warning text. */
    fun healthSensorName(kind: HealthSensorKind): String = when (kind) {
        HealthSensorKind.HEART_RATE -> "пульс (TYPE_HEART_RATE)"
        HealthSensorKind.STEP_COUNTER -> "счётчик шагов (TYPE_STEP_COUNTER)"
        HealthSensorKind.SIGNIFICANT_MOTION -> "значимое движение (TYPE_SIGNIFICANT_MOTION)"
        HealthSensorKind.ACCELEROMETER -> "акселерометр (TYPE_ACCELEROMETER)"
        HealthSensorKind.SPO2 -> "насыщение кислородом SpO₂ (вендорный тип)"
    }

    /**
     * Which sensors a health trigger needs. Each inner list is an AND-group; the outer list is an
     * OR of groups (`no_movement` is satisfied by significant motion OR the accelerometer). Returns
     * null for a non-health trigger.
     */
    fun healthSensorAlternatives(triggerType: String): List<List<HealthSensorKind>>? = when (triggerType) {
        AutomationSpec.TRIGGER_HEART_RATE_THRESHOLD -> listOf(listOf(HealthSensorKind.HEART_RATE))
        AutomationSpec.TRIGGER_STEPS_REACHED -> listOf(listOf(HealthSensorKind.STEP_COUNTER))
        AutomationSpec.TRIGGER_NO_MOVEMENT -> listOf(
            listOf(HealthSensorKind.SIGNIFICANT_MOTION),
            listOf(HealthSensorKind.ACCELEROMETER)
        )
        AutomationSpec.TRIGGER_SLEEP_WINDOW -> listOf(listOf(HealthSensorKind.ACCELEROMETER))
        else -> null
    }

    /**
     * The honest availability decision used on the Android side: returns the missing sensors of the
     * closest alternative that is not fully satisfied, or null when the trigger can run with
     * [available]. Returns null for a non-health trigger. Reporting the missing sensors lets the
     * caller name them instead of silently storing a rule that can never fire.
     */
    fun missingHealthSensors(
        triggerType: String,
        available: Set<HealthSensorKind>
    ): List<HealthSensorKind>? {
        val alternatives = healthSensorAlternatives(triggerType) ?: return null
        if (alternatives.any { group -> group.all { it in available } }) return null
        val closest = alternatives.minByOrNull { group -> group.count { it !in available } } ?: return emptyList()
        return closest.filter { it !in available }
    }

    // ---- heart_rate_threshold

    const val MIN_HEART_RATE_BPM = 20
    const val MAX_HEART_RATE_BPM = 250
    const val DEFAULT_HEART_RATE_DWELL_SEC = 0
    const val MAX_HEART_RATE_DWELL_SEC = 3600

    /**
     * `heart_rate_threshold` config: exactly one of [above]/[below] is set; the comparison is STRICT
     * ("выше 100" fires at 101, not at exactly 100). [dwellSec] requires the condition to hold
     * continuously for that many seconds before firing (0 = fire on the first matching sample).
     */
    data class HeartRateSpec(val above: Int?, val below: Int?, val dwellSec: Int) {

        fun holds(bpm: Int): Boolean = when {
            above != null -> bpm > above
            below != null -> bpm < below
            else -> false
        }
    }

    /**
     * Pure per-rule dwell state machine for `heart_rate_threshold`. [onSample] returns true ONCE,
     * when the condition has held continuously for [HeartRateSpec.dwellSec]; it re-arms only after
     * the condition breaks, so a sustained high pulse fires one event instead of one per sample.
     */
    class HeartRateDwellTracker(private val spec: HeartRateSpec) {
        private var sinceMs: Long? = null
        private var armed = true

        fun onSample(bpm: Int, nowMs: Long): Boolean {
            if (!spec.holds(bpm)) {
                sinceMs = null
                armed = true
                return false
            }
            if (!armed) return false
            val since = sinceMs ?: nowMs.also { sinceMs = it }
            if (nowMs - since >= spec.dwellSec * 1000L) {
                armed = false
                return true
            }
            return false
        }
    }

    /** Parses `heart_rate_threshold`; returns null for a missing/ambiguous/out-of-range config. */
    fun parseHeartRate(config: String): HeartRateSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var aboveRaw = params["above"] ?: params["bpm_above"] ?: params["max"]
        var belowRaw = params["below"] ?: params["bpm_below"] ?: params["min"]
        val bare = params[BARE_KEY]
        if (aboveRaw == null && belowRaw == null && bare != null && bare.toIntOrNull() != null) {
            aboveRaw = bare
        }
        if (aboveRaw == null && belowRaw == null) return null
        if (aboveRaw != null && belowRaw != null) return null
        val bpm = (aboveRaw ?: belowRaw)!!.toIntOrNull() ?: return null
        if (bpm !in MIN_HEART_RATE_BPM..MAX_HEART_RATE_BPM) return null

        val dwellRaw = params["dwell_sec"] ?: params["dwell"]
        val dwell = if (dwellRaw == null) {
            DEFAULT_HEART_RATE_DWELL_SEC
        } else {
            dwellRaw.toIntOrNull() ?: return null
        }
        if (dwell !in 0..MAX_HEART_RATE_DWELL_SEC) return null

        return if (aboveRaw != null) HeartRateSpec(bpm, null, dwell) else HeartRateSpec(null, bpm, dwell)
    }

    // ---- steps_reached

    const val MIN_STEPS_TARGET = 1L
    const val MAX_STEPS_TARGET = 1_000_000L

    /**
     * `steps_reached` config. [target] is a DELTA of the cumulative TYPE_STEP_COUNTER from the
     * baseline captured when the rule is subscribed (the counter itself is cumulative since boot,
     * so an absolute target would be meaningless). [delta] clamps a negative delta (device reboot
     * reset the counter) to 0, and the caller re-captures the baseline in that case.
     */
    data class StepsSpec(val target: Long) {

        fun delta(baseline: Long, current: Long): Long = (current - baseline).coerceAtLeast(0L)

        fun reached(baseline: Long, current: Long): Boolean = delta(baseline, current) >= target
    }

    fun parseSteps(config: String): StepsSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var targetRaw = params["target"] ?: params["steps"] ?: params["count"]
        val bare = params[BARE_KEY]
        if (targetRaw == null && bare != null && bare.toLongOrNull() != null) targetRaw = bare
        val target = targetRaw?.toLongOrNull() ?: return null
        if (target !in MIN_STEPS_TARGET..MAX_STEPS_TARGET) return null
        return StepsSpec(target)
    }

    // ---- no_movement

    const val MIN_IDLE_MINUTES = 1
    const val MAX_IDLE_MINUTES = 1440

    /** `no_movement` config: fire after [idleMinutes] without any significant motion. */
    data class NoMovementSpec(val idleMinutes: Int) {
        val idleMs: Long get() = idleMinutes * 60_000L
    }

    /**
     * Pure per-rule stillness state machine for `no_movement`. [onMotion] re-arms the rule and
     * stamps the last motion; [shouldFire] fires ONCE per idle period, after the tracker's baseline
     * (set by the monitor at subscribe time via [onMotion]) has been quiet for `idleMinutes`.
     */
    class NoMovementTracker(private val spec: NoMovementSpec) {
        private var lastMotionMs: Long? = null
        private var fired = false

        fun onMotion(nowMs: Long) {
            lastMotionMs = nowMs
            fired = false
        }

        /** Whole minutes since the last observed motion, or 0 when no baseline has been set yet. */
        fun idleMinutes(nowMs: Long): Long {
            val last = lastMotionMs ?: return 0L
            return ((nowMs - last) / 60_000L).coerceAtLeast(0L)
        }

        fun shouldFire(nowMs: Long): Boolean {
            val last = lastMotionMs ?: return false
            if (fired) return false
            if (nowMs - last >= spec.idleMs) {
                fired = true
                return true
            }
            return false
        }
    }

    fun parseNoMovement(config: String): NoMovementSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var minutesRaw = params["minutes"] ?: params["idle_minutes"] ?: params["min"] ?: params["idle"]
        val bare = params[BARE_KEY]
        if (minutesRaw == null && bare != null && bare.toIntOrNull() != null) minutesRaw = bare
        val minutes = minutesRaw?.toIntOrNull() ?: return null
        if (minutes !in MIN_IDLE_MINUTES..MAX_IDLE_MINUTES) return null
        return NoMovementSpec(minutes)
    }

    // ---- sleep_window

    /**
     * `sleep_window` config. Android has NO reliable public sleep sensor, so this is deliberately an
     * HONEST HEURISTIC, not a measurement: [isSleeping] is true only when the current minute-of-day
     * is inside the user window AND the screen is off AND the phone is still. The window wraps
     * around midnight (e.g. `23:00–07:00`). It must never be described to the user as sleep tracking.
     */
    data class SleepWindowSpec(val startMinute: Int, val endMinute: Int) {

        fun inWindow(minuteOfDay: Int): Boolean =
            if (startMinute <= endMinute) {
                minuteOfDay in startMinute..endMinute
            } else {
                minuteOfDay >= startMinute || minuteOfDay <= endMinute
            }

        fun isSleeping(minuteOfDay: Int, screenOff: Boolean, still: Boolean): Boolean =
            inWindow(minuteOfDay) && screenOff && still
    }

    fun parseSleepWindow(config: String): SleepWindowSpec? {
        val raw = config.trim()
        if (raw.isEmpty()) return null
        val params = parseParams(raw)
        var startRaw = params["start"] ?: params["from"] ?: params["begin"]
        var endRaw = params["end"] ?: params["to"] ?: params["finish"]
        val bare = params[BARE_KEY]
        if (startRaw == null && endRaw == null && bare != null) {
            val parts = bare.split('-', '–')
            if (parts.size == 2) {
                startRaw = parts[0].trim()
                endRaw = parts[1].trim()
            }
        }
        if (startRaw == null || endRaw == null) return null
        val start = AutomationSpec.parseTime(startRaw) ?: return null
        val end = AutomationSpec.parseTime(endRaw) ?: return null
        return SleepWindowSpec(start.first * 60 + start.second, end.first * 60 + end.second)
    }

    /** Formats a minute-of-day (0..1439) as `HH:MM`, used by trigger descriptions. */
    fun formatMinuteOfDay(minuteOfDay: Int): String =
        "%02d:%02d".format((minuteOfDay / 60) % 24, minuteOfDay % 60)

    // ------------------------------------------------------------------ shared

    /**
     * Parses `a=1; b=2` (any order, case/space tolerant) into a map; a token without `=` lands
     * under [BARE_KEY]. Returns an empty map for blank input. Unknown keys are ignored by callers.
     */
    internal fun parseParams(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.split(PAIR_SEPARATOR).forEach { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@forEach
            val sep = piece.indexOf(KEY_VALUE_SEPARATOR)
            if (sep <= 0) {
                if (!out.containsKey(BARE_KEY)) out[BARE_KEY] = piece
            } else {
                val key = piece.substring(0, sep).trim()
                    .lowercase()
                    .replace('-', '_')
                val value = piece.substring(sep + 1).trim()
                if (key.isNotEmpty()) out[key] = value
            }
        }
        return out
    }
}
