package com.zillit.desktop.feature.callsheet.ui.pages

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
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.ui.CallSheetEvent
import com.zillit.desktop.feature.callsheet.ui.CallSheetUiState
import com.zillit.desktop.feature.callsheet.ui.SheetEditor

/**
 * The sheet editor: header fields, then one card per section.
 *
 * The desktop edits the same structures the A4 renderer consumes — sections
 * as label/value grids — without pretending to be the printed page; the
 * faithful layout is the server's PDF, one click away.
 */
@Composable
internal fun SheetEditorPage(
    state: CallSheetUiState,
    editor: SheetEditor,
    onEvent: (CallSheetEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = editor.name.ifBlank { "New call sheet" },
            eyebrow = "Call sheet",
            actions = {
                ZillitButton(
                    text = "Back",
                    onClick = { onEvent(CallSheetEvent.CloseEditor) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = if (editor.sheetId == null) "Save draft" else "Save revision",
                    onClick = { onEvent(CallSheetEvent.SaveDraft) },
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
                        onClick = { onEvent(CallSheetEvent.DismissError) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            item { HeaderCard(state = state, editor = editor, onEvent = onEvent) }
            itemsIndexed(editor.payload.rows) { rowIndex, row ->
                PageRowCard(
                    row = row,
                    rowIndex = rowIndex,
                    sheetDateMs = editor.payload.shared.dateMs,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(
    state: CallSheetUiState,
    editor: SheetEditor,
    onEvent: (CallSheetEvent) -> Unit,
) {
    val shared = editor.payload.shared
    ZillitSectionCard(title = "Sheet") {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.name,
                onValueChange = { onEvent(CallSheetEvent.NameChanged(it)) },
                label = "Name",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = shared.shootDayNumber,
                    onValueChange = { onEvent(CallSheetEvent.SharedChanged(shootDayNumber = it)) },
                    label = "Shoot day",
                    modifier = Modifier.width(FIELD_NARROW),
                )
                ZillitTextField(
                    value = shared.totalDays,
                    onValueChange = { onEvent(CallSheetEvent.SharedChanged(totalDays = it)) },
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
                        onSelect = { onEvent(CallSheetEvent.SharedChanged(dayType = it)) },
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
    sheetDateMs: Long?,
    onEvent: (CallSheetEvent) -> Unit,
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
                sheetDateMs = sheetDateMs,
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
    sheetDateMs: Long?,
    onEvent: (CallSheetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(
        modifier = modifier,
        title = cell.title.ifBlank { if (cell.renderAs == RenderKind.Weather) "Weather" else "Untitled" },
        action = {
            ZillitButton(
                text = "Add row",
                onClick = { onEvent(CallSheetEvent.AddLine(rowIndex, cellIndex)) },
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
                        val isTime = spec?.type == "time"
                        ZillitTextField(
                            value = if (isTime) SheetTime.display(atom.value) else atom.value,
                            onValueChange = { raw ->
                                val encoded = if (isTime) SheetTime.encode(raw, sheetDateMs) else raw
                                onEvent(
                                    CallSheetEvent.ValueChanged(
                                        row = rowIndex,
                                        cell = cellIndex,
                                        line = lineIndex,
                                        column = column,
                                        value = encoded,
                                    ),
                                )
                            },
                            placeholder = if (isTime) "HH:mm" else null,
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
