package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.domain.settings.LlmProvider
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Сохранённые провайдеры» — the list of every saved provider profile, shown ABOVE the edit form
 * on the «Провайдеры» screen.
 *
 * Any number of profiles may be stored, but exactly one is active: each row is a radio option
 * (`Modifier.selectable` + `RadioButton(onClick = null)`, the standard Material single-choice
 * pattern), and tapping it loads that profile into the form AND makes it the active backend. The
 * trailing delete control asks for confirmation, because removing a profile also deletes its API
 * key. «Добавить провайдера» starts a blank draft without touching the currently active profile.
 *
 * Docs:
 * - Radio buttons / single-choice lists —
 *   https://developer.android.com/develop/ui/compose/components/radio-button
 * - `Modifier.selectable` (role + selected semantics) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/selection/package-summary#(androidx.compose.ui.Modifier).selectable(kotlin.Boolean,kotlin.Boolean,androidx.compose.ui.semantics.Role,kotlin.Function0)
 * - Material 3 `AlertDialog` —
 *   https://developer.android.com/develop/ui/compose/components/dialog
 */
@Composable
internal fun ProviderListSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.provider_saved_title),
        modifier = Modifier.enterAnimation(
            index = 0,
            tracker = tracker,
            itemKey = "provider_saved_list"
        )
    ) {
        if (state.providers.isEmpty()) {
            SettingsHelperText(stringResource(R.string.provider_saved_empty))
        } else {
            state.providers.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    isActive = provider.id == state.activeProviderId,
                    isEditing = provider.id == state.editingProviderId,
                    onSelect = { viewModel.selectProvider(provider.id) },
                    onDelete = { viewModel.requestDeleteProvider(provider.id) },
                )
            }
        }
        // Full-width yellow action, same filled-tonal pattern as the bordeaux permissions
        // button: gold fill with white content so the label is readable on the light card.
        FilledTonalButton(
            onClick = viewModel::startNewProvider,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = BrandGold,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.provider_add),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    val candidate = state.providerDeleteCandidateId
        ?.let { id -> state.providers.firstOrNull { it.id == id } }
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteProvider,
            title = { Text(stringResource(R.string.provider_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.provider_delete_dialog_text,
                        candidate.displayName.ifBlank {
                            stringResource(R.string.provider_unnamed)
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteProvider) {
                    Text(stringResource(R.string.settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeleteProvider) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

/**
 * One saved profile. The whole row is the radio target; the active one is highlighted with the
 * `primaryContainer` surface and an «активен» badge, the one currently loaded into the form gets a
 * small «редактируется» label, so the two states are readable at a glance.
 */
@Composable
private fun ProviderRow(
    provider: LlmProvider,
    isActive: Boolean,
    isEditing: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isActive,
                onClick = onSelect,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(
            selected = isActive,
            // The row above owns the selection semantics; a second handler would duplicate them.
            onClick = null,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val title = provider.displayName.ifBlank {
                stringResource(R.string.provider_unnamed)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (provider.model.isNotBlank()) {
                Text(
                    text = provider.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val badge = when {
                isActive && isEditing -> stringResource(R.string.provider_badge_active_editing)
                isActive -> stringResource(R.string.provider_badge_active)
                isEditing -> stringResource(R.string.provider_badge_editing)
                else -> null
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        IconButton(
            onClick = onDelete,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.provider_delete)
            )
        }
    }
}
