package com.andharness.ru.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.skill.Skill

/**
 * All dialogs of the «Навыки» screen: details, rename/description edit, delete confirmation,
 * parameter entry for a run and the run report. Each dialog reflects exactly one nullable field of
 * [SkillLibraryUiState], so at most one is visible at a time. All fixed UI copy is localized with
 * [stringResource].
 *
 * Docs: `AlertDialog` / `OutlinedTextField` —
 * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * ; `stringResource` —
 * https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@Composable
internal fun SkillLibraryDialogs(
    state: SkillLibraryUiState,
    viewModel: SkillLibraryViewModel,
) {
    state.details?.let { skill ->
        SkillDetailsDialog(skill = skill, onDismiss = viewModel::dismissDetails)
    }

    state.editTarget?.let { skill ->
        SkillEditDialog(
            skill = skill,
            onDismiss = viewModel::dismissEdit,
            onSave = { name, description -> viewModel.rename(skill.id, name, description) }
        )
    }

    state.deleteTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(stringResource(R.string.skills_delete_text, skill.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(skill.id) }) {
                    Text(stringResource(R.string.settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    state.runTarget?.let { skill ->
        SkillRunDialog(
            skill = skill,
            onDismiss = viewModel::dismissRun,
            onRun = { values -> viewModel.confirmRun(skill, values) }
        )
    }

    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReport,
            title = {
                Text(
                    if (report.success) {
                        stringResource(R.string.skills_report_success)
                    } else {
                        stringResource(R.string.skills_report_failure)
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(report.text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissReport) {
                    Text(stringResource(R.string.skills_close))
                }
            }
        )
    }
}

/** Read-only details: description, metadata, declared parameters and the numbered step list. */
@Composable
private fun SkillDetailsDialog(skill: Skill, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = SkillLibraryLogic.metaText(skill),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (skill.description.isNotBlank()) {
                    Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.skills_params_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (skill.parameters.isEmpty()) {
                    Text(
                        stringResource(R.string.skills_no_params),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    skill.parameters.forEach { parameter ->
                        Text(
                            text = "• " + SkillLibraryLogic.formatParameter(
                                name = parameter.name,
                                type = parameter.type,
                                required = parameter.required,
                                description = parameter.description,
                                default = parameter.default,
                                choices = parameter.choices,
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.skills_steps_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (skill.steps.isEmpty()) {
                    Text(
                        stringResource(R.string.skills_no_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    skill.steps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. " + SkillLibraryLogic.formatStep(step),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.skills_close)) }
        }
    )
}

/** Rename + description editing. Step editing is intentionally not offered here. */
@Composable
private fun SkillEditDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var description by remember(skill.id) { mutableStateOf(skill.description) }
    val canSave = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_edit_name)) },
                    singleLine = true,
                    isError = name.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.skills_edit_description)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.skills_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(name, description) }
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

/**
 * Parameter entry for a run: one field per declared parameter, pre-filled with its default (or the
 * first CHOICE value). Values are passed to [SkillLibraryViewModel.confirmRun], which plans and
 * validates them honestly before the engine runs.
 */
@Composable
private fun SkillRunDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    val values = remember(skill.id) {
        mutableStateMapOf<String, String>().apply {
            skill.parameters.forEach { parameter ->
                val initial = parameter.default
                    ?: parameter.choices.firstOrNull()
                    .orEmpty()
                put(parameter.name, initial)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_run_title, skill.name)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                skill.parameters.forEach { parameter ->
                    OutlinedTextField(
                        value = values[parameter.name].orEmpty(),
                        onValueChange = { values[parameter.name] = it },
                        label = {
                            Text(parameter.name + if (parameter.required) " *" else "")
                        },
                        supportingText = {
                            val choicesText = if (parameter.choices.isNotEmpty()) {
                                stringResource(
                                    R.string.skills_run_choices,
                                    parameter.choices.joinToString(", ")
                                )
                            } else {
                                ""
                            }
                            val hint = buildList {
                                if (parameter.description.isNotBlank()) add(parameter.description)
                                if (choicesText.isNotBlank()) add(choicesText)
                            }.joinToString(" · ")
                            if (hint.isNotBlank()) Text(hint)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = stringResource(R.string.skills_run_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(values.toMap()) }) {
                Text(stringResource(R.string.skills_run_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}
