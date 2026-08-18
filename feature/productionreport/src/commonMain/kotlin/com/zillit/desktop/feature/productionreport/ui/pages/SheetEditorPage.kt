package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.SheetEditor

/**
 * The report editor: header fields, then one card per section.
 *
 * Times are typed as wall-clock `HH:mm` (In/Out also accepts `Per HOD`,
 * `O/C`, or free text); the wire codec normalises on save, so partial input
 * never fights the keyboard.
 */
@Composable
internal fun SheetEditorPage(
    state: ReportUiState,
    editor: SheetEditor,
    onEvent: (ReportEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = editor.name.ifBlank { "New ${state.kind.nameStem.lowercase()}" },
            eyebrow = state.kind.nameStem,
            actions = {
                ZillitButton(
                    text = "Back",
                    onClick = { onEvent(ReportEvent.CloseEditor) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = if (editor.sheetId == null) "Save draft" else "Save revision",
                    onClick = { onEvent(ReportEvent.SaveDraft) },
                    loading = editor.saving,
                )
            },
        )

        if (editor.status.reviewInFlight) {
            ZillitNotice(
                text = "This sheet is under review — saving restarts the approval round.",
                tone = StatusTone.Pending,
            )
        }
        state.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                action = {
                    ZillitButton(
                        text = "Dismiss",
                        onClick = { onEvent(ReportEvent.DismissError) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            item { HeaderCard(state = state, editor = editor, onEvent = onEvent) }
            itemsIndexed(editor.payload.rows) { rowIndex, row ->
                PageRowCard(row = row, rowIndex = rowIndex, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun HeaderCard(
    state: ReportUiState,
    editor: SheetEditor,
    onEvent: (ReportEvent) -> Unit,
) {
    val shared = editor.payload.shared
    ZillitSectionCard(title = "Sheet") {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.name,
                onValueChange = { onEvent(ReportEvent.NameChanged(it)) },
                label = "Name",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = shared.shootDayNumber,
                    onValueChange = { onEvent(ReportEvent.SharedChanged(shootDayNumber = it)) },
                    label = "Shoot day",
                    modifier = Modifier.width(FIELD_NARROW),
                )
                ZillitTextField(
                    value = shared.totalDays,
                    onValueChange = { onEvent(ReportEvent.SharedChanged(totalDays = it)) },
                    label = "Of days",
                    modifier = Modifier.width(FIELD_NARROW),
                )
                Column {
                    ZillitText(
                        text = "Day type",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitSelect(
                        value = shared.dayType,
                        options = state.metadata.dayTypes.ifEmpty { listOf(shared.dayType) },
                        onSelect = { onEvent(ReportEvent.SharedChanged(dayType = it)) },
                        label = { it },
                        modifier = Modifier.width(FIELD_SELECT),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageRowCard(
    row: PageRow,
    rowIndex: Int,
    onEvent: (ReportEvent) -> Unit,
) {
    if (row.isBreak) {
        ZillitText(
            text = "— page break —",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        row.cells.forEachIndexed { cellIndex, cell ->
            CellCard(
                cell = cell,
                rowIndex = rowIndex,
                cellIndex = cellIndex,
                onEvent = onEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CellCard(
    cell: PageCell,
    rowIndex: Int,
    cellIndex: Int,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(
        modifier = modifier,
        title = cell.title.ifBlank { if (cell.renderAs == RenderKind.Weather) "Weather" else "Untitled" },
        action = {
            ZillitButton(
                text = "Add row",
                onClick = { onEvent(ReportEvent.AddLine(rowIndex, cellIndex)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (cell.columns.any { it.label.isNotBlank() }) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    cell.columns.forEach { column ->
                        ZillitText(
                            text = column.label,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            modifier = Modifier.weight((column.width ?: 1.0).toFloat()),
                        )
                    }
                }
            }
            cell.rows.forEachIndexed { lineIndex, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    line.values.forEachIndexed { column, atom ->
                        val spec = cell.columns.getOrNull(column)
                        ZillitTextField(
                            value = atom.value,
                            onValueChange = { raw ->
                                onEvent(
                                    ReportEvent.ValueChanged(
                                        row = rowIndex,
                                        cell = cellIndex,
                                        line = lineIndex,
                                        column = column,
                                        value = raw,
                                    ),
                                )
                            },
                            placeholder = if (spec?.type == "time") "HH:mm" else null,
                            modifier = Modifier.weight((spec?.width ?: 1.0).toFloat()),
                        )
                    }
                }
            }
        }
    }
}


private val FIELD_NARROW = 110.dp
private val FIELD_SELECT = 180.dp
