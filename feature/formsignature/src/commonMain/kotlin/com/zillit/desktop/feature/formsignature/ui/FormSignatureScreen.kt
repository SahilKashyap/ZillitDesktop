@file:Suppress("LongMethod") // The shell is one composable: header, body, dialogs.

package com.zillit.desktop.feature.formsignature.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.ui.components.ToolTopBar
import com.zillit.desktop.feature.formsignature.ui.dialogs.ConfirmDialog
import com.zillit.desktop.feature.formsignature.ui.dialogs.HistoryDialog
import com.zillit.desktop.feature.formsignature.ui.dialogs.ReceiverPickerDialog
import com.zillit.desktop.feature.formsignature.ui.dialogs.SendDocumentDialog
import com.zillit.desktop.feature.formsignature.ui.dialogs.SignaturePickerDialog
import com.zillit.desktop.feature.formsignature.ui.dialogs.UploadFormDialog
import com.zillit.desktop.feature.formsignature.ui.pages.ChatPage
import com.zillit.desktop.feature.formsignature.ui.pages.DetailPage
import com.zillit.desktop.feature.formsignature.ui.pages.DocumentsForSignaturePage
import com.zillit.desktop.feature.formsignature.ui.pages.DrawSignaturePage
import com.zillit.desktop.feature.formsignature.ui.pages.SignatureBlockPage
import com.zillit.desktop.feature.formsignature.ui.pages.StandardDocumentsPage
import com.zillit.desktop.feature.formsignature.ui.pages.TilesPage

/**
 * Documents & Signature — the web's `ContractSignatureMain`: one white
 * header with a back caret and the screen's title over a slate body, the
 * screens sliding in and out beneath it, and the dialogs over everything.
 */
@Composable
fun FormSignatureScreen(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
    host: FormSignatureHost = FormSignatureHost.None,
    /** The discussion room's board, lent by the host; null shows a placeholder. */
    chatBoard: (@Composable () -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        ToolTopBar(
            title = state.title,
            onBack = if (state.screen == FormSignScreen.Tiles) null else ({ onEvent(FormSignatureEvent.Back) }),
            trailing = { HeaderActions(state, onEvent) },
        )
        Box(Modifier.fillMaxSize()) {
            when {
                state.viewer.isBlocked -> ZillitNotice(
                    text = "You don’t have access to Documents & Signature on this project. " +
                        "Access is granted per tool, by the project’s admin.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                    modifier = Modifier.padding(ZillitTheme.spacing.xl),
                )
                else -> AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            (fadeIn() + slideInHorizontally { it / SLIDE_FRACTION }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / SLIDE_FRACTION })
                        } else {
                            (fadeIn() + slideInHorizontally { -it / SLIDE_FRACTION }) togetherWith
                                (fadeOut() + slideOutHorizontally { it / SLIDE_FRACTION })
                        }
                    },
                    label = "formSignScreen",
                ) { screen ->
                    Box(Modifier.fillMaxSize()) {
                        when (screen) {
                            FormSignScreen.Tiles -> TilesPage(state, onEvent)
                            FormSignScreen.StandardDocuments -> StandardDocumentsPage(state, onEvent)
                            FormSignScreen.DocumentsForSignature -> DocumentsForSignaturePage(state, onEvent)
                            FormSignScreen.SignatureBlock -> SignatureBlockPage(state, onEvent)
                            FormSignScreen.DrawSignature -> state.draw?.let { DrawSignaturePage(it, onEvent) }
                            FormSignScreen.Detail -> state.detail?.let { DetailPage(it, onEvent) }
                            FormSignScreen.Chat -> ChatPage(state, chatBoard)
                        }
                    }
                }
            }
        }
    }

    UploadFormDialog(state, onEvent)
    SendDocumentDialog(state, onEvent)
    SignaturePickerDialog(state, onEvent)
    HistoryDialog(state, host, onEvent)
    ReceiverPickerDialog(state, onEvent)
    ConfirmDialog(state, onEvent)
}

/** The header's right side: the room's "Select User" for an admin, Refresh on the list screens. */
@Composable
private fun HeaderActions(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val unit = state.chat.unit
    if (state.screen == FormSignScreen.Chat && unit != null && unit.answers(state.currentUserId)) {
        ZillitButton(
            text = state.chat.receiver?.label?.let { name ->
                if (name.length > RECEIVER_NAME_MAX) name.take(RECEIVER_NAME_MAX) + "…" else name
            } ?: "Select User",
            onClick = { onEvent(FormSignatureEvent.PickReceiver) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.User,
        )
    }
    if (state.screen in REFRESHABLE) {
        ZillitButton(
            text = "Refresh",
            onClick = { onEvent(FormSignatureEvent.Refresh) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Reload,
            loading = state.standard.loading || state.documents.loading || state.signatures.loading,
        )
    }
}

private val REFRESHABLE = setOf(
    FormSignScreen.StandardDocuments,
    FormSignScreen.DocumentsForSignature,
    FormSignScreen.SignatureBlock,
)
private const val RECEIVER_NAME_MAX = 20
private const val SLIDE_FRACTION = 20
