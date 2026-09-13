package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.CellValue
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SharedHeader
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetWeather
import com.zillit.desktop.feature.callsheet.domain.WeatherValue
import com.zillit.desktop.feature.callsheet.domain.convertedTo
import com.zillit.desktop.feature.callsheet.domain.insertCell
import com.zillit.desktop.feature.callsheet.domain.insertRow
import com.zillit.desktop.feature.callsheet.domain.moveBlock
import com.zillit.desktop.feature.callsheet.domain.removeCell
import com.zillit.desktop.feature.callsheet.domain.removeRow
import com.zillit.desktop.feature.callsheet.domain.toggledApprover
import com.zillit.desktop.feature.callsheet.domain.updateCell
import com.zillit.desktop.feature.callsheet.domain.withAtom
import com.zillit.desktop.feature.callsheet.domain.withColumn
import com.zillit.desktop.feature.callsheet.domain.withColumnAdded
import com.zillit.desktop.feature.callsheet.domain.withColumnRemoved
import com.zillit.desktop.feature.callsheet.domain.withColumnType
import com.zillit.desktop.feature.callsheet.domain.withColumnValue
import com.zillit.desktop.feature.callsheet.domain.withDate
import com.zillit.desktop.feature.callsheet.domain.withLineAdded
import com.zillit.desktop.feature.callsheet.domain.withLineHeight
import com.zillit.desktop.feature.callsheet.domain.withLineRemoved
import com.zillit.desktop.feature.callsheet.domain.withWeatherValue
import kotlinx.serialization.json.JsonObject

/**
 * Every change to the open document: the preview's inserts, the Sections
 * list's removals and drags, and the pane's header, approvers, grid and
 * weather editors — `PageRowsEditor.jsx`, `WeatherWidget.jsx`.
 *
 * Removals apply at once against the document as it is now, with a
 * 3-second undo — the web applied a snapshot taken when the removal was
 * scheduled, silently reverting any edit made in between.
 */
@Suppress("TooManyFunctions") // One function per document edit.
internal class DocumentController(private val ctx: SheetContext) {

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
            is DocumentEvent.SetDate -> {
                change(editor.document.withDate(event.epochMs))
                ctx.update { copy(editor = this.editor?.copy(weather = null)) }
            }
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
            is DocumentEvent.AddColumn -> cell(event.row, event.cell) { withColumnAdded() }
            is DocumentEvent.RemoveColumn -> removeColumn(editor, event.row, event.cell, event.column)
            is DocumentEvent.RenameColumn -> cell(event.row, event.cell) {
                withColumn(event.column) { it.copy(label = event.label) }
            }
            is DocumentEvent.SetColumnType -> cell(event.row, event.cell) { withColumnType(event.column, event.type) }
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
            is DocumentEvent.ApplyToColumn -> cell(event.row, event.cell) { withColumnValue(event.column, event.value) }
            is DocumentEvent.SetWeatherText -> cell(event.row, event.cell) { withWeatherValue(event.text) }
            is DocumentEvent.FetchWeather -> fetchWeather(event.row, event.cell, event.lat, event.lng, event.location)
            is DocumentEvent.PickWeatherDay -> pickWeatherDay(event.row, event.cell, event.index)
            is DocumentEvent.RefreshWeather -> refreshWeather(event.row, event.cell)
            is DocumentEvent.ClearWeather -> {
                cell(event.row, event.cell) { withWeatherValue("") }
                ctx.update { copy(editor = this.editor?.copy(weather = null)) }
            }
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

    // Removals: applied at once, with a 3-second undo ------------------------------------------------

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
        val rowGoes = pageRow.cells.size <= 1
        val action = if (rowGoes) {
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

    /** A grid line or note removed — "Row removed", with Undo. */
    private fun removeLine(editor: EditorState, row: Int, cellIndex: Int, line: Int) {
        val cell = editor.document.rows.getOrNull(row)?.cells?.getOrNull(cellIndex) ?: return
        val removed = cell.rows.getOrNull(line) ?: return
        val serial = ++undoSerial
        ctx.update {
            copy(
                editor = editor.copy(
                    document = editor.document.updateCell(row, cellIndex) { it.withLineRemoved(line) },
                    quickUndo = UndoRecord("Row removed", UndoAction.Line(row, cellIndex, removed, line), serial),
                ),
            )
        }
    }

    /** A column and its values removed — "Column removed", with Undo. The last column always stays. */
    private fun removeColumn(editor: EditorState, row: Int, cellIndex: Int, column: Int) {
        val cell = editor.document.rows.getOrNull(row)?.cells?.getOrNull(cellIndex) ?: return
        if (cell.columns.size <= 1) return
        val spec = cell.columns.getOrNull(column) ?: return
        val values = cell.rows.map { line -> line.values.getOrNull(column) ?: CellValue() }
        val serial = ++undoSerial
        ctx.update {
            copy(
                editor = editor.copy(
                    document = editor.document.updateCell(row, cellIndex) { it.withColumnRemoved(column) },
                    quickUndo = UndoRecord(
                        "Column removed",
                        UndoAction.Column(row, cellIndex, spec, values, column),
                        serial,
                    ),
                ),
            )
        }
    }

    // Header fields ----------------------------------------------------------------------------------

    /** A custom day type persists project-wide (`day_type_add`); it is offered, not auto-selected. */
    private fun addDayType(name: String) {
        val value = name.trim()
        val project = ctx.projectId()
        if (value.isEmpty() || project == null || !ctx.state.isPoster) return
        ctx.launchWork {
            val merged = when (val saved = ctx.repository.saveMetadata(project, MetadataUpdate(dayTypeAdd = value))) {
                is ZillitResult.Success ->
                    saved.data?.dayTypes ?: SheetMetadata.mergeDayTypes(ctx.state.metadata.dayTypes + value)
                is ZillitResult.Failure -> null
            } ?: return@launchWork
            ctx.update {
                copy(
                    metadata = metadata.copy(dayTypes = merged),
                    editor = editor?.copy(dayTypes = merged),
                )
            }
        }
    }

    /**
     * "Save Approvers": the sheet's approvers become the project default for
     * every call sheet. Only the approvers are written — the web's full-shape
     * write also moved the project's shoot-day counter from whatever draft was
     * open (B-18).
     */
    private fun saveApprovers() {
        val editor = ctx.state.editor ?: return
        val project = ctx.projectId() ?: return
        if (editor.savingApprovers || !ctx.state.isPoster) return
        val ids = editor.document.shared.approverIds
        if (ids.isEmpty()) return
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
                    ctx.toast("Approvers saved for all call sheets!")
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(editor = this.editor?.copy(savingApprovers = false)) }
                    ctx.toast("Failed to save approvers: ${saved.error.localised()}", isError = true)
                }
            }
        }
    }

    // Weather -----------------------------------------------------------------------------------------

    private fun fetchWeather(row: Int, cell: Int, lat: Double, lng: Double, location: String) {
        val editor = ctx.state.editor ?: return
        val source = ctx.services.weather
        if (source == null) {
            ctx.toast("Weather isn't available here.", isError = true)
            return
        }
        val previous = editor.weather?.takeIf { it.row == row && it.cell == cell }
        if (previous?.fetching == true) return
        val session = editor.session
        // The forecast in memory and its location stay until the new one arrives — a failed fetch keeps them.
        val starting = (previous ?: WeatherPanel(row, cell, location, lat, lng)).copy(fetching = true, error = null)
        ctx.update { copy(editor = this.editor?.copy(weather = starting)) }
        ctx.launchWork {
            when (val result = source.oneCall(lat, lng)) {
                is ZillitResult.Success -> weatherArrived(session, row, cell, location, lat, lng, result.data)
                is ZillitResult.Failure -> ctx.update {
                    val current = this.editor?.takeIf { it.session == session } ?: return@update this
                    copy(
                        editor = current.copy(
                            weather = current.weather?.copy(
                                fetching = false,
                                error = result.error.localised().ifBlank { "Failed to fetch weather." },
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * A forecast arrived: the shoot day's entry when the 8-day window reaches
     * it, otherwise the current conditions — written only if the sheet that
     * asked is still open and the cell is still a weather cell.
     */
    @Suppress("LongParameterList") // The request's coordinates travel with its answer.
    private fun weatherArrived(
        session: Long,
        row: Int,
        cell: Int,
        location: String,
        lat: Double,
        lng: Double,
        response: JsonObject,
    ) {
        ctx.update {
            val current = this.editor?.takeIf { it.session == session } ?: return@update this
            val target = current.document.rows.getOrNull(row)?.cells?.getOrNull(cell)
            if (target?.renderAs != RenderKind.Weather) return@update copy(editor = current.copy(weather = null))
            val dateMs = current.document.shared.dateMs
            val value = SheetWeather.valueFor(response, location, dateMs, ctx.now())
            copy(
                editor = current.copy(
                    weather = WeatherPanel(
                        row = row,
                        cell = cell,
                        location = location,
                        lat = lat,
                        lng = lng,
                        response = response,
                        selectedDay = SheetWeather.shootDayIndex(response, dateMs),
                        error = if (value == null) "No weather data received." else null,
                    ),
                    document = if (value == null) {
                        current.document
                    } else {
                        current.document.updateCell(row, cell) { it.withWeatherValue(value) }
                    },
                ),
            )
        }
    }

    /** A day picked on the forecast strip — today reads the current conditions. */
    private fun pickWeatherDay(row: Int, cell: Int, index: Int) {
        val editor = ctx.state.editor ?: return
        val panel = editor.weather?.takeIf { it.row == row && it.cell == cell } ?: return
        val response = panel.response ?: return
        val value = SheetWeather.valueForDay(response, index, panel.location, ctx.now()) ?: return
        ctx.update {
            val current = this.editor ?: return@update this
            copy(
                editor = current.copy(
                    weather = current.weather?.copy(selectedDay = index),
                    document = current.document.updateCell(row, cell) { it.withWeatherValue(value) },
                ),
            )
        }
    }

    /** ⟳: the stored point again; a value without one cannot refresh. */
    private fun refreshWeather(row: Int, cell: Int) {
        val editor = ctx.state.editor ?: return
        val panel = editor.weather?.takeIf { it.row == row && it.cell == cell }
        val stored = WeatherValue.parse(
            editor.document.rows.getOrNull(row)?.cells?.getOrNull(cell)
                ?.rows?.firstOrNull()?.values?.firstOrNull()?.value.orEmpty(),
        )
        val lat = stored?.lat ?: panel?.lat
        val lng = stored?.lon ?: panel?.lng
        if (lat == null || lng == null) {
            ctx.toast("Pick a location to fetch the weather.", isError = true)
            return
        }
        fetchWeather(row, cell, lat, lng, stored?.location ?: panel?.location.orEmpty())
    }
}
