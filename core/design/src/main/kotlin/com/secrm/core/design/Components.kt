package com.secrm.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Success / warning hues, next to the Material colour roles. */
val MaterialTheme.status: StatusColors @Composable @ReadOnlyComposable get() = LocalStatusColors.current

/** Monospace body-small, for hosts, keys and paths. */
val MaterialTheme.mono @Composable @ReadOnlyComposable get() = typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** A group heading on a settings-style screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Space.xs, top = Space.sm),
    )
}

/** A rounded status badge: coloured dot and a short label. */
@Composable
fun StatusPill(state: ConnectionVisual, label: String, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    val (container, content) = when (state) {
        ConnectionVisual.Up -> st.success.copy(alpha = 0.16f) to st.success
        ConnectionVisual.Connecting, ConnectionVisual.Warning -> st.warning.copy(alpha = 0.16f) to st.warning
        ConnectionVisual.Error -> s.errorContainer to s.onErrorContainer
        ConnectionVisual.Idle -> s.surfaceContainerHighest to s.onSurfaceVariant
    }
    Row(modifier.background(container, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** A small read-only label, e.g. "Tailscale" or "Key". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
