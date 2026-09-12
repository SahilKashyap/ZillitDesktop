package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.ActivityRow
import com.zillit.desktop.feature.invoices.domain.CostReportImpact
import com.zillit.desktop.feature.invoices.domain.DuplicateFlag
import com.zillit.desktop.feature.invoices.domain.InvoiceOverview
import com.zillit.desktop.feature.invoices.domain.PendingAction
import com.zillit.desktop.feature.invoices.domain.PipelineStage
import com.zillit.desktop.feature.invoices.domain.VendorAlert
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The accountant's dashboard — the web's `OverviewPage`.
 *
 * Every figure on it is the server's, formatted by the server; this screen
 * arranges them and turns each one into a way into the queue behind it.
 */
@Composable
internal fun OverviewPage(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val overview = state.overview
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (state.overviewLoading && overview == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = LOADING_PAD), contentAlignment = Alignment.Center) {
                ZillitSpinner()
            }
            return@ZillitScrollColumn
        }
        val data = overview ?: InvoiceOverview()
        StatTiles(data, onEvent)
        AlertBanners(data, onEvent)
        PipelinePanel(data.pipeline, onEvent)
        CostReportPanel(state, data.costReport)
        if (data.vendorAlerts.isNotEmpty()) VendorAlertsPanel(data.vendorAlerts, onEvent)
        DuplicatesPanel(state, onEvent)
        PendingAndRecent(data, onEvent)
    }
}

// -- the six tiles ------------------------------------------------------------

@Composable
private fun StatTiles(data: InvoiceOverview, onEvent: (InvoicesEvent) -> Unit) {
    val stats = data.stats
    val open = { page: AccountantPage -> { onEvent(InvoicesEvent.SelectPage(page)) } }
    StatGrid(
        columns = STAT_COLUMNS,
        tiles = listOf(
            {
                CountTile(
                    "Total Invoices", stats.totalInvoices, "This period",
                    ZillitIcons.File, null, open(AccountantPage.Register),
                )
            },
            {
                CountTile(
                    "Awaiting Match", stats.awaitingMatch, stats.awaitingMatchAmount,
                    ZillitIcons.Receipt, StatusTone.Pending, open(AccountantPage.Matching),
                )
            },
            {
                CountTile(
                    "In Approval", stats.inApproval, "${stats.overdueCount} overdue",
                    ZillitIcons.Shield, StatusTone.Progress, open(AccountantPage.ApprovalQueue),
                )
            },
            {
                CountTile(
                    "Ready to Pay", stats.readyToPay, stats.readyToPayAmount,
                    ZillitIcons.Wallet, StatusTone.Ready, open(AccountantPage.Payments),
                )
            },
            {
                MoneyTile(
                    "Due This Week", stats.dueThisWeek, "${stats.dueThisWeekCount} invoices",
                    ZillitIcons.Clock, StatusTone.Pending,
                )
            },
            { MoneyTile("Total AP", stats.totalAP, "${stats.vendorCount} vendors", ZillitIcons.Bank, null) },
        ),
    )
}

/** A tile whose figure is a count, with the queue it opens behind it. */
@Composable
private fun RowScope.CountTile(
    label: String,
    count: Int,
    sub: String,
    icon: ImageVector,
    tone: StatusTone?,
    onClick: () -> Unit,
) {
    ZillitStatTile(
        label = label,
        value = count.toString(),
        sub = sub.ifBlank { null },
        tone = tone,
        icon = icon,
        modifier = statTile,
        onClick = onClick,
    )
}

/** A tile whose figure is money the server already formatted. */
@Composable
private fun RowScope.MoneyTile(label: String, value: String, sub: String, icon: ImageVector, tone: StatusTone?) {
    ZillitStatTile(
        label = label,
        value = value.ifBlank { "—" },
        sub = sub,
        tone = tone,
        icon = icon,
        modifier = statTile,
    )
}

/** The two banners the web raises above the pipeline when there is work waiting. */
@Composable
private fun ColumnScope.AlertBanners(data: InvoiceOverview, onEvent: (InvoicesEvent) -> Unit) {
    val stats = data.stats
    if (stats.awaitingMatch <= 0 && stats.overdueCount <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (stats.awaitingMatch > 0) {
            Banner(
                title = "${stats.awaitingMatch} invoices need pre-approval",
                detail = "${stats.awaitingMatchAmount} unmatched".trim(),
                action = "Match",
                onAction = { onEvent(InvoicesEvent.SelectPage(AccountantPage.Matching)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (stats.overdueCount > 0) {
            Banner(
                title = "${stats.overdueAmount} overdue".trim(),
                detail = "${stats.overdueCount} invoices past terms",
                action = "Pay Now",
                onAction = { onEvent(InvoicesEvent.SelectPage(AccountantPage.Payments)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Banner(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The wash and its ink are the theme's matched pair. A hand-picked ink
    // over a themed wash reads in one theme and vanishes in the other — this
    // banner's title was white on light amber in dark mode before.
    val ink = ZillitTheme.colors.warning
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.warningSoft)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = ink,
            )
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ink.copy(alpha = DETAIL_ALPHA),
            )
        }
        ZillitButton(text = action, onClick = onAction, size = ButtonSize.Small)
    }
}

// -- the pipeline -------------------------------------------------------------

/** The five shapes the web offers this panel, in its order. */
private enum class PipeShape(val label: String) {
    Cards("Cards"),
    Rail("Rail"),
    Flow("Flow"),
    Funnel("Funnel"),
    Mono("Mono"),
}

/**
 * Where every unpaid invoice is standing — the web's `InvoicePipeline`.
 *
 * Five arrangements of one set of counts behind a chip row, defaulting to
 * Flow as the web does. Flow is the only proportional one: its three rows
 * share one set of widths, which is what makes a bar line up with its own
 * label. The others give every stage the same width, which reads better when
 * one stage holds most of the production's invoices.
 */
@Composable
private fun PipelinePanel(stages: List<PipelineStage>, onEvent: (InvoicesEvent) -> Unit) {
    var shape by remember { mutableStateOf(PipeShape.Flow) }
    ZillitSectionCard(
        title = "Invoice Pipeline",
        icon = ZillitIcons.Ledger,
        action = {
            PipeShape.entries.forEach { option ->
                ZillitChoiceChip(
                    label = option.label,
                    selected = option == shape,
                    onClick = { shape = option },
                )
            }
        },
    ) {
        if (stages.isEmpty()) {
            Hint("Nothing in the pipeline.")
            return@ZillitSectionCard
        }
        val open = { stage: PipelineStage ->
            { onEvent(InvoicesEvent.SelectPage(AccountantPage.forPipelineStage(stage.id))) }
        }
        when (shape) {
            PipeShape.Flow -> PipeFlow(stages, open)
            PipeShape.Cards -> PipeCards(stages, open)
            PipeShape.Rail -> PipeRail(stages, open)
            PipeShape.Funnel -> PipeFunnel(stages, open)
            PipeShape.Mono -> PipeMono(stages, open)
        }
    }
}

/** The web's default: icon, proportional bar and count, all on one set of widths. */
@Composable
private fun ColumnScope.PipeFlow(stages: List<PipelineStage>, open: (PipelineStage) -> () -> Unit) {
    PipelineRow(stages) { stage, modifier ->
        Box(modifier = modifier, contentAlignment = Alignment.Center) { StageIcon(stage) }
    }
    Spacer(Modifier.height(ZillitTheme.spacing.sm))
    PipelineRow(stages) { stage, modifier ->
        Box(
            modifier = modifier
                .height(PIPE_BAR)
                .clip(ZillitTheme.shapes.medium)
                .background(if (stage.count > 0) stageColour(stage.colour) else ZillitTheme.colors.surfaceSunken)
                .clickable(onClick = open(stage)),
        )
    }
    Spacer(Modifier.height(ZillitTheme.spacing.sm))
    PipelineRow(stages) { stage, modifier -> PipelineLegend(stage, open(stage), modifier) }
}

/** A card per stage with an arrow between — equal widths, so no label is crushed. */
@Composable
private fun PipeCards(stages: List<PipelineStage>, open: (PipelineStage) -> () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, stage ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.surface)
                    .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                    .clickable(onClick = open(stage))
                    .padding(vertical = ZillitTheme.spacing.md, horizontal = ZillitTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                StageIcon(stage)
                PipelineLegend(stage, open(stage))
            }
            if (index < stages.lastIndex) {
                ZillitIcon(
                    icon = ZillitIcons.ChevronRight,
                    tint = ZillitTheme.colors.textMuted,
                    size = STAGE_ICON,
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs),
                )
            }
        }
    }
}

/** Counts over circles on one line — the web's `PipeRail`. */
@Composable
private fun PipeRail(stages: List<PipelineStage>, open: (PipelineStage) -> () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RAIL_INSET, vertical = RAIL_LINE_TOP)
                .height(RAIL_LINE)
                .background(ZillitTheme.colors.border),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            stages.forEach { stage ->
                val active = stage.count > 0
                val tint = if (active) stageColour(stage.colour) else ZillitTheme.colors.textMuted
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = open(stage)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitText(
                        text = stage.count.toString(),
                        style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = tint,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .size(RAIL_DOT)
                            .clip(ZillitTheme.shapes.pill)
                            .background(if (active) tint else ZillitTheme.colors.surface)
                            .border(1.dp, if (active) tint else ZillitTheme.colors.border, ZillitTheme.shapes.pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(
                            icon = stageIcon(stage.id),
                            tint = if (active) ZillitTheme.colors.textOnAccent else tint,
                            size = STAGE_ICON,
                        )
                    }
                    StageName(stage.label)
                }
            }
        }
    }
}

/** A bar per stage, longest first — the web's `PipeFunnel`. */
@Composable
private fun ColumnScope.PipeFunnel(stages: List<PipelineStage>, open: (PipelineStage) -> () -> Unit) {
    val tallest = stages.maxOf { it.count }.coerceAtLeast(1)
    stages.forEach { stage ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ZillitTheme.spacing.xxs)
                .clickable(onClick = open(stage)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = stageIcon(stage.id), tint = stageColour(stage.colour), size = STAGE_ICON)
            ZillitText(
                text = stage.label,
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.width(FUNNEL_LABEL),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(FUNNEL_BAR)
                    .clip(ZillitTheme.shapes.medium)
                    .background(ZillitTheme.colors.surfaceSunken),
            ) {
                val share = (FUNNEL_FLOOR + (stage.count.toFloat() / tallest) * (1f - FUNNEL_FLOOR))
                Row(
                    modifier = Modifier
                        .fillMaxWidth(share)
                        .height(FUNNEL_BAR)
                        .clip(ZillitTheme.shapes.medium)
                        .background(if (stage.count > 0) stageColour(stage.colour) else Color.Transparent)
                        .padding(horizontal = ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = stage.count.toString(),
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = if (stage.count > 0) ZillitTheme.colors.textOnAccent else ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Just the figures, with an arrow between — the web's `PipeMono`. */
@Composable
private fun PipeMono(stages: List<PipelineStage>, open: (PipelineStage) -> () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        stages.forEachIndexed { index, stage ->
            Column(
                modifier = Modifier.clickable(onClick = open(stage)).padding(horizontal = ZillitTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                ZillitText(
                    text = stage.count.toString(),
                    style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (stage.count > 0) stageColour(stage.colour) else ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
                StageName(stage.label)
            }
            if (index < stages.lastIndex) {
                ZillitIcon(icon = ZillitIcons.ChevronRight, tint = ZillitTheme.colors.textMuted, size = STAGE_ICON)
            }
        }
    }
}

/**
 * One row of the Flow pipeline, weighted by count.
 *
 * Every stage keeps at least [MIN_SHARE] of the row. The web floors an empty
 * stage at a flat 0.55 and lets the rest fall where they may, which on a real
 * production — where nearly everything is already paid — squeezes four of the
 * five names down to "MAT…". A floor proportional to the whole keeps the
 * reading the web intends, and keeps every stage's own name under its bar.
 */
@Composable
private fun PipelineRow(
    stages: List<PipelineStage>,
    cell: @Composable (PipelineStage, Modifier) -> Unit,
) {
    val total = stages.sumOf { it.count }.toFloat()
    val floor = (total * MIN_SHARE).coerceAtLeast(EMPTY_WEIGHT)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEach { stage ->
            cell(stage, Modifier.weight(stage.count.toFloat().coerceAtLeast(floor)))
        }
    }
}

/** The tinted square over each stage — the web's `IconWash`. */
@Composable
private fun StageIcon(stage: PipelineStage) {
    val colors = ZillitTheme.colors
    val active = stage.count > 0
    val tint = if (active) stageColour(stage.colour) else colors.textMuted
    Box(
        modifier = Modifier
            .size(STAGE_ICON_BOX)
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) tint.copy(alpha = WASH_ALPHA) else colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = stageIcon(stage.id), tint = tint, size = STAGE_ICON)
    }
}

/** The web's per-stage icon, matched to the queue each one opens. */
private fun stageIcon(id: String) = when (id) {
    "inbox" -> ZillitIcons.Mail
    "matching" -> ZillitIcons.Receipt
    "approval" -> ZillitIcons.Shield
    "ready_to_pay" -> ZillitIcons.Wallet
    "paid" -> ZillitIcons.Check
    else -> ZillitIcons.File
}

@Composable
private fun PipelineLegend(stage: PipelineStage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val active = stage.count > 0
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = stage.count.toString(),
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) stageColour(stage.colour) else colors.textMuted,
            maxLines = 1,
        )
        StageName(stage.label)
    }
}

@Composable
private fun StageName(label: String) {
    ZillitText(
        text = label.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The server names a stage's colour; these are the web's five. */
@Composable
private fun stageColour(name: String): Color {
    val colors = ZillitTheme.colors
    return when (name.lowercase()) {
        "amber", "gold" -> colors.warning
        "blue" -> colors.info
        "pink", "red" -> colors.danger
        "teal", "green" -> colors.success
        else -> colors.accent
    }
}

// -- cost report impact -------------------------------------------------------

@Composable
private fun CostReportPanel(state: InvoicesUiState, impact: CostReportImpact) {
    ZillitSectionCard(
        title = "Pending Cost Report Impact",
        icon = ZillitIcons.Ledger,
        action = { ZillitStatusPill(label = "LIVE", tone = StatusTone.Progress, dot = true) },
    ) {
        StatGrid(
            columns = COST_COLUMNS,
            tiles = listOf(
                {
                    ZillitStatTile(
                        label = "Pending Invoices",
                        value = impact.pendingCount.toString(),
                        sub = "inbox + matching + approval",
                        tone = StatusTone.Pending,
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = "Pending Net Value",
                        value = impact.pendingNet.ifBlank { "—" },
                        sub = "will hit cost report on posting",
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = "Over Budget Depts",
                        value = impact.overBudgetDepts.toString(),
                        sub = "will exceed budget",
                        tone = StatusTone.Rejected,
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = "Under Budget",
                        value = impact.underBudgetDepts.toString(),
                        sub = "departments on track",
                        tone = StatusTone.Done,
                        modifier = statTile,
                    )
                },
            ),
        )
        if (impact.rows.isNotEmpty()) CostReportTable(state, impact)
    }
}

/** A row per department: its week's budget, what is pending against it, and where that lands. */
@Composable
private fun ColumnScope.CostReportTable(state: InvoicesUiState, impact: CostReportImpact) {
    Spacer(Modifier.height(ZillitTheme.spacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Caption("Department", Modifier.weight(2f))
        Caption("Budget (Wk)", Modifier.weight(1f))
        Caption("Pending", Modifier.weight(1f))
        Caption("Projected", Modifier.weight(1f))
        Caption("Var %", Modifier.weight(VARIANCE_WEIGHT))
    }
    impact.rows.forEach { row ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Cell(state.departmentName(row.departmentId), Modifier.weight(2f))
            Cell(row.budget, Modifier.weight(1f))
            Cell(row.pending, Modifier.weight(1f))
            Cell(row.projected, Modifier.weight(1f))
            Box(Modifier.weight(VARIANCE_WEIGHT)) {
                ZillitStatusPill(
                    label = (if (row.isOver) "+" else "") + "${row.variance.toInt()}%",
                    tone = if (row.isOver) StatusTone.Rejected else StatusTone.Done,
                )
            }
        }
    }
}

// -- vendor alerts, duplicates, actions, activity -----------------------------

@Composable
private fun VendorAlertsPanel(alerts: List<VendorAlert>, onEvent: (InvoicesEvent) -> Unit) {
    ZillitSectionCard(
        title = "Vendor Alerts",
        icon = ZillitIcons.Warning,
        action = { ZillitStatusPill(label = "${alerts.size} ACTIONS", tone = StatusTone.Pending) },
    ) {
        alerts.forEach { alert ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Box(Modifier.size(DOT).clip(ZillitTheme.shapes.pill).background(severityColour(alert.severity)))
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = alert.title,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    ZillitText(
                        text = listOf(alert.detail, alert.urgency).filter { it.isNotBlank() }.joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                AccountantPage.forHref(alert.href)?.let { page ->
                    ZillitButton(
                        text = alert.buttonLabel.ifBlank { "Open" },
                        onClick = { onEvent(InvoicesEvent.SelectPage(page)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun severityColour(severity: String): Color = when (severity.lowercase()) {
    "danger" -> ZillitTheme.colors.danger
    "warning" -> ZillitTheme.colors.warning
    else -> ZillitTheme.colors.success
}

/** The flags the server raised, each with the two answers only an accountant can give. */
@Composable
private fun DuplicatesPanel(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val flags = state.duplicates
    ZillitSectionCard(
        title = "Possible Duplicates",
        icon = ZillitIcons.File,
        action = {
            if (!state.duplicatesLoading) {
                ZillitStatusPill(
                    label = flags.size.toString(),
                    tone = if (flags.isEmpty()) StatusTone.Done else StatusTone.Rejected,
                )
            }
        },
    ) {
        when {
            state.duplicatesLoading -> Hint("Checking for duplicates…")
            flags.isEmpty() -> Hint("No duplicate invoices detected — all clear.")
            else -> flags.forEach { flag -> DuplicateRow(state, flag, onEvent) }
        }
    }
}

@Composable
private fun DuplicateRow(state: InvoicesUiState, flag: DuplicateFlag, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = if (flag.isConfirmed) "Confirmed Duplicate" else "Possible Duplicate",
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                ZillitStatusPill(label = "${flag.similarityScore}% match", tone = StatusTone.Pending)
            }
            ZillitText(
                text = duplicateSentence(flag, state.projectCurrency),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        if (flag.isPending) {
            ZillitButton(
                text = "Confirm",
                onClick = { onEvent(InvoicesEvent.ConfirmDuplicate(flag.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Dismiss",
                onClick = { onEvent(InvoicesEvent.DismissDuplicate(flag.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        } else {
            ZillitStatusPill(label = "CONFIRMED", tone = StatusTone.Rejected)
        }
    }
}

/** "INV-0012 Panavision £1,200.00 matches INV-0009 (paid 3 Mar) — same vendor, same amount." */
private fun duplicateSentence(flag: DuplicateFlag, currency: String): String {
    val amount = Money.format(flag.invoiceAmount, currency)
    val against = listOfNotNull(
        flag.duplicateStatus.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    val reasons = flag.matchReasons.joinToString(", ")
    return buildString {
        append(flag.invoiceRef.ifBlank { "This invoice" })
        append(" ")
        append(flag.vendorName.ifBlank { "Unknown" })
        append(" ")
        append(amount)
        append(" matches ")
        append(flag.duplicateRef.ifBlank { "another invoice" })
        if (against.isNotBlank()) append(" ($against)")
        if (reasons.isNotBlank()) append(" — $reasons")
        append(".")
    }
}

@Composable
private fun PendingAndRecent(data: InvoiceOverview, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.weight(1f)) { PendingActionsPanel(data.pendingActions, data.totalActions, onEvent) }
        Box(Modifier.weight(1f)) { RecentActivityPanel(data.recentActivity) }
    }
}

@Composable
private fun PendingActionsPanel(actions: List<PendingAction>, total: Int, onEvent: (InvoicesEvent) -> Unit) {
    ZillitSectionCard(
        title = "Pending Actions",
        icon = ZillitIcons.Info,
        action = { ZillitStatusPill(label = total.toString(), tone = StatusTone.Pending) },
    ) {
        if (actions.isEmpty()) Hint("No pending actions.")
        actions.forEach { action ->
            val page = AccountantPage.forHref(action.href)
            val open = page?.let { Modifier.clickable { onEvent(InvoicesEvent.SelectPage(it)) } } ?: Modifier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(open)
                    .padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitStatusPill(label = action.count.toString(), tone = StatusTone.Pending)
                ZillitText(text = action.label, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RecentActivityPanel(rows: List<ActivityRow>) {
    ZillitSectionCard(title = "Recent Activity", icon = ZillitIcons.Reload) {
        if (rows.isEmpty()) Hint("No recent activity.")
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitStatusPill(label = row.status, tone = activityTone(row.variant))
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = row.vendor,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    ZillitText(
                        text = listOf(row.reference, row.description).filter { it.isNotBlank() }.joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
                ZillitText(text = row.amount, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

/** The server's tag variant, in this app's tones. */
private fun activityTone(variant: String): StatusTone = when (variant.lowercase()) {
    "green", "teal" -> StatusTone.Done
    "amber", "gold" -> StatusTone.Pending
    "pink", "red" -> StatusTone.Rejected
    "purple", "violet", "blue" -> StatusTone.Progress
    else -> StatusTone.Neutral
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
private fun Cell(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.ifBlank { "—" },
        style = ZillitTheme.typography.bodySmall,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
private fun Hint(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

private const val DETAIL_ALPHA = 0.8f
private const val WASH_ALPHA = 0.14f
private const val EMPTY_WEIGHT = 0.55f
private const val MIN_SHARE = 0.11f
private const val VARIANCE_WEIGHT = 0.7f
private const val STAT_COLUMNS = 6
private const val COST_COLUMNS = 4
private const val FUNNEL_FLOOR = 0.3f
private val STAGE_ICON_BOX = 34.dp
private val STAGE_ICON = 16.dp
private val PIPE_BAR = 18.dp
private val RAIL_DOT = 38.dp
private val RAIL_LINE = 3.dp
private val RAIL_LINE_TOP = 50.dp
private val RAIL_INSET = 60.dp
private val FUNNEL_BAR = 30.dp
private val FUNNEL_LABEL = 110.dp
private val DOT = 8.dp
private val LOADING_PAD = 96.dp
