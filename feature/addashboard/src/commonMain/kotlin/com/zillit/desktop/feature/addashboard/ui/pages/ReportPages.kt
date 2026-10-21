package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.ReportBlockType
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportListRules
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.ReportValueType
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.ReportEditorState
import com.zillit.desktop.feature.addashboard.ui.ReportEvent

/**
 * Wrap Report: the merged Wrap and AD lists, which is the dashboard's landing
 * page.
 *
 * Both kinds on one page, each row tagged by the **endpoint that returned it** —
 * never one list filtered, because the AD endpoint also returns untyped legacy
 * reports and a row's own type cannot be trusted.
 */
@Composable
internal fun ReportsHubPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val rows = ReportListRules.sort(
        ReportListRules.filter(
            rows = state.reports.merged,
            kind = state.reports.kindFilter,
            status = state.reports.statusFilter,
            query = state.reports.search,
            canPost = state.viewer.canPost,
            day = state.reports.dayLink,
        ),
    )
    AdPage {
        ReportFilters(state, onEvent, kinds = ReportKind.merged)
        state.reports.dayLink?.let { day ->
            ZillitNotice(
                text = str(S.desktop_ad_showing_one_day, AdDates.formatDayFull(day)),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Calendar,
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitButton(
                text = str(S.desktop_ad_show_all_days),
                onClick = { onEvent(ReportEvent.ClearDayLink) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ReportTable(state, rows, onEvent)
        TemplateStrip(state, onEvent)
    }

    TemplatePickerDialog(state, onEvent)
    DeleteReportDialog(state, onEvent)
}

/**
 * SAG-AFTRA Exhibit G: the performer time sheet cast sign.
 *
 * Its own page and its own endpoint, because its layout is statutory: columns
 * and header fields are fixed and only performer rows may be added or removed.
 */
@Composable
internal fun ExhibitGPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val rows = ReportListRules.sort(
        ReportListRules.filter(
            rows = state.reports.exhibitRows,
            status = state.reports.statusFilter,
            query = state.reports.search,
            canPost = state.viewer.canPost,
        ),
    )
    AdPage {
        ReportFilters(state, onEvent, kinds = emptyList())
        ReportTable(state, rows, onEvent)
        if (state.viewer.canPost) {
            ZillitButton(
                text = str(S.desktop_ad_create_exhibit_g),
                onClick = {
                    val template = state.reports.templates.firstOrNull { it.kind == ReportKind.ExhibitG }
                    if (template != null) {
                        onEvent(ReportEvent.StartFromTemplate(template, state.shootDate))
                    } else {
                        onEvent(ReportEvent.OpenTemplatePicker)
                    }
                },
                leadingIcon = ZillitIcons.Add,
            )
        }
    }

    TemplatePickerDialog(state, onEvent)
    DeleteReportDialog(state, onEvent)
}

@Composable
private fun ReportFilters(state: AdUiState, onEvent: (AdEvent) -> Unit, kinds: List<ReportKind>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.reports.search,
            onValueChange = { onEvent(ReportEvent.Search(it)) },
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        if (kinds.isNotEmpty()) {
            ZillitSelect(
                value = state.reports.kindFilter,
                options = listOf(null) + kinds,
                onSelect = { onEvent(ReportEvent.FilterKind(it)) },
                label = { it?.label ?: str(S.desktop_ad_all_kinds) },
                modifier = Modifier.width(FILTER_WIDTH),
            )
        }
        ZillitSelect(
            value = state.reports.statusFilter,
            options = listOf(null) + ReportStatus.entries,
            onSelect = { onEvent(ReportEvent.FilterStatus(it)) },
            label = { it?.label ?: str(S.desktop_all_statuses) },
            modifier = Modifier.width(FILTER_WIDTH),
        )
        if (state.viewer.canPost) {
            ZillitButton(
                text = str(S.desktop_ad_new_report),
                onClick = { onEvent(ReportEvent.OpenTemplatePicker) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
            // The web reaches its Templates page by typing the URL. There is
            // no URL bar here, so without this the page would be unreachable.
            ZillitButton(
                text = str(S.templates),
                onClick = { onEvent(AdEvent.Open(AdDestination.Templates)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Grid,
            )
        }
    }
}

@Composable
private fun ReportTable(state: AdUiState, rows: List<ReportSummary>, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.reports),
        meta = str(S.desktop_ad_n_reports, rows.size),
        padded = false,
    ) {
        ZillitDataTable(
            rows = rows,
            columns = listOf(
                textColumn(str(S.bs_day), width = ColumnWidth.Fixed(DAY_WIDTH)) { row ->
                    row.dayNumber?.let { str(S.desktop_ad_sd_n, it) }
                        ?: AdDates.formatDayShort(row.shootDate)
                },
                textColumn(str(S.date)) { AdDates.formatDayShort(it.shootDate) },
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
                textColumn(str(S.desktop_csync_field_kind)) { it.kind.label },
                textColumn(str(S.txt_template)) { it.templateName },
                TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
                    ZillitStatusPill(label = row.status.label, tone = row.status.tone())
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitButton(
                            text = if (row.status.published) str(S.view) else str(S.edit),
                            onClick = { onEvent(ReportEvent.OpenReport(row)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                        // A published report is not deletable from here, and a
                        // non-posting viewer never sees the control.
                        if (state.viewer.canPost && !row.status.published) {
                            ZillitIconButton(
                                icon = ZillitIcons.Trash,
                                contentDescription = str(S.delete),
                                onClick = { onEvent(ReportEvent.AskDelete(row)) },
                            )
                        }
                    }
                },
            ),
            key = { it.id },
            onRowClick = { onEvent(ReportEvent.OpenReport(it)) },
            emptyTitle = str(S.desktop_ad_no_reports_yet),
            emptyMessage = str(S.desktop_ad_no_reports_yet_message),
            loading = state.reports.loading,
            virtualised = false,
        )
    }
}

/** The built-in template of each merged kind, as a quick start. */
@Composable
private fun TemplateStrip(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    if (!state.viewer.canPost) return
    val built = ReportListRules.systemTemplates(state.reports.templates)
    if (built.isEmpty()) return
    ZillitSectionCard(title = str(S.templates)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            built.forEach { template ->
                ZillitButton(
                    text = "${template.kind.label} · ${template.name}",
                    onClick = { onEvent(ReportEvent.StartFromTemplate(template, state.shootDate)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

/**
 * Templates: the reusable layouts.
 *
 * No row of its own in the sidebar, as on the web — it is reachable, but it is
 * not where an AD works, and a non-posting viewer cannot open it at all.
 */
@Composable
internal fun TemplatesPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        ZillitSectionCard(
            title = str(S.templates),
            meta = str(S.desktop_po_template_count_other, state.reports.templates.size),
            padded = false,
            action = {
                ZillitButton(
                    text = str(S.dd_new_template),
                    onClick = { onEvent(ReportEvent.NewTemplate) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            ZillitDataTable(
                rows = state.reports.templates,
                columns = listOf(
                    textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
                    textColumn(str(S.desktop_csync_field_kind)) { it.kind.label },
                    textColumn(str(S.desktop_ad_origin)) { it.originLabel },
                    textColumn(str(S.desktop_ad_blocks)) { it.blockCount.toString() },
                    textColumn(str(S.desktop_card_rows_header)) { it.rowCount.toString() },
                    TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(TEMPLATE_ACTION_WIDTH)) { template ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                            ZillitButton(
                                text = str(S.dm_template_use),
                                onClick = { onEvent(ReportEvent.OpenTemplate(template)) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.Copy,
                                contentDescription = str(S.action_duplicate),
                                onClick = { onEvent(ReportEvent.DuplicateTemplate(template)) },
                            )
                            // A built-in template belongs to the server and
                            // cannot be deleted by a project.
                            if (!template.isBuiltIn) {
                                ZillitIconButton(
                                    icon = ZillitIcons.Trash,
                                    contentDescription = str(S.delete),
                                    onClick = { onEvent(ReportEvent.DeleteTemplate(template)) },
                                )
                            }
                        }
                    },
                ),
                key = { it.id },
                emptyTitle = str(S.desktop_ds_no_templates),
                loading = state.reports.loading,
                virtualised = false,
            )
        }
    }
}

/**
 * The report editor.
 *
 * The layout is a list of blocks — a section of field/value rows, a table, or
 * notes — and each filled cell is keyed `cellId.rowId.col`. A row carrying a
 * **header key** is different: its value lives on the report's meta, because
 * Picture Title and Company are constant for the whole shoot.
 *
 * Every save also writes the report's call, wrap, scenes and day type back
 * onto the shoot day, and timecards read those values — which is why a
 * mis-bound row reaches payroll and why the day-field binding guards the
 * set-call labels before anything else.
 *
 * The on-screen paper is a structured editor rather than a replica of the
 * printed sheet: the PDF is rendered and stored server-side from the frozen
 * snapshot, and a published report is shown as that PDF.
 */
@Composable
internal fun ReportEditorPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.reports.editor ?: return
    AdPage {
        EditorHeader(state, editor, onEvent)

        if (editor.signaturesPending.isNotEmpty()) {
            SignaturesPending(editor, onEvent)
        }

        if (editor.report.published) {
            ZillitNotice(
                text = str(S.desktop_ad_report_published_read_only),
                tone = StatusTone.Done,
                icon = ZillitIcons.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        editor.report.pageRows.forEachIndexed { index, page ->
            if (page.isBreak) {
                ZillitDivider()
                ZillitSectionLabel(text = str(S.desktop_ad_page_break))
                return@forEachIndexed
            }
            page.cells.forEach { cell ->
                EditorBlock(state, editor, cell, index, onEvent)
            }
        }

        if (!editor.report.published && !editor.kind.layoutLocked && state.viewer.canPost) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ReportBlockType.entries.forEach { type ->
                    ZillitButton(
                        text = str(S.desktop_ad_add_block, type.label),
                        onClick = { onEvent(ReportEvent.AddBlock(type)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            }
        }
    }

    PublishReportDialog(state, onEvent)
    SaveAsTemplateDialog(state, onEvent)
    SignRowDialog(state, onEvent)
    ResetSignaturesDialog(state, onEvent)
}

@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun EditorHeader(state: AdUiState, editor: ReportEditorState, onEvent: (AdEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.back),
                onClick = { onEvent(ReportEvent.CloseEditor) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(text = editor.kind.label, style = ZillitTheme.typography.titleMedium)
            ZillitStatusPill(label = editor.report.status.label, tone = editor.report.status.tone())
            if (editor.dirty) {
                ZillitStatusPill(label = str(S.cs_exit_title), tone = StatusTone.Pending)
            }
        }

        if (!editor.report.published && state.viewer.canPost) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = editor.name,
                    onValueChange = { onEvent(ReportEvent.Name(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = str(S.name),
                )
                ZillitTextField(
                    value = AdDates.formatYmd(editor.effectiveShootDate),
                    onValueChange = { typed ->
                        AdDates.parseYmd(typed)?.let { onEvent(ReportEvent.PickDate(it)) }
                    },
                    modifier = Modifier.width(DATE_WIDTH),
                    label = str(S.date),
                    // Changing the date **moves** the report to that day; only
                    // the day number crosses with it.
                    helperText = str(S.desktop_ad_changing_date_moves_report),
                )
                ZillitTextField(
                    value = editor.dayNumberOverride,
                    onValueChange = { onEvent(ReportEvent.DayNumber(it)) },
                    modifier = Modifier.width(DAY_NUMBER_WIDTH),
                    label = str(S.desktop_ad_day_number),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitButton(
                    text = str(S.save),
                    onClick = { onEvent(ReportEvent.Save) },
                    enabled = editor.dirty || editor.isNew,
                    loading = editor.saving,
                    leadingIcon = ZillitIcons.Save,
                )
                ZillitButton(
                    text = str(S.desktop_ad_save_as),
                    onClick = { onEvent(ReportEvent.SaveAsCopy) },
                    variant = ButtonVariant.Secondary,
                    // The one way past the one-report-per-day rule: it
                    // deliberately creates a second draft.
                    loading = editor.saving,
                )
                ZillitButton(
                    text = str(S.publish),
                    onClick = { onEvent(ReportEvent.OpenPublish) },
                    variant = ButtonVariant.Secondary,
                    loading = editor.posting,
                    leadingIcon = ZillitIcons.Send,
                )
                ZillitButton(
                    text = str(S.save_as_template),
                    onClick = { onEvent(ReportEvent.OpenSaveAsTemplate) },
                    variant = ButtonVariant.Tertiary,
                )
                if (editor.kind == ReportKind.ExhibitG) {
                    ZillitButton(
                        text = str(S.desktop_ad_reset_signatures),
                        onClick = { onEvent(ReportEvent.AskResetSignatures) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
            }
        }

        if (editor.report.published) {
            ZillitButton(
                text = str(S.ah_download_pdf),
                onClick = { onEvent(ReportEvent.DownloadPdf) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Download,
            )
        }
    }
}

/**
 * Who still owes a signature.
 *
 * Shown as a list rather than a red error: the sheet is telling the AD which
 * performers to chase, which is work rather than a fault.
 */
@Composable
private fun SignaturesPending(editor: ReportEditorState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_ad_signatures_pending),
        meta = str(S.desktop_ad_n_unsigned, editor.signaturesPending.size),
        action = {
            ZillitButton(
                text = str(S.sync_action_dismiss),
                onClick = { onEvent(ReportEvent.DismissSignaturesPending) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            editor.signaturesPending.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = row.name.ifBlank { row.personId },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        style = ZillitTheme.typography.bodySmall,
                    )
                    ZillitButton(
                        text = str(S.desktop_ad_sign_here),
                        onClick = { onEvent(ReportEvent.StartSigning(row)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Signature,
                    )
                }
            }
        }
    }
}

/** One block of the report: a section, a table, or notes. */
@Composable
private fun EditorBlock(
    state: AdUiState,
    editor: ReportEditorState,
    cell: ReportCell,
    pageIndex: Int,
    onEvent: (AdEvent) -> Unit,
) {
    val editable = !editor.report.published && state.viewer.canPost
    ZillitSectionCard(
        title = if (cell.hideTitle) null else cell.title,
        meta = if (cell.isLiveTable) str(S.desktop_ad_filled_from_the_day) else null,
        padded = cell.type == ReportBlockType.Section,
        action = {
            if (!editable) return@ZillitSectionCard
            // Adding and removing rows is allowed even on Exhibit G: only its
            // columns and header fields are fixed.
            ZillitIconButton(
                icon = ZillitIcons.Add,
                contentDescription = str(S.desktop_ad_add_row),
                onClick = { onEvent(ReportEvent.AddRow(cell.id)) },
            )
            if (!editor.kind.layoutLocked) {
                ZillitIconButton(
                    icon = ZillitIcons.ChevronUp,
                    contentDescription = str(S.dd_cd_move_up),
                    onClick = { onEvent(ReportEvent.MoveBlock(cell.id, up = true)) },
                    enabled = pageIndex > 0,
                )
                ZillitIconButton(
                    icon = ZillitIcons.ChevronDown,
                    contentDescription = str(S.dd_cd_move_down),
                    onClick = { onEvent(ReportEvent.MoveBlock(cell.id, up = false)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.remove),
                    onClick = { onEvent(ReportEvent.RemoveBlock(cell.id)) },
                )
            }
        },
    ) {
        when (cell.type) {
            ReportBlockType.Section -> SectionBlock(editor, cell, editable, onEvent)
            ReportBlockType.Notes -> SectionBlock(editor, cell, editable, onEvent)
            ReportBlockType.Table -> TableBlock(state, editor, cell, editable, onEvent)
        }
    }
}

/** A section: one label and one value per row. */
@Composable
private fun SectionBlock(
    editor: ReportEditorState,
    cell: ReportCell,
    editable: Boolean,
    onEvent: (AdEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        cell.orderedRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = row.label.ifBlank { EM_DASH },
                    modifier = Modifier.width(FIELD_LABEL_WIDTH),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitTextField(
                    value = editor.valueOf(cell, row),
                    onValueChange = { typed ->
                        onEvent(ReportEvent.Fill(cell.id, row.id, ReportFills.VALUE_COLUMN, typed))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    enabled = editable,
                    placeholder = row.valueType.placeholder(),
                    singleLine = row.valueType != ReportValueType.Text,
                )
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(ReportEvent.RemoveRow(cell.id, row.id)) },
                    )
                }
            }
        }
    }
}

/** A table: a grid of cells, with a signature column when Exhibit G says so. */
@Composable
private fun TableBlock(
    state: AdUiState,
    editor: ReportEditorState,
    cell: ReportCell,
    editable: Boolean,
    onEvent: (AdEvent) -> Unit,
) {
    val signatureColumn = cell.signatureColumn
    ZillitDataTable(
        rows = cell.orderedRows,
        columns = buildList {
            cell.columns.forEachIndexed { index, column ->
                add(
                    TableColumn<ReportRow>(header = column.label) { row ->
                        if (index == signatureColumn) {
                            SignatureCell(state, editor, cell, row, onEvent)
                        } else {
                            ZillitTextField(
                                value = editor.valueOf(cell, row, index),
                                onValueChange = { typed ->
                                    onEvent(ReportEvent.Fill(cell.id, row.id, index, typed))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = editable && index != signatureColumn,
                                placeholder = column.type.placeholder(),
                            )
                        }
                    },
                )
            }
            if (editable) {
                add(
                    TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ROW_ACTION_WIDTH)) { row ->
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.remove),
                            onClick = { onEvent(ReportEvent.RemoveRow(cell.id, row.id)) },
                        )
                    },
                )
            }
        },
        key = { it.id },
        emptyTitle = str(S.desktop_ad_no_rows_yet),
        virtualised = false,
    )
}

/**
 * The performer's signature cell.
 *
 * Signed on the AD's own device and recorded as **witnessed** — the person
 * signing is not the person signed in — so a saved signature is proof the
 * performer was there, not proof of who was logged on.
 */
@Composable
private fun SignatureCell(
    state: AdUiState,
    editor: ReportEditorState,
    cell: ReportCell,
    row: ReportRow,
    onEvent: (AdEvent) -> Unit,
) {
    val signature = editor.report.signatures.firstOrNull {
        it.cellId == cell.id && it.rowId == row.id && it.signed
    }
    if (signature != null) {
        Column {
            ZillitStatusPill(label = str(S.signed), tone = StatusTone.Done)
            ZillitText(
                text = AdDates.formatInstant(signature.signedAt),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        return
    }
    val person = row.cells.firstOrNull()?.trim().orEmpty()
    if (person.isEmpty() || editor.report.published || !state.viewer.canPost) {
        ZillitText(
            text = EM_DASH,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    ZillitButton(
        text = str(S.desktop_ad_sign_here),
        onClick = {
            onEvent(
                ReportEvent.StartSigning(
                    com.zillit.desktop.feature.addashboard.domain.SigningRow(
                        cellId = cell.id,
                        rowId = row.id,
                        personId = person,
                        name = person,
                    ),
                ),
            )
        },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Signature,
    )
}

/**
 * What a cell shows: the manual fill first, then the row's own stored value.
 *
 * A header-keyed row resolves from the report's meta instead, and the one
 * header key with no slot — `date` — resolves from the report's own shoot
 * date, so there is one date and nothing to drift.
 */
internal fun ReportEditorState.valueOf(
    cell: ReportCell,
    row: ReportRow,
    column: Int = ReportFills.VALUE_COLUMN,
): String {
    if (column == ReportFills.VALUE_COLUMN) {
        row.headerKey?.let { key ->
            if (key == "date") return AdDates.formatYmd(effectiveShootDate)
            header[key]?.let { return it }
        }
    }
    fills[ReportFills.key(cell.id, row.id, column)]?.let { return it }
    return row.cells.getOrNull(column).orEmpty()
}

private fun ReportValueType.placeholder(): String? = when (this) {
    ReportValueType.Time -> "07:30"
    ReportValueType.Date -> "2026-01-31"
    ReportValueType.Number -> "0"
    ReportValueType.YesNo -> str(S.yes)
    else -> null
}

/** The templates a picker offers for a kind. */
internal fun List<ReportTemplate>.forKind(kind: ReportKind?): List<ReportTemplate> =
    if (kind == null) this else filter { it.kind == kind }

private val SEARCH_WIDTH = 240.dp
private val FILTER_WIDTH = 170.dp
private val DAY_WIDTH = 90.dp
private val STATUS_WIDTH = 120.dp
private val ACTION_WIDTH = 120.dp
private val TEMPLATE_ACTION_WIDTH = 160.dp
private val DATE_WIDTH = 170.dp
private val DAY_NUMBER_WIDTH = 120.dp
private val FIELD_LABEL_WIDTH = 220.dp
private val ROW_ACTION_WIDTH = 70.dp
