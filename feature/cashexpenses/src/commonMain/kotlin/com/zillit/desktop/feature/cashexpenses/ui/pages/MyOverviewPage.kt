package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.RecentClaim
import com.zillit.desktop.feature.cashexpenses.domain.Settlement
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.date
import kotlin.math.roundToInt

/**
 * What one crew member sees about their own cash — the web's `PCCrewOverviewPage`.
 *
 * Two columns (`PCCrewOverviewPage.jsx:226-313`): the float position, any
 * receipts that came back, and the recent claims on the left; recent floats,
 * quick actions and a note about notifications on the right.
 */
@Suppress("LongMethod") // Two columns of the web's page, read as one screen.
@Composable
fun MyOverviewPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val overview = state.myOverview
    val people = LocalCashPeople.current
    val name = people.nameOf(state.viewer.userId)
    val designation = state.assignees.firstOrNull { it.userId == state.viewer.userId }?.designation
        ?.takeIf { it.isNotBlank() }
    val open = { destination: CashDestination -> onEvent(CashEvent.Open(destination)) }
    val pettyCash = overview?.pettyCashClaims.orEmpty()
    val outOfPocket = overview?.outOfPocketClaims.orEmpty()
    val needAttention = (pettyCash + outOfPocket).count {
        it.status == BatchStatus.Queried || it.status == BatchStatus.Rejected
    }

    ScrollingPage {
        OverviewNotice(
            title = str(S.desktop_pc_my_overview_title, name),
            body = designation ?: str(S.desktop_pc_crew_member_fallback),
            tone = StatusTone.Pending,
            icon = ZillitIcons.User,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(LEFT_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(COLUMN_GAP),
            ) {
                FloatPositionCard(state)
                if (needAttention > 0) {
                    OverviewNotice(
                        title = countText(
                            needAttention,
                            S.desktop_pc_receipts_attention_one,
                            S.desktop_card_need_attention,
                        ),
                        body = str(S.desktop_pc_receipts_attention_body),
                        tone = StatusTone.Rejected,
                        icon = ZillitIcons.Warning,
                    )
                }
                RecentClaims(
                    title = str(S.desktop_pc_recent_pc_claims),
                    empty = str(S.desktop_pc_no_pc_claims),
                    claims = pettyCash,
                    state = state,
                    onViewMore = { open(CashDestination.ReceiptsHistory) },
                )
                RecentClaims(
                    title = str(S.desktop_pc_recent_oop_claims),
                    empty = str(S.desktop_pc_no_oop_claims),
                    claims = outOfPocket,
                    state = state,
                    onViewMore = { open(CashDestination.OutOfPocketHistory) },
                )
            }

            Column(
                modifier = Modifier.weight(RIGHT_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(COLUMN_GAP),
            ) {
                RecentFloats(state, overview?.floats.orEmpty(), onEvent)
                QuickActions(open)
                OverviewNotice(
                    title = null,
                    body = str(S.desktop_pc_notifications_note),
                    tone = StatusTone.Progress,
                    icon = ZillitIcons.Info,
                )
            }
        }
    }
}

/**
 * The statuses the position card counts as an open float — wider than the
 * submittable ones (`PCCrewOverviewPage.jsx:191`).
 */
internal val OPEN_POSITION_STATUSES = setOf(
    FloatStatus.Active,
    FloatStatus.Spending,
    FloatStatus.Approved,
    FloatStatus.AcctOverride,
    FloatStatus.AwaitingApproval,
    FloatStatus.Spent,
)

/** Issued, spent and remaining on the oldest open float, with the spend bar. */
@Composable
private fun FloatPositionCard(state: CashUiState) {
    // myFloats arrives oldest-first, so [0] is the float being spent down.
    val openFloats = state.myFloats.filter { it.status in OPEN_POSITION_STATUSES }
    val active = openFloats.firstOrNull()
    val issued = active?.issuedAmount ?: 0.0
    val balance = active?.balance ?: issued
    val spent = (issued - balance).coerceAtLeast(0.0)
    val percent = if (issued > 0) ((spent / issued) * PERCENT).roundToInt().coerceAtMost(PERCENT.toInt()) else 0
    val currency = active?.currency

    OverviewCard {
        if (openFloats.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_PAD, vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = str(S.desktop_pc_float_number, active?.requestNumber?.ifBlank { null } ?: "—"),
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = str(S.desktop_pc_floats_open, openFloats.size),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            RowRule()
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_PAD, vertical = CARD_PAD)) {
            FigureStat(
                label = str(S.desktop_ce_float_issued),
                value = state.formatMoney(issued, currency),
                tone = StatusTone.Pending,
                modifier = Modifier.weight(1f),
            )
            FigureStat(
                label = str(S.desktop_pc_spent_returned),
                value = state.formatMoney(spent, currency),
                tone = StatusTone.Progress,
                modifier = Modifier.weight(1f),
            )
            FigureStat(
                label = str(S.desktop_pc_remaining_balance),
                value = state.formatMoney(balance, currency),
                tone = remainingTone(balance),
                modifier = Modifier.weight(1f),
            )
        }
        RowRule()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_PAD, vertical = ZillitTheme.spacing.lg)) {
            LabelledMeter(
                label = str(S.desktop_pc_spend_progress).uppercase(),
                trailing = str(S.desktop_pc_percent_spent, "$percent%"),
                fraction = percent / PERCENT.toFloat(),
                tone = if (percent >= PERCENT) StatusTone.Escalated else StatusTone.Pending,
            )
        }
    }
}

/** The first five, with "View more" into the full history when there are any. */
@Composable
private fun RecentClaims(
    title: String,
    empty: String,
    claims: List<RecentClaim>,
    state: CashUiState,
    onViewMore: () -> Unit,
) {
    val shown = claims.take(RECENT_CLAIMS)
    OverviewSection(
        title = title,
        right = if (claims.isEmpty()) null else ({ ViewMoreLink(viewMoreLabel, onViewMore) }),
    ) {
        if (shown.isEmpty()) {
            OverviewEmpty(empty)
        } else {
            shown.forEachIndexed { index, claim -> ClaimLine(claim, state, last = index == shown.lastIndex) }
        }
    }
}

/** The web's `ClaimRow` (`PCCrewOverviewPage.jsx:89-111`). */
@Composable
private fun ClaimLine(claim: RecentClaim, state: CashUiState, last: Boolean) {
    OverviewRow(last = last) {
        IconChip(ZillitIcons.Receipt, StatusTone.Pending)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = "#" + claim.batchReference,
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (claim.description.isNotBlank()) {
                    ZillitText(
                        text = " · " + claim.description,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            ZillitText(
                text = listOfNotNull(
                    Settlement.label(claim.settlementType),
                    claim.category?.takeIf { it.isNotBlank() }?.let { ExpenseCategory.label(it) },
                    date(claim.date),
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = state.formatMoney(claim.grossAmount, claim.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            BatchStatusPill(claim.status, accountant = false)
        }
    }
}

/** The first three floats as cards; a click opens the float's details. */
@Composable
private fun RecentFloats(state: CashUiState, floats: List<CashFloat>, onEvent: (CashEvent) -> Unit) {
    val shown = floats.take(RECENT_FLOATS)
    OverviewSection(
        title = str(S.desktop_pc_recent_floats),
        right = if (floats.isEmpty()) {
            null
        } else {
            { ViewMoreLink(viewMoreLabel) { onEvent(CashEvent.Open(CashDestination.FloatRequest)) } }
        },
    ) {
        if (shown.isEmpty()) {
            OverviewEmpty(str(S.desktop_pc_no_floats_yet))
        } else {
            shown.forEachIndexed { index, float ->
                RecentFloatCard(state, float, onClick = { onEvent(CashEvent.OpenFloatDetail(float.id)) })
                if (index != shown.lastIndex) RowRule()
            }
        }
    }
}

@Composable
private fun RecentFloatCard(state: CashUiState, float: CashFloat, onClick: () -> Unit) {
    val issued = float.issuedAmount
    val balance = float.balance
    val spent = (issued - balance).coerceAtLeast(0.0)
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = "#" + float.requestNumber,
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = date(float.createdAt),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            FloatStatusPill(float.status)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            MiniStat(str(S.desktop_issued), state.formatMoney(issued, float.currency), null, Modifier.weight(1f))
            MiniStat(
                str(S.ah_spent_label),
                state.formatMoney(spent, float.currency),
                StatusTone.Progress,
                Modifier.weight(1f),
            )
            MiniStat(
                str(S.ah_lbl_remaining),
                state.formatMoney(balance, float.currency),
                remainingTone(balance),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, tone: StatusTone?, modifier: Modifier) {
    val shape = RoundedCornerShape(MINI_RADIUS)
    Column(
        modifier = modifier
            .border(1.dp, ZillitTheme.colors.border, shape)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
    ) {
        FigureStat(label = label, value = value, tone = tone, large = false)
    }
}

/** The three shortcuts (`PCCrewOverviewPage.jsx:300-308`). */
@Composable
private fun QuickActions(open: (CashDestination) -> Unit) {
    OverviewCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CARD_PAD),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = str(S.desktop_pc_quick_actions), style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = str(S.desktop_pc_quick_actions_sub),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            QuickAction(ZillitIcons.Upload, S.desktop_pc_qa_submit, S.desktop_pc_qa_submit_sub) {
                open(CashDestination.SubmitReceipts)
            }
            QuickAction(ZillitIcons.Add, S.desktop_pc_qa_request, S.desktop_pc_qa_request_sub) {
                open(CashDestination.FloatRequest)
            }
            QuickAction(ZillitIcons.Info, S.desktop_pc_qa_query, S.desktop_pc_qa_query_sub) {
                open(CashDestination.ReceiptsHistory)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, titleKey: String, subKey: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MINI_RADIUS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ZillitTheme.colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        IconChip(icon, StatusTone.Pending, size = QUICK_CHIP)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(titleKey),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitText(
                text = str(subKey),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitIcon(icon = ZillitIcons.ChevronRight, tint = ZillitTheme.colors.textMuted, size = QUICK_CHEVRON)
    }
}

private const val LEFT_WEIGHT = 1.7f
private const val RIGHT_WEIGHT = 1f
private const val RECENT_CLAIMS = 5
private const val RECENT_FLOATS = 3
private const val PERCENT = 100.0
private val COLUMN_GAP = 18.dp
private val CARD_PAD = 18.dp
private val MINI_RADIUS = 8.dp
private val QUICK_CHIP = 30.dp
private val QUICK_CHEVRON = 14.dp
