package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.cardLabel
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * The accountant's dashboard — the web's `OverviewPage.jsx:197-444`.
 *
 * Six tiles, the attention banners, the pending actions, the holding account,
 * the balance-sheet controlling accounts and the tax summary, in the web's
 * order. Every figure is `/overview`'s own; every tile and action row is a
 * link into the queue it counts, for the reason the cash overview gives:
 * figures nobody can act on are figures nobody returns to.
 */
@Suppress("LongMethod") // A dashboard: tiles, banners and four cards, each read in place.
@Composable
fun CardOverviewPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val overview = state.overview ?: CardOverview()
    val cards = overview.cards
    val currency = state.currency
    val go: (CardDestination) -> Unit = { onEvent(CardEvent.Open(it)) }
    val spent = cards.sumOf { it.spent }
    val pipeline = overview.pendingCoding + overview.inApproval + overview.approved

    ScrollingPage {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = str(S.ah_total_receipts),
                value = overview.transactionCount.toString(),
                sub = str(S.desktop_this_period),
                icon = ZillitIcons.Receipt,
                onClick = { go(CardDestination.AllTransactions) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.ah_unreconciled),
                value = money(spent, currency),
                sub = str(S.desktop_card_in_pipeline, pipeline),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
                onClick = { go(CardDestination.ReceiptInbox) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.ah_approval_queue),
                value = overview.inApproval.toString(),
                sub = str(S.desktop_inv_n_approved, overview.approved),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Shield,
                onClick = { go(CardDestination.ApprovalQueue) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = str(S.ah_ready_to_process),
                value = overview.approved.toString(),
                sub = str(if (overview.approved > 0) S.desktop_card_awaiting_posting else S.desktop_card_queue_clear),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Settings,
                onClick = { go(CardDestination.ProcessQueue) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.ah_status_posted),
                value = money(overview.postedTotal, currency),
                sub = str(S.desktop_card_receipt_count_other, overview.posted),
                tone = StatusTone.Done,
                icon = ZillitIcons.Ledger,
                onClick = { go(CardDestination.History) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.ah_card_requests),
                value = overview.requestedCards.toString(),
                sub = if (overview.requestedCards > 0) {
                    str(S.av_subtab_awaiting_approval)
                } else {
                    str(S.desktop_card_none_pending)
                },
                icon = ZillitIcons.CreditCard,
                onClick = { go(CardDestination.CardRegister) },
                modifier = Modifier.weight(1f),
            )
        }

        AttentionBanners(overview, currency, go)

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            PendingActions(overview, go, Modifier.weight(PENDING_WEIGHT))
            HoldingAccount(overview, currency, Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ZillitSectionCard(
                title = str(S.desktop_card_bs_accounts),
                icon = ZillitIcons.Ledger,
                meta = str(S.desktop_card_cards_count, overview.activeCards),
                padded = false,
                modifier = Modifier.weight(1f),
            ) {
                ZillitDataTable(
                    rows = cards,
                    columns = listOf(
                        textColumn(str(S.code), ColumnWidth.Weight(1f)) {
                            it.bsControlCode?.takeIf(String::isNotBlank) ?: "—"
                        },
                        textColumn(str(S.ah_my_cards), ColumnWidth.Weight(1f), muted = true) { cardLabel(it) },
                        textColumn(str(S.ah_holder), ColumnWidth.Weight(HOLDER_WEIGHT)) { state.holderShortName(it) },
                        textColumn(str(S.ah_spent_label), ColumnWidth.Weight(1f), numeric = true) {
                            money(it.spent, it.currency)
                        },
                        textColumn(str(S.ah_limit_label), ColumnWidth.Weight(1f), numeric = true) {
                            money(it.limit, it.currency)
                        },
                    ),
                    key = { it.id },
                    loading = state.loading,
                    emptyTitle = str(S.desktop_card_no_cards_issued),
                    emptyMessage = str(S.desktop_card_requests_appear_here),
                    onRowClick = { go(CardDestination.CardRegister) },
                    virtualised = false,
                )
            }
            TaxSummary(overview, currency, Modifier.weight(1f))
        }
    }
}

/** What needs somebody now: receipts stuck in coding or approval, top-ups to fund — or nothing. */
@Composable
private fun AttentionBanners(overview: CardOverview, currency: String?, go: (CardDestination) -> Unit) {
    val attention = overview.pendingCoding + overview.inApproval
    val topUps = overview.pendingTopUps
    if (attention > 0) {
        ZillitNotice(
            text = str(S.desktop_card_need_attention, attention) + " — " +
                str(S.desktop_card_attention_detail, overview.pendingCoding, overview.inApproval),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
            action = { BannerButton(str(S.av_review)) { go(CardDestination.PendingCoding) } },
        )
    }
    if (topUps.isNotEmpty()) {
        ZillitNotice(
            text = str(S.desktop_card_topups_pending_count, topUps.size) + " — " +
                money(topUps.sumOf { it.amount }, currency),
            tone = StatusTone.Rejected,
            icon = ZillitIcons.CreditCard,
            action = { BannerButton(str(S.txt_action)) { go(CardDestination.TopUpQueue) } },
        )
    }
    if (attention == 0 && topUps.isEmpty()) {
        ZillitNotice(
            text = str(S.desktop_card_all_clear) + " — " + str(S.desktop_card_all_clear_detail),
            tone = StatusTone.Done,
            icon = ZillitIcons.Check,
        )
    }
}

@Composable
private fun BannerButton(text: String, onClick: () -> Unit) {
    ZillitButton(text = text, onClick = onClick, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
}

/** The work waiting, as rows that open where it waits; only rows with something in them. */
@Composable
private fun PendingActions(overview: CardOverview, go: (CardDestination) -> Unit, modifier: Modifier) {
    val items = listOf(
        Triple(overview.pendingCoding, str(S.desktop_card_action_crew_coding), CardDestination.PendingCoding),
        Triple(overview.inApproval, str(S.desktop_card_action_in_approval), CardDestination.ApprovalQueue),
        Triple(overview.approved, str(S.ah_ready_to_process), CardDestination.ProcessQueue),
        Triple(overview.pendingTopUps.size, str(S.desktop_card_action_topups), CardDestination.TopUpQueue),
        Triple(overview.inbox, str(S.desktop_card_action_inbox), CardDestination.ReceiptInbox),
        Triple(overview.requestedCards, str(S.desktop_card_action_requests), CardDestination.CardRegister),
    ).filter { it.first > 0 }

    ZillitSectionCard(
        title = str(S.ah_pending_actions),
        icon = ZillitIcons.Clock,
        meta = items.sumOf { it.first }.toString(),
        modifier = modifier,
    ) {
        if (items.isEmpty()) {
            ZillitText(
                text = str(S.desktop_no_pending_actions),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        items.forEach { (count, label, destination) -> ActionRow(count, label) { go(destination) } }
    }
}

@Composable
private fun ActionRow(count: Int, label: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = ZillitTheme.spacing.xs, horizontal = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier.size(COUNT_CHIP).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(text = count.toString(), style = ZillitTheme.typography.numeric, color = colors.accentText)
        }
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ZillitIcon(ZillitIcons.ChevronRight, tint = colors.textMuted)
    }
}

/**
 * The card programme's money: what is granted, spent, waiting to be funded and
 * left — then how much of the receipts have reached the ledger.
 *
 * Only where the cards agree on a currency: adding yen to pounds is a number
 * that is not money. A mixed set names its currencies instead.
 */
@Composable
private fun HoldingAccount(overview: CardOverview, currency: String?, modifier: Modifier) {
    val cards = overview.cards
    val currencies = cards.mapNotNull { it.currency?.takeIf(String::isNotBlank) }.distinct()
    val pct = if (overview.transactionCount > 0) overview.posted * PERCENT / overview.transactionCount else 0
    ZillitSectionCard(title = str(S.desktop_card_holding_account), icon = ZillitIcons.Wallet, modifier = modifier) {
        if (currencies.size > 1) {
            ZillitText(
                text = str(S.desktop_card_cards_in_currencies, cards.size, currencies.sorted().joinToString(", ")),
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = str(S.desktop_card_multi_currency_note),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        } else {
            val code = currencies.firstOrNull() ?: currency
            SummaryLine(str(S.desktop_card_total_card_limit), money(cards.sumOf { it.limit }, code))
            SummaryLine(str(S.ah_total_spent), money(cards.sumOf { it.spent }, code))
            SummaryLine(str(S.ah_pending_topups), money(overview.pendingTopUps.sumOf { it.amount }, code))
            SummaryLine(str(S.desktop_card_available_balance), money(cards.sumOf { it.available }, code))
        }
        ZillitMeter(fraction = pct / PERCENT.toFloat(), tone = StatusTone.Done)
        ZillitText(
            text = str(S.desktop_card_posted_vs_total, pct, overview.posted, overview.transactionCount),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** What went through gross, what the tax on it is estimated at, and what is waiting to be funded. */
@Composable
private fun TaxSummary(overview: CardOverview, currency: String?, modifier: Modifier) {
    ZillitSectionCard(
        title = str(S.desktop_card_tax_summary),
        icon = ZillitIcons.Receipt,
        meta = str(S.desktop_card_estimated_from_posted),
        modifier = modifier,
    ) {
        SummaryLine(str(S.desktop_card_tax_posted_gross), money(overview.postedTotal, currency))
        SummaryLine(str(S.desktop_card_tax_estimated_net), money(overview.postedTotal - overview.vatEstimate, currency))
        SummaryLine(str(S.desktop_card_tax_recoverable), money(overview.vatEstimate, currency))
        SummaryLine(str(S.desktop_card_tax_total_spend), money(overview.totalSpend, currency))
        SummaryLine(str(S.desktop_card_tax_topup_value), money(overview.pendingTopUps.sumOf { it.amount }, currency))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric, maxLines = 1)
    }
}

/** What is left to spend; a card with no balance figure has its whole limit. */
private val ExpenseCard.available: Double get() = (balance ?: limit).coerceAtLeast(0.0)

private const val PERCENT = 100
private const val PENDING_WEIGHT = 1.2f
private const val HOLDER_WEIGHT = 1.4f
private val COUNT_CHIP = 30.dp
