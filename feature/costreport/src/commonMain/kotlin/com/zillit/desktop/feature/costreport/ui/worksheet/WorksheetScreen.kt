// The worksheet's shell: header, both panes' filter rows, controls and grids.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui.worksheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrTableSpec
import com.zillit.desktop.feature.costreport.domain.CrViewMode
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.SortDirection
import com.zillit.desktop.feature.costreport.domain.buildWorksheetTable
import com.zillit.desktop.feature.costreport.domain.currentWeek
import com.zillit.desktop.feature.costreport.ui.CrActionGroup
import com.zillit.desktop.feature.costreport.ui.CrFieldCell
import com.zillit.desktop.feature.costreport.ui.CrGridActions
import com.zillit.desktop.feature.costreport.ui.CrGroupButton
import com.zillit.desktop.feature.costreport.ui.CrGroupLabel
import com.zillit.desktop.feature.costreport.ui.CrHeaderBar
import com.zillit.desktop.feature.costreport.ui.CrHeaderTab
import com.zillit.desktop.feature.costreport.ui.CrLoaderOverlay
import com.zillit.desktop.feature.costreport.ui.CrPalette
import com.zillit.desktop.feature.costreport.ui.CrPeriodStepper
import com.zillit.desktop.feature.costreport.ui.CrProgressCard
import com.zillit.desktop.feature.costreport.ui.CrSegmented
import com.zillit.desktop.feature.costreport.ui.CrWorksheetGrid
import com.zillit.desktop.feature.costreport.ui.LedgerDialog
import com.zillit.desktop.feature.costreport.ui.SnapshotCallbacks
import com.zillit.desktop.feature.costreport.ui.SnapshotPage

/**
 * The accountant's Cost Report — the web's `CostReportWorksheetModule`
 * screen: the header bar with every page-level action, one filter row per
 * pane, the worksheet's controls and editable grid, the Live CR, and the
 * dialogs and cards that hang off them.
 */
@Composable
fun WorksheetScreen(
    state: WorksheetUiState,
    onEvent: (WorksheetEvent) -> Unit,
    nowMillis: () -> Long,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        val snapshot = state.snapshot
        if (snapshot != null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                SnapshotPage(
                    view = snapshot,
                    backLabel = "CR History",
                    resolveUser = resolveUser,
                    callbacks = SnapshotCallbacks(
                        onBack = { onEvent(WorksheetEvent.CloseSnapshot) },
                        onExport = { onEvent(WorksheetEvent.ExportSnapshot(it)) },
                        onSearch = { onEvent(WorksheetEvent.SearchSnapshot(it)) },
                        onToggleSection = { onEvent(WorksheetEvent.ToggleSnapshotSection(it)) },
                        onToggleHeader = { onEvent(WorksheetEvent.ToggleSnapshotHeader(it)) },
                        onDismissError = { onEvent(WorksheetEvent.CloseSnapshot) },
                    ),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(state, onEvent)
                when {
                    state.reference.error != null -> ZillitNotice(
                        text = state.reference.error,
                        tone = StatusTone.Rejected,
                        modifier = Modifier.padding(24.dp),
                        action = {
                            ZillitButton(text = "Retry", onClick = { onEvent(WorksheetEvent.Retry) },
                                variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                        },
                    )
                    state.pane == WorksheetPane.Worksheet -> WorksheetPaneView(
                        state,
                        onEvent,
                        nowMillis,
                        Modifier.weight(1f),
                    )
                    else -> LiveCrPaneView(state, onEvent, nowMillis, resolveUser, Modifier.weight(1f))
                }
            }
            // The entry loader, until the worksheet's first load settles.
            CrLoaderOverlay(
                visible = !state.ws.loadedOnce && state.reference.error == null,
                message = "Loading cost report…",
            )
        }
        WorksheetDialogs(state, onEvent, resolveUser)
        state.ledger?.let { LedgerDialog(it) { onEvent(WorksheetEvent.CloseLedger) } }
        val progress = state.progress
        CrProgressCard(
            title = progress?.title,
            detail = progress?.detail.orEmpty(),
            loading = progress?.status == ProgressStatus.Loading,
            success = progress?.status == ProgressStatus.Success,
            onClose = { onEvent(WorksheetEvent.DismissProgress) },
        )
        if (progress == null) {
            CrProgressCard(
                title = when {
                    state.exportDone -> "File Generated successfully"
                    state.exporting -> "Generating Cost Report"
                    else -> null
                },
                detail = if (state.exportDone) "File will be auto-downloaded" else "Auto-download on generation",
                loading = state.exporting && !state.exportDone,
                success = state.exportDone,
                onClose = { onEvent(WorksheetEvent.DismissExport) },
            )
        }
    }
}

// -- header ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(state: WorksheetUiState, onEvent: (WorksheetEvent) -> Unit) {
    // The "N over" count holds its last settled value while a compute is in
    // flight, so it never flashes a count from the old currency's figures.
    var heldOver by remember { mutableIntStateOf(0) }
    if (!state.ws.loading && !state.ws.computing) heldOver = state.overages.size
    val over = if (state.ws.loading || state.ws.computing) heldOver else state.overages.size
    val onWorksheet = state.pane == WorksheetPane.Worksheet
    val week = state.ws.week
    CrHeaderBar(
        tabs = listOf(
            CrHeaderTab(WorksheetPane.Worksheet.id, WorksheetPane.Worksheet.label, ZillitIcons.Grid),
            CrHeaderTab(
                WorksheetPane.Live.id,
                WorksheetPane.Live.label,
                ZillitIcons.Monitor,
                badge = over.takeIf { it > 0 }?.let { "$it over" },
            ),
        ),
        activeId = state.pane.id,
        onTab = { id -> onEvent(WorksheetEvent.SelectPane(WorksheetPane.entries.first { it.id == id })) },
        onBack = { onEvent(WorksheetEvent.Back) },
        onAnalytics = { onEvent(WorksheetEvent.OpenAnalytics) },
    ) {
        if (state.sourceStale) {
            Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .background(CrPalette.FORECAST.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .border(1.dp, CrPalette.FORECAST.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .clickable { onEvent(WorksheetEvent.RefreshSource) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(ZillitIcons.Reload, tint = CrPalette.FORECAST, size = 12.dp)
                    ZillitText(
                        "Source data updated · Refresh",
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                        color = CrPalette.FORECAST,
                    )
                }
            }
        }
        if (onWorksheet) {
            CrActionGroup {
                val canSave = state.ws.pending.versionId != null
                CrGroupButton(
                    label = if (state.saving) "Saving…" else "Save",
                    onClick = { onEvent(WorksheetEvent.Save) },
                    icon = ZillitIcons.Check,
                    enabled = canSave,
                    busy = state.saving,
                    tooltip = if (canSave) {
                        "Update the selected version with current ETC"
                    } else {
                        "Pick a saved version to enable Save"
                    },
                )
                CrGroupButton(
                    "Save Version",
                    onClick = { onEvent(WorksheetEvent.OpenSaveVersion) },
                    icon = ZillitIcons.Add,
                )
                CrGroupButton("History", onClick = { onEvent(WorksheetEvent.OpenHistory) }, icon = ZillitIcons.Clock)
            }
        }
        CrActionGroup {
            CrGroupButton(
                label = "Overages",
                onClick = { onEvent(WorksheetEvent.OpenOverages) },
                icon = ZillitIcons.Warning,
                badge = over.takeIf { it > 0 }?.toString(),
            )
            if (onWorksheet) {
                val label = week?.let { "Wk ${it.number}" } ?: "week"
                CrGroupButton(
                    label = if (state.isLocked) "$label locked" else "Lock $label",
                    onClick = { onEvent(WorksheetEvent.OpenLock) },
                    icon = ZillitIcons.Shield,
                    enabled = !state.isLocked && week != null,
                    busy = state.locking,
                    tooltip = if (state.isLocked) {
                        "${week?.label ?: "Week"} already locked"
                    } else {
                        "Lock ${week?.label ?: "this week"}"
                    },
                )
                CrGroupButton(
                    label = if (state.posting != null) "Publishing…" else "Publish",
                    onClick = { onEvent(WorksheetEvent.OpenPublish) },
                    enabled = state.posting == null && !state.reference.metaMissing,
                    busy = state.posting != null,
                    primary = true,
                    tooltip = if (state.posting != null) {
                        "A publish is in progress…"
                    } else {
                        "Publish a cost report snapshot"
                    },
                )
            }
            CrGroupButton("Export", onClick = { onEvent(WorksheetEvent.OpenExport) }, icon = ZillitIcons.Download)
        }
    }
}

// -- filter row -------------------------------------------------------------------------------

private val ALL_COMPANIES = CrCompany(id = "", name = "All Companies — Consolidated")
private val NO_BUDGET = BudgetVersion(id = "", version = "", label = "— Select budget —", status = "")
private val LATEST = EtcVersion(id = "", label = "— Latest (unsaved) —")

/** Company · Budget · Currency · Version · Period, and Compute while they differ from what is shown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaneFilterRow(
    state: WorksheetUiState,
    pane: WorksheetPane,
    onEvent: (WorksheetEvent) -> Unit,
    nowMillis: () -> Long,
) {
    val colors = ZillitTheme.colors
    val shown = if (pane == WorksheetPane.Worksheet) state.ws else state.live
    val reference = state.reference
    val disabled = reference.metaMissing || !reference.loaded
    val picked = shown.pending
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .alpha(if (disabled) DISABLED_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CrFieldCell("Company", Modifier.widthIn(min = 190.dp).weight(1.1f)) {
            ZillitSelect(
                value = reference.companies.firstOrNull { it.id == picked.companyId } ?: ALL_COMPANIES,
                options = listOf(ALL_COMPANIES) + reference.companies,
                onSelect = { onEvent(WorksheetEvent.SetCompany(pane, it.id.ifBlank { null })) },
                label = { it.name.ifBlank { it.id } },
                enabled = !disabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CrFieldCell("Budget", Modifier.widthIn(min = 220.dp).weight(1.4f)) {
            val selected = reference.budget(picked.budgetKey)
            ZillitSelect(
                value = selected ?: NO_BUDGET,
                options = (if (selected == null) listOf(NO_BUDGET) else emptyList()) + reference.budgets,
                onSelect = { onEvent(WorksheetEvent.SetBudget(pane, it.version.ifBlank { null })) },
                label = { it.display },
                enabled = !disabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CrFieldCell("Currency", Modifier.widthIn(min = 120.dp).weight(0.7f)) {
            val choices = reference.currencyChoices
            if (choices.isNotEmpty()) {
                ZillitSelect(
                    value = choices.firstOrNull { it.code.equals(
                        picked.currency,
                        ignoreCase = true,
                    ) } ?: choices.first(),
                    options = choices,
                    onSelect = { onEvent(WorksheetEvent.SetCurrency(pane, it.code)) },
                    label = { currencyOption(it, state) },
                    enabled = !disabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        CrFieldCell("Version", Modifier.widthIn(min = 200.dp).weight(1.1f)) {
            ZillitSelect(
                value = shown.versions.firstOrNull { it.id == picked.versionId } ?: LATEST,
                options = listOf(LATEST) + shown.versions,
                onSelect = { onEvent(WorksheetEvent.SetVersion(pane, it.id.ifBlank { null })) },
                label = { if (it.id.isBlank()) it.label else it.optionLabel },
                enabled = !disabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val week = shown.week
        if (week != null) {
            val now = currentWeek(nowMillis())
            CrFieldCell("Period") {
                CrPeriodStepper(
                    range = week.range,
                    canGoNext = week.startMs < now.startMs,
                    showGoCurrent = !week.contains(nowMillis()),
                    onPrevious = { onEvent(WorksheetEvent.StepWeek(pane, forward = false)) },
                    onNext = { onEvent(WorksheetEvent.StepWeek(pane, forward = true)) },
                    onGoCurrent = { onEvent(WorksheetEvent.GoToCurrentWeek(pane)) },
                    enabled = !disabled,
                )
            }
        }
        if (shown.dirty || shown.computing) {
            Box(Modifier.height(58.dp), contentAlignment = Alignment.BottomStart) {
                ZillitButton(
                    text = if (shown.computing) "Computing…" else "Compute",
                    onClick = { onEvent(WorksheetEvent.Compute(pane)) },
                    enabled = !disabled && !shown.computing,
                    loading = shown.computing,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

private fun currencyOption(currency: CrCurrency, state: WorksheetUiState): String {
    val symbol = currency.symbol.ifBlank { state.reference.symbolFor(currency.code) }
    return if (symbol.isBlank() || symbol.equals(
        currency.code,
        ignoreCase = true,
    )) currency.code else "$symbol ${currency.code}"
}

// -- the worksheet pane --------------------------------------------------------------------------

@Composable
private fun WorksheetPaneView(
    state: WorksheetUiState,
    onEvent: (WorksheetEvent) -> Unit,
    nowMillis: () -> Long,
    modifier: Modifier,
) {
    val ws = state.ws
    val view = state.wsView
    Column(modifier.fillMaxWidth()) {
        PaneFilterRow(state, WorksheetPane.Worksheet, onEvent, nowMillis)
        ControlRow(state, onEvent)
        view.sort.column?.let { column ->
            SortChip(column.label, view.sort.direction) { onEvent(WorksheetEvent.ClearSort) }
        }
        ws.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                action = {
                    ZillitButton(text = "Retry", onClick = { onEvent(WorksheetEvent.Retry) },
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                },
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.reference.metaMissing) {
                Unavailable("Cost report is unavailable", state.reference.metaMissingMessage)
            } else if (ws.loadedOnce) {
                val table = remember(ws.sections, ws.overrides, ws.baseline, view) {
                    buildWorksheetTable(
                        ws.sections,
                        ws.forecast,
                        CrTableSpec(view.search, view.toggles, view.viewMode, view.filter, view.sort),
                    )
                }
                val actions = remember(onEvent) {
                    CrGridActions(
                        onSection = { onEvent(WorksheetEvent.ToggleSection(WorksheetPane.Worksheet, it)) },
                        onHeader = { onEvent(WorksheetEvent.ToggleHeader(WorksheetPane.Worksheet, it)) },
                        onNominal = { onEvent(WorksheetEvent.ToggleNominal(WorksheetPane.Worksheet, it)) },
                        onLedger = { nominal, column -> onEvent(
                            WorksheetEvent.OpenLedger(WorksheetPane.Worksheet, nominal, column),
                        ) },
                        onSort = { onEvent(WorksheetEvent.SortBy(it)) },
                        onCommit = { nominal, column, value -> onEvent(
                            WorksheetEvent.CommitCell(nominal, column, value),
                        ) },
                        onClearFlat = { onEvent(WorksheetEvent.ClearFlat) },
                    )
                }
                CrWorksheetGrid(
                    table = table,
                    symbol = state.symbolFor(ws),
                    decimals = view.decimals,
                    projectName = state.projectName,
                    sort = view.sort,
                    locked = state.isLocked,
                    actions = actions,
                )
            }
            CrLoaderOverlay(visible = ws.loadedOnce && ws.loading && !ws.silent, message = ws.loaderMessage)
        }
    }
}

/** Figures · Expand · Filter · Find, and ATD as a share of budget at the far end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(state: WorksheetUiState, onEvent: (WorksheetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val view = state.wsView
    val ws = state.ws
    val disabled = state.reference.metaMissing
    val symbol = state.symbolFor(ws)
    val totals = remember(ws.sections, ws.overrides, ws.baseline) { ws.forecast.grandTotal(ws.sections) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .alpha(if (disabled) DISABLED_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrGroupLabel("Figures")
                CrSegmented(
                    options = listOf(0 to "${symbol}0", 1 to "${symbol}0.0", 2 to "${symbol}0.00"),
                    selected = view.decimals,
                    onSelect = { onEvent(WorksheetEvent.SetDecimals(it)) },
                    mono = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrGroupLabel("Expand")
                CrSegmented(
                    options = CrViewMode.entries.map { it to it.label },
                    selected = view.viewMode,
                    onSelect = { onEvent(WorksheetEvent.SetViewMode(it)) },
                )
                val allOpen = view.toggles.allHeadersOpen(ws.sections)
                ZillitButton(
                    text = if (allOpen) "Collapse all" else "Expand all",
                    onClick = { onEvent(WorksheetEvent.ToggleExpandAll) },
                    variant = if (allOpen) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrGroupLabel("Filter")
                CrSegmented(
                    options = CrLineFilter.entries.map { it to it.label },
                    selected = view.filter,
                    onSelect = { onEvent(WorksheetEvent.SetFilter(it)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrGroupLabel("Find")
                ZillitSearchField(
                    value = view.search,
                    onValueChange = { onEvent(WorksheetEvent.Search(WorksheetPane.Worksheet, it)) },
                    placeholder = "Find code or name",
                    modifier = Modifier.width(208.dp),
                )
            }
        }
        AtdGauge(CrFormat.percentOfBudget(totals.atd, totals.bud))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

/** "ATD 4.7% of budget" with its bar, red once spend passes the budget. */
@Composable
private fun AtdGauge(percent: String) {
    val colors = ZillitTheme.colors
    val raw = percent.replace(",", "").toDoubleOrNull() ?: 0.0
    Row(
        modifier = Modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(
            text = "ATD $percent% of budget",
            style = ZillitTheme.typography.numeric.copy(fontSize = 12.5.sp),
            color = colors.textSecondary,
            maxLines = 1,
        )
        Box(
            Modifier
                .width(56.dp)
                .height(6.dp)
                .background(colors.surfaceSunken, RoundedCornerShape(999.dp))
                .border(1.dp, colors.border, RoundedCornerShape(999.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = (raw / PERCENT).coerceIn(0.0, 1.0).toFloat())
                    .height(6.dp)
                    .background(
                        if (raw > PERCENT) CrPalette.LOCK_RED else CrPalette.ACTUALS,
                        RoundedCornerShape(999.dp),
                    ),
            )
        }
    }
}

@Composable
private fun SortChip(label: String, direction: SortDirection, onClear: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(CrPalette.CTA.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                .border(1.dp, CrPalette.CTA.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = "⇅ $label ${if (direction == SortDirection.Descending) "▼" else "▲"}",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = CrPalette.cta,
            )
            ZillitIcon(
                ZillitIcons.Close,
                tint = CrPalette.cta,
                size = 10.dp,
                contentDescription = "Clear sort",
                modifier = Modifier.clickable(onClick = onClear),
            )
        }
    }
}

@Composable
internal fun Unavailable(title: String, message: String) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
    }
}

private const val DISABLED_ALPHA = 0.5f
private const val PERCENT = 100.0
