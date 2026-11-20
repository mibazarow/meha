package com.andharness.ru.domain.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSpecTest {

    @Test
    fun normalizeTriggerAcceptsCanonicalAndUserFriendlyNames() {
        assertEquals(AutomationSpec.TRIGGER_TIME, AutomationSpec.normalizeTrigger("time"))
        assertEquals(AutomationSpec.TRIGGER_TIME, AutomationSpec.normalizeTrigger(" TIME "))
        assertEquals(AutomationSpec.TRIGGER_BATTERY_LOW, AutomationSpec.normalizeTrigger("battery-low"))
        assertEquals(AutomationSpec.TRIGGER_SCREEN_OFF, AutomationSpec.normalizeTrigger("screen off"))
        assertEquals(AutomationSpec.TRIGGER_WIFI_CONNECTED, AutomationSpec.normalizeTrigger("WIFI_CONNECTED"))
        assertEquals(AutomationSpec.TRIGGER_GEO_DISTANCE, AutomationSpec.normalizeTrigger("geo_distance"))
        assertEquals(AutomationSpec.TRIGGER_GEO_DISTANCE, AutomationSpec.normalizeTrigger("GEO DISTANCE"))
        assertNull(AutomationSpec.normalizeTrigger("teleport"))
        assertNull(AutomationSpec.normalizeTrigger(""))
    }

    @Test
    fun geoDistanceTriggerValidatesDescribesAndHasAHonestSource() {
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=300"))
        assertNull(
            AutomationSpec.validateTrigger(
                AutomationSpec.TRIGGER_GEO_DISTANCE,
                "distance_m=300; min_interval_sec=120"
            )
        )
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, ""))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_GEO_DISTANCE, "distance_m=1"))
        assertNotNull(
            AutomationSpec.validateTrigger(
                AutomationSpec.TRIGGER_GEO_DISTANCE,
                "distance_m=300; min_interval_sec=10"
            )
        )

        val described = AutomationSpec.describeTrigger(
            AutomationSpec.TRIGGER_GEO_DISTANCE,
            "distance_m=300; min_interval_sec=120"
        )
        assertTrue(described.contains("300"))
        assertTrue(described.contains("2 мин"))

        assertEquals(
            AutomationSpec.TriggerSource.LOCATION_MONITOR,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_GEO_DISTANCE)
        )
        assertTrue(AutomationSpec.TRIGGER_TYPES.contains(AutomationSpec.TRIGGER_GEO_DISTANCE))
    }

    @Test
    fun wifiOpenTriggerValidatesDescribesAndHasAHonestSource() {
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, ""))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_WIFI_OPEN, "ssid_contains=Free"))
        assertNull(
            AutomationSpec.validateTrigger(
                AutomationSpec.TRIGGER_WIFI_OPEN,
                "ssid_contains=Free; min_interval_sec=120"
            )
        )
        assertNotNull(
            AutomationSpec.validateTrigger(
                AutomationSpec.TRIGGER_WIFI_OPEN,
                "ssid_contains=Free; min_interval_sec=10"
            )
        )

        val described = AutomationSpec.describeTrigger(
            AutomationSpec.TRIGGER_WIFI_OPEN,
            "ssid_contains=Free; min_interval_sec=120"
        )
        assertTrue(described.contains("открытой"))
        assertTrue(described.contains("Free"))
        assertTrue(described.contains("2 мин"))

        assertEquals(
            AutomationSpec.TriggerSource.WIFI_SCAN,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_WIFI_OPEN)
        )
        assertTrue(AutomationSpec.TRIGGER_TYPES.contains(AutomationSpec.TRIGGER_WIFI_OPEN))
        assertEquals(AutomationSpec.TRIGGER_WIFI_OPEN, AutomationSpec.normalizeTrigger("wifi_open"))
        assertEquals(AutomationSpec.TRIGGER_WIFI_OPEN, AutomationSpec.normalizeTrigger("WIFI OPEN"))
    }

    @Test
    fun normalizeActionAcceptsCanonicalAndUserFriendlyNames() {
        assertEquals(AutomationSpec.ACTION_START_CHAT, AutomationSpec.normalizeAction("start_chat"))
        assertEquals(AutomationSpec.ACTION_START_CHAT, AutomationSpec.normalizeAction("start chat"))
        assertEquals(AutomationSpec.ACTION_AGENT_TASK, AutomationSpec.normalizeAction("agent_task"))
        assertEquals(AutomationSpec.ACTION_AGENT_TASK, AutomationSpec.normalizeAction("agent task"))
        assertEquals(AutomationSpec.ACTION_NOTIFY, AutomationSpec.normalizeAction("NOTIFY"))
        assertNull(AutomationSpec.normalizeAction("run_tool"))
    }

    @Test
    fun parseTimeAcceptsValidFormatsAndRejectsInvalidOnes() {
        assertEquals(8 to 30, AutomationSpec.parseTime("08:30"))
        assertEquals(0 to 0, AutomationSpec.parseTime("0:00"))
        assertEquals(23 to 59, AutomationSpec.parseTime("23.59"))
        assertEquals(7 to 5, AutomationSpec.parseTime("7-05"))
        assertNull(AutomationSpec.parseTime("24:00"))
        assertNull(AutomationSpec.parseTime("08:60"))
        assertNull(AutomationSpec.parseTime("8"))
        assertNull(AutomationSpec.parseTime(""))
    }

    @Test
    fun timeTriggerValidationRequiresAValidClock() {
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_TIME, "08:30"))
        assertNotNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_TIME, "not-a-time"))
    }

    @Test
    fun startChatValidationRequiresPrompt() {
        assertNull(AutomationSpec.validateAction(AutomationSpec.ACTION_START_CHAT, "Проверь погоду"))
        assertNotNull(AutomationSpec.validateAction(AutomationSpec.ACTION_START_CHAT, "   "))
    }

    @Test
    fun agentTaskValidationRequiresPromptTemplate() {
        assertNull(
            AutomationSpec.validateAction(
                AutomationSpec.ACTION_AGENT_TASK,
                "Сработал триггер {trigger}: {payload}"
            )
        )
        assertNotNull(AutomationSpec.validateAction(AutomationSpec.ACTION_AGENT_TASK, "   "))
        assertNotNull(AutomationSpec.validateAction(AutomationSpec.ACTION_AGENT_TASK, ""))
    }

    @Test
    fun describeActionDescribesAgentTask() {
        val text = AutomationSpec.describeAction(
            AutomationSpec.ACTION_AGENT_TASK,
            "Событие {trigger}\nподробнее"
        )
        assertTrue(text.contains("задача агенту"))
        assertTrue(text.contains("{trigger}"))
    }

    @Test
    fun parseNotifySupportsTitleTextAndOptionalChatId() {
        val plain = AutomationSpec.parseNotify("Просто текст")
        assertNotNull(plain)
        assertEquals(AutomationSpec.DEFAULT_NOTIFY_TITLE, plain!!.title)
        assertEquals("Просто текст", plain.text)
        assertNull(plain.chatId)

        val titled = AutomationSpec.parseNotify("Заголовок | Текст уведомления")
        assertEquals("Заголовок", titled!!.title)
        assertEquals("Текст уведомления", titled.text)
        assertNull(titled.chatId)

        val withChat = AutomationSpec.parseNotify("Заголовок | Текст | 42")
        assertEquals(42L, withChat!!.chatId)

        assertNull(AutomationSpec.parseNotify(""))
        assertNull(AutomationSpec.parseNotify("Заголовок | "))
    }

    @Test
    fun notifyValidationUsesParseResult() {
        assertNull(AutomationSpec.validateAction(AutomationSpec.ACTION_NOTIFY, "Заголовок | Текст"))
        assertNotNull(AutomationSpec.validateAction(AutomationSpec.ACTION_NOTIFY, ""))
    }

    @Test
    fun matchesRespectsEnabledFlagAndTriggerType() {
        val rule = rule(id = 1, trigger = AutomationSpec.TRIGGER_BOOT)
        assertTrue(AutomationSpec.matches(rule, AutomationEvent(AutomationSpec.TRIGGER_BOOT)))
        assertFalse(AutomationSpec.matches(rule, AutomationEvent(AutomationSpec.TRIGGER_NETWORK)))
        assertFalse(
            AutomationSpec.matches(
                rule.copy(enabled = false),
                AutomationEvent(AutomationSpec.TRIGGER_BOOT)
            )
        )
    }

    @Test
    fun appOpenedMatchesConfiguredPackageOnly() {
        val any = rule(id = 1, trigger = AutomationSpec.TRIGGER_APP_OPENED, triggerConfig = "")
        assertTrue(AutomationSpec.matches(any, AutomationEvent(AutomationSpec.TRIGGER_APP_OPENED, packageName = "com.x")))

        val specific = rule(id = 2, trigger = AutomationSpec.TRIGGER_APP_OPENED, triggerConfig = "com.example.app")
        assertTrue(
            AutomationSpec.matches(
                specific,
                AutomationEvent(AutomationSpec.TRIGGER_APP_OPENED, packageName = "com.example.app")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                specific,
                AutomationEvent(AutomationSpec.TRIGGER_APP_OPENED, packageName = "com.other")
            )
        )
    }

    @Test
    fun notificationMatchesPackageOrTextSubstring() {
        val any = rule(id = 1, trigger = AutomationSpec.TRIGGER_NOTIFICATION, triggerConfig = "")
        assertTrue(AutomationSpec.matches(any, AutomationEvent(AutomationSpec.TRIGGER_NOTIFICATION)))

        val byPackage = rule(id = 2, trigger = AutomationSpec.TRIGGER_NOTIFICATION, triggerConfig = "com.whatsapp")
        assertTrue(
            AutomationSpec.matches(
                byPackage,
                AutomationEvent(AutomationSpec.TRIGGER_NOTIFICATION, packageName = "com.whatsapp")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                byPackage,
                AutomationEvent(AutomationSpec.TRIGGER_NOTIFICATION, packageName = "com.telegram")
            )
        )

        val byText = rule(id = 3, trigger = AutomationSpec.TRIGGER_NOTIFICATION, triggerConfig = "привет")
        assertTrue(
            AutomationSpec.matches(
                byText,
                AutomationEvent(AutomationSpec.TRIGGER_NOTIFICATION, text = "Привет, как дела?")
            )
        )
    }

    @Test
    fun targetedEventOnlyFiresTheRequestedRule() {
        val first = rule(id = 1, trigger = AutomationSpec.TRIGGER_TIME, triggerConfig = "08:00")
        val second = rule(id = 2, trigger = AutomationSpec.TRIGGER_TIME, triggerConfig = "08:00")
        val event = AutomationEvent(AutomationSpec.TRIGGER_TIME, ruleId = 2)
        assertFalse(AutomationSpec.matches(first, event))
        assertTrue(AutomationSpec.matches(second, event))
    }

    @Test
    fun describeHelpersNeverThrowForUnknownTypes() {
        assertNotNull(AutomationSpec.describeTrigger("unknown", ""))
        assertNotNull(AutomationSpec.describeAction("unknown", ""))
        assertTrue(
            AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_TIME, "08:30").contains("08:30")
        )
    }

    @Test
    fun validateRuleAcceptsAValidTriggerAndAction() {
        assertNull(
            AutomationSpec.validateRule(
                AutomationSpec.TRIGGER_TIME, "08:30",
                AutomationSpec.ACTION_NOTIFY, "Заголовок | Текст"
            )
        )
    }

    @Test
    fun validateRuleReportsTheFirstProblemFound() {
        assertNotNull(
            AutomationSpec.validateRule(
                AutomationSpec.TRIGGER_TIME, "not-a-time",
                AutomationSpec.ACTION_NOTIFY, "Заголовок | Текст"
            )
        )
        assertNotNull(
            AutomationSpec.validateRule(
                AutomationSpec.TRIGGER_TIME, "08:30",
                AutomationSpec.ACTION_START_CHAT, "   "
            )
        )
    }

    @Test
    fun validateRuleNormalisesTypesSoAnImportedUppercaseRuleIsChecked() {
        val valid = AutomationSpec.validateRule(
            "SOUND_EVENT", "level_threshold=-20; sustain_ms=500",
            "NOTIFY", "Заголовок | Текст"
        )
        assertNull(valid)
        val invalid = AutomationSpec.validateRule(
            "SOUND_EVENT", "level_threshold=-100; sustain_ms=500",
            "NOTIFY", "Заголовок | Текст"
        )
        assertNotNull(invalid)
    }

    @Test
    fun smsTriggerNormalisesValidatesAndHasAHonestSource() {
        assertEquals(AutomationSpec.TRIGGER_SMS, AutomationSpec.normalizeTrigger("sms"))
        assertEquals(AutomationSpec.TRIGGER_SMS, AutomationSpec.normalizeTrigger("SMS"))
        assertTrue(AutomationSpec.TRIGGER_TYPES.contains(AutomationSpec.TRIGGER_SMS))
        assertNull(AutomationSpec.validateTrigger(AutomationSpec.TRIGGER_SMS, ""))
        assertNull(
            AutomationSpec.validateTrigger(
                AutomationSpec.TRIGGER_SMS,
                "from=900; text_contains=перевод"
            )
        )
        assertEquals(
            AutomationSpec.TriggerSource.MANIFEST_RECEIVER,
            AutomationSpec.sourceOf(AutomationSpec.TRIGGER_SMS)
        )
    }

    @Test
    fun smsMatchesSenderAndBodyFromTheEvent() {
        val any = rule(id = 1, trigger = AutomationSpec.TRIGGER_SMS, triggerConfig = "")
        assertTrue(
            AutomationSpec.matches(
                any,
                AutomationEvent(AutomationSpec.TRIGGER_SMS, value = "+79001234567", payload = "Привет")
            )
        )
        assertTrue(AutomationSpec.matches(any, AutomationEvent(AutomationSpec.TRIGGER_SMS)))

        val bySender = rule(id = 2, trigger = AutomationSpec.TRIGGER_SMS, triggerConfig = "from=900")
        assertTrue(
            AutomationSpec.matches(
                bySender,
                AutomationEvent(AutomationSpec.TRIGGER_SMS, value = "+79001234567", payload = "x")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                bySender,
                AutomationEvent(AutomationSpec.TRIGGER_SMS, value = "+79995554433", payload = "x")
            )
        )

        val byText = rule(id = 3, trigger = AutomationSpec.TRIGGER_SMS, triggerConfig = "text_contains=перевод")
        assertTrue(
            AutomationSpec.matches(
                byText,
                AutomationEvent(AutomationSpec.TRIGGER_SMS, value = "900", payload = "Зачислен ПЕРЕВОД")
            )
        )
        assertFalse(
            AutomationSpec.matches(
                byText,
                AutomationEvent(AutomationSpec.TRIGGER_SMS, value = "900", payload = "Баланс")
            )
        )
    }

    @Test
    fun smsDescribeShowsTheConfiguredFilters() {
        assertTrue(AutomationSpec.describeTrigger(AutomationSpec.TRIGGER_SMS, "").contains("SMS"))
        val described = AutomationSpec.describeTrigger(
            AutomationSpec.TRIGGER_SMS,
            "from=SBER; text_contains=списание"
        )
        assertTrue(described.contains("SBER"))
        assertTrue(described.contains("списание"))
    }

    private fun rule(
        id: Long,
        trigger: String,
        triggerConfig: String = ""
    ) = AutomationRule(
        id = id,
        name = "rule-$id",
        triggerType = trigger,
        triggerConfig = triggerConfig,
        actionType = AutomationSpec.ACTION_NOTIFY,
        actionConfig = "Заголовок | Текст"
    )
}
