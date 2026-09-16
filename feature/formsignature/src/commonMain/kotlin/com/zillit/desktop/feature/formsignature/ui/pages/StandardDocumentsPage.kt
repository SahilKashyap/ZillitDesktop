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
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.StandardTab
import com.zillit.desktop.feature.formsignature.ui.components.PersonChip
import com.zillit.desktop.feature.formsignature.ui.components.formDateTime

/**
 * Standard Documents — the web's `StandardFormsV2`: a search strip with the
 * discussion-room button and Upload, the Documents / My Downloads tabs, and
 * the listing with View, Add to My Downloads, History and Delete.
 */
@Composable
internal fun StandardDocumentsPage(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val standard = state.standard
    val colors = ZillitTheme.colors
    val chatUnit = state.chat.unit

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
                value = standard.search,
                onValueChange = { onEvent(FormSignatureEvent.SearchStandard(it)) },
                placeholder = "Search by id or name",
                modifier = Modifier.width(SEARCH_WIDTH.dp),
            )
            Spacer(Modifier.weight(1f))
            if (chatUnit != null) {
                val answers = chatUnit.answers(state.currentUserId)
                val unread = state.unread.chat(chatUnit.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitButton(
                        text = if (answers) "Chat with Users" else "Chat with Admins",
                        onClick = { onEvent(FormSignatureEvent.OpenChat) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Chat,
                    )
                    if (unread > 0) ZillitBadge(count = unread)
                }
            }
            if (standard.tab == StandardTab.All) {
                ZillitButton(
                    text = "Upload Document",
                    onClick = { onEvent(FormSignatureEvent.StartUploadForm) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg)) {
            ZillitSectionCard(padded = false, modifier = Modifier.fillMaxSize()) {
                ZillitTabStrip(
                    tabs = StandardTab.entries.map {
                        ZillitTab(it.wire, it.label, count = if (it == StandardTab.All) state.unread.allFormsTab else 0)
                    },
                    activeId = standard.tab.wire,
                    onSelect = { wire ->
                        StandardTab.entries.firstOrNull { it.wire == wire }
                            ?.takeIf { it != standard.tab && !standard.loading }
                            ?.let { onEvent(FormSignatureEvent.SwitchStandardTab(it)) }
                    },
                )
                ZillitDataTable(
                    rows = standard.visible,
                    key = { it.id },
                    loading = standard.loading,
                    columns = columns(state, onEvent),
                    onRowClick = { onEvent(FormSignatureEvent.OpenStandardForm(it)) },
                    emptyTitle = if (standard.search.isNotBlank()) {
                        "No documents match"
                    } else if (standard.tab == StandardTab.All) {
                        "No standard documents yet"
                    } else {
                        "Nothing in My Downloads yet"
                    },
                    emptyMessage = when {
                        standard.search.isNotBlank() -> null
                        standard.tab == StandardTab.All ->
                            "Documents uploaded here are shared with the whole production."
                        else -> "Use “Add to My Downloads” on the Documents tab to keep a copy you can sign."
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun columns(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
): List<TableColumn<StandardForm>> = listOf(
    textColumn(header = "ID", width = ColumnWidth.Fixed(ID_WIDTH.dp)) { it.serialNo.ifBlank { "—" } },
    TableColumn(header = "Name", width = ColumnWidth.Weight(2f)) { form ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitText(
                text = form.name.ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.standard.tab == StandardTab.All) {
                val unread = state.unread.form(form.id, form.documentId)
                if (unread > 0) ZillitBadge(count = unread)
            }
        }
    },
    textColumn(header = "Type", width = ColumnWidth.Fixed(TYPE_WIDTH.dp)) { it.type.label },
    textColumn(header = "Uploaded On", width = ColumnWidth.Fixed(DATE_WIDTH.dp), muted = true) {
        formDateTime(it.createdOn)
    },
    TableColumn(header = "Uploaded By", width = ColumnWidth.Weight(1.2f)) { form ->
        PersonChip(name = form.uploaderName)
    },
    TableColumn(header = "Action", width = ColumnWidth.Fixed(ACTIONS_WIDTH.dp)) { form ->
        RowActions(state, form, onEvent)
    },
)

@Composable
private fun RowActions(state: FormSignatureUiState, form: StandardForm, onEvent: (FormSignatureEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitButton(
            text = "View",
            onClick = { onEvent(FormSignatureEvent.OpenStandardForm(form)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        if (state.standard.tab == StandardTab.All) {
            ZillitButton(
                text = "Add to My Downloads",
                onClick = { onEvent(FormSignatureEvent.SelfAssign(form.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        ZillitTooltip("Check History") {
            ZillitIconButton(
                icon = ZillitIcons.Clock,
                contentDescription = "Check History",
                onClick = { onEvent(FormSignatureEvent.ShowHistory(form)) },
            )
        }
        // The web shows Delete on the shared list for posters; here it shows for
        // everyone and the handler refuses (and offers to ask an admin).
        if (state.standard.tab == StandardTab.All) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                tint = ZillitTheme.colors.danger,
                onClick = { onEvent(FormSignatureEvent.AskDeleteStandardForm(form.id)) },
            )
        }
    }
}

private const val SEARCH_WIDTH = 400
private const val ID_WIDTH = 64
private const val TYPE_WIDTH = 150
private const val DATE_WIDTH = 190
private const val ACTIONS_WIDTH = 330
