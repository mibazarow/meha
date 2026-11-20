package com.andharness.ru.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * The outlined field look used by the list screens (memory, skills, people, communications):
 * the raised `surfaceContainerLow` container with the scheme outline, matching the settings field.
 *
 * Deliberately narrower than `settingsFieldColors()`: it sets only the four states those screens
 * actually render (focused/unfocused container and border). Adding the disabled/error variants here
 * would change how those screens look, which this pass must not do.
 * https://developer.android.com/reference/kotlin/androidx/compose/material3/OutlinedTextFieldDefaults
 */
@Composable
internal fun searchFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)
