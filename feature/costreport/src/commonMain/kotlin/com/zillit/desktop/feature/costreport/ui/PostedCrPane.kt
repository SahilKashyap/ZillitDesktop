// One composable per piece of the Posted CRs tab and the snapshot page.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.FigureMode
import com.zillit.desktop.feature.costreport.domain.PostKind
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.figuresFor
import com.zillit.desktop.feature.costreport.domain.flattenRows
import com.zillit.desktop.feature.costreport.domain.isEmptyBesidesTotal
import com.zillit.desktop.feature.costreport.domain.kpis

private val DOT_SIZE = 12.dp
private val VARIANCE_WIDTH = 190.dp
private val SEARCH_WIDTH = 260.dp
private val WK_COLOUR = Color(0xFF0C7A6E)
private val DAILY_COLOUR = Color(0xFFB95B00)
private val LOCK_COLOUR = Color(0xFFDC2626)
private val INITIAL_COLOUR = Color(0xFF1D4ED8)

private fun PostKind.colour(): Color = when (this) {
    PostKind.Weekly -> WK_COLOUR
    PostKind.Daily -> DAILY_COLOUR
    PostKind.Lock -> LOCK_COLOUR
    PostKind.Initial -> INITIAL_COLOUR
}

private fun PostKind.tone(): StatusTone = when (this) {
    PostKind.Weekly -> StatusTone.Done
    PostKind.Daily -> StatusTone.Pending
    PostKind.Lock -> StatusTone.Rejected
    PostKind.Initial -> StatusTone.Progress
}

/** The Posted Cost Reports card: pills, refresh, and the timeline. */
@Composable
internal fun PostedCrPane(
    state: CostReportUiState,
    resolveUser: (String) -> String?,
    onEvent: (CostReportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val posted = state.posted
    val meta = when {
        posted.loading && !posted.loadedOnce -> "Loading…"
        else -> "${posted.rows.size} posts" + (posted.lastPostedMs?.let { " · last ${CrDates.dateTime(it)}" } ?: "")
    }
    ZillitSectionCard(
        title = "Posted Cost Reports",
        meta = meta,
        padded = false,
        modifier = Modifier.fillMaxSize(),
        action = {
            PostedFilter.entries.forEach { filter ->
                ZillitChoiceChip(
                    label = filter.label,
                    selected = posted.filter == filter,
                    onClick = { onEvent(CostReportEvent.SelectPostedFilter(filter)) },
                )
            }
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(CostReportEvent.RefreshPosted) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
                loading = posted.loading,
            )
        },
    ) {
        posted.error?.let { message ->
            ZillitNotice(text = message, tone = StatusTone.Rejected,
                modifier = Modifier.padding(ZillitTheme.spacing.md))
        }
        val rows = posted.shown
        when {
            posted.loading && posted.rows.isEmpty() -> Column(
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                repeat(3) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
            }
            rows.isEmpty() && posted.loadedOnce -> ZillitEmptyState(title = "No posts in this filter.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.header.id }) { row ->
                    val symbol = state.symbolFor(row.header.currency ?: state.current.currencyCode)
                    PostedRowItem(row, symbol, resolveUser) { onEvent(CostReportEvent.OpenSnapshot(row.header)) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                }
            }
        }
    }
}

@Composable
private fun PostedRowItem(row: PostedRow, symbol: String, resolveUser: (String) -> String?, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val header = row.header
    val kind = header.kind
    val who = header.postedBy?.let { resolveUser(it) ?: it }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(Modifier.size(DOT_SIZE).background(kind.colour(), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = header.title, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                ZillitStatusPill(label = kind.chip, tone = kind.tone())
            }
            if (header.postNote.isNotBlank()) {
                ZillitText(text = header.postNote, style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary, maxLines = 2)
            }
            ZillitText(
                text = listOfNotNull(CrDates.dateTime(header.postedAtMs).takeIf { it.isNotBlank() }, who)
                    .joinToString(" · "),
                style = ZillitTheme.typography.numeric.copy(fontSize = ZillitTheme.typography.labelSmall.fontSize),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Column(Modifier.width(VARIANCE_WIDTH), horizontalAlignment = Alignment.End) {
            val variance = row.variance
            ZillitText(
                text = CrFormat.variance(variance, symbol),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    variance == null || variance == 0.0 -> colors.textPrimary
                    variance < 0 -> colors.danger
                    else -> colors.success
                },
                maxLines = 1,
            )
            row.delta?.let { delta ->
                ZillitText(
                    text = CrFormat.delta(delta, symbol),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (delta >= 0) colors.success else colors.danger,
                    maxLines = 1,
                )
            }
        }
        ZillitButton(text = "View", onClick = onOpen, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
    }
}

// Snapshot detail ---------------------------------------------------------------

/** One posted snapshot: breadcrumb, meta, KPI strip, grouped table, export. */
@Composable
internal fun SnapshotPage(
    state: CostReportUiState,
    view: SnapshotView,
    resolveUser: (String) -> String?,
    onEvent: (CostReportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val header = view.shownHeader
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = "Back to Posted CRs",
                onClick = { onEvent(CostReportEvent.CloseSnapshot) })
            ZillitText(text = "REPORTS / Posted CRs / ${header.title}", style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            ExportFormat.entries.forEach { format ->
                ZillitButton(
                    text = "Export ${format.label}",
                    onClick = { onEvent(CostReportEvent.Export(format)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Download,
                    enabled = view.exporting == null && view.detail != null,
                    loading = view.exporting == format,
                )
            }
        }
        SnapshotTitle(header)
        view.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                action = {
                    ZillitButton(text = "Dismiss", onClick = { onEvent(CostReportEvent.DismissError) },
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                },
            )
        }
        SnapshotMeta(header, resolveUser)
        val detail = view.detail
        if (detail == null) {
            if (view.loading) {
                Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl), contentAlignment = Alignment.Center) {
                    ZillitSpinner()
                }
            }
            return@Column
        }
        val kpis = remember(detail) { detail.kpis() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitStatTile(label = "Total Budget", value = CrFormat.money(kpis.budget, view.symbol),
                modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Actuals to Date", value = CrFormat.money(kpis.actualsToDate, view.symbol),
                tone = StatusTone.Done, modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Total Commitments", value = CrFormat.money(kpis.commitments, view.symbol),
                tone = StatusTone.Progress, modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Est. Final Cost", value = CrFormat.money(kpis.estimatedFinalCost, view.symbol),
                tone = StatusTone.Pending, modifier = Modifier.weight(1f))
            ZillitStatTile(label = "Posted Variance", value = CrFormat.money(kpis.postedVariance, view.symbol),
                tone = if (kpis.postedVariance < 0) StatusTone.Rejected else StatusTone.Done,
                modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            ZillitSearchField(
                value = view.query,
                onValueChange = { onEvent(CostReportEvent.SearchSnapshot(it)) },
                placeholder = "Find code or name",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
        }
        SnapshotTable(state, view, onEvent, Modifier.weight(1f))
        ZillitText(
            text = "Snapshot id: ${header.id} · status ${header.status.ifBlank { "—" }} · " +
                "ref ${header.reference.ifBlank { "—" }}",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun SnapshotTitle(header: SnapshotHeader) {
    val colors = ZillitTheme.colors
    val title = when {
        header.kind == PostKind.Lock -> "Period Lock — ${header.name}"
        else -> "${header.cadence?.label ?: SnapshotCadence.Adhoc.label} CR — ${
            header.name.ifBlank { header.reference.ifBlank { "Cost Report Snapshot" } }
        }"
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleLarge,
            color = if (header.kind == PostKind.Lock) colors.danger else colors.textPrimary,
            maxLines = 2,
        )
        if (header.postNote.isNotBlank()) {
            ZillitText(
                text = "“${header.postNote}”",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.textSecondary,
                maxLines = 3,
            )
        }
    }
}

/** Cadence · Period · Posted · By. */
@Composable
private fun SnapshotMeta(header: SnapshotHeader, resolveUser: (String) -> String?) {
    val colors = ZillitTheme.colors
    val kind = header.kind
    val cadenceLabel = when {
        kind == PostKind.Lock -> "Period Lock"
        else -> header.cadence?.label ?: SnapshotCadence.Adhoc.label
    }
    val who = header.postedBy?.let { resolveUser(it) ?: it } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaItem("Cadence") { ZillitStatusPill(label = cadenceLabel, tone = kind.tone(), dot = true) }
        MetaItem("Period") {
            ZillitText(text = CrDates.range(header.periodStartMs, header.periodEndMs),
                style = ZillitTheme.typography.numeric, maxLines = 1)
        }
        MetaItem("Posted") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(text = CrDates.dateTime(header.postedAtMs).ifBlank { "—" },
                    style = ZillitTheme.typography.numeric, maxLines = 1)
                if (header.status.equals("published", ignoreCase = true) || header.publishedAtMs != null) {
                    ZillitStatusPill(label = "Posted to ledger", tone = StatusTone.Done)
                }
            }
        }
        MetaItem("By") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitAvatar(name = who, size = 24.dp)
                ZillitText(text = who, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MetaItem(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = label.uppercase(), style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted)
        content()
    }
}

@Composable
private fun SnapshotTable(
    state: CostReportUiState,
    view: SnapshotView,
    onEvent: (CostReportEvent) -> Unit,
    modifier: Modifier,
) {
    val detail = view.detail ?: return
    if (detail.lines.isEmpty()) {
        ZillitEmptyState(title = "This snapshot doesn't have any line data.", modifier = modifier)
        return
    }
    val rows = remember(view.sections, view.query, view.toggles) {
        flattenRows(
            sections = view.sections,
            figuresOf = figuresFor(FigureMode.Snapshot, emptyMap(), hasPrior = false),
            query = view.query,
            toggles = view.toggles,
        )
    }
    if (rows.isEmptyBesidesTotal() && view.query.isNotBlank()) {
        ZillitEmptyState(title = "No codes or descriptions match “${view.query.trim()}”.", modifier = modifier)
        return
    }
    val actions = remember(onEvent) {
        GridActions(
            onSection = { onEvent(CostReportEvent.ToggleSnapshotSection(it)) },
            onHeader = { onEvent(CostReportEvent.ToggleSnapshotHeader(it)) },
            onNominal = { onEvent(CostReportEvent.ToggleSnapshotNominal(it)) },
            onLedger = null,
        )
    }
    WorksheetGrid(
        rows = rows,
        mode = FigureMode.Snapshot,
        symbol = view.symbol,
        projectName = state.projectName,
        actions = actions,
        modifier = modifier,
    )
}
