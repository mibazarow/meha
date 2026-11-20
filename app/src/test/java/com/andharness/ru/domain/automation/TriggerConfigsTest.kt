package com.andharness.ru.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the update.md idea #18 trigger configs: parsing and the pure decision logic
 * (calendar "due now", BLE RSSI threshold, sound level threshold).
 */
class TriggerConfigsTest {

    // ------------------------------------------------------------------ calendar_event

    @Test
    fun calendarParsingDefaultsToAnyEventFiveMinutesBefore() {
        val spec = TriggerConfigs.parseCalendar("")
        assertNotNull(spec)
        assertNull(spec!!.titleContains)
        assertEquals(TriggerConfigs.DEFAULT_MINUTES_BEFORE, spec.minutesBefore)
    }

    @Test
    fun calendarParsingAcceptsKeyValueAndBareShorthands() {
        val full = TriggerConfigs.parseCalendar("title_contains=встреча; minutes_before=10")
        assertEquals("встреча", full!!.titleContains)
        assertEquals(10, full.minutesBefore)

        val bareTitle = TriggerConfigs.parseCalendar("созвон")
        assertEquals("созвон", bareTitle!!.titleContains)
        assertEquals(TriggerConfigs.DEFAULT_MINUTES_BEFORE, bareTitle.minutesBefore)

        val bareMinutes = TriggerConfigs.parseCalendar("15")
        assertNull(bareMinutes!!.titleContains)
        assertEquals(15, bareMinutes.minutesBefore)
    }

    @Test
    fun calendarParsingRejectsOutOfRangeMinutes() {
        assertNull(TriggerConfigs.parseCalendar("minutes_before=2000"))
        assertNull(TriggerConfigs.parseCalendar("minutes_before=abc"))
        assertNotNull(TriggerConfigs.parseCalendar("minutes_before=0"))
    }

    @Test
    fun calendarIsDueRespectsOffsetAndTrailingGrace() {
        val start = 1_000_000_000L
        val spec = TriggerConfigs.CalendarSpec(titleContains = null, minutesBefore = 5)
        val fireAt = start - 5 * 60_000L

        assertFalse(spec.isDue(start, fireAt - 1))
        assertTrue(spec.isDue(start, fireAt))
        assertTrue(spec.isDue(start, start))
        assertTrue(spec.isDue(start, start + TriggerConfigs.CALENDAR_GRACE_MS))
        assertFalse(spec.isDue(start, start + TriggerConfigs.CALENDAR_GRACE_MS + 1))
    }

    @Test
    fun calendarTitleFilterIsCaseInsensitiveSubstring() {
        val spec = TriggerConfigs.CalendarSpec(titleContains = "встреча", minutesBefore = 5)
        assertTrue(spec.matchesTitle("Утренняя ВСТРЕЧА с командой"))
        assertFalse(spec.matchesTitle("Обед"))
        assertFalse(spec.matchesTitle(null))
        assertTrue(TriggerConfigs.CalendarSpec(null, 5).matchesTitle(null))
    }

    // ------------------------------------------------------------------ ble_presence

    @Test
    fun bleParsingRequiresAddressOrNameAndUsesDefaults() {
        assertNull(TriggerConfigs.parseBlePresence(""))
        assertNull(TriggerConfigs.parseBlePresence("rssi_threshold=-60"))

        val byName = TriggerConfigs.parseBlePresence("name_contains=Mi Band")
        assertEquals("Mi Band", byName!!.nameContains)
        assertNull(byName.address)
        assertEquals(TriggerConfigs.DEFAULT_RSSI_THRESHOLD, byName.rssiThreshold)
        assertEquals(TriggerConfigs.DEFAULT_BLE_TIMEOUT_SEC, byName.timeoutSec)

        val byAddress = TriggerConfigs.parseBlePresence("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", byAddress!!.address)
        assertNull(byAddress.nameContains)

        val full = TriggerConfigs.parseBlePresence("address=AA:BB:CC:DD:EE:FF; rssi_threshold=-55; timeout_sec=30")
        assertEquals(-55, full!!.rssiThreshold)
        assertEquals(30, full.timeoutSec)
    }

    @Test
    fun bleParsingRejectsBadNumbers() {
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=10"))
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=1"))
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=-127"))
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=-101"))
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=abc"))
        assertNull(TriggerConfigs.parseBlePresence("name_contains=X; timeout_sec=0"))
    }

    @Test
    fun bleParsingAcceptsTheFullMeasurableRssiRange() {
        assertEquals(-100, TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=-100")!!.rssiThreshold)
        assertEquals(0, TriggerConfigs.parseBlePresence("name_contains=X; rssi_threshold=0")!!.rssiThreshold)
    }

    @Test
    fun bleMatchChecksThresholdAddressAndName() {
        val byAddress = TriggerConfigs.BlePresenceSpec(
            address = "AA:BB:CC:DD:EE:FF",
            nameContains = null,
            rssiThreshold = -70,
            timeoutSec = 15
        )
        assertTrue(byAddress.matches("aa:bb:cc:dd:ee:ff", null, -60))
        assertTrue(byAddress.matches("AA:BB:CC:DD:EE:FF", null, -70))
        assertFalse(byAddress.matches("AA:BB:CC:DD:EE:FF", null, -71))
        assertFalse(byAddress.matches("11:22:33:44:55:66", null, -40))
        assertFalse(byAddress.matches(null, null, -40))

        val byName = TriggerConfigs.BlePresenceSpec(
            address = null,
            nameContains = "band",
            rssiThreshold = -70,
            timeoutSec = 15
        )
        assertTrue(byName.matches(null, "Mi BAND 7", -50))
        assertFalse(byName.matches(null, "Mi Band 7", -80))
        assertFalse(byName.matches(null, null, -50))
    }

    // ------------------------------------------------------------------ sound_event

    @Test
    fun soundParsingUsesDefaultsAndBareNumericShorthand() {
        val defaults = TriggerConfigs.parseSoundEvent("")
        assertEquals(TriggerConfigs.DEFAULT_LEVEL_DBFS, defaults!!.levelThresholdDbfs, 0.0001)
        assertEquals(TriggerConfigs.DEFAULT_SUSTAIN_MS, defaults.sustainMs)

        val bare = TriggerConfigs.parseSoundEvent("-12")
        assertEquals(-12.0, bare!!.levelThresholdDbfs, 0.0001)

        val full = TriggerConfigs.parseSoundEvent("level_threshold=-30; sustain_ms=1000")
        assertEquals(-30.0, full!!.levelThresholdDbfs, 0.0001)
        assertEquals(1000L, full.sustainMs)
    }

    @Test
    fun soundParsingRejectsOutOfRangeValues() {
        assertNull(TriggerConfigs.parseSoundEvent("level_threshold=5"))
        assertNull(TriggerConfigs.parseSoundEvent("level_threshold=-200"))
        assertNull(TriggerConfigs.parseSoundEvent("sustain_ms=10"))
        assertNull(TriggerConfigs.parseSoundEvent("sustain_ms=999999"))
    }

    /**
     * Regression: a threshold at/below the monitor's digital-silence floor (MIN_DBFS = -100.0) was
     * accepted and made the rule fire forever ("digital silence yields exactly -100.0 >= -100.0").
     * The floor of the accepted range is now -90.0 dBFS (boundary inclusive), and the runtime
     * comparison stays `>=`.
     */
    @Test
    fun soundParsingForbidsEffectivelyAlwaysTrueThresholds() {
        assertNull(TriggerConfigs.parseSoundEvent("level_threshold=-100"))
        assertNull(TriggerConfigs.parseSoundEvent("level_threshold=-90.1"))
        assertNull(TriggerConfigs.parseSoundEvent("level_threshold=-91"))

        val boundary = TriggerConfigs.parseSoundEvent("level_threshold=-90")
        assertNotNull(boundary)
        assertEquals(-90.0, boundary!!.levelThresholdDbfs, 0.0001)

        val usable = TriggerConfigs.parseSoundEvent("level_threshold=-25")
        assertNotNull(usable)
        assertEquals(-25.0, usable!!.levelThresholdDbfs, 0.0001)
    }

    @Test
    fun soundThresholdDecisionIsInclusive() {
        val spec = TriggerConfigs.SoundEventSpec(levelThresholdDbfs = -20.0, sustainMs = 500)
        assertTrue(spec.triggers(-20.0))
        assertTrue(spec.triggers(-5.0))
        assertFalse(spec.triggers(-20.1))
    }

    // ------------------------------------------------------------------ geo_distance

    @Test
    fun geoDistanceParsingRequiresDistanceAndDefaultsTheInterval() {
        assertNull(TriggerConfigs.parseGeoDistance(""))
        assertNull(TriggerConfigs.parseGeoDistance("   "))
        assertNull(TriggerConfigs.parseGeoDistance("min_interval_sec=600"))

        val spec = TriggerConfigs.parseGeoDistance("distance_m=500")
        assertEquals(500.0, spec!!.distanceM, 0.0001)
        assertEquals(TriggerConfigs.DEFAULT_GEO_MIN_INTERVAL_SEC, spec.minIntervalSec)
    }

    @Test
    fun geoDistanceParsingAcceptsBareDistanceAndFullPair() {
        val bare = TriggerConfigs.parseGeoDistance("350")
        assertEquals(350.0, bare!!.distanceM, 0.0001)
        assertEquals(TriggerConfigs.DEFAULT_GEO_MIN_INTERVAL_SEC, bare.minIntervalSec)

        val full = TriggerConfigs.parseGeoDistance("distance_m=250.5; min_interval_sec=120")
        assertEquals(250.5, full!!.distanceM, 0.0001)
        assertEquals(120, full.minIntervalSec)
    }

    @Test
    fun geoDistanceParsingRejectsOutOfRangeAndNonNumericValues() {
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=9"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=1000001"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=abc"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=NaN"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=Infinity"))

        assertNotNull(TriggerConfigs.parseGeoDistance("distance_m=10"))
        assertNotNull(TriggerConfigs.parseGeoDistance("distance_m=1000000"))

        assertNull(TriggerConfigs.parseGeoDistance("distance_m=300; min_interval_sec=59"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=300; min_interval_sec=86401"))
        assertNull(TriggerConfigs.parseGeoDistance("distance_m=300; min_interval_sec=abc"))
        assertNotNull(TriggerConfigs.parseGeoDistance("distance_m=300; min_interval_sec=60"))
        assertNotNull(TriggerConfigs.parseGeoDistance("distance_m=300; min_interval_sec=86400"))
    }

    @Test
    fun geoDistanceTriggersInclusivelyAtTheThreshold() {
        val spec = TriggerConfigs.GeoDistanceSpec(distanceM = 100.0, minIntervalSec = 600)
        assertTrue(spec.triggers(100.0))
        assertTrue(spec.triggers(100.1))
        assertFalse(spec.triggers(99.9))
    }

    @Test
    fun haversineMatchesKnownGreatCircleDistances() {
        // Identical point.
        assertEquals(0.0, TriggerConfigs.distanceMeters(55.0, 37.0, 55.0, 37.0), 0.001)

        // One degree of latitude is ~111.19 km at the mean Earth radius.
        val oneDegreeLat = TriggerConfigs.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, oneDegreeLat, 100.0)

        // One degree of longitude on the equator is the same arc.
        val oneDegreeLon = TriggerConfigs.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, oneDegreeLon, 100.0)

        // A quarter circumference (equator to pole) is pi/2 * R.
        val quarter = TriggerConfigs.distanceMeters(0.0, 0.0, 90.0, 0.0)
        assertEquals(Math.PI / 2 * TriggerConfigs.EARTH_RADIUS_M, quarter, 1_000.0)
    }

    // ------------------------------------------------------------------ wifi_open

    @Test
    fun openWifiParsingUsesDefaultsAndBareShorthands() {
        val blank = TriggerConfigs.parseOpenWifi("")
        assertNotNull(blank)
        assertNull(blank!!.ssidContains)
        assertEquals(TriggerConfigs.DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC, blank.minIntervalSec)

        val bySsid = TriggerConfigs.parseOpenWifi("ssid_contains=Cafe")
        assertEquals("Cafe", bySsid!!.ssidContains)
        assertEquals(TriggerConfigs.DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC, bySsid.minIntervalSec)

        val full = TriggerConfigs.parseOpenWifi("ssid_contains=Free; min_interval_sec=120")
        assertEquals("Free", full!!.ssidContains)
        assertEquals(120, full.minIntervalSec)

        val bareSsid = TriggerConfigs.parseOpenWifi("FreeWiFi")
        assertEquals("FreeWiFi", bareSsid!!.ssidContains)
        assertEquals(TriggerConfigs.DEFAULT_OPEN_WIFI_MIN_INTERVAL_SEC, bareSsid.minIntervalSec)

        val bareInterval = TriggerConfigs.parseOpenWifi("120")
        assertNull(bareInterval!!.ssidContains)
        assertEquals(120, bareInterval.minIntervalSec)
    }

    @Test
    fun openWifiParsingRejectsBadIntervals() {
        assertNull(TriggerConfigs.parseOpenWifi("ssid_contains=X; min_interval_sec=59"))
        assertNull(TriggerConfigs.parseOpenWifi("ssid_contains=X; min_interval_sec=86401"))
        assertNull(TriggerConfigs.parseOpenWifi("ssid_contains=X; min_interval_sec=abc"))

        assertNotNull(TriggerConfigs.parseOpenWifi("ssid_contains=X; min_interval_sec=60"))
        assertNotNull(TriggerConfigs.parseOpenWifi("ssid_contains=X; min_interval_sec=86400"))
    }

    @Test
    fun isOpenNetworkDetectsUnsecuredCapabilities() {
        // Open: no authentication / key-management / encryption token.
        assertTrue(TriggerConfigs.isOpenNetwork("[ESS]"))
        assertTrue(TriggerConfigs.isOpenNetwork("[IBSS]"))
        assertTrue(TriggerConfigs.isOpenNetwork("[WPS][ESS]"))

        // Protected: WPA/WPA2/WPA3, SAE, EAP, WEP and Enhanced Open (OWE).
        assertFalse(TriggerConfigs.isOpenNetwork("[WPA2-PSK-CCMP][ESS]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[WPA-PSK-TKIP]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[WPA3-SAE]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[SAE]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[RSN-PSK-CCMP]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[WPA2-EAP]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[WEP]"))
        assertFalse(TriggerConfigs.isOpenNetwork("[RSN-OWE-CCMP]"))

        // Unknown capability strings must never be claimed as open.
        assertFalse(TriggerConfigs.isOpenNetwork(null))
        assertFalse(TriggerConfigs.isOpenNetwork(""))
        assertFalse(TriggerConfigs.isOpenNetwork("   "))
    }

    @Test
    fun openWifiSsidFilterIsCaseInsensitiveAndBlankMeansAny() {
        val any = TriggerConfigs.OpenWifiSpec(ssidContains = null, minIntervalSec = 600)
        assertTrue(any.matchesSsid("Cafe Free"))
        assertFalse(any.matchesSsid(null))
        assertFalse(any.matchesSsid(""))
        assertFalse(any.matchesSsid("   "))

        val filtered = TriggerConfigs.OpenWifiSpec(ssidContains = "cafe", minIntervalSec = 600)
        assertTrue(filtered.matchesSsid("My CAFE wifi"))
        assertTrue(filtered.matchesSsid("cafe"))
        assertFalse(filtered.matchesSsid("Home"))
    }

    @Test
    fun openNetworksToReportFiltersDedupesAndHonoursSsid() {
        val spec = TriggerConfigs.OpenWifiSpec(ssidContains = "free", minIntervalSec = 600)
        val results = listOf(
            observed("Free Cafe", "AA:BB:CC:00:00:01", "[ESS]"),
            observed("Free Cafe", "AA:BB:CC:00:00:01", "[ESS]"), // duplicate BSSID in one scan
            observed("Free Station", "AA:BB:CC:00:00:02", "[WPA2-PSK-CCMP][ESS]"), // protected
            observed("Home", "AA:BB:CC:00:00:03", "[ESS]"), // SSID filter
            observed("Totally Free", "AA:BB:CC:00:00:04", "[ESS]"),
            observed("Hidden", "", "[ESS]"), // no BSSID
        )

        val fresh = TriggerConfigs.openNetworksToReport(results, spec, emptySet())
        assertEquals(2, fresh.size)
        assertEquals("AA:BB:CC:00:00:01", fresh[0].bssid)
        assertEquals("AA:BB:CC:00:00:04", fresh[1].bssid)

        // A BSSID already reported for this rule is not offered again (case-insensitive).
        val deduped = TriggerConfigs.openNetworksToReport(
            results,
            spec,
            setOf("aa:bb:cc:00:00:01")
        )
        assertEquals(1, deduped.size)
        assertEquals("AA:BB:CC:00:00:04", deduped[0].bssid)

        val anySsid = TriggerConfigs.OpenWifiSpec(ssidContains = null, minIntervalSec = 600)
        val allOpen = TriggerConfigs.openNetworksToReport(results, anySsid, emptySet())
        assertEquals(3, allOpen.size)
    }

    @Test
    fun openWifiDebounceElapsedRespectsMinInterval() {
        assertTrue(TriggerConfigs.openWifiDebounceElapsed(1_000_000L, null, 600))
        assertFalse(TriggerConfigs.openWifiDebounceElapsed(1_000_000L, 1_000_000L, 600))
        assertFalse(TriggerConfigs.openWifiDebounceElapsed(1_000_000L + 599_999L, 1_000_000L, 600))
        assertTrue(TriggerConfigs.openWifiDebounceElapsed(1_000_000L + 600_000L, 1_000_000L, 600))
    }

    private fun observed(ssid: String, bssid: String, capabilities: String?) =
        TriggerConfigs.OpenWifiObservation(ssid = ssid, bssid = bssid, capabilities = capabilities)

    // ------------------------------------------------------------------ sms

    @Test
    fun smsParsingSupportsSenderAndTextFiltersWithBareShorthand() {
        val blank = TriggerConfigs.parseSms("")
        assertNull(blank.fromContains)
        assertNull(blank.textContains)

        val full = TriggerConfigs.parseSms("from=900; text_contains=перевод")
        assertEquals("900", full.fromContains)
        assertEquals("перевод", full.textContains)

        val aliases = TriggerConfigs.parseSms("sender=SBERBANK; contains=списание")
        assertEquals("SBERBANK", aliases.fromContains)
        assertEquals("списание", aliases.textContains)

        val bare = TriggerConfigs.parseSms("код подтверждения")
        assertNull(bare.fromContains)
        assertEquals("код подтверждения", bare.textContains)
    }

    @Test
    fun smsParsingIgnoresBlankValuesAndUnknownKeys() {
        val blankValues = TriggerConfigs.parseSms("from=; text_contains=")
        assertNull(blankValues.fromContains)
        assertNull(blankValues.textContains)

        val unknownKey = TriggerConfigs.parseSms("foo=bar")
        assertNull(unknownKey.fromContains)
        assertNull(unknownKey.textContains)
    }

    @Test
    fun smsMatchesSenderAndTextCaseInsensitively() {
        val any = TriggerConfigs.parseSms("")
        assertTrue(any.matches("+79001234567", "Любой текст"))
        assertTrue(any.matches(null, null))

        val bySender = TriggerConfigs.parseSms("from=900")
        assertTrue(bySender.matches("+79001234567", "тело"))
        assertTrue(bySender.matches("900", "тело"))
        assertFalse(bySender.matches("+79995554433", "тело"))

        val byText = TriggerConfigs.parseSms("text_contains=перевод")
        assertTrue(byText.matches("900", "Зачислен ПЕРЕВОД 1000 ₽"))
        assertFalse(byText.matches("900", "Баланс: 100 ₽"))
        assertFalse(byText.matches("900", null))

        val both = TriggerConfigs.parseSms("from=SBER; text_contains=списание")
        assertTrue(both.matches("SBERBANK", "СПИСАНИЕ 500 ₽"))
        assertFalse(both.matches("SBERBANK", "зачисление"))
        assertFalse(both.matches("T-BANK", "списание"))
    }

    // ------------------------------------------------------------------ AutomationSpec integration

    @Test
    fun normalizerAcceptsTheNewTriggerNames() {
        assertEquals(AutomationSpec.TRIGGER_CALENDAR_EVENT, AutomationSpec.normalizeTrigger("calendar_event"))
        assertEquals(AutomationSpec.TRIGGER_CALENDAR_EVENT, AutomationSpec.normalizeTrigger("calendar-event"))
        assertEquals(AutomationSpec.TRIGGER_BLE_PRESENCE, AutomationSpec.normalizeTrigger("ble_presence"))
        assertEquals(AutomationSpec.TRIGGER_SOUND_EVENT, AutomationSpec.normalizeTrigger("sound event"))
        assertEquals(AutomationSpec.TRIGGER_GEO_DISTANCE, AutomationSpec.normalizeTrigger("geo_distance"))
        assertEquals(AutomationSpec.TRIGGER_GEO_DISTANCE, AutomationSpec.normalizeTrigger("GEO-DISTANCE"))
        assertEquals(AutomationSpec.TRIGGER_WIFI_OPEN, AutomationSpec.normalizeTrigger("wifi_open"))
        assertEquals(AutomationSpec.TRIGGER_WIFI_OPEN, AutomationSpec.normalizeTrigger("WIFI-OPEN"))
        assertEquals(AutomationSpec.TRIGGER_SMS, AutomationSpec.normalizeTrigger("sms"))
        assertEquals(AutomationSpec.TRIGGER_SMS, AutomationSpec.normalizeTrigger("SMS"))
    }

    @Test
    fun newTriggerValidationRejectsMalformedConfigsHonestly() {
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_CALENDAR_EVENT, "встреча"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_CALENDAR_EVENT, "minutes_before=9999"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_BLE_PRESENCE, "name_contains=Band"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_BLE_PRESENCE, ""))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SOUND_EVENT, "level_threshold=-90"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SOUND_EVENT, "level_threshold=-25"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SOUND_EVENT, "level_threshold=-100"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SOUND_EVENT, "level_threshold=5"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=300"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, ""))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=5"))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, ""))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, "ssid_contains=Free"))
        assertNotNull(
            AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, "min_interval_sec=10")
        )
    }

    @Test
    fun matchesUsesTheNewEventFields() {
        val calendar = rule(AutomationSpec.TRIGGER_CALENDAR_EVENT, "title_contains=встреча; minutes_before=5")
        assertTrue(
            AutomationSpec.matches(
                calendar,
                AutomationEvent(AutomationSpec.TRIGGER_CALENDAR_EVENT, value = "Встреча с командой")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                calendar,
                AutomationEvent(AutomationSpec.TRIGGER_CALENDAR_EVENT, value = "Обед")
            )
        )

        val ble = rule(AutomationSpec.TRIGGER_BLE_PRESENCE, "name_contains=Band; rssi_threshold=-70")
        assertTrue(
            AutomationSpec.matches(
                ble,
                AutomationEvent(AutomationSpec.TRIGGER_BLE_PRESENCE, value = "AA:BB", name = "Mi Band 7", rssi = -55)
            )
        )
        assertFalse(
            AutomationSpec.matches(
                ble,
                AutomationEvent(AutomationSpec.TRIGGER_BLE_PRESENCE, name = "Mi Band 7", rssi = -80)
            )
        )

        val sound = rule(AutomationSpec.TRIGGER_SOUND_EVENT, "level_threshold=-20")
        assertTrue(AutomationSpec.matches(sound, AutomationEvent(AutomationSpec.TRIGGER_SOUND_EVENT, level = -10.0)))
        assertFalse(AutomationSpec.matches(sound, AutomationEvent(AutomationSpec.TRIGGER_SOUND_EVENT, level = -40.0)))
        assertFalse(AutomationSpec.matches(sound, AutomationEvent(AutomationSpec.TRIGGER_SOUND_EVENT)))

        val geo = rule(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=100")
        assertTrue(AutomationSpec.matches(geo, AutomationEvent(AutomationSpec.TRIGGER_GEO_DISTANCE, value = "120.5")))
        assertFalse(AutomationSpec.matches(geo, AutomationEvent(AutomationSpec.TRIGGER_GEO_DISTANCE, value = "80")))
        assertFalse(AutomationSpec.matches(geo, AutomationEvent(AutomationSpec.TRIGGER_GEO_DISTANCE)))

        val wifi = rule(AutomationSpec.TRIGGER_WIFI_OPEN, "ssid_contains=Free")
        assertTrue(AutomationSpec.matches(wifi, AutomationEvent(AutomationSpec.TRIGGER_WIFI_OPEN, value = "Free Cafe")))
        assertFalse(AutomationSpec.matches(wifi, AutomationEvent(AutomationSpec.TRIGGER_WIFI_OPEN, value = "Home")))
        assertFalse(AutomationSpec.matches(wifi, AutomationEvent(AutomationSpec.TRIGGER_WIFI_OPEN)))
    }

    @Test
    fun describeTriggerShowsTheNewTriggersConfig() {
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_CALENDAR_EVENT, "встреча; minutes_before=10")
                .contains("10 мин")
        )
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_BLE_PRESENCE, "name_contains=Band")
                .contains("Band")
        )
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_SOUND_EVENT, "").contains("dBFS")
        )
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=300")
                .contains("300")
        )
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, "ssid_contains=Free")
                .contains("Free")
        )
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, "").contains("открытой")
        )
    }

    @Test
    fun everyNewTriggerHasAnHonestSource() {
        assertNotNull(AutomationSpec.sourceOf(AutomationSpec.TRIGGER_CALENDAR_EVENT))
        assertNotNull(AutomationSpec.sourceOf(AutomationSpec.TRIGGER_BLE_PRESENCE))
        assertNotNull(AutomationSpec.sourceOf(AutomationSpec.TRIGGER_SOUND_EVENT))
        assertEquals(
            AutomationSpec.TriggerSource.LOCATION_MONITOR,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_GEO_DISTANCE)
        )
        assertEquals(
            AutomationSpec.TriggerSource.WIFI_SCAN,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_WIFI_OPEN)
        )
        assertEquals(
            AutomationSpec.TriggerSource.MANIFEST_RECEIVER,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_SMS)
        )
    }

    private fun rule(trigger: String, triggerConfig: String) = AutomationRule(
        id = 1,
        name = "rule",
        triggerType = trigger,
        triggerConfig = triggerConfig,
        actionType = AutomationSpec.ACTION_NOTIFY,
        actionConfig = "Заголовок | Текст"
    )
}
