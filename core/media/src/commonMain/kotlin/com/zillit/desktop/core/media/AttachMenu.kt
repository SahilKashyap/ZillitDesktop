package com.zillit.desktop.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The attach sheet: one button, a menu of kinds.
 *
 * The phones never open a bare file dialog from a composer — Android's
 * `PickerDialog` and iOS's action sheet both put *Photo / Video / Document /
 * Audio* between the button and the OS. The list is the composer's to choose:
 * chat and the boards offer all four, a call sheet offers documents only, and
 * a form that takes one PDF offers nothing to choose from at all — in which
 * case the button picks straight away, because a menu of one is a question
 * with no answer.
 */
@Composable
fun AttachMenu(
    kinds: List<PreviewKind>,
    onPick: (PreviewKind) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector = ZillitIcons.Add,
    contentDescription: String = "Attach",
) {
    val open = remember { mutableStateOf(false) }
    val single = kinds.singleOrNull()

    Box(modifier) {
        ZillitIconButton(
            icon = icon,
            contentDescription = if (single != null) "Attach a ${single.label.lowercase()}" else contentDescription,
            enabled = enabled && kinds.isNotEmpty(),
            onClick = { if (single != null) onPick(single) else open.value = true },
        )
        DropdownMenu(expanded = open.value, onDismissRequest = { open.value = false }) {
            kinds.forEach { kind ->
                DropdownMenuItem(
                    text = { AttachRow(kind) },
                    onClick = {
                        open.value = false
                        onPick(kind)
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachRow(kind: PreviewKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = ZillitTheme.colors.textSecondary,
            modifier = Modifier.size(MENU_ICON),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(ZillitTheme.spacing.sm))
        ZillitText(text = kind.label, style = ZillitTheme.typography.bodyMedium)
    }
}

private val MENU_ICON = 18.dp
