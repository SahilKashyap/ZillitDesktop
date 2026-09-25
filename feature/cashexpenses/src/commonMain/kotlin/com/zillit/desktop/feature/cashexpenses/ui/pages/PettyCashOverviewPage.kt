package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashStats
import com.zillit.desktop.feature.cashexpenses.domain.CashSummary
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill

/**
 * The accountant's petty-cash dashboard — the web's `PCOverviewPage`.
 *
 * A notice, four tiles, then two columns: the Action Queue over the Period
 * Summary on the left, the Float Register on the right
 * (`PCOverviewPage.jsx:384-617`). Aggregates are in the project default
 * currency, each float's balance in its own.
 */
@Suppress("LongMethod") // A dashboard: notice, tiles and two columns read as one screen.
@Composable
fun PettyCashOverviewPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val overview = state.pettyCashOverview
    val stats = overview?.stats ?: CashStats()
    val summary = overview?.summary ?: CashSummary()
    val open = { destination: CashDestination -> onEvent(CashEvent.Open(destination)) }

    ScrollingPage {
        OverviewNotice(
            title = str(S.desktop_pc_accounts_overview),
            body = str(S.desktop_pc_accounts_overview_body),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Grid,
        )

        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_active_floats),
                    value = stats.activeFloats.toString(),
                    sub = str(S.desktop_ce_funds_outstanding, state.formatAggregate(stats.totalOutstanding)),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_awaiting_audit),
                    value = stats.awaitingAudit.toString(),
                    sub = str(S.desktop_pc_receipt_batches_in_queue),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Receipt,
                ),
                StatTileSpec(
                    label = str(S.av_subtab_awaiting_approval),
                    value = stats.awaitingApproval.toString(),
                    sub = str(S.desktop_pc_pc_batches_in_queue),
                    tone = StatusTone.Escalated,
                    icon = ZillitIcons.Check,
                ),
                StatTileSpec(
                    label = str(S.ah_ready_to_post),
                    value = stats.readyToPost.toString(),
                    sub = str(S.desktop_ce_amount_to_ledger, state.formatAggregate(stats.readyToPostAmount)),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Ledger,
                ),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
        ) {
            Column(
                modifier = Modifier.weight(LEFT_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
            ) {
                ActionQueue(actionItems(state, stats, summary), open)
                PeriodSummary(state, summary)
            }
            FloatRegister(
                state = state,
                floats = overview?.floats.orEmpty(),
                onEvent = onEvent,
                modifier = Modifier.weight(RIGHT_WEIGHT),
            )
        }
    }
}

/** One line of the Action Queue. */
internal data class ActionItem(
    val icon: ImageVector,
    val tone: StatusTone,
    val title: String,
    val sub: String,
    val badge: String,
    val target: CashDestination,
)

/** The web's `actionItems` (`PCOverviewPage.jsx:304-350`): each only when its count is above zero. */
internal fun actionItems(state: CashUiState, stats: CashStats, summary: CashSummary): List<ActionItem> =
    listOfNotNull(
        ActionItem(
            icon = ZillitIcons.Receipt,
            tone = StatusTone.Progress,
            title = countText(stats.awaitingAudit, S.desktop_pc_awaiting_audit_one, S.desktop_pc_awaiting_audit_other),
            sub = str(S.desktop_pc_awaiting_audit_sub),
            badge = str(S.accounts),
            target = CashDestination.AuditQueue,
        ).takeIf { stats.awaitingAudit > 0 },
        ActionItem(
            icon = ZillitIcons.Check,
            tone = StatusTone.Escalated,
            title = countText(
                stats.awaitingApproval,
                S.desktop_pc_awaiting_approver_one,
                S.desktop_pc_awaiting_approver_other,
            ),
            sub = str(S.desktop_pc_awaiting_approver_sub),
            badge = str(S.desktop_approver),
            target = CashDestination.ApprovalQueue,
        ).takeIf { stats.awaitingApproval > 0 },
        ActionItem(
            icon = ZillitIcons.Ledger,
            tone = StatusTone.Ready,
            title = countText(stats.readyToPost, S.desktop_pc_ready_to_post_one, S.desktop_pc_ready_to_post_other),
            sub = str(S.desktop_ce_amount_to_ledger, state.formatAggregate(stats.readyToPostAmount)),
            badge = str(S.accounts),
            target = CashDestination.PostLedger,
        ).takeIf { stats.readyToPost > 0 },
        ActionItem(
            icon = ZillitIcons.Shield,
            tone = StatusTone.Pending,
            title = countText(stats.escalated, S.desktop_pc_escalated_one, S.desktop_pc_escalated_other),
            sub = str(S.desktop_pc_escalated_sub),
            badge = str(S.desktop_senior),
            target = CashDestination.PettyCashSignOff,
        ).takeIf { stats.escalated > 0 },
        ActionItem(
            icon = ZillitIcons.Ledger,
            tone = StatusTone.Rejected,
            title = str(S.desktop_pc_bacs_waiting, state.formatAggregate(summary.oopBacsQueued)),
            sub = str(S.desktop_pc_bacs_waiting_sub),
            badge = str(S.ah_topup_filter_urgent),
            target = CashDestination.PaymentRouting,
        ).takeIf { summary.oopBacsQueued > 0 },
    )

@Composable
private fun ActionQueue(items: List<ActionItem>, open: (CashDestination) -> Unit) {
    OverviewSection(
        title = str(S.desktop_pc_action_queue),
        sub = str(S.desktop_pc_action_queue_sub),
        right = if (items.isEmpty()) {
            null
        } else {
            {
                ZillitStatusPill(
                    label = countText(items.size, S.desktop_docdist_one_item, S.desktop_docdist_items_count),
                    tone = StatusTone.Rejected,
                    dot = true,
                )
            }
        },
    ) {
        if (items.isEmpty()) {
            OverviewEmpty(str(S.desktop_pc_all_clear))
        } else {
            items.forEachIndexed { index, item ->
                OverviewRow(last = index == items.lastIndex, onClick = { open(item.target) }) {
                    IconChip(item.icon, item.tone)
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = item.title,
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        ZillitText(
                            text = item.sub,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                    ZillitStatusPill(label = item.badge, tone = item.tone)
                    ZillitIcon(icon = ZillitIcons.ChevronRight, tint = ZillitTheme.colors.textMuted, size = CHEVRON)
                }
            }
        }
    }
}

/** The web's `summaryRows` (`PCOverviewPage.jsx:352-382`), signs and colours included. */
@Suppress("LongMethod") // Six rows with the web's signs and colours; a table in all but name.
@Composable
private fun PeriodSummary(state: CashUiState, summary: CashSummary) {
    val rows = listOf(
        SummaryRow(str(S.desktop_pc_total_pc_floats_issued), state.formatAggregate(summary.totalPettyCashIssued)),
        SummaryRow(
            str(S.desktop_pc_total_receipts_approved),
            "−" + state.formatAggregate(summary.totalReceiptsApproved),
            StatusTone.Rejected,
        ),
        SummaryRow(
            str(S.desktop_pc_cash_still_to_account),
            state.formatAggregate(summary.cashToAccount),
            highlight = true,
        ),
        SummaryRow(
            str(S.desktop_pc_input_tax_recoverable),
            state.formatAggregate(summary.vatRecoverable),
            StatusTone.Progress,
        ),
        SummaryRow(
            str(S.desktop_pc_oop_bacs_queued),
            "−" + state.formatAggregate(summary.oopBacsQueued),
            StatusTone.Rejected,
        ),
        SummaryRow(
            str(S.desktop_pc_oop_payroll_additions),
            "−" + state.formatAggregate(summary.oopPayrollAdditions),
            StatusTone.Rejected,
        ),
    )
    OverviewSection(title = str(S.desktop_pc_period_summary), sub = str(S.desktop_pc_period_summary_sub)) {
        rows.forEachIndexed { index, row ->
            OverviewRow(
                last = index == rows.lastIndex,
                modifier = if (row.highlight) {
                    Modifier.background(ZillitTheme.colors.accentSoft)
                } else {
                    Modifier
                },
            ) {
                ZillitText(
                    text = row.label,
                    style = if (row.highlight) {
                        ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        ZillitTheme.typography.bodyMedium
                    },
                    color = if (row.highlight) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = row.value,
                    style = if (row.highlight) {
                        ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold)
                    } else {
                        ZillitTheme.typography.numeric
                    },
                    color = when {
                        row.highlight -> ZillitTheme.colors.accentText
                        row.tone != null -> toneColor(row.tone)
                        else -> ZillitTheme.colors.textPrimary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private data class SummaryRow(
    val label: String,
    val value: String,
    val tone: StatusTone? = null,
    val highlight: Boolean = false,
)

/**
 * The Float Register (`PCOverviewPage.jsx:523-614`): Crew Member (with the
 * department under the name), Float, Balance, Age, Status; + New Float and
 * "View full register".
 */
@Composable
private fun FloatRegister(
    state: CashUiState,
    floats: List<CashFloat>,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewSection(
        title = str(S.desktop_pc_float_register),
        sub = str(S.desktop_pc_float_register_sub),
        modifier = modifier,
        right = {
            ZillitButton(
                text = str(S.ah_new_float),
                onClick = { onEvent(CashEvent.RaiseFloatForCrew) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = REGISTER_PAD, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RegisterHeader(str(S.crew_member), Modifier.weight(1f))
            RegisterHeader(str(S.ah_col_float), Modifier.width(FLOAT_COL))
            RegisterHeader(str(S.ah_balance_label), Modifier.width(BALANCE_COL))
            RegisterHeader(str(S.desktop_pc_age), Modifier.width(AGE_COL))
            RegisterHeader(str(S.status), Modifier.width(STATUS_COL))
        }
        RowRule()
        if (floats.isEmpty()) {
            OverviewEmpty(str(S.desktop_pc_no_active_floats))
        } else {
            floats.forEachIndexed { index, float -> RegisterRow(state, float, last = index == floats.lastIndex) }
        }
        RowRule()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier
                    .clickable { onEvent(CashEvent.Open(CashDestination.ActiveFloats)) }
                    .padding(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = str(S.desktop_pc_view_full_register),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitIcon(icon = ZillitIcons.ArrowRight, tint = ZillitTheme.colors.textSecondary, size = CHEVRON)
            }
        }
    }
}

@Composable
private fun RegisterHeader(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun RegisterRow(state: CashUiState, float: CashFloat, last: Boolean) {
    val balance = float.registerBalance
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = REGISTER_PAD, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CashPerson(
                userId = float.userId,
                recordedName = float.holderName,
                secondary = state.departmentName(float.departmentId) ?: float.departmentId,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = float.requestNumber.ifBlank { "—" },
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(FLOAT_COL),
            )
            ZillitText(
                text = (if (balance < 0) "−" else "") + state.formatMoney(kotlin.math.abs(balance), float.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = if (balance >= 0) ZillitTheme.colors.success else ZillitTheme.colors.danger,
                maxLines = 1,
                modifier = Modifier.width(BALANCE_COL),
            )
            ZillitText(
                text = str(S.desktop_pc_age_days, daysSince(float.createdAt)),
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(AGE_COL),
            )
            Row(modifier = Modifier.width(STATUS_COL)) { FloatStatusPill(float.status) }
        }
        if (!last) RowRule()
    }
}

private const val LEFT_WEIGHT = 1f
private const val RIGHT_WEIGHT = 1.1f
private val CHEVRON = 15.dp
private val REGISTER_PAD = 18.dp
private val FLOAT_COL = 90.dp
private val BALANCE_COL = 110.dp
private val AGE_COL = 60.dp
private val STATUS_COL = 130.dp
