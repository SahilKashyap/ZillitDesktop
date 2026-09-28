package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.SummaryCard
import com.zillit.desktop.feature.payroll.domain.SummaryTone
import com.zillit.desktop.feature.payroll.domain.WeekRow
import com.zillit.desktop.feature.payroll.domain.WeekView

/**
 * A crew member's week, read-only — the web's `ReadOnlyWeeklyView`.
 *
 * The KPI strip and the seven-row table, drawn from what the server stored on
 * the document. No deal is fetched and nothing is recomputed: this is what the
 * crew member's own weekly timecard shows, with every edit affordance removed.
 *
 * [dayCellExtra] is the one seam a host may fill — Production Report Payroll
 * puts its per-day fill control under the date there. [holidayPay] is the
 * accrual the host resolved, if it could; zero omits the card.
 */
@Composable
fun ReadOnlyWeeklyView(
    timecard: PayrollTimecard?,
    loading: Boolean,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    holidayPay: Double = 0.0,
    dayCellExtra: (@Composable (WeekRow) -> Unit)? = null,
) {
    when {
        loading -> WeekSkeleton(modifier)
        timecard == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ZillitText(
                text = emptyMessage,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }

        else -> Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            WeekSummaryStrip(WeekView.summary(timecard, holidayPay))
            InvoiceButton(timecard)
            WeekTableCard(timecard, dayCellExtra, Modifier.weight(1f))
        }
    }
}

/** The KPI strip — one card per figure, the gross carrying the accent. */
@Composable
private fun WeekSummaryStrip(cards: List<SummaryCard>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        cards.forEach { card -> SummaryTile(card, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/**
 * One KPI card. Written here rather than borrowed from the shared stat tile
 * because the strip's palette is a reading aid, not a status: OT is drawn as a
 * cost, allowances as additions, a missing figure as absent — and the gross
 * wears the accent so the eye lands on it first.
 */
@Composable
private fun SummaryTile(card: SummaryCard, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val value = when (card.tone) {
        SummaryTone.Ink -> colors.textPrimary
        SummaryTone.Mute -> colors.textMuted
        SummaryTone.Red -> colors.danger
        SummaryTone.Green -> colors.success
        SummaryTone.Pink -> colors.violet
        SummaryTone.Amber -> colors.accentText
    }
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (card.highlight) colors.accentSoft else colors.surface)
            .border(1.dp, if (card.highlight) colors.accent else colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = card.label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = colors.textMuted,
            maxLines = 2,
        )
        ZillitText(
            text = card.value,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = value,
            maxLines = 1,
        )
        card.sub?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 2)
        }
    }
}

/**
 * The table. The week's allowances are either one list beside the days
 * (Weekly) or a cell on each of them (Daily) — the same two shapes the web's
 * rowSpan'd column takes, and the toggle that moves between them.
 */
@Composable
private fun WeekTableCard(
    timecard: PayrollTimecard,
    dayCellExtra: (@Composable (WeekRow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val week = timecard.weekStarting ?: return
    val rows = remember(timecard) { WeekView.rows(week, timecard.days) }
    val meals = remember(rows) { WeekView.mealColumns(rows) }
    val entries = remember(timecard) {
        WeekView.entries(timecard.days, timecard.weeklyAllowances, timecard.weeklyExtras)
    }
    // Which day's breakdown is open, and which cadence the allowances read in.
    // Both are this view's own: a producer's choice here must never change what
    // a crew member sees on their own card, so neither is persisted.
    var expanded by remember(timecard.id) { mutableStateOf<Int?>(null) }
    var view by remember { mutableStateOf(AllowanceView.Daily) }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) {
        val tableWidth = maxOf(maxWidth, WeekColumns.minWidth(meals, showAllowances = true))
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).width(tableWidth)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                WeekTableHeader(meals) {
                    if (view == AllowanceView.Daily) AllowanceHeader(view) { view = it }
                }
                rows.forEach { row ->
                    ZillitDivider()
                    DayRowWithDetail(
                        row = row,
                        currency = timecard.currency,
                        mealColumns = meals,
                        view = view,
                        expanded = expanded == row.index,
                        onToggle = { expanded = if (expanded == row.index) null else row.index },
                        dayCellExtra = dayCellExtra,
                    )
                }
            }
            if (view == AllowanceView.Weekly) {
                Column(Modifier.width(WeekColumns.allowances).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)) {
                        AllowanceHeader(view) { view = it }
                    }
                    WeekEntriesList(entries, timecard.currency, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The invoice a Schedule D or loan-out crew member attached to their own week.
 *
 * View only: the owner's card owns replacing and removing it, and an approver
 * should not be one stray click from deleting the document their sign-off
 * rests on.
 */
@Composable
private fun InvoiceButton(timecard: PayrollTimecard) {
    val attachment = timecard.attachment ?: return
    val url = attachment.url ?: return
    val uriHandler = LocalUriHandler.current
    ZillitButton(
        text = attachment.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_payroll_view_invoice),
        onClick = { runCatching { uriHandler.openUri(url) } },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Paperclip,
    )
}

@Composable
private fun WeekSkeleton(modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            repeat(SKELETON_TILES) {
                Column(
                    modifier = Modifier.weight(1f).clip(ZillitTheme.shapes.large)
                        .background(ZillitTheme.colors.surface).padding(ZillitTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_LABEL))
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_VALUE))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            repeat(PayPeriod.DAYS_IN_WEEK) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun RowScope.AllowanceHeader(view: AllowanceView, onChange: (AllowanceView) -> Unit) {
    Column(
        modifier = Modifier.width(WeekColumns.allowances)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = str(S.desktop_payroll_allowances_rentals_upgrades_extras).uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AllowanceView.entries.forEach { option ->
                ZillitChoiceChip(
                    label = str(if (option == AllowanceView.Daily) S.daily else S.ce_weekly),
                    selected = option == view,
                    onClick = { onChange(option) },
                )
            }
        }
    }
}

private const val SKELETON_TILES = 5
private const val SKELETON_LABEL = 0.5f
private const val SKELETON_VALUE = 0.7f
