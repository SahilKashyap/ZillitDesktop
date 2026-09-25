package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * My Transactions — the cardholder's own receipts (`UserReceiptsPage.jsx`).
 *
 * A grid of receipt cards under the status chips, and the way into the
 * full-screen Upload Receipts page. Coding, matching and sending for
 * approval are not here: the web's crew page has none of them, and the
 * coding modal it still carries is never opened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyTransactionsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    LaunchedEffect(Unit) { onEvent(CrewEvent.Prime) }
    if (state.crew.uploadOpen) {
        UploadReceiptsPage(state, onEvent)
        return
    }

    val active = CrewRules.activeCard(state.cards)
    val filter = state.crew.filter
    val shown = state.receipts.filter { filter == null || it.status == filter }

    CrewScrollPage {
        // Only once the read has settled, so the notice never flashes before
        // the card arrives (`UserReceiptsPage.jsx:846-862`).
        if (!state.loading && active == null) NoActiveCardNotice()

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                CrewRules.FILTERS.forEach { status ->
                    ZillitChoiceChip(
                        label = status?.let(::crewStatusLabel) ?: str(S.all),
                        selected = filter == status,
                        onClick = { onEvent(CrewEvent.Filter(status)) },
                    )
                }
            }
            if (active != null) UploadControl(active, state.busy, onEvent)
        }

        when {
            state.loading && state.receipts.isEmpty() -> CenteredNote(str(S.desktop_pc_loading_receipts))
            shown.isEmpty() -> CenteredNote(str(S.desktop_ce_crew_no_receipts))
            else -> CrewCardGrid(shown, key = { it.id }) { receipt ->
                ReceiptCard(receipt, active, state.unreadRow("my_transactions", receipt.id), onEvent)
            }
        }
    }
}

/**
 * Upload Receipts, or — with the card's limit fully committed — the same
 * button disabled beside "Upload unavailable" and the reason in its tooltip.
 */
@Composable
private fun UploadControl(card: ExpenseCard, busy: Boolean, onEvent: (CardEvent) -> Unit) {
    val headroom = UploadHeadroom.of(card)
    if (!headroom.exhausted) {
        ZillitButton(
            text = str(S.ah_upload_receipts),
            onClick = { onEvent(CrewEvent.OpenUpload) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Upload,
            enabled = !busy,
        )
        return
    }
    val why = if (headroom.cardLimit <= 0) {
        str(S.desktop_ce_crew_no_limit_set)
    } else {
        str(
            S.desktop_ce_crew_limit_committed,
            money(headroom.receiptsCommit, card.currency),
            money(headroom.cardLimit, card.currency),
        )
    }
    ZillitTooltip(text = why) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = str(S.desktop_ce_crew_upload_unavailable),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
            ZillitButton(
                text = str(S.ah_upload_receipts),
                onClick = {},
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
                enabled = false,
            )
        }
    }
}

/** One receipt card (`UserReceiptsPage.jsx:923-1038`). */
@Suppress("LongMethod") // One card, top to bottom, as the web lays it out.
@Composable
private fun ReceiptCard(receipt: CardReceipt, card: ExpenseCard?, unread: Int, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val attached = CrewRules.hasReceipt(receipt)
    CrewTile(onClick = { onEvent(CrewEvent.OpenReceipt(receipt.id)) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            IconWell(ZillitIcons.CreditCard)
            ZillitText(
                text = receipt.description.ifBlank { "—" },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // The receipt's own unread (`UserReceiptsPage.jsx:951-954`).
            ZillitBadge(count = unread)
            if (receipt.urgent) UrgentPill()
            CrewStatusPill(receipt, unreconciled = true)
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (receipt.amount != 0.0) money(receipt.amount, receipt.currency ?: card?.currency) else "—",
                style = ZillitTheme.typography.titleLarge,
                color = colors.accentText,
            )
            ZillitText(
                text = date(receipt.date),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        guidance(receipt.status)?.let { line ->
            ZillitText(text = line, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
        }
        ZillitDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = if (attached) ZillitIcons.Paperclip else ZillitIcons.File,
                tint = if (attached) colors.success else colors.textMuted,
                size = ZillitDimens.iconSmall,
            )
            ZillitText(
                text = if (attached) str(S.ah_receipt_attached) else str(S.desktop_ce_crew_no_receipt),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (CrewRules.offersUpload(receipt)) {
                ZillitButton(
                    text = str(S.ah_upload_receipt_btn),
                    onClick = { onEvent(CrewEvent.OpenEdit(receipt.id)) },
                    size = ButtonSize.Small,
                )
            }
            if (CrewRules.canDelete(receipt)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.delete),
                    onClick = { onEvent(CrewEvent.AskDelete(receipt.id)) },
                )
            }
            if (receipt.status != CardWorkflowStatus.PendingReceipt) {
                ZillitButton(
                    text = str(S.details),
                    onClick = { onEvent(CrewEvent.OpenReceipt(receipt.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    trailingIcon = ZillitIcons.ChevronRight,
                )
            }
        }
    }
}

/** One line of what happens next, per status (`UserReceiptsPage.jsx:908-919`). */
private fun guidance(status: CardWorkflowStatus): String? = when (status) {
    CardWorkflowStatus.PendingReceipt -> str(S.desktop_ce_crew_hint_pending_receipt)
    CardWorkflowStatus.PendingCode -> str(S.desktop_ce_crew_hint_pending_code)
    CardWorkflowStatus.AwaitingApproval -> str(S.desktop_ce_crew_hint_awaiting_approval)
    CardWorkflowStatus.Approved -> str(S.desktop_ce_crew_hint_approved)
    CardWorkflowStatus.Rejected -> str(S.desktop_ce_crew_hint_rejected)
    CardWorkflowStatus.Queried -> str(S.desktop_ce_crew_hint_queried)
    CardWorkflowStatus.UnderReview -> str(S.desktop_ce_crew_hint_under_review)
    CardWorkflowStatus.Escalated -> str(S.desktop_ce_crew_hint_escalated)
    CardWorkflowStatus.Posted -> str(S.cr_posted_to_ledger)
    else -> null
}

/** "No active card" — why there is no Upload button (`UserReceiptsPage.jsx:850-862`). */
@Composable
private fun NoActiveCardNotice() {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        IconWell(ZillitIcons.CreditCard)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = str(S.desktop_ce_crew_no_active_card), style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = str(S.desktop_ce_crew_no_active_card_body),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** The web's centred grey line for loading and empty lists. */
@Composable
internal fun CenteredNote(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl)) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
