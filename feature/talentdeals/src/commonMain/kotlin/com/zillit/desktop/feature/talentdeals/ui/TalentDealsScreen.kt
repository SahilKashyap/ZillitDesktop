package com.zillit.desktop.feature.talentdeals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.ui.pages.ContractFormPage
import com.zillit.desktop.feature.talentdeals.ui.pages.ContractViewPage
import com.zillit.desktop.feature.talentdeals.ui.pages.FileViewerDialog
import com.zillit.desktop.feature.talentdeals.ui.pages.RegisterPage
import com.zillit.desktop.feature.talentdeals.ui.pages.RemoveContractDialog
import com.zillit.desktop.feature.talentdeals.ui.pages.RolePickerDialog

/**
 * Talent Deals, drawn: the face the route names, and the dialogs over it —
 * dialogs are called from the page root, never from inside a scrolling section
 * (a dialog shell inside a scroll column is drawn inline, out of sight).
 */
@Composable
fun TalentDealsScreen(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Reads a stored signature image for Exhibit G's signature cell; null shows the state in words. */
    signatureLoader: suspend (TalentAttachment) -> ByteArray? = { null },
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.noAccess -> ZillitEmptyState(
                title = str(S.desktop_td_no_access),
                icon = ZillitIcons.Lock,
                modifier = Modifier.fillMaxSize(),
            )
            else -> when (state.route) {
                TalentRoute.Register -> Column(Modifier.fillMaxSize()) {
                    TalentHeader(
                        backLabel = str(S.desktop_td_back_to_film_tools),
                        onBack = { onEvent(TalentDealsEvent.Back) },
                        eyebrow = str(S.desktop_td_title),
                    )
                    RegisterPage(state, onEvent, Modifier.weight(1f))
                }
                is TalentRoute.View -> ContractViewPage(state, onEvent, signatureLoader)
                is TalentRoute.New, is TalentRoute.Edit -> ContractFormPage(state, onEvent, signatureLoader)
            }
        }

        RolePickerDialog(open = state.register.rolePickerOpen, onEvent = onEvent)
        RemoveContractDialog(state.register, onEvent)
        FileViewerDialog(state.viewing, canDownload = state.viewer.canDownload, onEvent = onEvent)
    }
}
