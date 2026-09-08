package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState

/**
 * Documents for signature — the three lists.
 *
 * The web nominally gates this whole area on posting rights, but a view-only
 * crew member has to reach Received for Signature to sign what is sent to
 * them, so entry here is open and only authorship is gated: the sent list's
 * tab, uploading, and deleting.
 */
@Composable
internal fun DocumentsPage(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val docs = state.documents
    val tabs = SignDocumentTab.entries.filter {
        it != SignDocumentTab.Uploaded || state.viewer.canPost
    }

    DocumentsHeader(state, onEvent)

    ZillitTabStrip(
        tabs = tabs.map { ZillitTab(it.wire, it.label) },
        activeId = docs.tab.wire,
        onSelect = { wire ->
            SignDocumentTab.entries.firstOrNull { it.wire == wire }
                ?.let { onEvent(FormSignatureEvent.SwitchDocumentsTab(it)) }
        },
    )

    ZillitSectionCard(padded = false, modifier = Modifier.fillMaxWidth()) {
        ZillitDataTable(
            rows = docs.rows,
            key = { it.id },
            loading = docs.loading,
            columns = columns(state, onEvent),
            onRowClick = { onEvent(FormSignatureEvent.OpenDocument(it)) },
            emptyTitle = when (docs.tab) {
                SignDocumentTab.Uploaded -> "Nothing sent for signature yet"
                SignDocumentTab.Received -> "Nothing waiting for your signature"
                SignDocumentTab.Finalized -> "No fully signed documents yet"
            },
            emptyMessage = when (docs.tab) {
                SignDocumentTab.Uploaded ->
                    "Upload a PDF, place each signer’s boxes, and send it out."
                SignDocumentTab.Received ->
                    "Documents sent to you appear here, with their signature boxes placed."
                SignDocumentTab.Finalized ->
                    "Once every signer has signed, the finished copy lands here."
            },
        )
    }
}

@Composable
private fun DocumentsHeader(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    ZillitPageHeader(
        eyebrow = "Documents & Signature",
        title = "Documents for signature",
        description = "Send a document to be signed, sign what reaches you, and " +
            "collect the finished copies.",
        actions = {
            ZillitButton(
                text = "Upload & send",
                onClick = { onEvent(FormSignatureEvent.StartSend) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
            )
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(FormSignatureEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = state.documents.loading,
            )
        },
    )
}

private fun columns(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
): List<TableColumn<SignDocument>> = listOf(
    textColumn(header = "Document", width = ColumnWidth.Weight(2f)) { it.name },
    textColumn(header = "Uploaded by", muted = true) { it.uploaderName() },
    TableColumn(header = "Signers", width = ColumnWidth.Weight(1f)) { document ->
        SignersCell(document)
    },
    TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_WIDTH.dp)) { document ->
        RowActions(state, document, onEvent)
    },
)

@Composable
private fun SignersCell(document: SignDocument) {
    val signed = document.signers.count { it.signed }
    val total = document.signers.size
    ZillitStatusPill(
        label = when {
            document.finalized -> "Fully signed"
            total == 0 -> "No signers"
            else -> "$signed of $total signed"
        },
        tone = if (document.finalized) StatusTone.Done else StatusTone.Pending,
    )
}

@Composable
private fun RowActions(
    state: FormSignatureUiState,
    document: SignDocument,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        // Only what this person sent has signers of theirs to change.
        if (state.documents.tab == SignDocumentTab.Uploaded) {
            // Adding a name to a document already out for signature — the one
            // thing a sender routinely needs and had to leave the app for.
            if (!document.finalized) {
                ZillitIconButton(
                    icon = ZillitIcons.UserPlus,
                    contentDescription = "Change signers",
                    onClick = { onEvent(FormSignatureEvent.EditSigners(document)) },
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = { onEvent(FormSignatureEvent.DeleteDocument(document.id)) },
            )
        }
    }
}

private const val ACTIONS_WIDTH = 64
