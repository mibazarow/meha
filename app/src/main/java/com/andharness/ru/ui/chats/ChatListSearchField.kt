package com.andharness.ru.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.ui.theme.PillShape

/**
 * Editable chat-list search field (redesign §4 «Список чатов», phase 9): a fully rounded
 * ([PillShape]) pill on the `surfaceVariant` container with a leading magnifier, the «Поиск по
 * чатам» hint and a trailing clear (✕) button that appears only when the query is non-empty. Every
 * keystroke is forwarded to [onQueryChange]; the search itself is debounced and cancellable in the
 * ViewModel.
 *
 * It is built on [BasicTextField] rather than Material3 `TextField`: the latter enforces a 56dp
 * minimum height, so pinning it to the compact ~42dp pill clipped the hint/text. [BasicTextField]
 * has no built-in decoration and no minimum height, so the compact height is safe; the pill is drawn
 * by the surrounding [Surface] and the single line is vertically centred by the decoration box's
 * [Row] (`verticalAlignment = CenterVertically`). The value/`onValueChange` overload is used to keep
 * the existing VM-hoisted `String` state (the docs' own decoration-box example uses this overload);
 * the newer `state: TextFieldState` overload is equivalent and not required here.
 *
 * Docs:
 * - `BasicTextField` (value/`onValueChange` overload, `singleLine`, `textStyle`, `cursorBrush`,
 *   `decorationBox`; "Interactive text input field without decorations"; the `decorationBox`
 *   parameter "allows to add decorations around text field, such as icon, placeholder ... and
 *   automatically increase the hit target area"; `innerTextField` must be called exactly once) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/BasicTextField.composable
 * - Text fields guide — https://developer.android.com/develop/ui/compose/text/user-input
 * - `IconButton` (trailing clear) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#IconButton(kotlin.Function0,androidx.compose.ui.Modifier,kotlin.Boolean,androidx.compose.foundation.layout.PaddingValues,androidx.compose.foundation.interaction.MutableInteractionSource,kotlin.Function0)
 */
@Composable
internal fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxSize(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_search_label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // innerTextField must be invoked exactly once (BasicTextField contract).
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.memory_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}
