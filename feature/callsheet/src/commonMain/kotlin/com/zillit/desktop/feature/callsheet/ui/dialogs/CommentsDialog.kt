// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.dialogs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.ui.editor.ReadOnlySheet
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.MenuEntry
import com.zillit.desktop.feature.callsheet.ui.components.MenuTone
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetModal
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * One sheet's comment thread beside its read-only preview — `CommentsModal.jsx`'s
 * review layout: the sheet on the left, chat bubbles on the right (mine on the
 * right), edit and delete on my own, and a composer where Enter sends. Delete
 * asks inline first; the web deleted on the menu click.
 */
@Composable
internal fun CommentsDialog(state: SheetUiState, dialog: SheetDialog.Comments, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    SheetModal(
        title = "Comments - ${dialog.sheetName.ifBlank { "Call Sheet" }}",
        onClose = { onEvent(DialogEvent.Dismiss) },
        modifier = Modifier.fillMaxHeight(REVIEW_HEIGHT),
        width = 1380.dp,
        maxHeight = 4000.dp,
        scrollable = false,
        onEscape = {
            when {
                dialog.confirmDeleteId != null -> onEvent(DialogEvent.CancelDeleteComment)
                dialog.editingId != null -> onEvent(DialogEvent.CancelCommentEdit)
                else -> onEvent(DialogEvent.Dismiss)
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PreviewPane(state, dialog, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(THREAD_WIDTH.dp).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LaunchedEffect(dialog.comments.size, dialog.loading) {
                    if (dialog.comments.isNotEmpty()) listState.animateScrollToItem(dialog.comments.lastIndex)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        dialog.loading && dialog.comments.isEmpty() -> ThreadNote(loading = true)
                        dialog.comments.isEmpty() -> ThreadNote(loading = false)
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(dialog.comments, key = { it.id }) { comment ->
                                CommentItem(state, dialog, comment, onEvent)
                            }
                        }
                    }
                }
                if (!dialog.readOnly) {
                    Composer(dialog, onEvent, Modifier.padding(top = 12.dp))
                } else {
                    Text(
                        "Comments are closed on a call sheet approved for publishing.",
                        style = sheetText(11.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** The sheet as it was sent, read-only, on the preview ground. */
@Composable
private fun PreviewPane(state: SheetUiState, dialog: SheetDialog.Comments, modifier: Modifier) {
    val colors = SheetTheme.colors
    val scroll = rememberScrollState()
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.previewBg)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        val preview = dialog.preview
        when {
            preview != null -> Box(
                Modifier.fillMaxSize().zillitVerticalScroll(scroll).padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ReadOnlySheet(preview, state.members, Modifier.widthIn(max = 1120.dp))
            }
            dialog.previewFailed -> Text(
                "Couldn't load the preview.",
                style = sheetText(14.sp),
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Text(
                "Loading preview…",
                style = sheetText(14.sp),
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

private const val REVIEW_HEIGHT = 0.92f
private const val THREAD_WIDTH = 400


@Composable
private fun ThreadNote(loading: Boolean) {
    val colors = SheetTheme.colors
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            Text(
                "Loading...",
                style = sheetText(13.sp),
                color = colors.textMeta,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.accentLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SheetIcons.Comment,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                "No comments yet.",
                style = sheetText(13.sp, FontWeight.Medium),
                color = colors.textMeta,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CommentItem(
    state: SheetUiState,
    dialog: SheetDialog.Comments,
    comment: SheetComment,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val isMe = state.me.isNotBlank() && comment.authorId == state.me
    val member = state.member(comment.authorId)
    val name = member?.fullName?.ifBlank { null } ?: comment.authorName.ifBlank { "Unknown" }
    val role = member?.designation?.ifBlank { null } ?: comment.authorRole
    val edited = comment.id in dialog.editedIds ||
        (comment.updatedOn != null && comment.createdOn != null && comment.updatedOn != comment.createdOn)
    val deleting = dialog.deletingId == comment.id
    val (rowSource, rowHovered) = rememberHover()
    val fade by animateFloatAsState(if (deleting) 0.5f else 1f, tween(150))
    Column(
        Modifier.fillMaxWidth().alpha(fade).hoverable(rowSource),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
    ) {
        AuthorLine(comment.authorId, name, role, isMe)
        Row(
            Modifier.padding(start = if (isMe) 0.dp else 36.dp, end = if (isMe) 36.dp else 0.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val busyHere = dialog.editingId == comment.id || dialog.confirmDeleteId == comment.id
            val canAct = isMe && !busyHere && !dialog.readOnly
            if (isMe) CommentActions(
                visible = canAct,
                rowHovered = rowHovered,
                deleting = deleting,
                comment = comment,
                onEvent = onEvent,
            )
            Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                Bubble(dialog, comment, isMe, onEvent)
                Row(
                    Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(formatDateTime(comment.createdOn), style = sheetText(10.sp), color = colors.textMuted)
                    if (edited) {
                        Text(
                            "(edited)",
                            style = sheetText(10.sp).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(userId: String, name: String, role: String, isMe: Boolean) {
    val colors = SheetTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isMe) Face(userId, name, 28.dp)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                name,
                style = sheetText(13.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (role.isNotBlank()) {
                Text(
                    "(${role.localised()})",
                    style = sheetText(11.sp),
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isMe) Face(userId, name, 28.dp)
    }
}

@Composable
private fun Bubble(
    dialog: SheetDialog.Comments,
    comment: SheetComment,
    isMe: Boolean,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    val ink = if (colors.isDark) colors.textPrimary else Color(0xFF344054)
    val bg = if (isMe) (if (colors.isDark) Color(0x0FFC9404) else Color(0xFFFFF7ED)) else colors.sunken
    val border = if (isMe) colors.accent.copy(alpha = 0.3f) else colors.border
    Column(
        Modifier
            .widthIn(min = 180.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .animateContentSize(tween(150))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when {
            dialog.editingId == comment.id -> EditBox(dialog, onEvent)
            dialog.confirmDeleteId == comment.id -> {
                Text(comment.text, style = sheetText(13.sp, lineHeight = 21.sp), color = ink.copy(alpha = 0.6f))
                Text(
                    "Delete this comment?",
                    style = sheetText(12.sp, FontWeight.SemiBold),
                    color = colors.red,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetButton(
                        "Cancel",
                        { onEvent(DialogEvent.CancelDeleteComment) },
                        kind = ButtonKind.Outline,
                        height = 26.dp,
                        fontSize = 11.sp,
                        horizontalPadding = 10.dp,
                    )
                    SheetButton(
                        "Delete",
                        { onEvent(DialogEvent.ConfirmDeleteComment) },
                        kind = ButtonKind.Danger,
                        height = 26.dp,
                        fontSize = 11.sp,
                        horizontalPadding = 10.dp,
                    )
                }
            }
            else -> Text(comment.text, style = sheetText(13.sp, lineHeight = 21.sp), color = ink)
        }
    }
}

@Composable
private fun EditBox(dialog: SheetDialog.Comments, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val lines = (dialog.editText.count { it == '\n' } + 2).coerceIn(2, 6)
    val canSave = dialog.editText.isNotBlank() && !dialog.savingEdit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = dialog.editText,
            onValueChange = { onEvent(DialogEvent.EditCommentText(it)) },
            textStyle = sheetText(13.sp, lineHeight = 21.sp).copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            minLines = lines,
            maxLines = 6,
            modifier = Modifier
                .widthIn(min = 260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (colors.isDark) Color(0x0FFFFFFF) else Color.White)
                .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(8.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val shortcut = event.isMetaPressed || event.isCtrlPressed
                    val save = event.type == KeyEventType.KeyDown && event.key == Key.Enter && shortcut
                    if (save && canSave) onEvent(DialogEvent.SaveCommentEdit)
                    save
                },
        )
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(
                "Cancel",
                { onEvent(DialogEvent.CancelCommentEdit) },
                kind = ButtonKind.Ghost,
                icon = ZillitIcons.Close,
                enabled = !dialog.savingEdit,
                height = 26.dp,
                fontSize = 11.sp,
                horizontalPadding = 8.dp,
            )
            SheetButton(
                if (dialog.savingEdit) "Saving..." else "Save",
                { onEvent(DialogEvent.SaveCommentEdit) },
                kind = ButtonKind.Accent,
                icon = ZillitIcons.Check,
                enabled = canSave,
                height = 26.dp,
                fontSize = 11.sp,
                horizontalPadding = 10.dp,
            )
        }
    }
}

/** The chevron beside my own comment — always composed, revealed on hover so the press lands. */
@Composable
private fun CommentActions(
    visible: Boolean,
    rowHovered: Boolean,
    deleting: Boolean,
    comment: SheetComment,
    onEvent: (SheetEvent) -> Unit,
) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val shown = visible && (rowHovered || hovered || open)
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(120))
    Box {
        Box(
            Modifier
                .size(28.dp)
                .alpha(reveal)
                .clip(CircleShape)
                .background(if (hovered) colors.sunken else Color.Transparent)
                .hoverable(source)
                .plainClick(enabled = visible, source = source) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitIcons.ChevronDown,
                contentDescription = "Comment actions",
                tint = if (hovered) colors.accent else colors.textTertiary,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surface,
            modifier = Modifier.border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.widthIn(min = 150.dp).padding(horizontal = 6.dp)) {
                val entries = listOf(
                    MenuEntry.Action("edit", "Edit", ZillitIcons.Edit, MenuTone.Primary, enabled = !deleting) {
                        onEvent(DialogEvent.StartCommentEdit(comment.id))
                    },
                    MenuEntry.Action("delete", "Delete", ZillitIcons.Trash, MenuTone.Danger, enabled = !deleting) {
                        onEvent(DialogEvent.AskDeleteComment(comment.id))
                    },
                )
                entries.forEachIndexed { index, entry ->
                    if (index > 0) Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .heightIn(min = 1.dp, max = 1.dp)
                            .background(colors.border),
                    )
                    CommentMenuRow(entry) {
                        open = false
                        entry.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentMenuRow(action: MenuEntry.Action, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val danger = action.tone == MenuTone.Danger
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !hovered || !action.enabled -> Color.Transparent
                    danger -> colors.redBg
                    else -> colors.accentLight
                },
            )
            .alpha(if (action.enabled) 1f else 0.5f)
            .hoverable(source)
            .plainClick(enabled = action.enabled, source = source, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            action.icon,
            contentDescription = null,
            tint = if (danger) colors.red else colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            action.label,
            style = sheetText(13.sp, FontWeight.Medium),
            color = if (danger) colors.red else colors.textPrimary,
        )
    }
}

@Composable
private fun Composer(dialog: SheetDialog.Comments, onEvent: (SheetEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = SheetTheme.colors
    val canSend = dialog.draft.isNotBlank() && !dialog.sending
    val (fieldSource, _) = rememberHover()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (colors.isDark) Color(0x0AFFFFFF) else Color.White)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
            .hoverable(fieldSource)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
            if (dialog.draft.isEmpty()) Text("Type a comment...", style = sheetText(14.sp), color = colors.textMuted)
            BasicTextField(
                value = dialog.draft,
                onValueChange = { onEvent(DialogEvent.EditCommentDraft(it)) },
                textStyle = sheetText(14.sp).copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    val send = event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed
                    if (send && canSend) onEvent(DialogEvent.SendComment)
                    send
                },
            )
        }
        val (sendSource, sendHovered) = rememberHover()
        Box(
            Modifier
                .size(40.dp)
                .alpha(if (canSend || dialog.sending) 1f else 0.5f)
                .clip(CircleShape)
                .background(if (sendHovered && canSend) colors.accentHover else colors.accent)
                .hoverable(sendSource)
                .plainClick(enabled = canSend, source = sendSource) { onEvent(DialogEvent.SendComment) },
            contentAlignment = Alignment.Center,
        ) {
            if (dialog.sending) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(ZillitIcons.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
