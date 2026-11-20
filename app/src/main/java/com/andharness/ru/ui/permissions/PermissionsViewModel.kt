package com.andharness.ru.ui.permissions

import androidx.lifecycle.ViewModel
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.permission.mergeIntoCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissionManager: PermissionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // One row per capability: READ_/WRITE_ duplicates are merged so no two rows look alike.
        val items = permissionManager.all().mergeIntoCapabilities().map { capability ->
            PermissionItem(
                androidPermissions = capability.androidPermissions,
                displayName = capability.displayName,
                description = capability.description,
                granted = capability.androidPermissions.all { permissionManager.isGranted(it) }
            )
        }
        // The app declares REQUEST_INSTALL_PACKAGES (src/sideload manifest), so the
        // "install unknown apps" row always applies.
        val specialAccess = SPECIAL_ACCESS_ENTRIES
            .map { (token, displayName) ->
                SpecialAccessItem(
                    token = token,
                    displayName = displayName,
                    granted = permissionManager.isGranted(token)
                )
            }
        _uiState.value = PermissionsUiState(items = items, specialAccess = specialAccess)
    }

    companion object {
        /** Display order of the special-access block, as agreed for the «Все разрешения» screen. */
        private val SPECIAL_ACCESS_ENTRIES = listOf(
            SpecialAccess.USAGE_ACCESS to "Доступ к данным об использовании",
            SpecialAccess.NOTIFICATION_ACCESS to "Доступ к уведомлениям",
            SpecialAccess.ACCESSIBILITY to "Специальные возможности",
            SpecialAccess.MANAGE_EXTERNAL_STORAGE to "Доступ ко всем файлам",
            SpecialAccess.WRITE_SETTINGS to "Изменение системных настроек",
            SpecialAccess.DEVICE_ADMIN to "Администратор устройства",
            SpecialAccess.AUTOFILL to "Автозаполнение",
            SpecialAccess.INSTALL_UNKNOWN_APPS to "Установка неизвестных приложений"
        )
    }
}
