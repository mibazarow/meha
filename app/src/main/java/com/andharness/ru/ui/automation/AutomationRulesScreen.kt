package com.andharness.ru.ui.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsClickRow
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * View/manage screen for automation rules («Правила автоматизации»).
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer is a [SettingsSection] with muted [SettingsHelperText] and an «Открыть чаты»
 * [SettingsClickRow], and every rule is a rounded [Surface] card (`shapes.large`,
 * `surfaceContainerLow`) carrying its switch and a delete glyph in the `error` role, instead of a
 * flat row followed by a divider. Spacing (16 dp gutters, 8 dp gaps) and `bodyLarge` / `bodySmall`
 * typography follow the shared kit. Behaviour is unchanged — no confirmation dialogs, per the agreed
 * product decision (dangerous actions run immediately).
 *
 * Rule *creation* deliberately stays tool-driven: ask the agent in chat and it calls
 * `automation_rule_create`. The helper text says so, so the missing «add» button is honest.
 *
 * Note (follow-up): [AutomationSpec.describeTrigger] / [AutomationSpec.describeAction] are domain
 * helpers that return Russian text, and `rule.name` is user data; they are not localized here.
 *
 * References (official docs):
 * - Material 3 components / `Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationRulesScreen(
    onBack: () -> Unit,
    onOpenChats: () -> Unit = {},
    viewModel: AutomationRulesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер «уже анимированных» строк: каскад проигрывается один раз за визит экрана, а не при
    // переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    // Значения входа — https://developer.android.com/develop/ui/compose/animation/value-based
    val rowTracker = remember { EnterAnimationTracker() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_automation),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsSection(
                    title = stringResource(R.string.automation_section_about_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        // Вход интро-панели — первая ступень каскада (and-fade-up).
                        .enterAnimation(index = 0)
                ) {
                    SettingsHelperText(stringResource(R.string.automation_intro))
                    SettingsClickRow(
                        title = stringResource(R.string.schedule_open_chats),
                        leadingIcon = Icons.AutoMirrored.Filled.List,
                        onClick = onOpenChats
                    )
                }

                when {
                    state.isLoading && state.rules.isEmpty() -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { CenterMessage(stringResource(R.string.schedule_loading), textAlign = null) }

                    state.rules.isEmpty() -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.automation_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            PrimaryGradientButton(
                                text = stringResource(R.string.schedule_open_chats),
                                onClick = onOpenChats,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = state.rules,
                            key = { _, rule -> rule.id }
                        ) { index, rule ->
                            AutomationRuleCard(
                                rule = rule,
                                onToggle = { enabled -> viewModel.setEnabled(rule.id, enabled) },
                                onDelete = { viewModel.delete(rule.id) },
                                modifier = Modifier.enterAnimation(
                                    index = index + 1,
                                    tracker = rowTracker,
                                    itemKey = rule.id
                                )
                            )
                        }
                        item {
                            Text(
                                text = stringResource(R.string.automation_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        state.error?.let { error ->
                            item {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleLabel = if (rule.enabled) {
        stringResource(R.string.automation_disable_rule_cd, rule.name)
    } else {
        stringResource(R.string.automation_enable_rule_cd, rule.name)
    }
    val deleteLabel = stringResource(R.string.automation_delete_rule_cd)
    // Верхняя строка: имя слева, переключатель и удаление — справа; описания ниже идут на всю
    // ширину строки (не зажаты управлениями), чтобы длинный триггер/действие читались целиком.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = toggleLabel }
                )
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        .semantics { contentDescription = deleteLabel }
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Описания — на всю ширину строки (edge-to-edge), отдельным семантическим узлом, чтобы
            // TalkBack читал триггер и действие вместе; переключатель и удаление остаются отдельно.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.automation_when,
                        AutomationSpec.describeTrigger(rule.triggerType, rule.triggerConfig)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.automation_action,
                        AutomationSpec.describeAction(rule.actionType, rule.actionConfig)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = if (rule.enabled) {
                    stringResource(R.string.automation_enabled)
                } else {
                    stringResource(R.string.automation_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rule.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
