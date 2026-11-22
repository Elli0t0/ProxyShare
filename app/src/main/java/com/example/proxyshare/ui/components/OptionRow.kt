package com.example.proxyshare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One shared option row, used everywhere a small set of choices is
 * offered (outbound mode, auth, theme). A pill outline with the
 * selected segment filled in primaryContainer: the M3 segmented
 * defaults read inverted with dynamic color, so this does not use
 * them at all. The selected segment is the most prominent one.
 */
@Composable
fun <T> OptionRow(
    options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier.weight(1f).height(40.dp)
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected = sel, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, maxLines = 1, style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
