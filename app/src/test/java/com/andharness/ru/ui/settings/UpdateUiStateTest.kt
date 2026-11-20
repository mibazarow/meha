package com.andharness.ru.ui.settings

import com.andharness.ru.data.update.UpdateState
import com.andharness.ru.data.update.hasKnownTotal
import com.andharness.ru.data.update.progressFraction
import com.andharness.ru.domain.update.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the updater fields/projections of [SettingsUiState] and the progress helpers on
 * [UpdateState.Downloading]. No Android/Compose API is touched, so they run as plain unit tests.
 *
 * References:
 * - kotlinx.coroutines `StateFlow`: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 * - Compose progress: https://developer.android.com/develop/ui/compose/components/progress
 */
class UpdateUiStateTest {

    private fun manifest(
        versionCode: Long = 50,
        versionName: String = "1.5",
        mandatory: Boolean = false,
        minSupportedVersionCode: Long = 0,
    ) = UpdateManifest(
        versionCode = versionCode,
        url = "https://example.com/updates/meha-50.apk",
        versionName = versionName,
        sha256 = "a".repeat(64),
        mandatory = mandatory,
        minSupportedVersionCode = minSupportedVersionCode,
    )

    @Test
    fun errorStateIsSurfacedAsAnErrorStatus() {
        val state = SettingsUiState(updaterState = UpdateState.Error("Сеть недоступна"))
        assertEquals("Сеть недоступна", state.updateStatusText)
        assertTrue(state.updateStatusIsError)
    }

    @Test
    fun transientSuccessMessageIsShownAndNotAnError() {
        val state = SettingsUiState(
            updaterState = UpdateState.UpToDate,
            updateMessage = "Установлена последняя версия.",
        )
        assertEquals("Установлена последняя версия.", state.updateStatusText)
        assertFalse(state.updateStatusIsError)
    }

    @Test
    fun availableUpdateIsExposedAndNotMandatoryByDefault() {
        val state = SettingsUiState(
            updaterState = UpdateState.Available(manifest()),
            installedVersionCode = 42,
        )
        assertEquals(50L, state.availableUpdate?.versionCode)
        assertFalse(state.isUpdateMandatory)
    }

    @Test
    fun mandatoryFlagMakesAvailableUpdateMandatory() {
        val state = SettingsUiState(
            updaterState = UpdateState.Available(manifest(mandatory = true)),
            installedVersionCode = 42,
        )
        assertTrue(state.isUpdateMandatory)
    }

    @Test
    fun belowMinSupportedVersionCodeMakesUpdateMandatory() {
        val state = SettingsUiState(
            updaterState = UpdateState.Available(manifest(minSupportedVersionCode = 48)),
            installedVersionCode = 42,
        )
        assertTrue(state.isUpdateMandatory)
    }

    @Test
    fun downloadingProgressIsDeterminateOnlyWhenTotalIsKnown() {
        val known = UpdateState.Downloading(manifest(), downloadedBytes = 512, totalBytes = 1024)
        assertEquals(0.5f, known.progressFraction, 0.0001f)
        assertTrue(known.hasKnownTotal)

        val unknown = UpdateState.Downloading(manifest(), downloadedBytes = 512, totalBytes = -1L)
        assertEquals(0f, unknown.progressFraction, 0.0001f)
        assertFalse(unknown.hasKnownTotal)
    }
}
