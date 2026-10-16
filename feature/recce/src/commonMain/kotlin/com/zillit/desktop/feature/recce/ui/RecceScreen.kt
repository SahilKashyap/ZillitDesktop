package com.zillit.desktop.feature.recce.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.recce.ui.pages.RecceDetailPage
import com.zillit.desktop.feature.recce.ui.pages.RecceFormPage
import com.zillit.desktop.feature.recce.ui.pages.RecceIndexPage
import com.zillit.desktop.feature.recce.ui.pages.ReccePdfOverlay

/**
 * The recce tool: the list, one recce, or the form — the web's three routes
 * in one window — with the delete confirm, the leave-unsaved prompt and the
 * report viewer floated over whichever page is up.
 *
 * [scrollToTop] is a counter the host bumps on a refused publish; the form
 * scrolls to its first section, where the required fields live.
 * [scrollToPersonnel] is the same for a publish refused only for want of a
 * crew member, which is in section 2.
 */
@Composable
fun RecceScreen(
    state: RecceUiState,
    onEvent: (RecceEvent) -> Unit,
    scrollToTop: Int = 0,
    scrollToPersonnel: Int = 0,
) {
    val formScroll = rememberScrollState()
    LaunchedEffect(scrollToTop) { if (scrollToTop > 0) formScroll.animateScrollTo(0) }
    // A fresh form starts at the top — the offset must not carry over from the last one.
    LaunchedEffect(state.route) { if (state.route is ReccePage.Form) formScroll.scrollTo(0) }
    val dark = ZillitTheme.colors.isDark
    LaunchedEffect(dark) { onEvent(RecceEvent.Theme(dark)) }

    Box(Modifier.fillMaxSize()) {
        when (val page = state.route) {
            ReccePage.Index -> RecceIndexPage(state, onEvent)
            is ReccePage.Detail -> RecceDetailPage(state, onEvent)
            is ReccePage.Form -> RecceFormPage(
                state = state,
                onEvent = onEvent,
                editing = page.id != null,
                scroll = formScroll,
                scrollToPersonnel = scrollToPersonnel,
            )
        }
        DeleteDialog(state, onEvent)
        DistributeDialogs(state, onEvent)
        LeaveDialog(state, onEvent)
        ReccePdfOverlay(state.pdf, onEvent)
    }
}

/** "Delete recce?" — a two-step delete, the web's confirm modal. */
@Composable
private fun DeleteDialog(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val target = state.deleteTarget ?: return
    ZillitDialogShell(
        title = str(S.recce_delete_title),
        onDismiss = { onEvent(RecceEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.recce_cancel),
                onClick = { onEvent(RecceEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
                enabled = !state.deleting,
            )
            ZillitButton(
                text = str(S.recce_delete_confirm),
                onClick = { onEvent(RecceEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.deleting,
            )
        },
    ) {
        ZillitText(
            text = buildAnnotatedString {
                val message = str(S.desktop_recce_delete_message, target.label)
                val at = message.indexOf(target.label)
                if (at < 0) {
                    append(message)
                } else {
                    append(message.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(target.label) }
                    append(message.substring(at + target.label.length))
                }
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/**
 * "Send to Document Distribution": the confirm that names the file, then the
 * notice that it landed — the two popups every tool's hand-off shows
 * (`dd_publish_confirm_title` / `desktop_added_to_document_distribution`).
 *
 * The target is held after it clears so the closing animation still has a
 * name to draw, as the other tools' dialogs do.
 */
@Composable
private fun DistributeDialogs(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val held = remember { arrayOfNulls<DistributeTarget>(1) }
    state.distribute?.let { held[0] = it }
    val shown = state.distribute ?: held[0]
    val name = shown?.fileName.orEmpty()
    val target = state.distribute
    val sending = target?.sending == true

    ZillitDialogShell(
        title = str(S.dd_publish_confirm_title),
        icon = ZillitIcons.Send,
        onDismiss = { if (!sending) onEvent(RecceEvent.CancelSendToDocDist) },
        visible = target != null && !target.done,
        actions = {
            ZillitButton(
                text = str(S.recce_cancel),
                onClick = { onEvent(RecceEvent.CancelSendToDocDist) },
                variant = ButtonVariant.Tertiary,
                enabled = !sending,
            )
            ZillitButton(
                text = str(S.publish),
                onClick = { onEvent(RecceEvent.ConfirmSendToDocDist) },
                loading = sending,
                enabled = !sending,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_cl_publish_to_library_question, name),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }

    ZillitDialogShell(
        title = str(S.cs_published),
        icon = ZillitIcons.Check,
        onDismiss = { onEvent(RecceEvent.CancelSendToDocDist) },
        visible = target?.done == true,
        actions = {
            ZillitButton(text = str(S.ok), onClick = { onEvent(RecceEvent.CancelSendToDocDist) })
        },
    ) {
        ZillitText(
            text = str(S.desktop_added_to_document_distribution, name),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** "Save changes before leaving?" — keeps unsaved work from being lost on back / Cancel. */
@Composable
private fun LeaveDialog(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    if (!state.leavePrompt) return
    ZillitDialogShell(
        title = str(S.desktop_save_changes_before_leaving),
        onDismiss = { onEvent(RecceEvent.KeepEditing) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.ah_keep_editing),
                onClick = { onEvent(RecceEvent.KeepEditing) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.ah_discard),
                onClick = { onEvent(RecceEvent.DiscardChanges) },
                variant = ButtonVariant.Danger,
            )
            ZillitButton(
                text = str(S.recce_save_draft),
                onClick = { onEvent(RecceEvent.SaveDraft) },
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_unsaved_changes_draft_prompt),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}
