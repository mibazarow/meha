package com.andharness.ru.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R

/**
 * Leading glyph for the transient turn-status line (D10): the state is conveyed by the icon and its
 * colour — running = spinner/primary, success = check/primary, failed = warning/error,
 * compaction = hourglass/muted, streaming = pen/primary. Emoji are never used.
 */
@Composable
internal fun ChatStatusLeadingIcon(kind: ChatStatusKind) {
    val scheme = MaterialTheme.colorScheme
    when (kind) {
        ChatStatusKind.TOOL_RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = scheme.primary
        )
        ChatStatusKind.COMPACTION -> Icon(
            painter = painterResource(R.drawable.ic_hourglass),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        ChatStatusKind.TOOL_SUCCEEDED -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(16.dp)
        )
        ChatStatusKind.TOOL_FAILED -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(16.dp)
        )
        ChatStatusKind.THINKING -> Icon(
            painter = painterResource(R.drawable.ic_hourglass),
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(16.dp)
        )
        ChatStatusKind.PROVIDER_SLOW -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(16.dp)
        )
        ChatStatusKind.STREAMING -> Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}
