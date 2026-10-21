package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.DayLinkTarget
import com.zillit.desktop.feature.addashboard.domain.PostOutcome
import com.zillit.desktop.feature.addashboard.domain.ReportBlockType
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportColumn
import com.zillit.desktop.feature.addashboard.domain.ReportDraft
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportListRules
import com.zillit.desktop.feature.addashboard.domain.ReportPageRow
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.buildDayPatch

/**
 * The three report kinds, their templates and their signing.
 *
 * ## Two rules worth knowing before changing anything here
 *
 * **A report's kind comes from the endpoint that answered**, never from the
 * row: the AD endpoint also returns untyped legacy reports, so a row's own
 * `report_type` cannot be trusted — and the kind decides which endpoint every
 * later call for that row uses. Fetching one list and filtering it once showed
 * identical data under two headings.
 *
 * **A create is idempotent per day and kind.** When a new draft's first save
 * lands on a day that already has a report of that kind, the server returns
 * the existing one flagged `existed`, and that is treated as a **refusal**:
 * the draft, its dirty flag and the open editor are left exactly as they were,
 * so the AD can change the date instead of having their typing destroyed.
 */
// Three report kinds, their templates, the editor and Exhibit G signing are
// one area on the web too — splitting them would mean four classes sharing the
// same open report and the same autosave timer.
@Suppress("TooManyFunctions", "LargeClass")
internal class ReportActions(private val vm: AdViewModel) {

    /**
     * The debounced write, restarted by every edit.
     *
     * Silent on purpose: it sends `notify: false`, so the server skips the
     * bell and the history entry and emits no event — if it fired one, this
     * editor's own listener would reload the report over whatever was typed
     * after the debounced write went out.
     */
    private var autosave: kotlinx.coroutines.Job? = null

    /** When the open report first went dirty, so a long edit still gets saved. */
    private var dirtySince: Long? = null

    fun loadMerged() {
        vm.update { copy(reports = reports.copy(loading = true)) }
        vm.run {
            val wrap = vm.repo.reports(ReportKind.WrapReport)
            val ad = vm.repo.reports(ReportKind.AdReport)
            vm.update {
                copy(
                    reports = reports.copy(
                        loading = false,
                        wrapRows = (wrap as? ZillitResult.Success)?.data ?: reports.wrapRows,
                        adRows = (ad as? ZillitResult.Success)?.data ?: reports.adRows,
                    ),
                )
            }
            (wrap as? ZillitResult.Failure)?.let { vm.failWith(it.error) }
            loadTemplatesQuietly()
        }
    }

    fun loadExhibitG() {
        vm.update { copy(reports = reports.copy(loading = true)) }
        vm.run {
            when (val rows = vm.repo.reports(ReportKind.ExhibitG)) {
                is ZillitResult.Success -> vm.update {
                    copy(reports = reports.copy(loading = false, exhibitRows = rows.data))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(reports = reports.copy(loading = false)) }
                    vm.failWith(rows.error)
                }
            }
        }
    }

    fun loadTemplates() {
        vm.update { copy(reports = reports.copy(loading = true)) }
        vm.run {
            loadTemplatesQuietly()
            vm.update { copy(reports = reports.copy(loading = false)) }
        }
    }

    private suspend fun loadTemplatesQuietly() {
        (vm.repo.templates() as? ZillitResult.Success)?.let { rows ->
            vm.update { copy(reports = reports.copy(templates = rows.data)) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table; one line per control.
    fun on(event: ReportEvent) {
        when (event) {
            is ReportEvent.FilterKind -> vm.update { copy(reports = reports.copy(kindFilter = event.kind)) }
            is ReportEvent.FilterStatus -> vm.update { copy(reports = reports.copy(statusFilter = event.status)) }
            is ReportEvent.Search -> vm.update { copy(reports = reports.copy(search = event.text)) }
            is ReportEvent.OpenDayLink -> openDayLink(event.shootDate)
            ReportEvent.ClearDayLink -> vm.update { copy(reports = reports.copy(dayLink = null)) }
            ReportEvent.OpenTemplatePicker -> vm.update {
                copy(reports = reports.copy(templatePickerOpen = true))
            }

            ReportEvent.CloseTemplatePicker -> vm.update {
                copy(reports = reports.copy(templatePickerOpen = false))
            }

            is ReportEvent.StartFromTemplate -> startFromTemplate(event.template, event.shootDate)
            is ReportEvent.GenerateForDay -> generateForDay(event.shootDate, event.template)
            is ReportEvent.OpenReport -> openReport(event.row)
            ReportEvent.CloseEditor -> {
                cancelAutosave()
                vm.update { copy(reports = reports.copy(editor = null)) }
            }
            is ReportEvent.AskDelete -> vm.posting {
                vm.update { copy(reports = reports.copy(confirmDelete = event.row)) }
            }

            ReportEvent.ConfirmDelete -> confirmDelete()
            ReportEvent.CancelDelete -> vm.update { copy(reports = reports.copy(confirmDelete = null)) }
            is ReportEvent.Name -> patchEditor { copy(name = event.text, dirty = true) }
            is ReportEvent.PickDate -> patchEditor { copy(pendingDate = event.shootDate, dirty = true) }
            is ReportEvent.DayNumber -> patchEditor { copy(dayNumberOverride = event.text, dirty = true) }
            is ReportEvent.Fill -> fill(event)
            is ReportEvent.HeaderField -> patchEditor {
                copy(header = header + (event.key to event.value), dirty = true)
            }

            is ReportEvent.SelectCell -> patchEditor { copy(selectedCell = event.cellId) }
            is ReportEvent.AddRow -> addRow(event.cellId)
            is ReportEvent.RemoveRow -> removeRow(event.cellId, event.rowId)
            is ReportEvent.AddBlock -> addBlock(event.type)
            is ReportEvent.RemoveBlock -> removeBlock(event.cellId)
            is ReportEvent.BlockTitle -> patchCell(event.cellId) { copy(title = event.title) }
            is ReportEvent.ColumnLabel -> patchColumn(event.cellId, event.columnId) { copy(label = event.label) }
            is ReportEvent.ColumnType -> patchColumn(event.cellId, event.columnId) { copy(type = event.type) }
            is ReportEvent.AddColumn -> addColumn(event.cellId)
            is ReportEvent.RemoveColumn -> removeColumn(event.cellId, event.columnId)
            ReportEvent.OpenRearrange -> patchEditor { copy(rearrangeOpen = true) }
            ReportEvent.CloseRearrange -> patchEditor { copy(rearrangeOpen = false) }
            is ReportEvent.MoveBlock -> moveBlock(event.cellId, event.up)
            ReportEvent.Save -> save(asCopy = false)
            ReportEvent.SaveAsCopy -> save(asCopy = true)
            ReportEvent.OpenPublish -> openPublish()
            ReportEvent.ClosePublish -> patchEditor { copy(publishOpen = false) }
            ReportEvent.Publish -> publish()
            ReportEvent.DismissSignaturesPending -> patchEditor { copy(signaturesPending = emptyList()) }
            ReportEvent.OpenSaveAsTemplate -> patchEditor { copy(saveAsTemplateOpen = true, templateName = name) }
            ReportEvent.CloseSaveAsTemplate -> patchEditor { copy(saveAsTemplateOpen = false) }
            is ReportEvent.TemplateName -> patchEditor { copy(templateName = event.text) }
            ReportEvent.SaveAsTemplate -> saveAsTemplate()
            ReportEvent.DownloadPdf -> downloadPdf()
            is ReportEvent.StartSigning -> patchEditor {
                copy(signingRow = event.row, signStrokes = emptyList(), signTypedName = "")
            }

            ReportEvent.CancelSigning -> patchEditor {
                copy(signingRow = null, signStrokes = emptyList(), signTypedName = "")
            }

            is ReportEvent.SignStroke -> patchEditor {
                copy(signStrokes = signStrokes + listOf(event.points))
            }

            is ReportEvent.SignTypedName -> patchEditor { copy(signTypedName = event.text) }
            ReportEvent.ClearSignPad -> patchEditor { copy(signStrokes = emptyList(), signTypedName = "") }
            is ReportEvent.RenderRowSignature -> renderRowSignature(event)
            is ReportEvent.SignRow -> signRow(event)
            ReportEvent.AskResetSignatures -> patchEditor { copy(confirmReset = true) }
            ReportEvent.ConfirmResetSignatures -> resetSignatures()
            ReportEvent.CancelResetSignatures -> patchEditor { copy(confirmReset = false) }
            is ReportEvent.OpenTemplate -> openTemplate(event.template)
            is ReportEvent.DuplicateTemplate -> duplicateTemplate(event.template)
            is ReportEvent.DeleteTemplate -> deleteTemplate(event.template)
            ReportEvent.NewTemplate -> newTemplate()
        }
    }

    // -- opening -------------------------------------------------------------

    /**
     * A day link names a shoot day but no kind.
     *
     * One report that day opens it; several pin the list to that day and let
     * the AD pick; none offers the templates.
     */
    private fun openDayLink(shootDate: Long) {
        // The lists are loaded first when they have not been: the Schedule
        // page reads only the day headers, so a "View report" click used to
        // resolve against nothing and always offer the templates.
        if (vm.current.reports.merged.isEmpty()) {
            vm.run {
                val wrap = vm.repo.reports(ReportKind.WrapReport)
                val ad = vm.repo.reports(ReportKind.AdReport)
                vm.update {
                    copy(
                        reports = reports.copy(
                            wrapRows = (wrap as? ZillitResult.Success)?.data ?: reports.wrapRows,
                            adRows = (ad as? ZillitResult.Success)?.data ?: reports.adRows,
                        ),
                    )
                }
                resolveDayLink(shootDate)
            }
            return
        }
        resolveDayLink(shootDate)
    }

    private fun resolveDayLink(shootDate: Long) {
        val visible = ReportListRules.filter(
            rows = vm.current.reports.merged,
            canPost = vm.current.viewer.canPost,
        )
        when (val target = ReportListRules.dayLinkTarget(visible, shootDate)) {
            is DayLinkTarget.Open -> openReport(target.report)
            DayLinkTarget.Choose -> vm.update { copy(reports = reports.copy(dayLink = shootDate)) }
            DayLinkTarget.None -> vm.update {
                copy(reports = reports.copy(dayLink = shootDate, templatePickerOpen = viewer.canPost))
            }
        }
    }

    /**
     * Opens a report for filling in, or for reading.
     *
     * A viewer without posting rights may open a **published** report only:
     * hiding the buttons alone would leak a draft to anyone who kept the row.
     */
    private fun openReport(row: ReportSummary) {
        if (!vm.current.viewer.canPost && !row.status.published) {
            vm.askForPostingRights()
            return
        }
        vm.update { copy(reports = reports.copy(busy = true)) }
        vm.run {
            when (val full = vm.repo.report(row.kind, row.id)) {
                is ZillitResult.Success -> adopt(row.kind, full.data, isNew = false)
                is ZillitResult.Failure -> vm.report(full.error)
            }
            vm.update { copy(reports = reports.copy(busy = false)) }
        }
    }

    /**
     * An unsaved draft from a template.
     *
     * Never saved on open: the first explicit Save creates it, carrying a
     * client-minted id so a retry adopts the same report rather than making a
     * second.
     */
    private fun startFromTemplate(template: ReportTemplate, shootDate: Long?) = vm.posting {
        vm.run {
            // The slim template carries no layout, so the detail is fetched.
            val detail = (vm.repo.template(template.id) as? ZillitResult.Success)?.data ?: template
            val draft = AdReport(
                id = NEW_REPORT_ID,
                kind = detail.kind,
                name = detail.name,
                status = ReportStatus.Draft,
                shootDate = shootDate ?: vm.current.shootDate,
                pageRows = detail.pageRows,
                header = detail.header,
                templateName = detail.name,
            )
            adopt(detail.kind, draft, isNew = true)
        }
    }

    /**
     * The schedule's **Generate report** on a submitted day.
     *
     * The day's existing report of that kind opens if it has one; otherwise
     * the template creates it there and then, which is what makes the button
     * read "Generate" rather than "New".
     */
    private fun generateForDay(shootDate: Long, template: ReportTemplate) = vm.posting {
        vm.update { copy(reports = reports.copy(busy = true)) }
        vm.run {
            val kind = template.kind
            when (val existing = vm.repo.reportByDay(kind, shootDate)) {
                is ZillitResult.Success -> {
                    val found = existing.data
                    if (found != null) {
                        adopt(kind, found, isNew = false)
                    } else {
                        createFromTemplate(kind, template, shootDate)
                    }
                }

                is ZillitResult.Failure -> vm.report(existing.error)
            }
            vm.update { copy(reports = reports.copy(busy = false)) }
        }
    }

    private suspend fun createFromTemplate(kind: ReportKind, template: ReportTemplate, shootDate: Long) {
        val detail = (vm.repo.template(template.id) as? ZillitResult.Success)?.data ?: template
        val draft = ReportDraft(
            name = detail.name,
            shootDate = shootDate,
            templateId = detail.id,
            pageRows = detail.pageRows,
            header = detail.header,
        )
        when (val created = vm.repo.createReport(kind, draft)) {
            is ZillitResult.Success -> adopt(kind, created.data, isNew = false)
            is ZillitResult.Failure -> vm.report(created.error)
        }
    }

    private fun adopt(kind: ReportKind, report: AdReport, isNew: Boolean): Unit = vm.update {
        copy(
            destination = if (kind == ReportKind.ExhibitG) AdDestination.ExhibitG else AdDestination.WrapReport,
            reports = reports.copy(
                editor = ReportEditorState(
                    kind = kind,
                    report = report,
                    fills = report.fills,
                    header = report.header,
                    name = report.name,
                    dayNumberOverride = report.dayNumber?.toString().orEmpty(),
                    isNew = isNew,
                    clientId = if (isNew) newClientReportId(vm.clock()) else null,
                ),
            ),
        )
    }

    // -- editing -------------------------------------------------------------

    /**
     * One cell filled in.
     *
     * A row that carries a header key is a **named header field**: its value
     * lives on the meta, not in the fills, because Picture Title and Company
     * are constant for the whole shoot and as free rows they were retyped
     * daily. The `date` key is the report's own shoot date, so editing it
     * moves the report rather than writing a decorative string.
     */
    @Suppress("NestedBlockDepth") // Cell → row → header key is the shape of the layout.
    private fun fill(event: ReportEvent.Fill) {
        val editor = vm.current.reports.editor ?: return
        if (event.column == ReportFills.VALUE_COLUMN) {
            val row = editor.report.cells
                .firstOrNull { it.id == event.cellId }
                ?.rows
                ?.firstOrNull { it.id == event.rowId }
            val key = row?.headerKey
            if (key != null) {
                if (key == "date") {
                    val moved = com.zillit.desktop.feature.addashboard.domain.AdDates.parseYmd(event.value)
                    if (moved != null) patchEditor { copy(pendingDate = moved, dirty = true) }
                } else {
                    patchEditor { copy(header = header + (key to event.value), dirty = true) }
                }
                return
            }
        }
        patchEditor {
            copy(
                fills = fills + (ReportFills.key(event.cellId, event.rowId, event.column) to event.value),
                dirty = true,
            )
        }
    }

    /** Adding and removing performer rows is allowed even on a locked layout. */
    private fun addRow(cellId: String) = patchCell(cellId) {
        copy(rows = rows + ReportRow(id = "r${vm.clock()}-${rows.size}", cells = List(columns.size) { "" }))
    }

    private fun removeRow(cellId: String, rowId: String) = patchCell(cellId) {
        copy(rows = rows.filterNot { it.id == rowId })
    }

    /**
     * A new block on the page.
     *
     * Refused on Exhibit G: the sheet is a statutory form, so its columns and
     * header fields are fixed and only its performer rows may change.
     */
    private fun addBlock(type: ReportBlockType) {
        val editor = vm.current.reports.editor ?: return
        if (editor.kind.layoutLocked) {
            vm.fail(str(S.desktop_ad_exhibit_g_layout_locked))
            return
        }
        val cell = ReportCell(
            id = "cell${vm.clock()}",
            type = type,
            title = type.label,
            columns = when (type) {
                ReportBlockType.Section -> listOf(
                    ReportColumn(id = "c-field", label = str(S.desktop_ds_field)),
                    ReportColumn(id = "c-value", label = str(S.desktop_csync_value)),
                )

                ReportBlockType.Notes -> listOf(ReportColumn(id = "c-note", label = str(S.note_label)))
                ReportBlockType.Table -> List(NEW_TABLE_COLUMNS) { index ->
                    ReportColumn(id = "c$index", label = str(S.desktop_column_n, index + 1))
                }
            },
            rows = emptyList(),
        )
        patchEditor {
            copy(
                report = report.copy(
                    pageRows = report.pageRows + ReportPageRow(id = "pr${vm.clock()}", cells = listOf(cell)),
                ),
                dirty = true,
            )
        }
    }

    private fun removeBlock(cellId: String) {
        val editor = vm.current.reports.editor ?: return
        if (editor.kind.layoutLocked) {
            vm.fail(str(S.desktop_ad_exhibit_g_layout_locked))
            return
        }
        patchEditor {
            copy(
                report = report.copy(
                    pageRows = report.pageRows
                        .map { page -> page.copy(cells = page.cells.filterNot { it.id == cellId }) }
                        .filterNot { it.cells.isEmpty() && !it.isBreak },
                ),
                dirty = true,
            )
        }
    }

    private fun addColumn(cellId: String) {
        if (refuseLockedLayout()) return
        patchCell(cellId) {
            copy(
                columns = columns + ReportColumn(
                    id = "c${vm.clock()}",
                    label = str(S.desktop_column_n, columns.size + 1),
                ),
                rows = rows.map { it.copy(cells = it.cells + "") },
            )
        }
    }

    private fun removeColumn(cellId: String, columnId: String) {
        if (refuseLockedLayout()) return
        patchCell(cellId) {
            val index = columns.indexOfFirst { it.id == columnId }
            if (index < 0) {
                this
            } else {
                copy(
                    columns = columns.filterNot { it.id == columnId },
                    rows = rows.map { row ->
                        row.copy(cells = row.cells.filterIndexed { position, _ -> position != index })
                    },
                )
            }
        }
    }

    private fun moveBlock(cellId: String, up: Boolean) {
        if (refuseLockedLayout()) return
        patchEditor {
            val pages = report.pageRows.toMutableList()
            val from = pages.indexOfFirst { page -> page.cells.any { it.id == cellId } }
            val to = if (up) from - 1 else from + 1
            if (from < 0 || to !in pages.indices) {
                this
            } else {
                val moved = pages.removeAt(from)
                pages.add(to, moved)
                copy(report = report.copy(pageRows = pages), dirty = true)
            }
        }
    }

    private fun refuseLockedLayout(): Boolean {
        val locked = vm.current.reports.editor?.kind?.layoutLocked == true
        if (locked) vm.fail(str(S.desktop_ad_exhibit_g_layout_locked))
        return locked
    }

    // -- saving --------------------------------------------------------------

    /**
     * Saves the open report, creating it if it has never been saved.
     *
     * `as_copy` is the one way past the one-report-per-day-and-kind rule, and
     * it is **Save as**: it deliberately creates a second draft.
     */
    private fun save(asCopy: Boolean) = vm.posting {
        cancelAutosave()
        val editor = vm.current.reports.editor ?: return@posting
        if (editor.report.published) {
            vm.fail(str(S.desktop_ad_report_published_read_only))
            return@posting
        }
        patchEditor { copy(saving = true) }
        vm.run {
            val draft = editor.draft(dayNumberOnly = false)
            val result = when {
                editor.isNew || asCopy -> createThenAdopt(editor, draft, asCopy)
                else -> {
                    val saved = vm.repo.saveReport(editor.kind, editor.report.id, draft)
                    if (saved is ZillitResult.Success) reloadOpen(editor) else Unit
                    saved
                }
            }
            patchEditor { copy(saving = false) }
            when (result) {
                is ZillitResult.Failure -> vm.report(result.error)
                else -> Unit
            }
            if (result is ZillitResult.Success<*>) {
                vm.notice(str(S.desktop_ad_report_saved))
                refreshListFor(editor.kind)
            }
        }
    }

    /**
     * The create, and the one answer that is **not** a result.
     *
     * A report already exists for that day and kind: the server returns it
     * flagged `existed`, and the draft is left untouched so the date can be
     * changed. Adopting it here would destroy what was typed.
     */
    private suspend fun createThenAdopt(
        editor: ReportEditorState,
        draft: ReportDraft,
        asCopy: Boolean,
    ): ZillitResult<Unit> {
        val created = vm.repo.createReport(
            kind = editor.kind,
            draft = draft.copy(id = editor.clientId),
            asCopy = asCopy,
        )
        return when (created) {
            is ZillitResult.Failure -> ZillitResult.Failure(created.error)
            is ZillitResult.Success -> {
                if (created.data.existed && !asCopy) {
                    patchEditor { copy(saving = false) }
                    vm.fail(str(S.desktop_ad_report_exists_for_day))
                    ZillitResult.Failure(
                        com.zillit.desktop.core.common.ZillitError.Validation(
                            userMessage = str(S.desktop_ad_report_exists_for_day),
                        ),
                    )
                } else {
                    adopt(editor.kind, created.data, isNew = false)
                    ZillitResult.Success(Unit)
                }
            }
        }
    }

    private suspend fun reloadOpen(editor: ReportEditorState) {
        (vm.repo.report(editor.kind, editor.report.id) as? ZillitResult.Success)?.let { fresh ->
            vm.update {
                copy(
                    reports = reports.copy(
                        editor = reports.editor?.copy(
                            report = fresh.data,
                            dirty = false,
                            // The fills are kept: a reload lands after the
                            // save, and re-seeding them would drop anything
                            // typed since.
                        ),
                    ),
                )
            }
        }
    }

    private fun refreshListFor(kind: ReportKind) {
        if (kind == ReportKind.ExhibitG) loadExhibitG() else loadMerged()
    }

    // -- publishing ----------------------------------------------------------

    /**
     * Exhibit G is checked before the dialog opens.
     *
     * The server refuses the same way, but asking first means the AD sees who
     * is outstanding instead of a refusal they have to interpret.
     */
    private fun openPublish() {
        val editor = vm.current.reports.editor ?: return
        if (editor.kind == ReportKind.ExhibitG) {
            vm.run {
                val fresh = (vm.repo.report(editor.kind, editor.report.id) as? ZillitResult.Success)?.data
                val outstanding = fresh?.unsignedCast.orEmpty()
                if (outstanding.isNotEmpty()) {
                    patchEditor { copy(signaturesPending = outstanding) }
                } else {
                    patchEditor { copy(publishOpen = true) }
                }
            }
            return
        }
        patchEditor { copy(publishOpen = true) }
    }

    /**
     * Publishes: freeze, render the PDF, e-mail the distribution list.
     *
     * The recipients are the server's to resolve from Rates & Config; whatever
     * the client sends is ignored. A never-saved report is created first.
     */
    private fun publish() = vm.posting {
        cancelAutosave()
        val editor = vm.current.reports.editor ?: return@posting
        patchEditor { copy(posting = true) }
        vm.run {
            val target = if (editor.isNew) {
                val created = createThenAdopt(editor, editor.draft(dayNumberOnly = false), asCopy = false)
                if (created is ZillitResult.Failure) {
                    patchEditor { copy(posting = false) }
                    return@run
                }
                vm.current.reports.editor ?: return@run
            } else {
                editor
            }
            val outcome = vm.repo.postReport(target.kind, target.report.id, target.draft(dayNumberOnly = false))
            patchEditor { copy(posting = false) }
            when (outcome) {
                is ZillitResult.Failure -> vm.report(outcome.error)
                is ZillitResult.Success -> when (val answer = outcome.data) {
                    PostOutcome.Published -> {
                        vm.update {
                            copy(
                                reports = reports.copy(editor = null),
                                notice = str(S.desktop_ad_report_published),
                            )
                        }
                        refreshListFor(target.kind)
                        // Publishing marks the day published, which locks it.
                        vm.loadDay()
                    }

                    is PostOutcome.SignaturesPending -> patchEditor {
                        copy(publishOpen = false, signaturesPending = answer.rows)
                    }
                }
            }
        }
    }

    private fun confirmDelete() {
        val target = vm.current.reports.confirmDelete ?: return
        vm.update { copy(reports = reports.copy(confirmDelete = null, busy = true)) }
        vm.run {
            val result = vm.repo.deleteReport(target.kind, target.id)
            vm.update { copy(reports = reports.copy(busy = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_ad_report_deleted))
                    refreshListFor(target.kind)
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    private fun saveAsTemplate() = vm.posting {
        val editor = vm.current.reports.editor ?: return@posting
        val name = editor.templateName.trim().takeIf { it.isNotEmpty() } ?: return@posting
        vm.run {
            when (val result = vm.repo.saveReportAsTemplate(editor.kind, editor.report.id, name)) {
                is ZillitResult.Success -> {
                    patchEditor { copy(saveAsTemplateOpen = false) }
                    vm.notice(str(S.docusign_template_saved))
                    loadTemplatesQuietly()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    /**
     * A published report's PDF.
     *
     * Rendered from the frozen snapshot, which is why it is the server's file
     * rather than anything drawn here.
     */
    private fun downloadPdf() {
        val editor = vm.current.reports.editor ?: return
        vm.run {
            when (val pdf = vm.repo.reportPdf(editor.kind, editor.report.id)) {
                is ZillitResult.Failure -> vm.report(pdf.error)
                is ZillitResult.Success -> when (
                    val saved = vm.machine.saveToDownloads(pdf.data.fileName, pdf.data.bytes)
                ) {
                    is ZillitResult.Success -> {
                        vm.machine.openFile(saved.data)
                        vm.emit(AdEffect.Saved(saved.data))
                    }

                    is ZillitResult.Failure -> vm.report(saved.error)
                }
            }
        }
    }

    // -- Exhibit G signing ---------------------------------------------------

    /**
     * Renders the cast pad, then records the signature.
     *
     * Refused rather than sent as non-image bytes: an Exhibit G signature is
     * what the sheet's publish gate counts, and a corrupt one would pass the
     * count while printing as nothing.
     */
    private fun renderRowSignature(event: ReportEvent.RenderRowSignature) {
        val editor = vm.current.reports.editor ?: return
        if (editor.signStrokes.none { it.size > 1 } && editor.signTypedName.isBlank()) return
        vm.run {
            val bytes = vm.machine.renderSignature(
                strokes = editor.signStrokes,
                typedName = editor.signTypedName,
                widthPx = event.widthPx,
                heightPx = event.heightPx,
            )
            if (bytes == null) {
                vm.fail(str(S.desktop_ad_signature_not_drawn))
                return@run
            }
            on(ReportEvent.SignRow(editor.signTypedName.ifBlank { editor.signingRow?.name.orEmpty() }, bytes))
        }
    }

    /**
     * One cast signature, captured on the AD's own device.
     *
     * Witnessed rather than authenticated — `in_person` — because the person
     * signing is not the person signed in. An unsaved report is saved first,
     * since a signature needs a report id to hang on.
     */
    private fun signRow(event: ReportEvent.SignRow) = vm.posting {
        val editor = vm.current.reports.editor ?: return@posting
        val row = editor.signingRow ?: return@posting
        vm.run {
            val target = if (editor.isNew) {
                val created = createThenAdopt(editor, editor.draft(dayNumberOnly = false), asCopy = false)
                if (created is ZillitResult.Failure) return@run
                vm.current.reports.editor ?: return@run
            } else {
                editor
            }
            val result = vm.repo.signReportRow(
                id = target.report.id,
                personId = row.personId,
                cellId = row.cellId,
                rowId = row.rowId,
                signatureName = event.name,
                signatureBytes = event.bytes,
                inPerson = target.signingInPerson,
            )
            when (result) {
                is ZillitResult.Success -> {
                    patchEditor { copy(signingRow = null) }
                    vm.notice(str(S.desktop_ad_signature_recorded))
                    reloadOpen(target)
                }

                // The dialog stays open so the drawing survives a retry.
                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    /** Voiding the set bumps the round, which kills every outstanding link. */
    private fun resetSignatures() = vm.posting {
        val editor = vm.current.reports.editor ?: return@posting
        patchEditor { copy(confirmReset = false) }
        vm.run {
            when (val result = vm.repo.resetSignatures(editor.report.id)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_ad_signatures_reset))
                    reloadOpen(editor)
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    // -- templates -----------------------------------------------------------

    private fun openTemplate(template: ReportTemplate) = vm.posting {
        vm.run {
            when (val detail = vm.repo.template(template.id)) {
                is ZillitResult.Success -> adopt(
                    detail.data.kind,
                    AdReport(
                        id = NEW_REPORT_ID,
                        kind = detail.data.kind,
                        name = detail.data.name,
                        pageRows = detail.data.pageRows,
                        header = detail.data.header,
                        shootDate = vm.current.shootDate,
                    ),
                    isNew = true,
                )

                is ZillitResult.Failure -> vm.report(detail.error)
            }
        }
    }

    private fun duplicateTemplate(template: ReportTemplate) = vm.posting {
        vm.run {
            val name = str(S.desktop_ad_template_copy_of, template.name)
            when (val result = vm.repo.duplicateTemplate(template.id, name)) {
                is ZillitResult.Success -> {
                    vm.notice(name)
                    loadTemplatesQuietly()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    private fun deleteTemplate(template: ReportTemplate) = vm.posting {
        if (template.isBuiltIn) {
            vm.fail(str(S.desktop_ad_template_built_in))
            return@posting
        }
        vm.run {
            when (val result = vm.repo.deleteTemplate(template.id)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.dd_template_deleted))
                    loadTemplatesQuietly()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    private fun newTemplate() = vm.posting {
        vm.run {
            when (val created = vm.repo.createTemplate(str(S.dd_new_template), ReportKind.AdReport)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_ad_template_created))
                    loadTemplatesQuietly()
                }

                is ZillitResult.Failure -> vm.report(created.error)
            }
        }
    }

    // -- plumbing ------------------------------------------------------------

    private fun patchEditor(change: ReportEditorState.() -> ReportEditorState) {
        vm.update { copy(reports = reports.copy(editor = reports.editor?.change())) }
        if (vm.current.reports.editor?.dirty == true) armAutosave()
    }

    /**
     * Re-arms the autosave: 2 seconds after the last keystroke, and never
     * more than 15 seconds after the first unsaved change.
     *
     * Not run on a posted report — a published report is frozen — and not run
     * for a viewer who may not post, whose edits would be refused anyway.
     */
    private fun armAutosave() {
        val editor = vm.current.reports.editor ?: return
        if (editor.report.published || !vm.current.viewer.canPost) return
        val firstDirty = dirtySince ?: vm.clock().also { dirtySince = it }
        autosave?.cancel()
        autosave = vm.run {
            val waited = vm.clock() - firstDirty
            val idle = (MAX_AUTOSAVE_WAIT_MS - waited).coerceIn(0L, IDLE_AUTOSAVE_MS)
            kotlinx.coroutines.delay(idle)
            persistAutosave()
        }
    }

    /**
     * The autosave itself.
     *
     * A never-saved draft is **created** here, carrying the same client-minted
     * id the explicit Save would, so the two cannot race into two reports.
     */
    private suspend fun persistAutosave() {
        val editor = vm.current.reports.editor ?: return
        if (!editor.dirty || editor.saving || editor.posting) return
        val draft = editor.draft(dayNumberOnly = false)
        if (editor.isNew) {
            val created = vm.repo.createReport(
                kind = editor.kind,
                draft = draft.copy(id = editor.clientId),
                notify = false,
            )
            // An `existed` answer is the day already having a report of this
            // kind; the draft is left alone so the date can be changed, and
            // the autosave simply stops trying.
            if (created is ZillitResult.Success && !created.data.existed) {
                adopt(editor.kind, created.data, isNew = false)
                dirtySince = null
            }
            return
        }
        val saved = vm.repo.saveReport(editor.kind, editor.report.id, draft, notify = false)
        if (saved is ZillitResult.Success) {
            dirtySince = null
            vm.update { copy(reports = reports.copy(editor = reports.editor?.copy(dirty = false))) }
        }
    }

    /** An explicit save, a publish or a close makes the pending autosave moot. */
    private fun cancelAutosave() {
        autosave?.cancel()
        autosave = null
        dirtySince = null
    }

    private fun patchCell(cellId: String, change: ReportCell.() -> ReportCell) = patchEditor {
        copy(
            report = report.copy(
                pageRows = report.pageRows.map { page ->
                    page.copy(cells = page.cells.map { if (it.id == cellId) it.change() else it })
                },
            ),
            dirty = true,
        )
    }

    private fun patchColumn(cellId: String, columnId: String, change: ReportColumn.() -> ReportColumn) {
        if (refuseLockedLayout()) return
        patchCell(cellId) {
            copy(columns = columns.map { if (it.id == columnId) it.change() else it })
        }
    }

    private companion object {
        const val NEW_REPORT_ID = "new-report"

        /** The web's `useDealAutosave` window: 2 s idle, 15 s at the latest. */
        const val IDLE_AUTOSAVE_MS = 2_000L
        const val MAX_AUTOSAVE_WAIT_MS = 15_000L

        /** A fresh table starts with three columns, as the web's does. */
        const val NEW_TABLE_COLUMNS = 3
    }
}

/**
 * The payload a save sends, including what it writes back onto the day.
 *
 * Every save carries the day patch: the report's crew call, unit wrap, scenes
 * and day type land on the `AdShootDay`, and timecards read those values. A
 * mis-bound row therefore reaches payroll, which is why
 * [com.zillit.desktop.feature.addashboard.domain.DayFieldBinding] guards the
 * set-call labels before anything else.
 */
internal fun ReportEditorState.draft(dayNumberOnly: Boolean): ReportDraft = ReportDraft(
    id = if (isNew) clientId else report.id,
    name = name,
    shootDate = effectiveShootDate,
    pageRows = report.pageRows,
    fills = fills,
    header = header,
    dayPatch = buildDayPatch(
        pageRows = report.pageRows,
        fills = fills,
        shootDate = effectiveShootDate,
        dayNumberOverride = dayNumberOverride.toIntOrNull(),
        dayNumberOnly = dayNumberOnly,
    ),
)

/**
 * A client-minted, ObjectId-shaped id.
 *
 * Stamped on the create so it is idempotent: a retry after a timeout adopts
 * the same report rather than leaving two drafts for one day.
 */
internal fun newClientReportId(now: Long): String {
    val hex = now.toString(HEX_RADIX)
    val tail = (now * HASH_MULTIPLIER).toString(HEX_RADIX)
    return (hex + tail).padEnd(OBJECT_ID_LENGTH, '0').take(OBJECT_ID_LENGTH)
}

private const val OBJECT_ID_LENGTH = 24
private const val HEX_RADIX = 16
private const val HASH_MULTIPLIER = 2_654_435_761L
