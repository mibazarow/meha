package com.andharness.ru.domain.license

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лицензионный гейт: экран EULA показывается, пока сохранённая версия меньше текущей. Тесты
 * покрывают первый запуск, повторный запуск и выпуск новой версии текста.
 */
class LicenseAcceptanceTest {

    private val current = 1

    @Test
    fun noStoredVersionRequiresAcceptance() {
        assertFalse(LicenseAcceptance.isAccepted(LicenseAcceptance.NO_VERSION, current))
    }

    @Test
    fun sameVersionIsAccepted() {
        assertTrue(LicenseAcceptance.isAccepted(current, current))
    }

    @Test
    fun newerStoredVersionIsAccepted() {
        assertTrue(LicenseAcceptance.isAccepted(current + 1, current))
    }

    @Test
    fun versionBumpRequiresFreshAcceptance() {
        assertFalse(LicenseAcceptance.isAccepted(current, current + 1))
    }

    @Test
    fun negativeStoredVersionIsNotAccepted() {
        assertFalse(LicenseAcceptance.isAccepted(-1, current))
    }
}
