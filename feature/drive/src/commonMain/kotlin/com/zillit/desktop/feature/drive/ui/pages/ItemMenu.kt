package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntries
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import kotlin.math.roundToInt
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The pointer-anchored row menu — `getContextMenuItems`, opened by a
 * right-click, the ⋮ button, or a click on an editable document.
 *
 * A popup placed by [PointerMenuPosition] at the press itself, flipping
 * above or to the left of the pointer only when the listing has no room
 * beneath or beside it. Not a `DropdownMenu`: that one judges room against
 * a window size the popup layer reports too small for this screen, and so
 * centred every menu on its anchor instead of dropping from the pointer.
 */
@Composable
internal fun ItemMenuHost(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val menu = state.menu ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(MENU_RADIUS)
    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Popup(
            popupPositionProvider = remember(menu, origin) {
                PointerMenuPosition(Offset(menu.x - origin.x, menu.y - origin.y))
            },
            onDismissRequest = { onEvent(DriveEvent.CloseMenu) },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    // The popup offers the whole window; the rows would fill it.
                    .width(IntrinsicSize.Max)
                    .shadow(MENU_SHADOW, shape)
                    .clip(shape)
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, shape)
                    .padding(vertical = ZillitTheme.spacing.xs),
            ) {
                ZillitMenuEntries(
                    entries = menuEntries(menu.item, state, onEvent),
                    onDismiss = { onEvent(DriveEvent.CloseMenu) },
                )
            }
        }
    }
}

/**
 * Drops the menu from [pointer] (relative to the anchor, which on desktop is
 * the whole window) and keeps it on screen: below and to the right of the
 * pointer when there is room, above or to the left when there is not, and
 * pushed against the far edge when neither fits.
 */
private class PointerMenuPosition(private val pointer: Offset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val size = popupContentSize
        return IntOffset(
            x = place(anchorBounds.left + pointer.x.roundToInt(), size.width, anchorBounds.left, anchorBounds.right),
            y = place(anchorBounds.top + pointer.y.roundToInt(), size.height, anchorBounds.top, anchorBounds.bottom),
        )
    }

    private fun place(at: Int, extent: Int, min: Int, max: Int): Int = when {
        at + extent <= max -> at
        at - extent >= min -> at - extent
        else -> (max - extent).coerceAtLeast(min)
    }
}

/**
 * The entries, gated exactly as the web gates them: a view-only share
 * (view without edit, download or delete) offers only Open/Preview and
 * Favourite; everything else appears with the right behind it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One gate per entry, as the web's list is written.
internal fun menuEntries(item: DriveItem, state: DriveUiState, onEvent: (DriveEvent) -> Unit): List<ZillitMenuEntry> {
    val viewer = state.viewer
    val viewOnly = viewer.isViewOnly(item)
    val canEdit = viewer.may(DriveAction.Edit, item)
    val canDownload = viewer.may(DriveAction.Download, item)
    val canDelete = viewer.may(DriveAction.Delete, item)
    val canShare = viewer.may(DriveAction.Share, item)
    val entries = mutableListOf<ZillitMenuEntry>()

    if (item.isFolder) {
        val openFolder = str(S.desktop_drive_open_folder)
        entries += ZillitMenuEntry.Action(openFolder, ZillitIcons.Folder, ZillitMenuTone.Primary) {
            onEvent(DriveEvent.OpenFolder(item.id))
        }
    } else {
        entries += ZillitMenuEntry.Action(str(S.preview), ZillitIcons.Eye, ZillitMenuTone.Primary) {
            onEvent(DriveEvent.Preview(item))
        }
        if (canDownload && !viewOnly) {
            entries += ZillitMenuEntry.Action(str(S.download), ZillitIcons.Download, ZillitMenuTone.Info) {
                onEvent(DriveEvent.Download(item))
            }
        }
        if (item.isEditableDocument && !viewOnly) {
            entries += ZillitMenuEntry.Action(
                label = if (canEdit) str(S.desktop_drive_open_in_editor) else str(S.desktop_drive_view_in_editor),
                icon = ZillitIcons.Edit,
                tone = ZillitMenuTone.Info,
            ) { onEvent(DriveEvent.OpenInEditor(item, canEdit)) }
        }
    }

    if (!viewOnly) entries += ZillitMenuEntry.Divider

    if (canEdit && !viewOnly) {
        entries += ZillitMenuEntry.Action(str(S.drive_edit_info), ZillitIcons.Edit) {
            onEvent(DriveEvent.OpenEdit(item))
        }
    }
    if (canShare && !viewOnly) {
        entries += ZillitMenuEntry.Action(
            label = if (item.isFolder) str(S.drive_btn_manage_access) else str(S.share),
            icon = ZillitIcons.Users,
        ) { onEvent(DriveEvent.OpenShare(item)) }
    }
    if (!item.isFolder && canEdit) {
        entries += ZillitMenuEntry.Action(str(S.drive_menu_copy_link), ZillitIcons.Link) {
            onEvent(DriveEvent.CopyLink(item))
        }
    }
    if (canEdit && !viewOnly) {
        entries += ZillitMenuEntry.Action(str(S.drive_move_to_ellipsis), ZillitIcons.ArrowRight) {
            onEvent(DriveEvent.OpenMoveTo(listOf(item)))
        }
    }
    if (item.isFolder && !viewOnly && state.canCreateHere) {
        entries += ZillitMenuEntry.Action(str(S.drive_request_files_title), ZillitIcons.Inbox) {
            onEvent(DriveEvent.OpenFileRequests(item))
        }
    }

    val starred = state.isFavourite(item)
    entries += ZillitMenuEntry.Action(
        label = if (starred) str(S.desktop_remove_from_favourites) else str(S.desktop_add_to_favourites),
        icon = if (starred) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
        tone = if (starred) ZillitMenuTone.Approve else ZillitMenuTone.Neutral,
    ) { onEvent(DriveEvent.ToggleFavourite(item.ref)) }

    if (!viewOnly) {
        entries += ZillitMenuEntry.Action(str(S.info), ZillitIcons.Info) { onEvent(DriveEvent.ShowDetails(item)) }
    }

    if (canDelete && !viewOnly) {
        entries += ZillitMenuEntry.Divider
        entries += ZillitMenuEntry.Action(str(S.delete), ZillitIcons.Trash, ZillitMenuTone.Danger) {
            onEvent(DriveEvent.RequestDelete(listOf(item.ref)))
        }
    }
    return entries
}

private val MENU_RADIUS = 12.dp
private val MENU_SHADOW = 8.dp
