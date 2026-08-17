package com.zillit.desktop.feature.formsignature.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.ui.pages.DetailPage
import com.zillit.desktop.feature.formsignature.ui.pages.DocumentsPage
import com.zillit.desktop.feature.formsignature.ui.pages.HistoryDialog
import com.zillit.desktop.feature.formsignature.ui.pages.SendDialog
import com.zillit.desktop.feature.formsignature.ui.pages.SignaturesPage
import com.zillit.desktop.feature.formsignature.ui.pages.StandardFormsPage
import com.zillit.desktop.feature.formsignature.ui.pages.UploadFormDialog

/**
 * Documents & Signature — the web's tile hub, as a screen with a back rail.
 *
 * An open document takes the whole surface: reading and signing a contract
 * is the tool's centre of gravity, and a preview squeezed beside a table
 * serves neither.
 */
@Composable
fun FormSignatureScreen(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        when {
            state.viewer.isBlocked -> {
                ZillitPageHeader(eyebrow = "Film Tools", title = "Documents & Signature")
                ZillitNotice(
                    text = "You don’t have access to Documents & Signature on this " +
                        "production. Access is granted per tool, by the production’s admin.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                )
            }

            state.detail != null -> DetailPage(state.detail, onEvent)

            state.area == FormSignatureArea.Hub -> HubPage(onEvent)

            else -> AreaPage(state, onEvent)
        }
    }

    SendDialog(state, onEvent)
    UploadFormDialog(state, onEvent)
    HistoryDialog(state, onEvent)
}

@Composable
private fun HubPage(onEvent: (FormSignatureEvent) -> Unit) {
    ZillitPageHeader(
        eyebrow = "Film Tools",
        title = "Documents & Signature",
        description = "Standard forms and contracts, documents sent for signature, " +
            "and your signature block.",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        HubTile(
            title = FormSignatureArea.StandardForms.label,
            text = "The production’s shared library — download, keep your own list, sign.",
            onOpen = { onEvent(FormSignatureEvent.SwitchArea(FormSignatureArea.StandardForms)) },
        )
        HubTile(
            title = FormSignatureArea.Documents.label,
            text = "Send a document out for signatures, and sign what is sent to you.",
            onOpen = { onEvent(FormSignatureEvent.SwitchArea(FormSignatureArea.Documents)) },
        )
        HubTile(
            title = FormSignatureArea.Signatures.label,
            text = "Draw the signature and initials that sign for you here.",
            onOpen = { onEvent(FormSignatureEvent.SwitchArea(FormSignatureArea.Signatures)) },
        )
    }
}

@Composable
private fun HubTile(title: String, text: String, onOpen: () -> Unit) {
    ZillitSectionCard(
        modifier = Modifier.width(TILE_WIDTH.dp).clickable(onClick = onOpen),
        title = title,
        icon = ZillitIcons.File,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun AreaPage(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = "All tools",
            onClick = { onEvent(FormSignatureEvent.SwitchArea(FormSignatureArea.Hub)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
    }
    when (state.area) {
        FormSignatureArea.StandardForms -> StandardFormsPage(state, onEvent)
        FormSignatureArea.Documents -> DocumentsPage(state, onEvent)
        FormSignatureArea.Signatures -> SignaturesPage(state, onEvent)
        FormSignatureArea.Hub -> Unit
    }
}

private const val TILE_WIDTH = 300
