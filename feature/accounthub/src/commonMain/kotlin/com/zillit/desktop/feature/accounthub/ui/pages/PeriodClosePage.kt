package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.CashCloseDashboard
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.ClosingReport
import com.zillit.desktop.feature.accounthub.domain.HEAT_WEEKS
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.PeriodCloseTab
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.DateField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.HubSideRail
import com.zillit.desktop.feature.accounthub.ui.components.RailRow
import com.zillit.desktop.feature.accounthub.ui.components.RailSection
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.domain.CommitmentWeek

/**
 * Closing a period — the web's `PeriodCloseModule`, three tabs down a rail.
 *
 * **Period Close** moves one boundary forward. Everything dated on or before it
 * goes read-only in every source module, and there is no way to reopen it, so
 * the form states what is already closed, asks before it acts, and offers
 * nothing that looks like an undo.
 *
 * **Cash & Close** is the Weekly Close Command Centre: a server-shaped dashboard
 * with a checklist ticked locally.
 *
 * **Publish Package** bundles the period's reports and e-mails them, one
 * package per audience.
 */
@Composable
fun PeriodClosePage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, nowMillis: Long) {
    val close = state.periodClose
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        Rail(close.tab, onEvent)
        when (close.tab) {
            PeriodCloseTab.Close -> CloseForm(state, onEvent, nowMillis)
            PeriodCloseTab.CashClose -> CashCloseDashboardView(state)
            PeriodCloseTab.Publish -> PublishView(state, onEvent)
        }
    }
    ConfirmDialog(state, onEvent)
}

/**
 * The web's own rail for this page (`PeriodCloseModule.jsx`): it renders
 * full-bleed, without the hub's sidebar, so the header card's back chip is the
 * way back to the hub — the web's `/film-tools/account-hub`, not the tools
 * grid the console's own arrow goes to.
 */
@Composable
private fun Rail(active: PeriodCloseTab, onEvent: (AccountHubEvent) -> Unit) {
    HubSideRail(
        title = str(S.desktop_period_close),
        backLabel = str(S.desktop_hub_back_to_account_hub),
        onBack = { onEvent(AccountHubEvent.BackToHub) },
        sections = listOf(
            RailSection(
                title = str(S.close),
                rows = PeriodCloseTab.entries.map { tab ->
                    RailRow(
                        id = tab.name,
                        label = tab.label,
                        icon = when (tab) {
                            PeriodCloseTab.Close -> AhIcons.Lock
                            PeriodCloseTab.CashClose -> AhIcons.Bank
                            PeriodCloseTab.Publish -> AhIcons.Send
                        },
                        active = tab == active,
                    )
                },
            ),
        ),
        onSelect = { id ->
            PeriodCloseTab.entries.firstOrNull { it.name == id }
                ?.let { onEvent(AccountHubEvent.SwitchPeriodCloseTab(it)) }
        },
    )
}

// -- close form --------------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod") // One form, read top to bottom.
@Composable
private fun RowScope.CloseForm(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, nowMillis: Long) {
    val close = state.periodClose
    val lock = close.lock
    val colors = ZillitTheme.colors
    // The day after the lock, read at noon UTC so a zone west of UTC does not
    // name the lock day itself — which the server refuses.
    val minDate = IsoDate.toEpochMillis(lock.lockedThrough)?.let { EpochDate.isoDate(it + DAY_MILLIS + DAY_MILLIS / 2) }
    val target = IsoDate.toEpochMillis(close.closeDateText)
    val afterLock = target != null && (IsoDate.toEpochMillis(lock.lockedThrough)?.let { target > it } ?: true)
    val canSubmit = state.viewer.canActAsAccountant && afterLock && !close.closing && !close.loading

    Box(Modifier.weight(1f).fillMaxHeight()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = FORM_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(HERO_ICON).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Shield, tint = colors.accentText, size = 17.dp)
                    }
                    Column {
                        ZillitText(
                            text = str(S.desktop_close_accounting_period),
                            style = ZillitTheme.typography.titleLarge,
                        )
                        FieldHint(str(S.desktop_hub_advance_the_lock_so_everything_on_or_before_the_close))
                    }
                }
                SubCard {
                    MonoLabel(str(S.desktop_last_closed_period))
                    when {
                        close.loading && lock.lockedThrough.isBlank() -> Row(
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ZillitSpinner()
                            FieldHint(str(S.loading_))
                        }
                        lock.isClosed -> ZillitText(
                            text = IsoDate
                                .toEpochMillis(lock.lockedThrough)?.let { EpochDate.date(it) } ?: lock.lockedThrough,
                            style = ZillitTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                        else -> ZillitText(
                            text = str(S.desktop_hub_no_period_locked_yet),
                            style = ZillitTheme.typography.titleSmall,
                            color = colors.textSecondary,
                        )
                    }
                    FieldHint(str(S.desktop_hub_locked_through_this_date_inclusive))
                }
                SubCard {
                    MonoLabel(str(S.desktop_close_through_date))
                    DateField(
                        value = close.closeDateText,
                        onValueChange = { onEvent(AccountHubEvent.EditCloseDate(it)) },
                        min = minDate,
                        enabled = state.viewer.canActAsAccountant,
                        errorText =
                            if (close.closeDateText.isNotBlank() && !afterLock) {
                                str(S.desktop_hub_must_be_after_the_last_closed_date)
                            } else {
                                null
                            },
                    )
                    FieldHint(str(S.desktop_hub_must_be_after_the_last_closed_date_the_server_resolves))
                }
                ZillitNotice(
                    text = str(S.desktop_hub_if_any_transaction_in_this_period_is_still_unposted_the),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Warning,
                )
                if (!state.viewer.canActAsAccountant) {
                    ZillitNotice(
                        text = str(S.desktop_hub_closing_a_period_is_the_accounts_departments_and_an_admin),
                        tone = StatusTone.Neutral,
                        icon = ZillitIcons.Info,
                    )
                }
                close.result?.let { result ->
                    ZillitNotice(
                        text = result.message,
                        tone = if (result.ok) StatusTone.Done else StatusTone.Rejected,
                        icon = if (result.ok) ZillitIcons.Tick else ZillitIcons.Warning,
                    )
                }
                // A footer bar, as the Publish tab has: it says what the
                // button is about to do, so an irreversible act is not a lone
                // grey control floating under a warning.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surface)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FieldHint(
                        when {
                            !state.viewer.canActAsAccountant -> str(S.desktop_hub_read_only_for_your_role)
                            target == null -> str(S.desktop_hub_pick_a_date_to_close_through)
                            !afterLock -> str(S.desktop_hub_that_date_is_on_or_before_the_last_closed_period)
                            else -> "Everything on or before ${EpochDate.date(target)} becomes read-only."
                        },
                        Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = if (close.closing) str(S.desktop_closing) else str(S.desktop_close_period),
                        onClick = {
                            target?.let { onEvent(
                                AccountHubEvent.ProposePeriodClose(it + DAY_MILLIS - 1),
                            ) } ?: onEvent(AccountHubEvent.ProposePeriodClose(nowMillis))
                        },
                        leadingIcon = ZillitIcons.Shield,
                        loading = close.closing,
                        enabled = canSubmit,
                    )
                }
            }
        }
    }
}

/**
 * The confirmation.
 *
 * A dialog rather than an inline toggle because the act has no undo: the
 * question has to be asked somewhere the answer cannot be given by accident.
 */
@Composable
private fun ConfirmDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val close = state.periodClose
    val pending = close.pendingCloseMillis
    HubConfirmDialog(
        visible = pending != null,
        title = str(S.desktop_close_this_period),
        // Both dates in words, and both read at noon UTC of their day: `as_of` is
        // the chosen day's last UTC millisecond, which east of UTC formats as the
        // next day — the dialog asked about 7 Sep for a 6 Sep close in India.
        message = str(S.desktop_hub_close_confirm_message, dayInWords(pending?.let { it - it % DAY_MILLIS })) +
            if (close.lock.isClosed) {
                val lockDay = IsoDate.toEpochMillis(close.lock.lockedThrough)
                " " + str(S.desktop_hub_currently_closed_through_x, dayInWords(lockDay))
            } else {
                ""
            },
        confirmLabel = str(S.desktop_close_period),
        loading = close.closing,
        onConfirm = { onEvent(AccountHubEvent.ConfirmPeriodClose) },
        onDismiss = { onEvent(AccountHubEvent.CancelPeriodClose) },
    )
}

// -- cash & close ---------------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod") // The dashboard's panels, in the web's order.
@Composable
private fun RowScope.CashCloseDashboardView(state: AccountHubUiState) {
    val cash = state.periodClose.cashClose
    val dash = cash.dashboard
    val loading = cash.loading
    val total = dash.checklist.size.takeIf { it > 0 } ?: dash.progressTotal
    val done = dash.checklist.count { it.label in cash.checked }
    val pct = if (dash.checklist.isNotEmpty()) (done * PERCENT / dash.checklist.size) else dash.progressPercent

    Box(Modifier.weight(1f).fillMaxHeight()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitPageHeader(
                eyebrow = str(S.desktop_management),
                title = str(S.desktop_hub_weekly_close_command_centre),
                description = str(S.desktop_hub_cash_position_commitments_and_weekly_close_checklist),
            )
            if (!loading && cash.loaded && dash.isEmpty) {
                ZillitNotice(
                    text = str(S.desktop_hub_the_cash_close_analytics_returned_nothing_for_this_production_yet),
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Info,
                )
            }
            Panel(
                // Not `Mark`: that is the Zillit "Z", the brand glyph, and it
                // read as a stray letter at the head of the panel. The web
                // uses a flag here — a clock is this set's nearest honest
                // equivalent, and the panel's own target is a deadline.
                title = str(S.desktop_weekly_close_progress),
                icon = ZillitIcons.Clock,
                right = { ZillitText(
                    text = if (loading) "…" else "$done / $total",
                    style = ZillitTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = ZillitTheme.colors.accentText,
                ) },
            ) {
                if (loading) {
                    ZillitSkeletonBar(modifier = Modifier.fillMaxWidth().height(10.dp))
                } else {
                    ZillitProgressBar(
                        fraction = (pct / PERCENT.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FieldHint("$pct% complete")
                        FieldHint("${(total - done).coerceAtLeast(0)} items remaining")
                        FieldHint(str(S.desktop_target_friday_5pm))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Panel(
                    title = str(S.desktop_cash_flow_forecast),
                    icon = ZillitIcons.Wallet,
                    right = { Pill(str(S.desktop_12_week_view), tone = StatusTone.Done) },
                    modifier = Modifier.weight(1f),
                ) {
                    if (loading) ZillitSkeletonBar(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT))
                    else Waterfall(dash)
                }
                Panel(
                    title = str(S.desktop_commitment_burn_rate),
                    icon = ZillitIcons.Siren,
                    right = { Pill(str(S.desktop_by_department), tone = StatusTone.Pending) },
                    modifier = Modifier.weight(1f),
                ) {
                    FieldHint(str(S.desktop_hub_po_commitment_drawdown_by_week_darker_higher_spend_that_week))
                    if (loading) repeat(HEAT_SKELETONS) {
                        ZillitSkeletonBar(modifier = Modifier.fillMaxWidth().height(HEAT_CELL))
                    }
                    else Heatmap(dash)
                    FieldHint(str(S.desktop_hub_values_in_000s_darker_cells_higher_commitment_drawdown_that_week))
                }
            }
            Panel(
                title = str(S.desktop_weekly_close_checklist),
                icon = ZillitIcons.Check,
                padded = false,
            ) {
                if (loading) repeat(HEAT_SKELETONS) {
                    ZillitSkeletonBar(
                        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md).height(HEAT_CELL),
                    )
                }
                // Every other panel here says so when it has nothing; without
                // this one the card is a blank white band.
                if (!loading && dash.checklist.isEmpty()) {
                    ZillitText(
                        text = str(S.desktop_hub_no_checklist_for_this_week_yet),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.padding(
                            horizontal = ZillitTheme.spacing.lg,
                            vertical = ZillitTheme.spacing.md,
                        ),
                    )
                }
                // Read-only, as the web's since 2026-09-04: a row is done when
                // the close endpoint says so. The local ticks and Reset All it
                // had moved "3 / 8" without saving anything.
                dash.checklist.forEachIndexed { index, item ->
                    val isDone = item.label in cash.checked
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isDone) DONE_ALPHA else 1f)
                            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            ZillitText(
                                text = item.label,
                                style = ZillitTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                                ),
                                color = if (isDone) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
                            )
                            if (item.meta.isNotBlank()) FieldHint(item.meta)
                        }
                        Pill(
                            if (isDone) str(S.ah_done) else item.badge.ifBlank { str(S.pending) },
                            tone = if (isDone) StatusTone.Done else toneOf(item.badgeTone),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Panel(
                    title = str(S.desktop_supplier_reconciliation_status),
                    icon = ZillitIcons.Search,
                    padded = false,
                    modifier = Modifier.weight(1f),
                ) {
                    if (loading) repeat(RECON_SKELETONS) {
                        ZillitSkeletonBar(
                            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md).height(HEAT_CELL),
                        )
                    }
                    if (!loading && dash.recon.isEmpty()) FieldHint(
                        str(S.desktop_hub_no_supplier_reconciliations_reported),
                        Modifier.padding(ZillitTheme.spacing.lg),
                    )
                    dash.recon.forEachIndexed { index, row ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            // Icons, as the web draws them now, not the ✓ ⚠ ✗ text marks.
                            ZillitIcon(
                                icon = when (row.status) {
                                    "green" -> AhIcons.CheckCircle
                                    "amber" -> AhIcons.AlertTriangle
                                    else -> AhIcons.XCircle
                                },
                                tint = colorOf(row.status),
                                size = 16.dp,
                            )
                            ZillitText(
                                text = row.supplier,
                                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                            )
                            ZillitText(
                                text = row.detail,
                                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colorOf(row.status),
                            )
                        }
                    }
                }
                Panel(
                    title = str(S.desktop_hub_upcoming_commitments_next_4_weeks_paren),
                    icon = ZillitIcons.Calendar,
                    modifier = Modifier.weight(1f),
                ) {
                    if (loading) repeat(RECON_SKELETONS) {
                        ZillitSkeletonBar(modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                    if (!loading && dash.weeks.isEmpty()) FieldHint(str(S.desktop_hub_no_upcoming_commitments_reported))
                    dash.weeks.forEach { week ->
                        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                ZillitText(
                                    text = week.label,
                                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                ZillitText(
                                    text = week.amount,
                                    style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = colorOf(week.color),
                                )
                            }
                            ZillitProgressBar(
                                fraction = (week.percent / PERCENT.toFloat()).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                fillColor = colorOf(week.color),
                            )
                            if (week.detail.isNotBlank() || week.peak || week.netflix) WeekNote(week)
                        }
                    }
                }
            }
        }
    }
}

/** A dashboard panel — title with icon on the left, an optional pill or count on the right. */
@Composable
private fun Panel(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    right: (@Composable () -> Unit)? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    SubCard(
        modifier = modifier.fillMaxWidth(),
        padded = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = icon, tint = ZillitTheme.colors.accentText, size = 14.dp)
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            right?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        Column(
            modifier = if (padded) Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg) else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            content = content,
        )
    }
}

/** The forecast's bars, sized by the server's own heights. */
@Composable
private fun Waterfall(dash: CashCloseDashboard) {
    val colors = ZillitTheme.colors
    if (dash.waterfall.isEmpty()) {
        FieldHint(str(S.desktop_no_forecast_reported))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        dash.waterfall.forEach { bar ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                MonoLabel(bar.amount)
                Box(
                    Modifier
                        .fillMaxWidth()
                        // The web lifts a full-height bar by `marginBottom`; padding
                        // inside the height shortened it by that much instead.
                        .padding(bottom = bar.marginBottom.coerceAtLeast(0).dp)
                        .height(bar.height.coerceIn(2, CHART_HEIGHT_PX).dp)
                        .clip(ZillitTheme.shapes.small)
                        .background(
                            when (bar.type) {
                                "net" -> colors.accent
                                "pos" -> colors.teal
                                else -> colors.danger
                            },
                        ),
                )
                FieldHint(bar.label)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        Legend(colors.teal, str(S.desktop_cash_in))
        Legend(colors.danger, str(S.desktop_cash_out))
        Legend(colors.accent, str(S.desktop_net_position))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        FieldHint(label)
    }
}

/** The burn-rate grid — week headers, then a row per department with the server's tints. */
@Composable
private fun Heatmap(dash: CashCloseDashboard) {
    if (dash.heatRows.isEmpty()) {
        FieldHint(str(S.desktop_hub_no_commitment_drawdown_reported))
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.width(HEAT_LABEL))
        HEAT_WEEKS.forEach { week -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { MonoLabel(week) } }
    }
    dash.heatRows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = row.label,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.width(HEAT_LABEL),
                maxLines = 1,
            )
            row.cells.take(HEAT_WEEKS.size).forEach { cell ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(HEAT_CELL)
                        .clip(ZillitTheme.shapes.small)
                        .background(parseCss(cell.background) ?: ZillitTheme.colors.surfaceSunken),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = cell.value,
                        style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = parseCss(cell.color) ?: ZillitTheme.colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            repeat((HEAT_WEEKS.size - row.cells.size).coerceAtLeast(0)) { Box(Modifier.weight(1f).height(HEAT_CELL)) }
        }
    }
}

/** `#rrggbb` or `#rgb`; the server's other CSS spellings fall back to the palette. */
private fun parseCss(value: String): Color? {
    val clean = value.trim().removePrefix("#")
    val hex = when (clean.length) {
        SHORT_HEX -> clean.map { "$it$it" }.joinToString("")
        FULL_HEX -> clean
        else -> return null
    }
    val rgb = hex.toLongOrNull(HEX_RADIX) ?: return null
    return Color((rgb or ALPHA_MASK).toInt())
}

private fun toneOf(tone: String): StatusTone = when (tone) {
    "green", "ok", "teal" -> StatusTone.Done
    "amber", "accent" -> StatusTone.Pending
    "red", "pink" -> StatusTone.Rejected
    else -> StatusTone.Pending
}

@Composable
private fun colorOf(tone: String): Color = when (tone) {
    "teal", "green", "ok" -> ZillitTheme.colors.teal
    "amber", "accent" -> ZillitTheme.colors.accent
    "red", "pink" -> ZillitTheme.colors.danger
    else -> ZillitTheme.colors.textSecondary
}

// -- publish closing package ----------------------------------------------------------------

@Suppress("LongMethod") // The package list and its publish bar, in the web's order.
@Composable
private fun RowScope.PublishView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val publish = state.periodClose.publish
    val colors = ZillitTheme.colors
    Box(Modifier.weight(1f).fillMaxHeight()) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = PUBLISH_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(HERO_ICON).clip(ZillitTheme.shapes.medium).background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Send, tint = colors.textOnAccent, size = 20.dp)
                    }
                    Column {
                        ZillitText(
                            text = str(S.desktop_publish_closing_package),
                            style = ZillitTheme.typography.titleLarge,
                        )
                        FieldHint("Bundle period-close reports and send them to recipients. Add a package per " +
                            "audience.")
                    }
                }
                publish.packages.forEachIndexed {
                    index, pkg -> PackageCard(index, pkg, publish.packages.size > 1, state, onEvent)
                }
                ZillitButton(
                    text = str(S.desktop_add_package),
                    onClick = { onEvent(AccountHubEvent.AddPackage) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
                publish.result?.let { result ->
                    ZillitNotice(
                        text = if (result.ok) "${result.message} Recipients have been e-mailed." else result.message,
                        tone = if (result.ok) StatusTone.Done else StatusTone.Rejected,
                        icon = if (result.ok) ZillitIcons.Tick else ZillitIcons.Warning,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surface)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FieldHint(
                        "${plural(
                            publish.packages.size,
                            "package",
                        )} · ${plural(
                            publish.totalRecipients,
                            "recipient",
                        )} · ${plural(publish.totalReports, "report")} selected",
                        Modifier.weight(1f),
                    )
                    val valid = publish.validPackages.size
                    ZillitButton(
                        text = when {
                            publish.publishing -> str(S.desktop_publishing)
                            valid > 0 -> "Publish ($valid)"
                            else -> str(S.publish)
                        },
                        onClick = { onEvent(AccountHubEvent.PublishPackages) },
                        leadingIcon = ZillitIcons.Send,
                        loading = publish.publishing,
                        enabled = valid > 0 && !publish.publishing && state.viewer.canActAsAccountant,
                    )
                }
                if (!state.viewer.canActAsAccountant) {
                    ZillitNotice(
                        text = "Publishing the closing package is the accounts department's.",
                        tone = StatusTone.Neutral,
                        icon = ZillitIcons.Info,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // Recipients, e-mails and reports — one card, top to bottom.
@Composable
private fun PackageCard(
    index: Int,
    pkg: ClosingPackage,
    removable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val publish = state.periodClose.publish
    val colors = ZillitTheme.colors
    val menuOpen = publish.openMenu == pkg.id
    fun patch(next: ClosingPackage) =
        onEvent(AccountHubEvent.EditPackages(publish.packages.map { if (it.id == pkg.id) next else it }))
    SubCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                Modifier.size(NUMBER_CHIP).clip(ZillitTheme.shapes.small).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "${index + 1}",
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.accentText,
                )
            }
            MonoLabel("Package ${index + 1}", Modifier.weight(1f))
            FieldHint("${plural(pkg.recipientCount, "recipient")} · ${plural(pkg.reports.size, "report")}")
            if (removable) ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove package ${index + 1}",
                onClick = { onEvent(AccountHubEvent.RemovePackage(pkg.id)) },
                tint = colors.danger,
            )
        }

        MonoLabel(str(S.recipients))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, if (menuOpen) colors.accent else colors.border, ZillitTheme.shapes.medium)
                .clickable { onEvent(AccountHubEvent.OpenPackageMenu(if (menuOpen) null else pkg.id)) }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.docusign_section_add_recipients),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(
                icon = if (menuOpen) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                tint = colors.textMuted,
                size = 14.dp,
            )
        }
        if (menuOpen) {
            val matches = HubUsers.search(HubUsers.available(state.users), publish.menuQuery)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium),
            ) {
                ZillitSearchField(
                    value = publish.menuQuery,
                    onValueChange = { onEvent(AccountHubEvent.SearchPackageMenu(it)) },
                    placeholder = str(S.drive_search_team_members),
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                )
                if (matches.isEmpty()) FieldHint(
                    str(S.desktop_no_team_members),
                    Modifier.padding(ZillitTheme.spacing.md),
                )
                ZillitScrollColumn(modifier = Modifier.fillMaxWidth().height(MENU_HEIGHT)) {
                    matches.forEach { person ->
                        val on = person.id in pkg.userIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (on) colors.accentSoft else Color.Transparent)
                                .clickable { onEvent(AccountHubEvent.TogglePackageRecipient(pkg.id, person.id)) }
                                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitAvatar(name = person.name.ifBlank { str(S.desktop_unnamed) },
                                userId = person.id,
                                size = 30.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                ZillitText(
                                    text = person.name.ifBlank { str(S.desktop_unnamed) },
                                    style = ZillitTheme.typography.bodyMedium,
                                )
                                if (person.roleLabel.isNotBlank()) FieldHint(person.roleLabel)
                            }
                            if (on) ZillitIcon(icon = ZillitIcons.Tick, tint = colors.accentText, size = 12.dp)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.xs,
                )) {
                    FieldHint("${matches.size} " +
                        "${if (matches.size == 1) "option" else "options"}" +
                            if (pkg.userIds.isNotEmpty()) " · ${pkg.userIds.size} selected" else "",
                    )
                }
            }
        }
        if (pkg.userIds.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                pkg.userIds.forEach {
                    id -> Chip(
                        text = state.userName(id),
                        onRemove = { onEvent(AccountHubEvent.TogglePackageRecipient(pkg.id, id)) },
                    )
                }
            }
        }

        MonoLabel(str(S.desktop_external_emails))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = pkg.emailDraft,
                onValueChange = { patch(pkg.copy(emailDraft = it)) },
                placeholder = "name@example.com",
                modifier = Modifier.weight(1f),
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                onImeAction = { onEvent(AccountHubEvent.AddPackageEmail(pkg.id)) },
            )
            ZillitButton(
                text = str(S.add),
                onClick = { onEvent(AccountHubEvent.AddPackageEmail(pkg.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = pkg.emailDraft.isNotBlank(),
            )
        }
        if (pkg.emails.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                pkg.emails.forEach {
                    email -> Chip(text = email, onRemove = { patch(pkg.copy(emails = pkg.emails - email)) })
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(str(S.reports), Modifier.weight(1f))
            ZillitButton(
                text = if (pkg.reports.size == ClosingReport.entries.size) {
                    str(S.docusign_initials_clear_all)
                } else {
                    str(S.dd_select_all)
                },
                onClick = { onEvent(AccountHubEvent.TogglePackageAllReports(pkg.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ClosingReport.entries.forEach { report ->
                val on = report in pkg.reports
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ZillitTheme.shapes.medium)
                        .background(if (on) colors.accentSoft else colors.surface)
                        .border(1.dp, if (on) colors.accent else colors.border, ZillitTheme.shapes.medium)
                        .clickable { onEvent(AccountHubEvent.TogglePackageReport(pkg.id, report)) }
                        .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitCheckbox(
                        checked = on,
                        onCheckedChange = { onEvent(AccountHubEvent.TogglePackageReport(pkg.id, report)) },
                    )
                    ZillitIcon(
                        icon = when (report) {
                            ClosingReport.CostReport -> ZillitIcons.BarChart
                            ClosingReport.TrialBalance -> ZillitIcons.Ledger
                            ClosingReport.BibleReport -> ZillitIcons.File
                        },
                        tint = colors.textSecondary,
                        size = 14.dp,
                    )
                    ZillitText(
                        text = report.label,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

private val FORM_WIDTH = 560.dp
private val PUBLISH_WIDTH = 720.dp
private val HERO_ICON = 40.dp
private val NUMBER_CHIP = 25.dp
private val MENU_HEIGHT = 240.dp
private val CHART_HEIGHT = 170.dp
private val HEAT_LABEL = 84.dp
private val HEAT_CELL = 28.dp
private const val CHART_HEIGHT_PX = 150
private const val PERCENT = 100
private const val HEAT_SKELETONS = 4
private const val RECON_SKELETONS = 3
private const val DAY_MILLIS = 86_400_000L
private const val SHORT_HEX = 3
private const val FULL_HEX = 6
private const val HEX_RADIX = 16
private const val ALPHA_MASK = 0xFF000000L
private const val DONE_ALPHA = 0.7f

/** A UTC day (its midnight in millis) in words, read at noon so every zone within ±12h names the same day. */
private fun dayInWords(utcMidnight: Long?): String =
    utcMidnight?.let { EpochDate.date(it + DAY_MILLIS / 2) }.orEmpty()

/** The week's note, then the web's two bold flags after it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekNote(week: CommitmentWeek) {
    val colors = ZillitTheme.colors
    val flag = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (week.detail.isNotBlank()) FieldHint(week.detail)
        if (week.peak) ZillitText(text = str(S.desktop_hub_peak_week), style = flag, color = colors.danger)
        if (week.netflix) {
            ZillitText(text = str(S.desktop_hub_netflix_advance_due), style = flag, color = colors.success)
        }
    }
}
