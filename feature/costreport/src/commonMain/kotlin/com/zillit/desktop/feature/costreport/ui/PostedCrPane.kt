// One composable per piece of the posted timeline and the snapshot page.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "LongParameterList")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.CrFormat
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.PostKind
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.kpis

private val WEEKLY_INK = Color(0xFF0C7A6E)
private val DAILY_INK = Color(0xFFB95B00)
private val LOCK_INK = Color(0xFFDC2626)
private val INITIAL_INK = Color(0xFF1D4ED8)
private const val DARK_INK_ALPHA = 0.9f

private fun PostKind.colour(): Color = when (this) {
    PostKind.Weekly -> WEEKLY_INK
    PostKind.Daily -> DAILY_INK
    PostKind.Lock -> LOCK_INK
    PostKind.Initial -> INITIAL_INK
}

/** The Posted CRs tab of the crew tool: the timeline card. */
@Composable
internal fun PostedCrPane(
    state: CostReportUiState,
    resolveUser: (String) -> String?,
    onEvent: (CostReportEvent) -> Unit,
) {
    val posted = state.posted
    Column(Modifier.fillMaxSize()) {
        posted.error?.let { message ->
            ZillitNotice(text = message, tone = StatusTone.Rejected, modifier = Modifier.padding(bottom = 12.dp))
        }
        CrHistoryCard(
            rows = posted.rows,
            loading = posted.loading || !posted.loadedOnce,
            filter = posted.filter,
            symbolFor = { state.symbolFor(it.currency ?: state.current.currencyCode) },
            resolveUser = resolveUser,
            onFilter = { onEvent(CostReportEvent.SelectPostedFilter(it)) },
            onRefresh = { onEvent(CostReportEvent.RefreshPosted) },
            onOpen = { onEvent(CostReportEvent.OpenSnapshot(it)) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * "Posted Cost Reports" — the web's `LCRHistoryPane`, shared by the crew
 * tool's Posted CRs tab and the worksheet's CR History.
 *
 * Every post is a stop on a timeline: its kind's dot (a tick for a week-end,
 * a lock for a period lock), the name and kind chip, the note, when and by
 * whom, and the Total Variance it landed at with the movement since the post
 * before it. Locks fold under Wk-end, since a lock always closes a week.
 */
@Composable
internal fun CrHistoryCard(
    rows: List<SnapshotHeader>,
    loading: Boolean,
    filter: PostedFilter,
    symbolFor: (SnapshotHeader) -> String,
    resolveUser: (String) -> String?,
    onFilter: (PostedFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SnapshotHeader) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val shown = remember(rows, filter) {
        rows.mapIndexed { index, header ->
            val variance = header.totalVariance ?: 0.0
            val older = rows.getOrNull(index + 1)?.let { it.totalVariance ?: 0.0 }
            PostedRow(header, variance, older?.let { variance - it })
        }.filter { filter.admits(it.header.kind) }
    }
    Column(
        modifier = modifier
            .background(colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    "Posted Cost Reports",
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                val last = rows.firstOrNull()?.postedAtMs?.let { " · last ${CrDates.dateTime(it)}" }.orEmpty()
                ZillitText(
                    text = if (loading) "Loading…" else "${rows.size} post${if (rows.size == 1) "" else "s"}$last",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PostedFilter.entries.forEach { option ->
                    Pill(option.label, active = filter == option) { onFilter(option) }
                }
                Pill(
                    if (loading) "Refreshing…" else "↻ Refresh",
                    active = false,
                    enabled = !loading,
                    onClick = onRefresh,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        when {
            loading && rows.isEmpty() -> Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(3) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
            }
            shown.isEmpty() -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                ZillitText(
                    "No posts in this filter.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 8.dp)) {
                itemsIndexed(shown, key = { _, row -> row.header.id }) { index, row ->
                    HistoryItem(
                        row = row,
                        first = index == 0,
                        last = index == shown.lastIndex,
                        symbol = symbolFor(row.header),
                        who = row.header.postedBy?.let { resolveUser(it) ?: it },
                        onOpen = { onOpen(row.header) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = label,
        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        color = if (active) CrPalette.ctaInk else colors.textSecondary,
        modifier = Modifier
            .background(if (active) CrPalette.cta else colors.surfaceSunken, RoundedCornerShape(999.dp))
            .border(1.dp, if (active) CrPalette.cta else colors.border, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun HistoryItem(
    row: PostedRow,
    first: Boolean,
    last: Boolean,
    symbol: String,
    who: String?,
    onOpen: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val kind = row.header.kind
    val tint = kind.colour()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val line = colors.borderStrong
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The dot, with the timeline threading through it.
        Box(
            modifier = Modifier
                .width(36.dp)
                .heightIn(min = 44.dp)
                .drawBehind {
                    val x = size.width / 2
                    val stroke = 1.dp.toPx()
                    val pad = 14.dp.toPx()
                    val dotBottom = 22.dp.toPx()
                    if (!first) drawRect(line, Offset(x - stroke / 2, -pad), Size(stroke, pad))
                    if (!last) {
                        drawRect(line, Offset(x - stroke / 2, dotBottom), Size(stroke, size.height - dotBottom + pad))
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(colors.surface, CircleShape)
                    .border(2.dp, tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when (kind) {
                    PostKind.Weekly -> ZillitIcon(ZillitIcons.Check, tint = tint, size = 11.dp)
                    PostKind.Daily -> Box(Modifier.size(6.dp).background(tint, CircleShape))
                    PostKind.Initial -> ZillitText(
                        "i",
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = tint,
                    )
                    PostKind.Lock -> ZillitIcon(ZillitIcons.Shield, tint = tint, size = 11.dp)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = row.header.title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                    maxLines = 1,
                )
                ZillitText(
                    text = kind.chip.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    ),
                    color = tint,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.08f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (row.header.postNote.isNotBlank()) {
                ZillitText(row.header.postNote, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            }
            ZillitText(
                text = listOfNotNull(CrDates.dateTime(row.header.postedAtMs).ifBlank { null }, who).joinToString(" · "),
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Column(Modifier.width(180.dp), horizontalAlignment = Alignment.End) {
            val variance = row.variance ?: 0.0
            ZillitText(
                text = CrFormat.variance(variance, symbol),
                style = ZillitTheme.typography.numeric.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = when {
                    variance < 0 -> CrPalette.over
                    variance > 0 -> CrPalette.under
                    else -> colors.textPrimary
                },
                maxLines = 1,
            )
            row.delta?.takeIf { it != 0.0 }?.let { delta ->
                ZillitText(
                    text = CrFormat.delta(delta, symbol).replace("vs last", "VS LAST"),
                    style = ZillitTheme.typography.numeric.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                    color = if (delta > 0) CrPalette.under else CrPalette.over,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.width(100.dp), contentAlignment = Alignment.TopEnd) {
            ZillitButton(text = "View", onClick = onOpen, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
    }
}

// Snapshot detail ---------------------------------------------------------------

/** What the snapshot page asks of its host. */
internal data class SnapshotCallbacks(
    val onBack: () -> Unit,
    val onExport: (ExportFormat) -> Unit,
    val onSearch: (String) -> Unit,
    val onToggleSection: (String) -> Unit,
    val onToggleHeader: (String) -> Unit,
    val onDismissError: () -> Unit,
)

/**
 * One posted snapshot — the web's `CostReportSnapshotDetailPage`: breadcrumb,
 * title and note, the meta list, the KPI strip, the frozen table and the
 * export menu.
 */
@Composable
internal fun SnapshotPage(
    view: SnapshotView,
    backLabel: String,
    resolveUser: (String) -> String?,
    callbacks: SnapshotCallbacks,
) {
    val colors = ZillitTheme.colors
    val header = view.shownHeader
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ArrowLeft,
                contentDescription = "Back to $backLabel",
                onClick = callbacks.onBack,
            )
            ZillitText(
                "REPORTS",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = CrPalette.cta,
            )
            ZillitText("/", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitText(
                text = backLabel,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                modifier = Modifier.clickable(onClick = callbacks.onBack),
            )
            if (header.name.isNotBlank() || header.reference.isNotBlank()) {
                ZillitText("/", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                ZillitText(
                    header.title,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            SnapshotTitle(header, Modifier.weight(1f))
            ExportMenu(exporting = view.exporting, enabled = view.detail != null, onExport = callbacks.onExport)
        }
        view.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                action = {
                    ZillitButton(text = "Dismiss", onClick = callbacks.onDismissError,
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                },
            )
        }
        SnapshotMeta(header, resolveUser)
        val detail = view.detail
        if (detail == null) {
            if (view.loading) {
                Column(
                    Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSpinner()
                    ZillitText("Loading snapshot…", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            return@Column
        }
        val kpis = remember(detail) { detail.kpis() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiTile("Total Budget", kpis.budget, view.symbol, null, Modifier.weight(1f))
            KpiTile("Actuals to Date", kpis.actualsToDate, view.symbol, Color(0xFF0C6A3F), Modifier.weight(1f))
            KpiTile("Total Commitments", kpis.commitments, view.symbol, Color(0xFF1D4ED8), Modifier.weight(1f))
            KpiTile("Est. Final Cost", kpis.estimatedFinalCost, view.symbol, Color(0xFF8A5B00), Modifier.weight(1f))
            val negative = kpis.postedVariance < 0
            KpiTile(
                label = "Posted Variance",
                value = kpis.postedVariance,
                symbol = view.symbol,
                ink = if (negative) Color(0xFFB22A2A) else Color(0xFF0C6A3F),
                modifier = Modifier.weight(1f),
                highlight = if (negative) Color(0xFFB22A2A) else Color(0xFF0C6A3F),
            )
        }
        SnapshotDetailTable(view, callbacks, Modifier.weight(1f))
        ZillitText(
            text = "Snapshot id: ${header.id}" +
                header.status.takeIf { it.isNotBlank() }?.let { " · status $it" }.orEmpty() +
                header.reference.takeIf { it.isNotBlank() }?.let { " · ref $it" }.orEmpty(),
            style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

/** A snapshot KPI: the label small and quiet, the figure large in its column's colour. */
@Composable
private fun KpiTile(
    label: String,
    value: Double,
    symbol: String,
    ink: Color?,
    modifier: Modifier,
    highlight: Color? = null,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(highlight?.copy(alpha = 0.06f) ?: colors.surface)
            .border(1.dp, highlight?.copy(alpha = 0.25f) ?: colors.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            color = colors.textMuted,
            maxLines = 1,
        )
        // A nine-figure budget in a narrow window shrinks rather than ending in "…".
        FitFigureText(
            text = CrFormat.money(value, symbol),
            style = ZillitTheme.typography.numeric.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            ),
            color = ink?.let { if (colors.isDark) it.copy(alpha = DARK_INK_ALPHA) else it } ?: colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExportMenu(exporting: ExportFormat?, enabled: Boolean, onExport: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = "Export",
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            trailingIcon = ZillitIcons.ChevronDown,
            enabled = enabled && exporting == null,
            loading = exporting != null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { ZillitText("Export as ${format.label}", style = ZillitTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        onExport(format)
                    },
                )
            }
        }
    }
}

@Composable
private fun SnapshotTitle(header: SnapshotHeader, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val lock = header.kind == PostKind.Lock
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lock) ZillitIcon(ZillitIcons.Shield, tint = CrPalette.LOCK_RED, size = 20.dp)
            ZillitText(
                text = if (lock) "Period Lock —" else "${header.cadence?.label ?: SnapshotCadence.Adhoc.label} CR —",
                style = ZillitTheme.typography.titleLarge.copy(
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = if (lock) CrPalette.LOCK_RED else colors.textMuted,
                maxLines = 1,
            )
            ZillitText(
                text = header.name.ifBlank { header.reference.ifBlank { "Cost Report Snapshot" } },
                style = ZillitTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp),
                maxLines = 2,
            )
        }
        if (header.postNote.isNotBlank()) {
            ZillitText(
                text = "“${header.postNote}”",
                style = ZillitTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = colors.textMuted,
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
        MetaItem("Cadence") {
            val (label, tone) = when {
                kind == PostKind.Lock -> "Period Lock" to StatusTone.Rejected
                header.cadence == SnapshotCadence.Daily -> "Daily" to StatusTone.Pending
                header.cadence == SnapshotCadence.Weekly -> "Weekly" to StatusTone.Progress
                else -> "Ad-hoc" to StatusTone.Neutral
            }
            ZillitStatusPill(label = label, tone = tone, dot = true)
        }
        MetaItem("Period") {
            ZillitText(text = CrDates.range(header.periodStartMs, header.periodEndMs),
                style = ZillitTheme.typography.numeric, maxLines = 1)
        }
        MetaItem("Posted") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(text = CrDates.dateTime(header.postedAtMs).ifBlank { "—" },
                    style = ZillitTheme.typography.numeric, maxLines = 1)
                ZillitStatusPill(label = "Posted to ledger", tone = StatusTone.Done, dot = true)
            }
        }
        MetaItem("By") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitAvatar(name = who, size = 24.dp)
                ZillitText(
                    text = who,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
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
