// Small paddings; the page is one composable, as the web's is one component.
@file:Suppress("LongMethod", "MagicNumber")

package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.components.BadgedSegmented
import com.zillit.desktop.feature.formsignature.ui.components.InfoBand
import com.zillit.desktop.feature.formsignature.ui.components.PersonChip
import com.zillit.desktop.feature.formsignature.ui.components.formDateTime

/**
 * Documents for Signature — the web's `DocumentsForSignature`: search and
 * Upload, the three segments with their badges, the two notes on the send
 * tab, and the listing with View and Delete.
 */
@Composable
internal fun DocumentsForSignaturePage(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val docs = state.documents
    val colors = ZillitTheme.colors

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = docs.search,
                onValueChange = { onEvent(FormSignatureEvent.SearchDocuments(it)) },
                placeholder = str(S.search),
                modifier = Modifier.width(SEARCH_WIDTH.dp),
            )
            Spacer(Modifier.weight(1f))
            if (docs.tab == SignDocumentTab.Uploaded) {
                ZillitButton(
                    text = str(S.txt_document_add),
                    onClick = { onEvent(FormSignatureEvent.StartSend) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg)) {
            ZillitSectionCard(padded = false, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    BadgedSegmented(
                        options = SignDocumentTab.entries.map { tab ->
                            ZillitTab(tab.wire, tab.label, count = state.unread.tab(tab))
                        },
                        activeId = docs.tab.wire,
                        onSelect = { wire ->
                            SignDocumentTab.entries.firstOrNull { it.wire == wire }
                                ?.takeIf { it != docs.tab }
                                ?.let { onEvent(FormSignatureEvent.SwitchDocumentsTab(it)) }
                        },
                    )
                    // ZL-17482: the notes belong to the send tab only.
                    if (docs.tab == SignDocumentTab.Uploaded) {
                        InfoBand {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                NoteLine(
                                    str(S.desktop_note_1),
                                    str(S.desktop_fs_note_1_body),
                                )
                                NoteLine(str(S.desktop_note_2), str(S.desktop_fs_note_2_body))
                            }
                        }
                    }
                }
                ZillitDataTable(
                    rows = docs.visible,
                    key = { it.id },
                    loading = docs.loading,
                    columns = columns(docs.tab, onEvent),
                    onRowClick = { onEvent(FormSignatureEvent.OpenDocument(it)) },
                    emptyTitle = when {
                        docs.search.isNotBlank() -> str(S.desktop_fs_no_documents_match)
                        docs.tab == SignDocumentTab.Uploaded -> str(S.desktop_fs_nothing_sent_yet)
                        docs.tab == SignDocumentTab.Received -> str(S.desktop_fs_nothing_waiting)
                        else -> str(S.desktop_fs_no_fully_signed_yet)
                    },
                    emptyMessage = when {
                        docs.search.isNotBlank() -> null
                        docs.tab == SignDocumentTab.Uploaded ->
                            str(S.desktop_fs_upload_pdf_hint)
                        docs.tab == SignDocumentTab.Received -> str(S.desktop_fs_received_hint)
                        else -> str(S.desktop_fs_finalized_hint)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun NoteLine(lead: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(lead, style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
        ZillitText(text, style = ZillitTheme.typography.bodySmall)
    }
}

private fun columns(
    tab: SignDocumentTab,
    onEvent: (FormSignatureEvent) -> Unit,
): List<TableColumn<SignDocument>> = listOf(
    TableColumn(header = str(S.name), width = ColumnWidth.Weight(2f)) { document ->
        ZillitText(
            text = document.name.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    },
    TableColumn(header = str(S.txt_uploaded_by), width = ColumnWidth.Weight(1.2f)) { document ->
        PersonChip(name = document.uploaderName(), userId = document.uploadedBy)
    },
    textColumn(header = str(S.desktop_uploaded_on), width = ColumnWidth.Fixed(DATE_WIDTH.dp), muted = true) {
        formDateTime(it.createdOn)
    },
    TableColumn(header = str(S.signed), width = ColumnWidth.Fixed(STATUS_WIDTH.dp)) { document ->
        SignersCell(document)
    },
    TableColumn(header = str(S.txt_action), width = ColumnWidth.Fixed(ACTIONS_WIDTH.dp)) { document ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitButton(
                text = str(S.view),
                onClick = { onEvent(FormSignatureEvent.OpenDocument(document)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            if (tab == SignDocumentTab.Uploaded) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.delete),
                    tint = ZillitTheme.colors.danger,
                    onClick = { onEvent(FormSignatureEvent.AskDeleteDocument(document.id)) },
                )
            }
        }
    },
)

/** Not a web column, but the one fact every sender asks — kept small. */
@Composable
private fun SignersCell(document: SignDocument) {
    val signed = document.signers.count { it.signed }
    val total = document.signers.size
    ZillitStatusPill(
        label = when {
            document.finalized -> str(S.desktop_fs_fully_signed)
            total == 0 -> "—"
            else -> str(S.docusign_field_of, signed, total)
        },
        tone = if (document.finalized) StatusTone.Done else StatusTone.Pending,
        dot = true,
    )
}

private const val SEARCH_WIDTH = 400
private const val DATE_WIDTH = 190
private const val STATUS_WIDTH = 120
private const val ACTIONS_WIDTH = 130
