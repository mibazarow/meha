package com.andharness.ru.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.ui.permission.SpecialAccessIntents

/**
 * Full permission inventory (runtime permissions + special-access toggles).
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer (and the «Особые доступы» group header) is a [SettingsSection] with muted
 * [SettingsHelperText], and every permission is a rounded [Surface] card whose trailing column shows
 * the state word — `primary` when granted, `error` when denied (with the compact «Запросить» action)
 * or muted «Особая» for special access. Spacing (16 dp gutters, 8 dp gaps) and `bodyLarge` /
 * `bodySmall` typography follow the shared kit.
 *
 * Visual polish only: the launcher, the ON_RESUME refresh and the per-row request behaviour are
 * unchanged, and every colour comes from [MaterialTheme.colorScheme]. All fixed UI copy is localized.
 *
 * Note (follow-up): the entry names/descriptions (`state.items` / `state.specialAccess`) come from
 * the permission registry (domain) and are still Russian-only.
 *
 * References (official docs):
 * - Material 3 components / `Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Lists in Compose — https://developer.android.com/develop/ui/compose/lists
 * - Requesting permissions — https://developer.android.com/training/permissions/requesting
 * - `stringResource` — https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permissions can be granted/revoked in system settings while this screen is backgrounded, so
    // re-read the snapshot every time it comes back to the foreground (not only once in `init`).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    // A merged capability may map to several Android permissions (READ_/WRITE_…), so keep both
    // launchers: the single-permission contract for one-permission rows and the batch contract for
    // the rest. Both simply refresh the snapshot. https://developer.android.com/training/permissions/requesting
    val singlePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refresh()
        }
    val multiplePermissionsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refresh()
        }

    /** Requests every underlying permission of a capability row in one system dialog. */
    fun requestPermissions(permissions: List<String>) {
        when {
            permissions.isEmpty() -> Unit
            permissions.size == 1 -> singlePermissionLauncher.launch(permissions.first())
            else -> multiplePermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    // Трекер «уже анимированных» строк: каскадный вход проигрывается один раз за визит экрана,
    // а не при переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    // Значения входа — https://developer.android.com/develop/ui/compose/animation/value-based
    val rowTracker = remember { EnterAnimationTracker() }

    // Android 13+ hides the Accessibility / Notification-access toggles behind "Allow restricted
    // settings" for apps installed outside a store. Explain that step only when it can be the
    // blocker: sensitive access is still off and we were not installed by a store.
    val showRestrictedSettingsHint = remember(state.specialAccess, context) {
        val sensitiveDenied = state.specialAccess.any {
            !it.granted && (it.token == SpecialAccess.ACCESSIBILITY ||
                it.token == SpecialAccess.NOTIFICATION_ACCESS)
        }
        val installer = runCatching {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        }.getOrNull()
        RestrictedSettingsHint.shouldShow(Build.VERSION.SDK_INT, installer, sensitiveDenied)
    }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.settings_permissions_btn),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsSection(
                    title = stringResource(R.string.permissions_section_about_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        // Вход интро-панели — первая ступень каскада (and-fade-up).
                        .enterAnimation(
                            index = 0,
                            tracker = rowTracker,
                            itemKey = "permissions-intro"
                        )
                ) {
                    SettingsHelperText(stringResource(R.string.permissions_intro))
                }
            }
            itemsIndexed(
                items = state.items,
                key = { _, permission -> permission.displayName }
            ) { index, permission ->
                val isRuntimePermission = permission.androidPermissions.isNotEmpty()
                PermissionCard(
                    title = permission.displayName,
                    subtitle = permission.description
                        .takeIf { it.isNotBlank() && it != permission.displayName },
                    isRuntimePermission = isRuntimePermission,
                    granted = permission.granted,
                    showOpen = !permission.granted && !isRuntimePermission,
                    onRequest = { requestPermissions(permission.androidPermissions) },
                    onOpen = {},
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .enterAnimation(
                            index = index + 1,
                            tracker = rowTracker,
                            itemKey = permission.displayName
                        )
                )
            }
            // «Особые доступы» — grants that cannot use the runtime dialog: each row opens its
            // system-settings screen. Status comes from PermissionManager (single source of truth).
            item {
                SettingsSection(
                    title = stringResource(R.string.permissions_special_access_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    SettingsHelperText(stringResource(R.string.permissions_special_access_hint))
                }
            }
            if (showRestrictedSettingsHint) {
                item {
                    SettingsSection(
                        title = stringResource(R.string.permissions_restricted_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .enterAnimation(
                                index = state.items.size,
                                tracker = rowTracker,
                                itemKey = "permissions-restricted-settings"
                            )
                    ) {
                        SettingsHelperText(stringResource(R.string.permissions_restricted_hint))
                    }
                }
            }
            itemsIndexed(
                items = state.specialAccess,
                key = { _, item -> item.token }
            ) { index, item ->
                // Resolve the settings Intent once per row; unknown tokens yield null (no crash).
                val intent = remember(item.token) {
                    runCatching { SpecialAccessIntents.intentFor(context, item.token) }.getOrNull()
                }
                PermissionCard(
                    title = item.displayName,
                    subtitle = null,
                    isRuntimePermission = false,
                    granted = item.granted,
                    showOpen = true,
                    onRequest = {},
                    onOpen = {
                        // Best-effort: a device without the dedicated screen must not crash the
                        // list. https://developer.android.com/reference/android/content/Intent#startActivity(android.content.Intent)
                        runCatching { intent?.let(context::startActivity) }
                    },
                    openEnabled = intent != null,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .enterAnimation(
                            index = state.items.size + index + 1,
                            tracker = rowTracker,
                            itemKey = item.token
                        )
                )
            }
        }
    }
}

/**
 * One permission as a rounded card (unified internal-page style): title + optional subtitle on the
 * left, and a trailing column with the state word and — when the permission is not granted — the
 * matching action. Runtime permissions show «Запросить» (system dialog); special accesses show
 * «Открыть» (system settings). Long titles wrap to two lines instead of ellipsizing.
 */
@Composable
private fun PermissionCard(
    title: String,
    subtitle: String?,
    isRuntimePermission: Boolean,
    granted: Boolean,
    showOpen: Boolean,
    onRequest: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    openEnabled: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when {
                        granted -> stringResource(R.string.permissions_granted)
                        isRuntimePermission -> stringResource(R.string.permissions_denied)
                        else -> stringResource(R.string.permissions_special)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        granted -> MaterialTheme.colorScheme.primary
                        isRuntimePermission -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (!granted && isRuntimePermission) {
                    OutlinedButton(
                        onClick = onRequest,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.permissions_request),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                // Special-access rows (no runtime permission) cannot be requested with a dialog:
                // open the matching system-settings screen.
                if (showOpen) {
                    OutlinedButton(
                        onClick = onOpen,
                        enabled = openEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_search_result_open),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}
