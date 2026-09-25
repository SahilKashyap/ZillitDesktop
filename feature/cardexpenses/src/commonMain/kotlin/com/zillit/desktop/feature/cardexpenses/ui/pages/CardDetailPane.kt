package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardChain
import com.zillit.desktop.feature.cardexpenses.domain.CardChainStep
import com.zillit.desktop.feature.cardexpenses.domain.CardNumbers
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.ChainStepStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.StepDot
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.defaultCurrency
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.tone

/**
 * One card, full-page — the web's `CardDetailModal.jsx:532-1082`, taking over
 * the content column while the sidebar stays.
 *
 * A breadcrumb header with the card's actions; the approval chain, a
 * rejection or an "Action Needed" nudge above the body; then a 360px details
 * column beside the card's receipts. History opens in a side panel.
 *
 * The lifecycle actions are the register's (an accountant's); the Card tab
 * opens the same page with the chain for reading, Edit and Delete.
 */
@Composable
fun CardDetailPage(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        DetailHeader(state, card, onEvent)
        ZillitDivider()
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ZillitScrollColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = SECTION_GAP),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                if (card.status == CardStatus.Pending) ApprovalPanel(state, card, onEvent)
                if (card.status == CardStatus.Rejected && !card.rejectionReason.isNullOrBlank()) {
                    RejectionPanel(state, card)
                }
                if (card.status == CardStatus.Requested && state.viewer.isAccountant) ActionNeeded(card, onEvent)
                DetailBody(state, card, onEvent)
            }
            if (state.cardsArea.historyOpen) {
                ZillitVerticalDivider()
                HistoryPanel(state, card, onEvent)
            }
        }
    }
}

/** The breadcrumb and the card's actions (`CardDetailModal.jsx:536-594`). */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod") // The header's action set, one gate per button.
@Composable
private fun DetailHeader(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val viewer = state.viewer
    val accountant = viewer.isAccountant
    val live = card.live
    val busy = state.busy
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_GUTTER, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_ce_cards_back_to_cards),
                onClick = { onEvent(CardsEvent.OpenCard(null)) },
            )
            ZillitText(
                text = str(S.ah_card_expenses).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accentText,
                modifier = Modifier.clickable { onEvent(CardsEvent.OpenCard(null)) },
            )
            Crumb("/")
            Crumb(str(S.ah_cards))
            Crumb("/")
            ZillitText(
                text = "•••• " + if (live) card.lastFour ?: "0000" else "••••",
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitStatusPill(label = detailStatusLabel(card, accountant), tone = card.status.tone, dot = true)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            HeaderButton(str(S.history), ZillitIcons.Clock) { onEvent(CardsEvent.ShowHistory(true)) }
            val editable = CardRules.canEditRequest(card, viewer.userId, accountant)
            if (editable && !(accountant && card.status == CardStatus.Requested)) {
                HeaderButton(str(S.edit), ZillitIcons.Edit, !busy) { onEvent(CardEvent.OpenCardEdit(card.id)) }
            }
            if (accountant && card.status == CardStatus.Active) {
                ZillitButton(
                    text = str(S.desktop_card_suspend),
                    onClick = { onEvent(CardsEvent.Suspend(card.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                    loading = busy && state.cardsArea.actionCardId == card.id,
                )
            }
            if (accountant && card.status == CardStatus.Suspended) {
                ZillitButton(
                    text = str(S.desktop_card_reactivate),
                    onClick = { onEvent(CardsEvent.Reactivate(card.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                    loading = busy && state.cardsArea.actionCardId == card.id,
                )
            }
            if (accountant && (card.status == CardStatus.Approved || card.status == CardStatus.Override)) {
                ZillitButton(
                    text = str(S.desktop_card_activate_assign_number),
                    onClick = { onEvent(CardEvent.OpenActivation(card.id)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.CreditCard,
                    enabled = !busy,
                )
            }
            if (accountant && card.isDigitalActive) {
                HeaderButton(str(S.desktop_ce_cards_assign_physical), ZillitIcons.CreditCard, !busy) {
                    onEvent(CardsEvent.AskAssignPhysical(card.id))
                }
            }
            if (CardRules.canDeleteRequest(card, viewer.userId)) {
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(CardsEvent.AskDelete(card.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun Crumb(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

@Composable
private fun HeaderButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = icon,
        enabled = enabled,
    )
}

/**
 * The chain above the body, with Approve / Reject — or Override for whoever
 * the chain does not name — beside it. With no level-1 approver for the
 * card's department the accountant is sent to set one instead
 * (`CardDetailModal.jsx:605-705`).
 */
@Suppress("LongMethod") // The panel and its banner are one decision.
@Composable
private fun ApprovalPanel(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val viewer = state.viewer
    val configs = viewer.metadata.tierConfigs
    if (viewer.isAccountant && CardChain.needsApprovalLevel(card, configs)) {
        Banner(tone = StatusTone.Pending) {
            ZillitText(
                text = str(S.desktop_pc_no_tiers_for_department),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.warning,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_pc_set_approval_level_arrow),
                onClick = { onEvent(CardsEvent.OpenApprovers) },
                size = ButtonSize.Small,
            )
        }
        return
    }
    val steps = CardChain.steps(card, configs)
    val step = state.cardApproval(card)
    val busy = state.busy
    Panel {
        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                FieldGroupLabel(str(S.ah_approval_chain))
                ZillitText(
                    text = str(
                        S.av_approval_progress,
                        steps.count { it.status == ChainStepStatus.Approved },
                        steps.size,
                    ),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
            Box(Modifier.width(1.dp).height(CHAIN_RULE).background(ZillitTheme.colors.border))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Top,
            ) {
                steps.forEachIndexed { index, node ->
                    ChainNode(state, node)
                    if (index < steps.lastIndex) {
                        Box(
                            Modifier
                                .padding(top = 13.dp)
                                .width(28.dp)
                                .height(2.dp)
                                .clip(ZillitTheme.shapes.pill)
                                .background(
                                    if (node.status == ChainStepStatus.Approved) {
                                        ZillitTheme.colors.success
                                    } else {
                                        ZillitTheme.colors.border
                                    },
                                ),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (viewer.isAccountant && viewer.canOverrideCard && !step.canApprove) {
                ZillitButton(
                    text = str(S.dm_nom_table_override),
                    onClick = { onEvent(CardsEvent.AskOverride(card.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
            if (viewer.isAccountant && step.canApprove) {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onEvent(CardsEvent.AskReject(card.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = str(S.approve),
                    onClick = { onEvent(CardsEvent.Approve(card.id)) },
                    size = ButtonSize.Small,
                    enabled = !busy,
                    loading = busy && state.cardsArea.actionCardId == card.id,
                )
            }
        }
    }
}

/** One level: who signed it and when, or who may. */
@Composable
private fun ChainNode(state: CardUiState, node: CardChainStep) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.widthIn(min = 92.dp, max = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepDot(node.status, onCard = false)
        ZillitText(
            text = str(S.desktop_level_n, node.tier).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (node.status == ChainStepStatus.Approved) {
            val approver = state.people.firstOrNull { it.id == node.approverId }
            ZillitText(
                text = approver?.name ?: node.approverId.orEmpty(),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.success,
                maxLines = 2,
            )
            approver?.designation?.takeIf { it.isNotBlank() }?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.success)
            }
            node.approvedAt?.let {
                ZillitText(text = date(it), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
        } else {
            ZillitText(
                text = if (node.status == ChainStepStatus.Current) {
                    str(S.av_subtab_awaiting_approval)
                } else {
                    str(S.pending)
                },
                style = ZillitTheme.typography.bodySmall.copy(
                    fontWeight = if (node.status == ChainStepStatus.Current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (node.status == ChainStepStatus.Current) colors.accentText else colors.textMuted,
            )
            node.approverIds.takeIf { it.isNotEmpty() }?.let { ids ->
                ZillitText(
                    text = ids.joinToString(", ") { id -> state.people.firstOrNull { it.id == id }?.name ?: id },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 3,
                )
            }
        }
    }
}

/** The reason, and who rejected it when — the only workflow record a rejected card keeps. */
@Composable
private fun RejectionPanel(state: CardUiState, card: ExpenseCard) {
    val by = card.rejectedBy?.let { id -> state.people.firstOrNull { it.id == id }?.name }
    Banner(tone = StatusTone.Rejected) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel(str(S.cs_rejection_reason))
            ZillitText(text = card.rejectionReason.orEmpty(), color = ZillitTheme.colors.danger)
            if (by != null || card.rejectedAt != null) {
                ZillitText(
                    text = listOfNotNull(
                        by?.let { str(S.desktop_pc_rejected_by, it) } ?: str(S.rejected),
                        card.rejectedAt?.let(::date),
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

/** A freshly requested card, nudging the accountant to review it before it goes for approval. */
@Composable
private fun ActionNeeded(card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    Banner(tone = StatusTone.Pending) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel(str(S.desktop_ce_cards_action_needed))
            ZillitText(text = str(S.desktop_ce_cards_action_needed_body), color = ZillitTheme.colors.warning)
        }
        ZillitButton(
            text = str(S.av_review),
            onClick = { onEvent(CardEvent.OpenCardEdit(card.id)) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Edit,
        )
    }
}

/** The details column beside the receipts; stacked where the content column is narrow. */
@Composable
private fun DetailBody(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SIDE_BY_SIDE) {
            Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP), verticalAlignment = Alignment.Top) {
                DetailsCard(state, card, onEvent, Modifier.width(DETAILS_WIDTH))
                ReceiptsCard(state, card, onEvent, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                DetailsCard(state, card, onEvent, Modifier.fillMaxWidth())
                ReceiptsCard(state, card, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Limit, available and spent with the utilisation bar on a live card — the
 * proposal on one still in flight — then who holds it, the control code (with
 * its pencil while it may still be corrected), the provider, the numbers and
 * the justification.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One card's facts, in the web's reading order.
@Composable
private fun DetailsCard(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit, modifier: Modifier) {
    val holder = state.people.firstOrNull { it.id == card.holderId }
    val holderName = holder?.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_card_card_holder)
    val provider = state.providers.firstOrNull { it.id == card.providerId }?.name
    val currency = card.currency ?: state.defaultCurrency
    val limit = card.detailLimit
    val balance = card.balance ?: limit
    val spent = (limit - balance).coerceAtLeast(0.0)
    val percent = if (limit > 0) (spent / limit * PERCENT).toInt() else 0

    Box(modifier.cardSurface()) {
        Column {
            Column(
                Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(CARD_PADDING),
            ) {
                ZillitText(
                    text = "•••• " + if (card.live) card.lastFour ?: "0000" else "••••",
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (card.live) ZillitTheme.colors.accentText else ZillitTheme.colors.textMuted,
                )
                ZillitText(
                    text = listOfNotNull(holderName, holder?.designation?.takeIf { it.isNotBlank() }, provider)
                        .joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            Column(Modifier.padding(CARD_PADDING), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                if (card.live) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        Figure(
                            str(S.desktop_ce_cards_card_limit),
                            money(limit, currency),
                            ZillitTheme.colors.accentText,
                        )
                        Figure(str(S.available), money(balance, currency), ZillitTheme.colors.teal)
                        Figure(str(S.ah_total_spent), money(spent, currency), ZillitTheme.colors.textPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldGroupLabel(str(S.desktop_ce_cards_utilisation), Modifier.weight(1f))
                        ZillitText(text = "$percent%", style = ZillitTheme.typography.numeric)
                    }
                    ZillitProgressBar(
                        fraction = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 0f,
                        fillColor = when {
                            percent > DANGER_PERCENT -> ZillitTheme.colors.danger
                            percent > WARN_PERCENT -> ZillitTheme.colors.warning
                            else -> ZillitTheme.colors.teal
                        },
                    )
                    Row {
                        Small(str(S.desktop_ce_cards_spent_value, money(spent, currency)), Modifier.weight(1f))
                        Small(str(S.desktop_ce_cards_limit_value, money(limit, currency)))
                    }
                } else {
                    Figure(
                        str(S.desktop_ce_cards_proposed_limit),
                        money(limit, currency),
                        ZillitTheme.colors.accentText,
                    )
                }
                ZillitDivider()
                FactPair(
                    { Fact(str(S.desktop_card_card_holder), holderName, holder?.designation) },
                    { holder?.department?.takeIf { it.isNotBlank() }?.let { Fact(str(S.department), it) } },
                )
                FactPair(
                    { BsCodeFact(state, card, onEvent) },
                    { Fact(str(S.desktop_ce_cards_card_provider), provider ?: "—") },
                )
                if (card.digitalCardNumber != null || card.physicalCardNumber != null) {
                    ZillitDivider()
                    FactPair(
                        {
                            card.digitalCardNumber?.let {
                                Fact(str(S.desktop_ce_cards_digital_card), CardNumbers.grouped(it))
                            }
                        },
                        {
                            card.physicalCardNumber?.let {
                                Fact(str(S.desktop_ce_cards_physical_card), CardNumbers.grouped(it))
                            }
                        },
                    )
                }
                card.justification?.takeIf { it.isNotBlank() }?.let {
                    ZillitDivider()
                    Fact(str(S.desktop_card_justification), it)
                }
            }
        }
    }
}

/**
 * The control code, and its pencil: an accountant may correct it until the
 * first receipt exists against the card (`CardDetailModal.jsx:219-253`).
 */
@Composable
private fun BsCodeFact(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.bsDraft
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(str(S.desktop_ce_cards_bs_control_code))
        if (draft == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = card.bsControlCode?.takeIf { it.isNotBlank() } ?: "—",
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                )
                if (state.canCorrectBsCode(card)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = str(S.desktop_ce_cards_edit_bs_tip),
                        onClick = { onEvent(CardsEvent.StartBsEdit) },
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitTextField(
                    value = draft,
                    onValueChange = { onEvent(CardsEvent.EditBsDraft(it)) },
                    placeholder = str(S.desktop_ce_cards_bs_placeholder),
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Check,
                    contentDescription = str(S.desktop_ce_cards_save_bs_tip),
                    onClick = { onEvent(CardsEvent.SaveBsEdit) },
                    enabled = draft.isNotBlank() && !state.busy,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_ce_cards_cancel_bs_tip),
                    onClick = { onEvent(CardsEvent.CancelBsEdit) },
                    enabled = !state.busy,
                )
            }
        }
    }
}

/**
 * The card's receipts, in the Receipt Inbox's columns: loading rows, then an
 * error with its hint, an empty line, or every receipt — no cap — each
 * opening the receipt.
 */
@Composable
private fun ReceiptsCard(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit, modifier: Modifier) {
    val detail = state.cardDetail?.takeIf { it.cardId == card.id }
    val loading = detail == null || detail.loading
    val failed = detail != null && !detail.loading && !detail.receiptsRead
    val receipts = detail?.receipts.orEmpty()
    Box(modifier.cardSurface()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(CARD_PADDING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(ZillitIcons.Receipt, tint = ZillitTheme.colors.accent)
                ZillitText(
                    text = str(S.ah_receipts_label),
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.weight(1f),
                )
                if (!loading && !failed) {
                    Small(
                        if (receipts.size == 1) {
                            str(S.desktop_pc_receipt_one)
                        } else {
                            str(S.desktop_card_receipt_count_other, receipts.size)
                        },
                    )
                }
            }
            ZillitDivider()
            when {
                failed -> CenteredNote(
                    str(S.desktop_ce_cards_receipts_failed),
                    str(S.desktop_ce_cards_receipts_failed_hint),
                )
                !loading && receipts.isEmpty() -> CenteredNote(str(S.desktop_ce_cards_receipts_empty), null)
                else -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    ReceiptHeaderRow()
                    if (loading) {
                        repeat(SKELETON_ROWS) { SkeletonRow() }
                    } else {
                        receipts.forEach { receipt -> ReceiptRow(state, receipt, onEvent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptHeaderRow() {
    Row(
        Modifier.background(ZillitTheme.colors.surfaceSunken).padding(vertical = ZillitTheme.spacing.sm),
    ) {
        RECEIPT_COLUMNS.forEach { (key, width) ->
            FieldGroupLabel(str(key), Modifier.width(width).padding(horizontal = ZillitTheme.spacing.md))
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(Modifier.padding(vertical = ZillitTheme.spacing.md)) {
        RECEIPT_COLUMNS.forEach { (_, width) ->
            Box(Modifier.width(width).padding(horizontal = ZillitTheme.spacing.md)) {
                ZillitSkeletonBar(Modifier.fillMaxWidth())
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Six cells, the Receipt Inbox's.
@Composable
private fun ReceiptRow(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    val holder = state.people.firstOrNull { it.id == receipt.holderId }
    ZillitDivider()
    Row(
        modifier = Modifier
            .clickable { onEvent(CardsEvent.OpenReceipt(receipt.id)) }
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Cell(0) { Small(date(receipt.date ?: receipt.createdAt)) }
        Cell(1) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = receipt.description.ifBlank { receipt.merchant ?: str(S.desktop_ce_cards_unknown_merchant) },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                receipt.matchScore?.let { score ->
                    ZillitStatusPill(
                        label = "$score%",
                        tone = when {
                            score >= STRONG_MATCH -> StatusTone.Done
                            score >= WEAK_MATCH -> StatusTone.Pending
                            else -> StatusTone.Neutral
                        },
                    )
                }
                if (receipt.urgent) ZillitStatusPill(str(S.ah_topup_filter_urgent), tone = StatusTone.Rejected)
            }
            receipt.codeDescription?.takeIf { it.isNotBlank() }?.let { Small(it) }
            if (!receipt.transactionId.isNullOrBlank()) {
                Small(
                    str(S.desktop_ce_cards_linked_txn) + " " + listOfNotNull(
                        receipt.transactionMerchant ?: receipt.description,
                        receipt.transactionAmount?.let { money(it, receipt.currency) },
                        receipt.transactionCardLastFour?.let { "···· $it" },
                        receipt.transactionDate?.let(::date),
                    ).joinToString(" · "),
                )
            }
        }
        Cell(2) {
            if (holder == null) {
                Small("—")
            } else {
                ZillitText(
                    text = holder.name,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                )
                holder.designation.takeIf { it.isNotBlank() }?.let { Small(it) }
            }
        }
        Cell(3) {
            ZillitText(
                text = receipt.nominalCode?.takeIf { it.isNotBlank() } ?: "—",
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.gold,
            )
        }
        Cell(4) {
            ZillitText(
                text = receipt.amount.takeIf { it != 0.0 }?.let { money(it, receipt.currency) } ?: "—",
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accentText,
            )
        }
        Cell(5) { WorkflowStatusPill(receipt.status) }
    }
}

@Composable
private fun Cell(index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.width(RECEIPT_COLUMNS[index].second).padding(horizontal = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        content = content,
    )
}

/** The side panel the History button opens (`HistoryPanel`, "Card History"). */
@Composable
private fun HistoryPanel(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val detail = state.cardDetail?.takeIf { it.cardId == card.id }
    ZillitScrollColumn(
        modifier = Modifier.width(HISTORY_WIDTH).fillMaxHeight().background(ZillitTheme.colors.surface),
        contentPadding = PaddingValues(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ZillitText(text = str(S.desktop_ce_cards_card_history), style = ZillitTheme.typography.titleSmall)
                Small(str(S.desktop_ce_cards_card_history_sub, card.lastFour.orEmpty()))
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.close),
                onClick = { onEvent(CardsEvent.ShowHistory(false)) },
            )
        }
        CardHistoryTrail(
            entries = detail?.history.orEmpty(),
            emptyMessage = str(S.desktop_card_no_history_yet),
        )
    }
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(CARD_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
        content = content,
    )
}

@Composable
private fun Banner(tone: StatusTone, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val (fill, line) = when (tone) {
        StatusTone.Rejected -> colors.dangerSoft to colors.danger
        else -> colors.warningSoft to colors.warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(fill)
            .border(HAIRLINE, line.copy(alpha = BANNER_EDGE), ZillitTheme.shapes.large)
            .padding(CARD_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
        content = content,
    )
}

@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(label)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun Fact(label: String, value: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        sub?.takeIf { it.isNotBlank() }?.let { Small(it) }
    }
}

/** Two facts side by side, the web's two-column detail grid. */
@Composable
private fun FactPair(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        modifier = modifier,
    )
}

@Composable
private fun CenteredNote(title: String, hint: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NOTE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(text = title, color = ZillitTheme.colors.textSecondary)
        hint?.let { Small(it) }
    }
}

/** A bordered white card, as the web's detail and receipts panes sit. */
@Composable
private fun Modifier.cardSurface(): Modifier = clip(ZillitTheme.shapes.large)
    .background(ZillitTheme.colors.surface)
    .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)

/** "In-Progress" for an approved card, and an overridden one to a cardholder; the status's own label otherwise. */
private fun detailStatusLabel(card: ExpenseCard, accountant: Boolean): String =
    if (card.status == CardStatus.Approved || (!accountant && card.status == CardStatus.Override)) {
        str(S.desktop_ce_cards_in_progress)
    } else {
        card.status.label
    }

/** Live cards show their number and balance; the rest are still requests. */
private val ExpenseCard.live: Boolean get() = status == CardStatus.Active || status == CardStatus.Suspended

/** `monthly_limit || card_limit || proposed_limit` (`CardDetailModal.jsx:271`). */
private val ExpenseCard.detailLimit: Double
    get() = monthlyLimit?.takeIf { it > 0 } ?: limit.takeIf { it > 0 } ?: proposedLimit ?: 0.0

private val RECEIPT_COLUMNS = listOf(
    S.date to 110.dp,
    S.ah_receipt_details to 280.dp,
    S.desktop_card_card_holder to 170.dp,
    S.code to 110.dp,
    S.amount to 120.dp,
    S.status to 150.dp,
)

private val PAGE_GUTTER = 28.dp
private val SECTION_GAP = 20.dp
private val CARD_PADDING = 20.dp
private val DETAILS_WIDTH = 360.dp
private val SIDE_BY_SIDE = 900.dp
private val HISTORY_WIDTH = 380.dp
private val CHAIN_RULE = 40.dp
private val HAIRLINE = 1.dp
private val NOTE_PADDING = 48.dp
private const val PERCENT = 100
private const val DANGER_PERCENT = 80
private const val WARN_PERCENT = 50
private const val STRONG_MATCH = 80
private const val WEAK_MATCH = 50
private const val SKELETON_ROWS = 4
private const val BANNER_EDGE = 0.4f
