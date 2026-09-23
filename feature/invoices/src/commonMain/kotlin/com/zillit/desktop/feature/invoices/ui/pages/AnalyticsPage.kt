package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.core.designsystem.component.ZillitDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * Spend by department and by vendor — the web's `AnalyticsPage`.
 *
 * Server-shaped like the dashboard: the figures arrive formatted, and the
 * variance column is the server's own string, shown rather than parsed.
 */
@Composable
internal fun ColumnScope.AnalyticsPage(state: InvoicesUiState) {
    val analytics = state.analytics
    if (state.analyticsLoading && analytics == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = LOADING_PAD), contentAlignment = Alignment.Center) {
            ZillitSpinner()
        }
        return
    }
    val data = analytics ?: InvoiceAnalytics()
    ZillitScrollColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        StatRow(data)
        SummaryRow(data)
        DepartmentTable(state, data)
        VendorSpendPanel(data)
    }
}

@Composable
private fun StatRow(data: InvoiceAnalytics) {
    val stats = data.stats
    StatGrid(
        columns = ANALYTICS_COLUMNS,
        tiles = listOf(
            {
                ZillitStatTile(
                    label = str(S.desktop_total_ap_spend),
                    value = stats.totalApSpend.ifBlank { "—" },
                    sub = stats.totalApSubtitle.ifBlank { "production total" },
                    modifier = statTile,
                )
            },
            {
                ZillitStatTile(
                    label = str(S.desktop_avg_invoice),
                    value = stats.averageInvoice.ifBlank { "—" },
                    sub = stats.averageInvoiceSubtitle.ifBlank { null },
                    modifier = statTile,
                )
            },
            {
                ZillitStatTile(
                    label = str(S.desktop_on_time_payment),
                    value = stats.onTimePayment.ifBlank { "—" },
                    sub = stats.onTimeSubtitle.ifBlank { null },
                    tone = StatusTone.Done,
                    modifier = statTile,
                )
            },
            {
                ZillitStatTile(
                    label = str(S.desktop_ap_days),
                    value = stats.apDays.ifBlank { "—" },
                    sub = stats.apDaysSubtitle.ifBlank { "avg days to payment" },
                    modifier = statTile,
                )
            },
        ),
    )
}

@Composable
private fun SummaryRow(data: InvoiceAnalytics) {
    val summary = data.summary
    ZillitSectionCard(title = str(S.desktop_cost_report_impact_of_ap), icon = ZillitIcons.Ledger) {
        StatGrid(
            columns = ANALYTICS_COLUMNS,
            tiles = listOf(
                {
                    ZillitStatTile(
                        label = str(S.cr_posted_to_ledger),
                        value = summary.posted.ifBlank { "—" },
                        sub = summary.postedSubtitle.ifBlank { null },
                        tone = StatusTone.Done,
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = str(S.desktop_pending_post),
                        value = summary.pending.ifBlank { "—" },
                        sub = summary.pendingSubtitle.ifBlank { null },
                        tone = StatusTone.Pending,
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = str(S.desktop_estimated_unknowns),
                        value = summary.unknown.ifBlank { "—" },
                        sub = summary.unknownSubtitle.ifBlank { null },
                        tone = StatusTone.Rejected,
                        modifier = statTile,
                    )
                },
                {
                    ZillitStatTile(
                        label = str(S.desktop_total_projected_ap),
                        value = summary.projected.ifBlank { "—" },
                        sub = summary.projectedSubtitle.ifBlank { null },
                        modifier = statTile,
                    )
                },
            ),
        )
    }
}

@Composable
private fun DepartmentTable(state: InvoicesUiState, data: InvoiceAnalytics) {
    ZillitSectionCard(title = str(S.ah_spend_by_department), icon = ZillitIcons.File) {
        if (data.departments.isEmpty()) {
            Muted(str(S.desktop_inv_nothing_attributed_to_department))
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Caption(str(S.department), Modifier.weight(2f))
            Caption(str(S.ah_step_posted), Modifier.weight(1f))
            Caption(str(S.pending), Modifier.weight(1f))
            Caption(str(S.desktop_unattributed), Modifier.weight(1f))
            Caption(str(S.desktop_projected), Modifier.weight(1f))
            Caption(str(S.desktop_variance), Modifier.weight(1f))
        }
        data.departments.forEach { row ->
            ZillitDivider()
            Row(
                // The web gives each cell `py-3`; rows with no breathing room
                // read as one block of figures rather than a table.
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Cell(state.departmentName(row.name.ifBlank { row.code }), Modifier.weight(2f))
                Cell(row.posted, Modifier.weight(1f))
                Cell(row.pending, Modifier.weight(1f))
                Cell(row.unknown, Modifier.weight(1f))
                Cell(row.projected, Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    if (row.isOver) {
                        ZillitStatusPill(label = row.variance.ifBlank { "over" }, tone = StatusTone.Rejected)
                    } else {
                        Cell(row.variance)
                    }
                }
            }
        }
    }
}

/** Each vendor's share, as a bar the width of its own percentage. */
@Composable
private fun VendorSpendPanel(data: InvoiceAnalytics) {
    ZillitSectionCard(title = str(S.desktop_top_vendors_by_spend), icon = ZillitIcons.Users) {
        if (data.vendors.isEmpty()) {
            Muted(str(S.desktop_no_vendor_spend_yet))
            return@ZillitSectionCard
        }
        data.vendors.forEachIndexed { index, vendor ->
            Column(
                modifier = Modifier.padding(top = if (index == 0) 0.dp else ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = vendor.name.ifBlank { str(S.desktop_unknown) },
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = "${vendor.percent.toInt()}%",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(
                        text = vendor.amount.ifBlank { "—" },
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(BAR)
                        .clip(ZillitTheme.shapes.pill)
                        .background(ZillitTheme.colors.surfaceSunken),
                ) {
                    // The web floors the bar at 3% so a small vendor still shows.
                    val share = (vendor.percent.coerceAtLeast(MIN_BAR) / WHOLE_SHARE).coerceAtMost(1.0)
                    Box(
                        Modifier
                            .fillMaxWidth(share.toFloat())
                            .height(BAR)
                            .clip(ZillitTheme.shapes.pill)
                            .background(ZillitTheme.colors.accent),
                    )
                }
            }
        }
    }
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
    ZillitText(text = text.ifBlank { "—" }, style = ZillitTheme.typography.bodySmall, modifier = modifier, maxLines = 1)
}

@Composable
private fun Muted(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

private const val ANALYTICS_COLUMNS = 4
private val BAR = 8.dp
private val LOADING_PAD = 96.dp
private const val MIN_BAR = 3.0
private const val WHOLE_SHARE = 100.0
