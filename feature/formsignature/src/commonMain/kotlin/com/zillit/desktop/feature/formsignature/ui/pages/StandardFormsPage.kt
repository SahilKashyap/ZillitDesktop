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
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.StandardTab

/**
 * The standard forms & contracts library — the web's two tabs.
 *
 * "Add to your documents" is open to every viewer (that is the tab's whole
 * point: take a form, sign your copy); upload and delete are authorship and
 * stay posting-only.
 */
@Composable
internal fun StandardFormsPage(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    val standard = state.standard

    ZillitPageHeader(
        eyebrow = "Documents & Signature",
        title = "Standard forms & contracts",
        description = "The production’s shared library. Add a form to your documents " +
            "to sign your own copy.",
        actions = {
            ZillitButton(
                text = "Upload document",
                onClick = { onEvent(FormSignatureEvent.StartUploadForm) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
            )
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(FormSignatureEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = standard.loading,
            )
        },
    )

    ZillitTabStrip(
        tabs = StandardTab.entries.map { ZillitTab(it.name, it.label) },
        activeId = standard.tab.name,
        onSelect = { id ->
            StandardTab.entries.firstOrNull { it.name == id }
                ?.let { onEvent(FormSignatureEvent.SwitchStandardTab(it)) }
        },
    )

    ZillitSectionCard(padded = false, modifier = Modifier.fillMaxWidth()) {
        ZillitDataTable(
            rows = standard.rows,
            key = { it.id },
            loading = standard.loading,
            columns = columns(state, onEvent),
            onRowClick = { onEvent(FormSignatureEvent.OpenStandardForm(it)) },
            emptyTitle = if (standard.tab == StandardTab.All) {
                "No standard documents yet"
            } else {
                "Nothing in your documents yet"
            },
            emptyMessage = if (standard.tab == StandardTab.All) {
                "Documents uploaded to the library appear here for the whole production."
            } else {
                "Use “Add to your documents” on the All documents tab."
            },
        )
    }
}

private fun columns(
    state: FormSignatureUiState,
    onEvent: (FormSignatureEvent) -> Unit,
): List<TableColumn<StandardForm>> = listOf(
    textColumn(header = "ID", width = ColumnWidth.Fixed(ID_WIDTH.dp)) { it.serialNo },
    textColumn(header = "Name", width = ColumnWidth.Weight(2f)) { it.name },
    textColumn(header = "Type") { it.type.label },
    textColumn(header = "Uploaded by", muted = true) { it.uploaderName },
    TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_WIDTH.dp)) { form ->
        RowActions(state, form, onEvent)
    },
)

private const val ID_WIDTH = 72
private const val ACTIONS_WIDTH = 150

@Composable
private fun RowActions(
    state: FormSignatureUiState,
    form: StandardForm,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (state.standard.tab == StandardTab.All) {
            ZillitIconButton(
                icon = ZillitIcons.Add,
                contentDescription = "Add to your documents",
                onClick = { onEvent(FormSignatureEvent.SelfAssign(form.id)) },
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Clock,
            contentDescription = "History",
            onClick = { onEvent(FormSignatureEvent.ShowHistory(form.id)) },
        )
        // Only the shared library holds forms that can be removed at all.
        if (state.standard.tab == StandardTab.All) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = { onEvent(FormSignatureEvent.DeleteStandardForm(form.id)) },
            )
        }
    }
}
