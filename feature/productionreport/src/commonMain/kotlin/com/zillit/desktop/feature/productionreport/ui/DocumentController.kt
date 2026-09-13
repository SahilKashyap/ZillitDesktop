package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.ReportWeather
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SharedHeader
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.insertCell
import com.zillit.desktop.feature.productionreport.domain.insertRow
import com.zillit.desktop.feature.productionreport.domain.moveBlock
import com.zillit.desktop.feature.productionreport.domain.removeCell
import com.zillit.desktop.feature.productionreport.domain.removeRow
import com.zillit.desktop.feature.productionreport.domain.toggledApprover
import com.zillit.desktop.feature.productionreport.domain.updateCell
import com.zillit.desktop.feature.productionreport.domain.withAtom
import com.zillit.desktop.feature.productionreport.domain.withColumn
import com.zillit.desktop.feature.productionreport.domain.withColumnAdded
import com.zillit.desktop.feature.productionreport.domain.withColumnRemoved
import com.zillit.desktop.feature.productionreport.domain.withColumnValue
import com.zillit.desktop.feature.productionreport.domain.withDate
import com.zillit.desktop.feature.productionreport.domain.withLineAdded
import com.zillit.desktop.feature.productionreport.domain.withLineHeight
import com.zillit.desktop.feature.productionreport.domain.withLineRemoved
import com.zillit.desktop.feature.productionreport.domain.withWeatherValue
import com.zillit.desktop.feature.productionreport.domain.convertedTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every change to the open document: the preview's inserts, the Sections
 * list's removals and drags, and the pane's field, grid and weather editors.
 */
@Suppress("TooManyFunctions") // One function per document edit.
internal class DocumentController(private val ctx: ReportContext) {

    private var undoSerial = 0L

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per edit.
    fun onEvent(event: DocumentEvent) {
        val editor = ctx.state.editor ?: return
        when (event) {
            is DocumentEvent.InsertRow -> {
                val (doc, selection) = editor.document.insertRow(
                    event.kind,
                    event.afterIndex,
                    event.lockApprovers,
                    event.aboveHeader,
                )
                change(doc, select = selection)
            }
            is DocumentEvent.InsertCell -> {
                val (doc, selection) = editor.document.insertCell(event.row, event.afterCell, event.kind)
                change(doc, select = selection)
            }
            is DocumentEvent.RemoveRow -> removeRow(editor, event.row)
            is DocumentEvent.RemoveCell -> removeCell(editor, event.row, event.cell)
            is DocumentEvent.MoveBlock -> change(
                editor.document.moveBlock(event.fromKey, event.toKey),
                keepSelection = false,
            )
            is DocumentEvent.SetShootDay -> shared { copy(shootDayNumber = event.value) }
            is DocumentEvent.SetTotalDays -> shared { copy(totalDays = event.value) }
            is DocumentEvent.SetDate -> change(editor.document.withDate(event.ymd))
            is DocumentEvent.SetDayType -> shared { copy(dayType = event.value) }
            is DocumentEvent.AddDayType -> addDayType(event.name)
            is DocumentEvent.ToggleApprover -> change(editor.document.toggledApprover(event.userId))
            DocumentEvent.SaveApprovers -> saveApprovers()
            is DocumentEvent.SetTitle -> cell(event.row, event.cell) { copy(title = event.title) }
            is DocumentEvent.SetHideTitle -> cell(event.row, event.cell) { copy(hideTitle = event.hidden) }
            is DocumentEvent.SetVertical -> cell(event.row, event.cell) {
                copy(headerOrientation = if (event.vertical) "vertical" else "horizontal")
            }
            is DocumentEvent.SetKind -> cell(event.row, event.cell) { convertedTo(event.kind) }
            is DocumentEvent.SetNotesHorizontal -> cell(event.row, event.cell) {
                copy(viewType = if (event.horizontal) "horizontal" else "vertical")
            }
            is DocumentEvent.AddColumn -> cell(event.row, event.cell) { withColumnAdded() }
            is DocumentEvent.RemoveColumn -> removeColumn(editor, event.row, event.cell, event.column)
            is DocumentEvent.RenameColumn -> cell(
                event.row,
                event.cell,
            ) { withColumn(event.column) { it.copy(label = event.label) } }
            is DocumentEvent.SetColumnType -> cell(
                event.row,
                event.cell,
            ) { withColumn(event.column) { it.copy(type = event.type) } }
            is DocumentEvent.SetColumnWidths -> cell(event.row, event.cell) {
                copy(
                    columns = columns.mapIndexed { i, column ->
                        event.widths.getOrNull(i)?.let { column.copy(width = it) } ?: column
                    },
                )
            }
            is DocumentEvent.AddLine -> cell(event.row, event.cell) { withLineAdded() }
            is DocumentEvent.RemoveLine -> removeLine(editor, event.row, event.cell, event.line)
            is DocumentEvent.SetLineHeight -> cell(event.row, event.cell) { withLineHeight(event.line, event.height) }
            is DocumentEvent.SetValue -> cell(event.row, event.cell) {
                withAtom(event.line, event.column) { it.copy(value = event.value) }
            }
            is DocumentEvent.SetLocation -> cell(event.row, event.cell) { withLocation(event) }
            is DocumentEvent.ApplyToColumn -> cell(event.row, event.cell) { withColumnValue(event.column, event.value) }
            is DocumentEvent.SetWeatherText -> cell(event.row, event.cell) { withWeatherValue(event.text) }
            is DocumentEvent.FetchWeather -> fetchWeather(event.row, event.cell, event.lat, event.lng, event.location)
            is DocumentEvent.PickWeatherDay -> pickWeatherDay(event.row, event.cell, event.index)
            is DocumentEvent.RefreshWeather -> refreshWeather(event.row, event.cell)
        }
    }

    private fun change(
        document: SheetPayload,
        select: EditorSelection? = null,
        keepSelection: Boolean = true,
    ) = ctx.update {
        val current = editor ?: return@update this
        copy(
            editor = current.copy(
                document = document,
                selection = select ?: if (keepSelection) current.selection else null,
                sidebarVisible = if (select != null) false else current.sidebarVisible,
                paneOpen = if (select != null) true else current.paneOpen,
            ),
        )
    }

    private fun shared(edit: SharedHeader.() -> SharedHeader) {
        val editor = ctx.state.editor ?: return
        change(editor.document.copy(shared = editor.document.shared.edit()))
    }

    private fun cell(row: Int, cell: Int, edit: PageCell.() -> PageCell) {
        val editor = ctx.state.editor ?: return
        change(editor.document.updateCell(row, cell) { it.edit() })
    }

    // Removals: applied at once, with a 3-second undo --------------------------------------

    private fun removeRow(editor: EditorState, row: Int) {
        val doc = editor.document
        val removed = doc.rows.getOrNull(row) ?: return
        val label = removed.cells.mapNotNull { it.title.trim().ifEmpty { null } }.joinToString(", ")
            .ifEmpty { "Row ${row + 1}" }
        val action = UndoAction.Row(removed, row, doc.shared.headerPosition, doc.shared.approversPosition)
        applyRemoval(
            editor = editor,
            document = doc.removeRow(row),
            record = action,
            label = label,
            clearsSelection = true,
            systemCells = removed.cells.mapIndexedNotNull { index, cell ->
                RemovedDefault(cell, row, index, rowCells = removed.cells).takeIf { cell.systemDefault }
            },
        )
    }

    private fun removeCell(editor: EditorState, row: Int, cellIndex: Int) {
        val doc = editor.document
        val pageRow = doc.rows.getOrNull(row) ?: return
        val removed = pageRow.cells.getOrNull(cellIndex) ?: return
        val action = if (pageRow.cells.size <= 1) {
            UndoAction.Row(pageRow, row, doc.shared.headerPosition, doc.shared.approversPosition)
        } else {
            UndoAction.Cell(removed, row, cellIndex)
        }
        val selected = editor.selection as? EditorSelection.Cell
        applyRemoval(
            editor = editor,
            document = doc.removeCell(row, cellIndex),
            record = action,
            label = removed.title.trim().ifEmpty { "Section" },
            clearsSelection = selected?.row == row,
            systemCells = listOfNotNull(
                removed.takeIf { it.systemDefault }?.let {
                    val rowGoes = pageRow.cells.size <= 1
                    RemovedDefault(it, row, cellIndex, rowCells = if (rowGoes) pageRow.cells else emptyList())
                },
            ),
        )
    }

    private fun applyRemoval(
        editor: EditorState,
        document: SheetPayload,
        record: UndoAction,
        label: String,
        clearsSelection: Boolean,
        systemCells: List<RemovedDefault>,
    ) {
        val serial = ++undoSerial
        ctx.update {
            copy(
                editor = editor.copy(
                    document = document,
                    selection = if (clearsSelection) null else editor.selection,
                    paneOpen = editor.paneOpen || editor.selection != null,
                    undo = UndoRecord(label, record, serial),
                    removedDefaults = editor.removedDefaults + systemCells,
                ),
            )
        }
    }

    private fun removeLine(editor: EditorState, row: Int, cellIndex: Int, line: Int) {
        val cell = editor.document.rows.getOrNull(row)?.cells?.getOrNull(cellIndex) ?: return
        val removed = cell.rows.getOrNull(line) ?: return
        val serial = ++undoSerial
        ctx.update {
            copy(
                editor = editor.copy(
                    document = editor.document.updateCell(row, cellIndex) { it.withLineRemoved(line) },
                    undo = UndoRecord("Row ${line + 1}", UndoAction.Line(row, cellIndex, removed, line), serial),
                ),
            )
        }
    }

    private fun removeColumn(editor: EditorState, row: Int, cellIndex: Int, column: Int) {
        val cell = editor.document.rows.getOrNull(row)?.cells?.getOrNull(cellIndex) ?: return
        if (cell.columns.size <= 1) return
        val spec = cell.columns.getOrNull(column) ?: return
        val values = cell.rows.map { line ->
            line.values.getOrNull(column) ?: com.zillit.desktop.feature.productionreport.domain.CellValue()
        }
        val serial = ++undoSerial
        ctx.update {
            copy(
                editor = editor.copy(
                    document = editor.document.updateCell(row, cellIndex) { it.withColumnRemoved(column) },
                    undo = UndoRecord(
                        spec.label.trim().ifEmpty { "Column ${column + 1}" },
                        UndoAction.Column(row, cellIndex, spec, values, column),
                        serial,
                    ),
                ),
            )
        }
    }

    // Header fields ---------------------------------------------------------------------------

    /** A custom day type persists project-wide, then becomes the report's own. */
    private fun addDayType(name: String) {
        val value = name.trim()
        val project = ctx.projectId()
        if (value.isEmpty() || project == null) return
        ctx.launchWork {
            val saved = ctx.repository.saveMetadata(project, MetadataUpdate(dayTypeAdd = value))
            val merged = when (saved) {
                is ZillitResult.Success ->
                    saved.data?.dayTypes ?: SheetMetadata.mergeDayTypes(ctx.state.metadata.dayTypes + value)
                is ZillitResult.Failure -> null
            }
            if (merged != null) {
                ctx.update {
                    copy(
                        metadata = metadata.copy(dayTypes = merged),
                        editor = editor?.let { current ->
                            current.copy(
                                dayTypes = merged,
                                document = current.document.copy(
                                    shared = current.document.shared.copy(dayType = value),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    /** "Save Approvers": the report's approvers become the project default for every report. */
    private fun saveApprovers() {
        val editor = ctx.state.editor ?: return
        val project = ctx.projectId() ?: return
        if (editor.savingApprovers || !ctx.state.isPoster) return
        val ids = editor.document.shared.approverIds
        ctx.update { copy(editor = editor.copy(savingApprovers = true)) }
        ctx.launchWork {
            when (val saved = ctx.repository.saveMetadata(project, MetadataUpdate(finalApproverIds = ids))) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            metadata = metadata.copy(finalApproverIds = ids),
                            editor = this.editor?.copy(savingApprovers = false, initialApproverIds = ids),
                        )
                    }
                    ctx.toast("Approvers saved for all production reports!")
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(editor = this.editor?.copy(savingApprovers = false)) }
                    ctx.toast("Failed to save approvers: ${saved.error.localised()}", isError = true)
                }
            }
        }
    }

    // Location and weather ---------------------------------------------------------------------

    /**
     * The address as `value`, the pin as `{"lat","lng"}` in `attachment` — cleared when the
     * text no longer names a pin.
     */
    private fun PageCell.withLocation(event: DocumentEvent.SetLocation): PageCell = withAtom(
        event.line,
        event.column,
    ) { atom ->
        val pin = if (event.lat != null && event.lng != null) {
            buildJsonObject {
                put("lat", JsonPrimitive(event.lat))
                put("lng", JsonPrimitive(event.lng))
            }.toString()
        } else {
            ""
        }
        atom.copy(value = event.address, attachment = pin)
    }

    private fun fetchWeather(row: Int, cell: Int, lat: Double, lng: Double, location: String) {
        val source = ctx.services.weather ?: return
        ctx.update {
            copy(editor = editor?.copy(weather = WeatherPanel(row, cell, location, lat, lng, fetching = true)))
        }
        ctx.launchWork {
            when (val result = source.oneCall(lat, lng)) {
                is ZillitResult.Success -> weatherArrived(row, cell, location, lat, lng, result.data)
                is ZillitResult.Failure -> ctx.update {
                    copy(
                        editor = editor?.copy(
                            weather = editor.weather?.copy(
                                fetching = false,
                                error = result.error.localised().ifBlank { "Failed to fetch weather." },
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun weatherArrived(row: Int, cell: Int, location: String, lat: Double, lng: Double, response: JsonObject) {
        val editor = ctx.state.editor ?: return
        val value = ReportWeather.valueFor(response, location, editor.document.shared.dateYmd, ctx.now())
        ctx.update {
            val current = this.editor ?: return@update this
            copy(
                editor = current.copy(
                    weather = WeatherPanel(
                        row,
                        cell,
                        location,
                        lat,
                        lng,
                        response = response,
                        error = if (value == null) "No weather data received." else null,
                    ),
                    document = if (value == null) current.document else current.document.updateCell(
                        row,
                        cell,
                    ) { it.withWeatherValue(value) },
                ),
            )
        }
    }

    private fun pickWeatherDay(row: Int, cell: Int, index: Int) {
        val panel = ctx.state.editor?.weather?.takeIf { it.row == row && it.cell == cell } ?: return
        val response = panel.response ?: return
        val value = ReportWeather.valueForDay(response, index, panel.location, ctx.now()) ?: return
        cell(row, cell) { withWeatherValue(value) }
    }

    private fun refreshWeather(row: Int, cell: Int) {
        val editor = ctx.state.editor ?: return
        val panel = editor.weather?.takeIf { it.row == row && it.cell == cell }
        val stored = com.zillit.desktop.feature.productionreport.domain.WeatherValue.parse(
            editor.document.rows.getOrNull(row)?.cells?.getOrNull(cell)
                ?.rows?.firstOrNull()?.values?.firstOrNull()?.value.orEmpty(),
        )
        val lat = panel?.lat ?: stored?.lat ?: return
        val lng = panel?.lng ?: stored?.lon ?: return
        fetchWeather(row, cell, lat, lng, panel?.location ?: stored?.location.orEmpty())
    }
}
