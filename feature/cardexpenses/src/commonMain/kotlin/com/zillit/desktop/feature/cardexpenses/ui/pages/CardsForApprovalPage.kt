package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.TierVisibility
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewApprovalTab
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.PersonCell
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * The cardholder's Approval Queue (`CardsForApprovalPage.jsx`): card requests
 * this viewer signs next, and the receipts waiting on them.
 *
 * Its own page rather than the accountant's register: the web routes this
 * one only for crew, with two sections, a card tile per request and a plain
 * table of receipts.
 */
@Composable
fun CardsForApprovalPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    LaunchedEffect(Unit) { onEvent(CrewEvent.Prime) }
    val crew = state.crew
    crew.approvalCards.firstOrNull { it.id == crew.approvalCardId }?.let { open ->
        ApprovalCardDetail(state, open, onEvent)
        return
    }

    CrewScrollPage {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            SectionTab(
                title = str(S.ah_cards),
                caption = str(S.desktop_pc_pending_approval_count, crew.approvalCards.size),
                icon = ZillitIcons.CreditCard,
                selected = crew.approvalTab == CrewApprovalTab.Cards,
                onClick = { onEvent(CrewEvent.ShowApprovalTab(CrewApprovalTab.Cards)) },
                modifier = Modifier.weight(1f),
            )
            SectionTab(
                title = str(S.desktop_ce_crew_receipts_transactions),
                caption = str(S.desktop_docdist_items_count, crew.approvalReceipts.size),
                icon = ZillitIcons.File,
                selected = crew.approvalTab == CrewApprovalTab.Receipts,
                onClick = { onEvent(CrewEvent.ShowApprovalTab(CrewApprovalTab.Receipts)) },
                modifier = Modifier.weight(1f),
            )
        }

        when (crew.approvalTab) {
            CrewApprovalTab.Cards -> when {
                state.loading && crew.approvalCards.isEmpty() -> CenteredNote(str(S.ah_loading))
                crew.approvalCards.isEmpty() -> CenteredNote(str(S.desktop_ce_crew_no_cards_pending))
                else -> CrewCardGrid(crew.approvalCards, key = { it.id }) { card ->
                    ApprovalCardTile(state, card, onEvent)
                }
            }

            CrewApprovalTab.Receipts ->
                if (crew.approvalReceipts.isEmpty()) {
                    CenteredNote(str(S.desktop_ce_crew_no_receipts_pending))
                } else {
                    ZillitDataTable(
                        rows = crew.approvalReceipts,
                        columns = receiptColumns(state, onEvent),
                        key = { it.id },
                        onRowClick = { onEvent(CrewEvent.OpenApprovalReceipt(it.id)) },
                        virtualised = false,
                    )
                }
        }
    }
}

/** One of the two section buttons over the queue (`:246-274`). */
@Composable
private fun SectionTab(
    title: String,
    caption: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.surfaceSunken else colors.surface)
            .border(
                if (selected) SELECTED_BORDER else CREW_HAIRLINE,
                if (selected) colors.accent else colors.border,
                ZillitTheme.shapes.large,
            )
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        IconWell(icon)
        Column {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
            ZillitText(text = caption, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

private fun receiptColumns(state: CardUiState, onEvent: (CardEvent) -> Unit): List<TableColumn<CardReceipt>> = listOf(
    TableColumn(str(S.date), ColumnWidth.Fixed(DATE_COLUMN)) { receipt ->
        ZillitText(text = date(receipt.date), style = ZillitTheme.typography.numeric)
    },
    TableColumn(str(S.ah_merchant), ColumnWidth.Weight(2f)) { receipt ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = receipt.description.ifBlank { "—" }, maxLines = 1)
            // The receipt's own unread (`CardsForApprovalPage.jsx:322-325`).
            ZillitBadge(count = state.unreadRow("receipt_approval_queue", receipt.id))
            if (receipt.urgent) UrgentPill()
        }
    },
    TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(HOLDER_WEIGHT)) { receipt ->
        val name = state.personName(receipt.holderId)
        if (name == "—") ZillitText(text = name) else PersonCell(name = name, userId = receipt.holderId)
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(AMOUNT_COLUMN), numeric = true) { receipt ->
        ZillitText(text = money(receipt.amount, receipt.currency), style = ZillitTheme.typography.numeric)
    },
    TableColumn(str(S.code), ColumnWidth.Fixed(CODE_COLUMN)) { receipt ->
        ZillitText(
            text = receipt.nominalCode?.takeIf { it.isNotBlank() } ?: "—",
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.accentText,
        )
    },
    TableColumn(str(S.status), ColumnWidth.Fixed(STATUS_COLUMN)) { _ ->
        ZillitStatusPill(str(S.dm_filter_status_pending), tone = StatusTone.Pending, dot = true)
    },
    TableColumn("", ColumnWidth.Fixed(ACTION_COLUMN)) { receipt ->
        val busy = state.crew.actionId == receipt.id
        ZillitButton(
            text = if (busy) "…" else str(S.approve),
            onClick = { onEvent(CrewEvent.ApproveReceipt(receipt.id)) },
            size = ButtonSize.Small,
            enabled = !busy,
        )
    },
)

/** Where a queued request stands for this viewer — the web's `getApprovalVisibility`. */
private fun CardUiState.step(card: ExpenseCard): Pair<List<List<String>>?, TierVisibility> {
    val chain = ApprovalTiers.resolve(viewer.metadata.tierConfigs, card.departmentId, card.monthlyLimit)
    return chain to ApprovalTiers.visibility(chain, card.approvals, viewer.userId)
}

/**
 * A pending card request as a tile (`CardItem.jsx`, pending branch): who,
 * the control code, the proposed limit, the chain, and Reject / Approve for
 * the person the next tier names.
 */
@Composable
private fun ApprovalCardTile(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val (chain, step) = state.step(card)
    CrewTile(onClick = { onEvent(CrewEvent.OpenApprovalCard(card.id)) }) {
        // The card's own unread (`CardsForApprovalPage.jsx:290`, drawn by `CardItem.jsx:96`).
        CardSummary(state, card, chain, step, unread = state.unreadRow("card_approval_queue", card.id))
        if (step.canApprove) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Spacer(Modifier.weight(1f))
                ApprovalButtons(state, card, onEvent)
            }
        }
    }
}

@Composable
private fun ApprovalButtons(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val busy = state.crew.actionId == card.id
    ZillitButton(
        text = str(S.reject),
        onClick = { onEvent(CrewEvent.AskReject(card.id, receipt = false)) },
        variant = ButtonVariant.Danger,
        size = ButtonSize.Small,
        enabled = !busy,
    )
    ZillitButton(
        text = if (busy) "…" else str(S.approve),
        onClick = { onEvent(CrewEvent.ApproveCard(card.id)) },
        size = ButtonSize.Small,
        enabled = !busy,
    )
}

@Composable
private fun CardSummary(
    state: CardUiState,
    card: ExpenseCard,
    chain: List<List<String>>?,
    step: TierVisibility,
    unread: Int = 0,
) {
    val colors = ZillitTheme.colors
    val holder = state.people.firstOrNull { it.id == card.holderId }
    val limit = card.proposedLimit ?: card.monthlyLimit ?: card.limit
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZillitIcon(ZillitIcons.CreditCard, tint = colors.gold)
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        ZillitBadge(count = unread)
        Spacer(Modifier.weight(1f))
        ZillitStatusPill(
            label = if (step.totalTiers > 0) {
                str(S.ah_status_pending_progress, card.approvals.size, step.totalTiers)
            } else {
                str(S.desktop_ce_cards_pending_approval)
            },
            tone = StatusTone.Pending,
            dot = true,
        )
    }
    ZillitText(
        text = str(S.desktop_ce_cards_pending_approval),
        style = ZillitTheme.typography.label,
        color = colors.warning,
    )
    ZillitText(
        text = holder?.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_card_card_holder),
        style = ZillitTheme.typography.titleSmall,
    )
    val sub = listOfNotNull(
        holder?.designation?.takeIf { it.isNotBlank() },
        state.issuerName(card),
    ).joinToString(" · ")
    if (sub.isNotBlank()) {
        ZillitText(text = sub, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_ce_insights_bs_control),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = card.bsControlCode?.takeIf { it.isNotBlank() } ?: "—",
            style = ZillitTheme.typography.titleSmall,
        )
    }
    if (limit > 0) {
        CrewField(str(S.desktop_card_proposed_limit), str(S.desktop_ce_crew_per_month, money(limit, card.currency)))
    }
    if (chain != null) ApprovalChain(state, card, chain)
}

/**
 * The chain as steps: signed (who), current, and waiting — the web's
 * `buildApprovalChainSteps`.
 */
@Suppress("CyclomaticComplexMethod") // Three step states, each coloured twice.
@Composable
private fun ApprovalChain(state: CardUiState, card: ExpenseCard, chain: List<List<String>>) {
    val colors = ZillitTheme.colors
    val signed = card.approvals.associateBy { it.tierNumber }
    Row(verticalAlignment = Alignment.Top) {
        chain.indices.forEach { index ->
            val tier = index + 1
            val approval = signed[tier]
            val current = approval == null && (tier == 1 || signed.containsKey(tier - 1))
            Column(
                modifier = Modifier.widthIn(min = STEP_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Box(
                    modifier = Modifier
                        .size(STEP_DOT)
                        .clip(CircleShape)
                        .background(
                            when {
                                approval != null -> colors.success
                                current -> colors.accent
                                else -> colors.border
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (approval != null) {
                        ZillitIcon(ZillitIcons.Check, tint = colors.textOnAccent, size = ZillitDimens.iconSmall)
                    }
                }
                ZillitText(
                    text = str(S.desktop_level_n, tier),
                    style = ZillitTheme.typography.labelSmall,
                    color = when {
                        approval != null -> colors.success
                        current -> colors.accentText
                        else -> colors.textMuted
                    },
                )
                ZillitText(
                    text = approval?.let { state.personName(it.userId) } ?: str(S.ds_sent_filter_awaiting),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (index < chain.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(top = STEP_DOT / 2)
                        .width(STEP_RULE)
                        .height(CREW_HAIRLINE * 2)
                        .background(if (approval != null) colors.success else colors.border),
                )
            }
        }
    }
}

/**
 * A card request opened from the queue, taking over the page (`:213-239`):
 * the whole request, its chain, and Reject / Approve for the next approver.
 * Closes on its own once the request leaves the queue.
 */
@Composable
private fun ApprovalCardDetail(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val (chain, step) = state.step(card)
    CrewScrollPage {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.back),
                onClick = { onEvent(CrewEvent.OpenApprovalCard(null)) },
            )
            ZillitText(
                text = str(S.desktop_ce_crew_card_request),
                style = ZillitTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (step.canApprove) ApprovalButtons(state, card, onEvent)
        }
        CrewTile(modifier = Modifier.widthIn(max = DETAIL_MAX)) {
            CardSummary(state, card, chain, step)
            val department = state.people.firstOrNull { it.id == card.holderId }?.department
            if (!department.isNullOrBlank()) CrewField(str(S.department), department)
            card.justification?.takeIf { it.isNotBlank() }?.let {
                CrewField(str(S.desktop_card_justification), it)
            }
            card.createdAt?.let { CrewField(str(S.txt_submitted), date(it)) }
        }
    }
}

/** "Reject Receipt" / "Reject Card Request" — a reason is required (`:364-384`). */
@Composable
internal fun CrewRejectDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val reject = state.crew.reject
    val busy = reject != null && state.crew.actionId == reject.targetId
    ZillitDialogShell(
        title = str(
            if (reject?.receipt == true) S.desktop_ce_process_reject_receipt else S.desktop_ce_cards_reject_title,
        ),
        visible = reject != null,
        onDismiss = { onEvent(CrewEvent.CloseReject) },
        icon = ZillitIcons.Warning,
        width = REJECT_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (busy) str(S.ah_run_detail_btn_rejecting) else str(S.reject),
                onClick = { onEvent(CrewEvent.ConfirmReject) },
                variant = ButtonVariant.Danger,
                enabled = reject?.reason?.isNotBlank() == true && !busy,
            )
        },
    ) {
        val open = reject ?: return@ZillitDialogShell
        ZillitText(
            text = if (open.receipt) {
                str(S.desktop_ce_crew_reject_receipt_body, open.subject)
            } else {
                str(S.desktop_ce_cards_reject_body, open.subject)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(CrewEvent.EditReject(it)) },
            label = str(S.reason) + " *",
            placeholder = str(S.desktop_ce_crew_reason_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth().height(REASON_HEIGHT),
        )
    }
}

private val SELECTED_BORDER = 2.dp
private val DATE_COLUMN = 110.dp
private val AMOUNT_COLUMN = 120.dp
private val CODE_COLUMN = 100.dp
private val STATUS_COLUMN = 170.dp
private val ACTION_COLUMN = 110.dp
private val STEP_WIDTH = 72.dp
private val STEP_DOT = 26.dp
private val STEP_RULE = 24.dp
private val DETAIL_MAX = 720.dp
private val REJECT_WIDTH = 480.dp
private val REASON_HEIGHT = 110.dp
private const val HOLDER_WEIGHT = 1.5f
