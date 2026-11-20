package com.andharness.ru.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Centred status line for an empty/loading state inside a [BoxScope]: body-large text on the
 * muted on-surface colour, 24 dp from the edges.
 *
 * [textAlign] stays a parameter because two screens (`SuggestionsScreen`, `AutomationRulesScreen`)
 * render short single-line statuses where the platform default alignment is the intended look;
 * everything else centres multi-line copy.
 */
@Composable
internal fun BoxScope.CenterMessage(text: String, textAlign: TextAlign? = TextAlign.Center) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
    )
}
