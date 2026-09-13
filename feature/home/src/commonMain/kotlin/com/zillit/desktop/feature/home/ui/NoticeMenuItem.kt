package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import kotlin.math.roundToInt

/** One entry in a post's menu — the label, the tile that leads it, and its tone. */
data class NoticeMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val tone: ZillitMenuTone = ZillitMenuTone.Neutral,
    val onClick: () -> Unit,
)

/** The design-system row for a [NoticeMenuItem]. */
fun NoticeMenuItem.asEntry(): ZillitMenuEntry.Action =
    ZillitMenuEntry.Action(label = label, icon = icon, tone = tone, onClick = onClick)

/**
 * Right-click opens a menu over [content], at the pointer; an empty item
 * list opens nothing.
 *
 * Drawn by the app rather than the platform's `ContextMenuArea`: it is the
 * same menu the kebab and a long-press open, and the three ways in should
 * look like one menu — the call sheet's card with its toned tiles — not
 * two. The press is caught on the initial pass and consumed so the card's
 * long-press detector underneath never starts a second timer on it.
 */
@Composable
fun NoticeContextMenu(
    items: () -> List<NoticeMenuItem>,
    content: @Composable () -> Unit,
) {
    var anchor by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    anchor = event.changes.first().position
                }
            }
        },
    ) {
        content()
        anchor?.let { at ->
            val entries = items().map { it.asEntry() }
            if (entries.isEmpty()) {
                anchor = null
                return@let
            }
            // A point-sized anchor at the click, so the popup hangs from
            // the pointer rather than from the card's bottom edge.
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(0.dp)) {
                ZillitActionMenu(
                    expanded = true,
                    onDismissRequest = { anchor = null },
                    entries = entries,
                )
            }
        }
    }
}
