package com.andharness.ru.ui.license

import androidx.lifecycle.ViewModel
import com.andharness.ru.data.prefs.LicenseStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Единственное действие экрана лицензии — зафиксировать принятие актуальной версии текста.
 * ViewModel скрывает от композабла [LicenseStore], как это принято в проекте (`ThemeViewModel`).
 */
@HiltViewModel
class LicenseViewModel @Inject constructor(
    private val store: LicenseStore
) : ViewModel() {

    /** Только фиксирует согласие: приложение не отправляет никакой статистики. */
    fun accept() {
        store.accept(LicenseStore.LICENSE_VERSION)
    }
}
