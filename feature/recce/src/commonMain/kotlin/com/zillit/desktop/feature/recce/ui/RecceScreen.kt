package com.zillit.desktop.feature.recce.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * scrolls to its first section, where every required field lives.
 */
@Composable
fun RecceScreen(state: RecceUiState, onEvent: (RecceEvent) -> Unit, scrollToTop: Int = 0) {
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
            is ReccePage.Form -> RecceFormPage(state, onEvent, editing = page.id != null, scroll = formScroll)
        }
        DeleteDialog(state, onEvent)
        LeaveDialog(state, onEvent)
        ReccePdfOverlay(state.pdf, onEvent)
    }
}

/** "Delete recce?" — a two-step delete, the web's confirm modal. */
@Composable
private fun DeleteDialog(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val target = state.deleteTarget ?: return
    ZillitDialogShell(
        title = "Delete recce?",
        onDismiss = { onEvent(RecceEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(RecceEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
                enabled = !state.deleting,
            )
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(RecceEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.deleting,
            )
        },
    ) {
        ZillitText(
            text = buildAnnotatedString {
                append("Delete ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(target.label) }
                append("? This can’t be undone from here.")
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

/** "Save changes before leaving?" — keeps unsaved work from being lost on back / Cancel. */
@Composable
private fun LeaveDialog(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    if (!state.leavePrompt) return
    ZillitDialogShell(
        title = "Save changes before leaving?",
        onDismiss = { onEvent(RecceEvent.KeepEditing) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Keep editing",
                onClick = { onEvent(RecceEvent.KeepEditing) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Discard",
                onClick = { onEvent(RecceEvent.DiscardChanges) },
                variant = ButtonVariant.Danger,
            )
            ZillitButton(
                text = "Save as Draft",
                onClick = { onEvent(RecceEvent.SaveDraft) },
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        ZillitText(
            text = "You have unsaved changes. Save them as a draft so you don't lose your work, or discard and leave.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}
