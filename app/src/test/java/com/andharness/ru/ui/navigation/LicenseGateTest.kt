package com.andharness.ru.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистого решения первого экрана ([LicenseGate]): гейт завершения первого запуска —
 * сохранённое подключение к модели. Пока подключения нет, стартовый экран ВСЕГДА — выбор языка
 * (шаг языка не пропускается, даже если его выбирали ранее); после сохранения — главный экран
 * (список чатов). После выбора языка экран лицензии показывается, только если EULA ещё не принята.
 * При неподтверждённой лицензии ни одно входящее намерение не обрабатывается.
 */
class LicenseGateTest {

    @Test
    fun startDestination_modelNotConfigured_isLanguage() {
        assertEquals(
            AppDestination.LANGUAGE,
            LicenseGate.startDestination(modelConfigured = false)
        )
    }

    @Test
    fun startDestination_modelConfigured_isChats() {
        assertEquals(
            AppDestination.CHATS,
            LicenseGate.startDestination(modelConfigured = true)
        )
    }

    @Test
    fun afterLanguage_mirrorsLicenseState() {
        assertEquals(AppDestination.LICENSE, LicenseGate.afterLanguage(licenseAccepted = false))
        assertEquals(AppDestination.ONBOARDING, LicenseGate.afterLanguage(licenseAccepted = true))
    }

    @Test
    fun inbound_droppedWhileLicenseNotAccepted() {
        assertFalse(LicenseGate.inboundAllowed(licenseAccepted = false))
    }

    @Test
    fun inbound_allowedAfterAcceptance() {
        assertTrue(LicenseGate.inboundAllowed(licenseAccepted = true))
    }
}
