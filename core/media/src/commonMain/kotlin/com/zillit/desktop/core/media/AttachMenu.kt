package com.zillit.desktop.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    contentDescription: String = str(S.dm_docs_attach),
) {
    val open = remember { mutableStateOf(false) }
    val single = kinds.singleOrNull()

    Box(modifier) {
        ZillitIconButton(
            icon = icon,
            contentDescription = if (single != null) {
                str(S.desktop_media_attach_a, single.label.lowercase())
            } else {
                contentDescription
            },
            enabled = enabled && kinds.isNotEmpty(),
            onClick = { if (single != null) onPick(single) else open.value = true },
        )
        ZillitActionMenu(
            expanded = open.value,
            onDismissRequest = { open.value = false },
            entries = kinds.map { kind ->
                ZillitMenuEntry.Action(label = kind.label, icon = kind.icon) { onPick(kind) }
            },
        )
    }
}
