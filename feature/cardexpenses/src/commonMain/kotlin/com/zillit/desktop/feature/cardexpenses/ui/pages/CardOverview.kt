package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CompactAmount
import com.zillit.desktop.feature.cardexpenses.domain.ConvertedTotal
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.defaultCurrency
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * The accountant's dashboard — the web's `OverviewPage.jsx:222-445`.
 *
 * Six tinted figures in one row; the attention banners; Pending Actions beside
 * the card holders; the holding account across the page; then the controlling
 * accounts beside the tax summary. Money is in the project's default currency,
 * a mix of card currencies converted through the project rates with the mix
 * named on hover. Skeletons stand in while the figures load — never an
 * "All clear" that has not been checked.
 */
@Suppress("LongMethod") // A dashboard: each block read in place.
@Composable
fun CardOverviewPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val overview = state.overview
    val loading = overview == null
    val data = overview ?: CardOverview()
    val go: (CardDestination) -> Unit = { onEvent(CardEvent.Open(it)) }
    val code = state.defaultCurrency
    val spent = state.convert(data.cards) { it.spent }

    ScrollingPage {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(TILE_GAP)) {
            if (loading) {
                repeat(TILES) { SkeletonTile(Modifier.weight(1f)) }
            } else {
                val pipeline = data.pendingCoding + data.inApproval + data.approved
                StatTile(
                    str(S.ah_total_receipts),
                    "${data.transactionCount}",
                    str(S.desktop_this_period),
                    OverviewTone.Ink,
                )
                StatTile(
                    str(S.ah_unreconciled),
                    Money.format(spent.amount, code),
                    str(S.desktop_card_in_pipeline, pipeline),
                    OverviewTone.Amber,
                )
                StatTile(
                    str(S.ah_approval_queue),
                    "${data.inApproval}",
                    str(S.desktop_inv_n_approved, data.approved),
                    OverviewTone.Red,
                )
                StatTile(
                    str(S.ah_ready_to_process),
                    "${data.approved}",
                    str(if (data.approved > 0) S.desktop_card_awaiting_posting else S.desktop_card_queue_clear),
                    OverviewTone.Blue,
                )
                StatTile(
                    str(S.ah_step_posted),
                    Money.format(data.postedTotal, code),
                    str(S.desktop_card_receipt_count_other, data.posted),
                    OverviewTone.Green,
                )
                StatTile(
                    str(S.ah_card_requests),
                    "${data.requestedCards}",
                    str(if (data.requestedCards > 0) S.av_subtab_awaiting_approval else S.desktop_card_none_pending),
                    OverviewTone.Purple,
                )
            }
        }

        if (loading) SkeletonBanner() else AttentionBanners(state, data, go)

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(BLOCK_GAP)) {
            PendingActions(data, loading, go, Modifier.weight(PENDING_WEIGHT))
            HolderStatus(state, data.cards, loading, Modifier.weight(1f))
        }

        HoldingAccount(state, data, loading)

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(BLOCK_GAP)) {
            ControllingAccounts(state, data, loading, Modifier.weight(1f))
            TaxSummary(state, data, loading, Modifier.weight(1f))
        }
    }
}

/** One of the six figures: tinted with its tone, not a link (`AdminStatTile`). */
@Composable
private fun RowScope.StatTile(label: String, value: String, sub: String, tone: OverviewTone) {
    val colors = ZillitTheme.colors
    val ink = tone.ink()
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(ZillitTheme.shapes.large)
            .background(ink.copy(alpha = if (colors.isDark) DARK_TINT else LIGHT_TINT).compositeOver(colors.surface))
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = ink,
            maxLines = 1,
        )
        ZillitText(text = sub, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun SkeletonTile(modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_WIDE))
        ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_HALF), height = 20.dp)
        ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_WIDE))
    }
}

@Composable
private fun SkeletonBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitSkeletonBar(Modifier.width(208.dp))
        ZillitSkeletonBar(Modifier.width(288.dp))
    }
}

/**
 * Receipts needing attention, top-ups to fund — or all clear. Two lines each,
 * with the orange button into the queue (`OverviewPage.jsx:264-294`).
 */
@Composable
private fun AttentionBanners(state: CardUiState, data: CardOverview, go: (CardDestination) -> Unit) {
    val attention = data.pendingCoding + data.inApproval
    val topUps = data.pendingTopUps
    if (attention > 0) {
        Banner(
            tone = OverviewTone.Amber,
            icon = ZillitIcons.Warning,
            title = str(S.desktop_card_need_attention, attention),
            detail = str(S.desktop_card_attention_detail, data.pendingCoding, data.inApproval),
            action = str(S.av_review) to { go(CardDestination.PendingCoding) },
        )
    }
    if (topUps.isNotEmpty()) {
        val urgent = topUps.count { it.uploadType == URGENT }
        val total = Money.format(state.convert(topUps.map { it.amount to it.currency }).amount, state.defaultCurrency)
        Banner(
            tone = OverviewTone.Red,
            icon = ZillitIcons.CreditCard,
            title = if (topUps.size == 1) {
                str(S.desktop_ce_cards_topups_one)
            } else {
                str(S.desktop_card_topups_pending_count, topUps.size)
            },
            detail = if (urgent > 0) {
                str(S.desktop_ce_cards_topups_detail_urgent, urgent, total)
            } else {
                str(S.desktop_ce_cards_topups_detail, total)
            },
            action = str(S.txt_action) to { go(CardDestination.TopUpQueue) },
        )
    }
    if (attention == 0 && topUps.isEmpty()) {
        Banner(
            tone = OverviewTone.Teal,
            icon = ZillitIcons.CreditCard,
            title = str(S.desktop_card_all_clear),
            detail = str(S.desktop_card_all_clear_detail),
            action = null,
        )
    }
}

@Composable
private fun Banner(
    tone: OverviewTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    action: Pair<String, () -> Unit>?,
) {
    val ink = tone.ink()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ink.copy(alpha = BANNER_TINT).compositeOver(ZillitTheme.colors.surface))
            .border(1.dp, ink.copy(alpha = BANNER_EDGE), ZillitTheme.shapes.large)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZillitIcon(icon, tint = ink, size = 22.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = ink,
            )
            ZillitText(text = detail, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        }
        action?.let { (label, onClick) ->
            ZillitButton(
                text = label,
                onClick = onClick,
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronRight,
            )
        }
    }
}

/** The work waiting, each count in its row's colour, opening where it waits (`:299-315`). */
@Composable
private fun PendingActions(data: CardOverview, loading: Boolean, go: (CardDestination) -> Unit, modifier: Modifier) {
    val amber = OverviewTone.Amber
    val red = OverviewTone.Red
    val purple = OverviewTone.Purple
    val items = listOf(
        PendingAction(data.pendingCoding, S.desktop_card_action_crew_coding, amber, CardDestination.PendingCoding),
        PendingAction(data.inApproval, S.desktop_card_action_in_approval, red, CardDestination.ApprovalQueue),
        PendingAction(data.approved, S.desktop_ce_cards_act_process, OverviewTone.Blue, CardDestination.ProcessQueue),
        PendingAction(data.pendingTopUps.size, S.desktop_card_action_topups, red, CardDestination.TopUpQueue),
        PendingAction(data.inbox, S.desktop_card_action_inbox, purple, CardDestination.ReceiptInbox),
        PendingAction(data.requestedCards, S.desktop_card_action_requests, purple, CardDestination.CardRegister),
    ).filter { it.count > 0 }

    ZillitSectionCard(
        title = str(S.ah_pending_actions),
        icon = ZillitIcons.Clock,
        meta = if (loading) null else items.sumOf { it.count }.toString(),
        padded = false,
        modifier = modifier,
    ) {
        when {
            loading -> repeat(SKELETON_ROWS) { SkeletonLine() }
            items.isEmpty() -> EmptyNote(str(S.desktop_ce_cards_no_pending_actions))
            else -> items.forEachIndexed { index, item ->
                if (index > 0) ZillitDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { go(item.destination) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val ink = item.tone.ink()
                    Box(
                        Modifier
                            .size(COUNT_CHIP)
                            .clip(ZillitTheme.shapes.medium)
                            .background(ink.copy(alpha = BANNER_TINT).compositeOver(ZillitTheme.colors.surface))
                            .border(1.dp, ink.copy(alpha = BANNER_EDGE), ZillitTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitText(
                            text = item.count.toString(),
                            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.ExtraBold),
                            color = ink,
                        )
                    }
                    ZillitText(
                        text = str(item.labelKey),
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitIcon(ZillitIcons.ChevronRight, tint = ZillitTheme.colors.textMuted)
                }
            }
        }
    }
}

/** Each card's holder: face, "Name (Designation)", its number and code, and an Active pill (`:317-337`). */
@Composable
private fun HolderStatus(state: CardUiState, cards: List<ExpenseCard>, loading: Boolean, modifier: Modifier) {
    ZillitSectionCard(
        title = str(S.desktop_ce_cards_holder_status),
        icon = ZillitIcons.Users,
        padded = false,
        modifier = modifier,
    ) {
        when {
            loading -> repeat(2) { SkeletonLine() }
            cards.isEmpty() -> EmptyNote(str(S.desktop_ce_cards_no_active_cards))
            else -> cards.forEachIndexed { index, card ->
                if (index > 0) ZillitDivider()
                val person = state.people.firstOrNull { it.id == card.holderId }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ZillitAvatar(name = person?.name ?: "—", userId = card.holderId, size = AVATAR)
                    Column(Modifier.weight(1f)) {
                        ZillitText(
                            text = state.holderLine(card),
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        ZillitText(
                            text = str(
                                S.desktop_ce_cards_holder_card_line,
                                card.lastFour.orEmpty(),
                                card.bsControlCode?.takeIf { it.isNotBlank() } ?: "—",
                            ),
                            style = ZillitTheme.typography.numeric,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    Pill(str(S.active), OverviewTone.Teal)
                }
            }
        }
    }
}

/**
 * The holding account (9900): limits, spend, top-ups waiting and balance,
 * each converted to the default and compact, the exact figure on hover; then
 * how much of the receipts have been posted (`:340-377`).
 */
@Suppress("LongMethod") // Four figures and the posted bar, one card.
@Composable
private fun HoldingAccount(state: CardUiState, data: CardOverview, loading: Boolean) {
    val cards = data.cards
    val figures = listOf(
        Triple(S.desktop_card_total_card_limit, state.convert(cards) { it.overviewLimit }, OverviewTone.Amber),
        Triple(S.ah_total_spent, state.convert(cards) { it.spent }, OverviewTone.Amber),
        Triple(
            S.ah_pending_topups,
            state.convert(data.pendingTopUps.map { it.amount to it.currency }),
            OverviewTone.Red,
        ),
        Triple(
            S.desktop_card_available_balance,
            state.convert(cards) { (it.balance ?: it.overviewLimit).coerceAtLeast(0.0) },
            OverviewTone.Green,
        ),
    )
    val percent = if (data.transactionCount > 0) data.posted * PERCENT / data.transactionCount else 0
    ZillitSectionCard(title = str(S.desktop_ce_cards_holding_account), icon = ZillitIcons.Bank) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(TILE_GAP)) {
            figures.forEach { (labelKey, total, tone) ->
                Box(Modifier.weight(1f)) {
                    ZillitTooltip(state.exact(total)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.large)
                                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ZillitText(
                                text = str(labelKey).uppercase(),
                                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = ZillitTheme.colors.textMuted,
                                textAlign = TextAlign.Center,
                            )
                            if (loading) {
                                ZillitSkeletonBar(Modifier.width(80.dp).padding(top = 8.dp), height = 20.dp)
                            } else {
                                ZillitText(
                                    text = Money.symbol(total.currency) + CompactAmount.of(total.amount),
                                    style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = tone.ink(),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitText(
                text = str(S.desktop_ce_cards_posted_vs_total),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitProgressBar(fraction = percent / PERCENT.toFloat(), modifier = Modifier.weight(1f))
            ZillitText(
                text = str(S.desktop_ce_cards_posted_ratio, "$percent%", data.posted, data.transactionCount),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** Each card against its control code: Code, Card, "Name (Designation)", Spent, Limit (`:381-418`). */
@Composable
private fun ControllingAccounts(state: CardUiState, data: CardOverview, loading: Boolean, modifier: Modifier) {
    ZillitSectionCard(
        title = str(S.desktop_card_bs_accounts),
        icon = ZillitIcons.Bank,
        meta = if (loading) null else str(S.desktop_ce_cards_n_active_cards, data.activeCards),
        padded = false,
        modifier = modifier,
    ) {
        TableRow(
            listOf(
                str(S.code),
                str(S.ah_my_cards),
                str(S.ah_holder),
                str(S.ah_spent_label),
                str(S.ah_limit_label),
            ).map { it.uppercase() },
            header = true,
        )
        if (loading) {
            repeat(2) { SkeletonLine() }
        } else {
            data.cards.forEach { card ->
                ZillitDivider()
                val currency = card.currency ?: state.defaultCurrency
                TableRow(
                    listOf(
                        card.bsControlCode?.takeIf { it.isNotBlank() } ?: "—",
                        "•••• ${card.lastFour.orEmpty()}",
                        state.holderLine(card),
                        money(card.spent, currency),
                        money(card.overviewLimit, currency),
                    ),
                    header = false,
                )
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (header) 10.dp else 12.dp)) {
        cells.forEachIndexed { index, text ->
            ZillitText(
                text = text,
                style = if (header) {
                    ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    ZillitTheme.typography.numeric
                },
                color = when {
                    header -> ZillitTheme.colors.textMuted
                    index == 0 -> ZillitTheme.colors.accentText
                    index == 1 || index == TABLE_LIMIT -> ZillitTheme.colors.textMuted
                    else -> ZillitTheme.colors.textPrimary
                },
                textAlign = if (index >= TABLE_SPENT) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(if (index == TABLE_HOLDER) HOLDER_WEIGHT else 1f).padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * Posted gross and net, the recoverable tax flagged "Estimated", all spend,
 * and the top-ups waiting with their count (`:420-444`).
 */
@Suppress("LongMethod") // Five rows, each with its own colour and badge.
@Composable
private fun TaxSummary(state: CardUiState, data: CardOverview, loading: Boolean, modifier: Modifier) {
    val code = state.defaultCurrency
    val topUps = state.convert(data.pendingTopUps.map { it.amount to it.currency }).amount
    ZillitSectionCard(
        title = str(S.desktop_card_tax_summary),
        icon = ZillitIcons.Ledger,
        meta = str(S.desktop_card_estimated_from_posted),
        padded = false,
        modifier = modifier,
    ) {
        if (loading) {
            repeat(TAX_ROWS) { SkeletonLine() }
            return@ZillitSectionCard
        }
        val rows = listOf(
            OverviewTaxRow(
                S.desktop_card_tax_posted_gross,
                Money.format(data.postedTotal, code),
                OverviewTone.Ink,
                null,
            ),
            OverviewTaxRow(
                S.desktop_card_tax_estimated_net,
                Money.format(data.postedTotal - data.vatEstimate, code),
                OverviewTone.Muted,
                null,
            ),
            OverviewTaxRow(
                S.desktop_card_tax_recoverable,
                Money.format(data.vatEstimate, code),
                OverviewTone.Teal,
                str(S.desktop_cr_estimated) to OverviewTone.Amber,
            ),
            OverviewTaxRow(
                S.desktop_card_tax_total_spend,
                Money.format(data.totalSpend, code),
                OverviewTone.Amber,
                null,
            ),
            OverviewTaxRow(
                S.desktop_card_tax_topup_value,
                Money.format(topUps, code),
                OverviewTone.Red,
                str(S.desktop_card_pending_count, data.pendingTopUps.size) to OverviewTone.Red,
            ),
        )
        rows.forEachIndexed { index, row ->
            if (index > 0) ZillitDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(
                    text = str(row.labelKey),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = row.value,
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    color = row.tone.ink(),
                    maxLines = 1,
                )
                row.badge?.let { (text, tone) -> Pill(text, tone) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, tone: OverviewTone) {
    val ink = tone.ink()
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ink.copy(alpha = BANNER_TINT).compositeOver(ZillitTheme.colors.surface))
            .border(1.dp, ink.copy(alpha = BANNER_EDGE), ZillitTheme.shapes.medium)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun SkeletonLine() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSkeletonBar(Modifier.size(30.dp), height = 30.dp)
        ZillitSkeletonBar(Modifier.weight(1f))
    }
}

@Composable
private fun EmptyNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

/** `card_limit ?? monthly_limit` (`OverviewPage.jsx:158`). */
private val ExpenseCard.overviewLimit: Double
    get() = limit.takeIf { it > 0 } ?: monthlyLimit ?: 0.0

/** "Name (Designation)" — the holder as the dashboard names them; never an id. */
private fun CardUiState.holderLine(card: ExpenseCard): String {
    val person = people.firstOrNull { it.id == card.holderId } ?: return "—"
    val name = person.name.ifBlank { "—" }
    return person.designation.takeIf { it.isNotBlank() }?.let { "$name ($it)" } ?: name
}

private fun CardUiState.convert(cards: List<ExpenseCard>, amount: (ExpenseCard) -> Double): ConvertedTotal =
    convert(cards.map { amount(it) to it.currency })

private fun CardUiState.convert(rows: List<Pair<Double, String?>>): ConvertedTotal =
    cardsArea.reference.copy(defaultCurrency = defaultCurrency).total(rows)

/** The exact figure behind a compact one, and the warning when some had no rate. */
private fun CardUiState.exact(total: ConvertedTotal): String {
    val figure = Money.format(total.amount, total.currency.ifBlank { defaultCurrency })
    return if (total.unrated) "$figure · " + str(S.desktop_br_total_unrated) else figure
}

private data class PendingAction(
    val count: Int,
    val labelKey: String,
    val tone: OverviewTone,
    val destination: CardDestination,
)

private data class OverviewTaxRow(
    val labelKey: String,
    val value: String,
    val tone: OverviewTone,
    val badge: Pair<String, OverviewTone>?,
)

/** The dashboard's inks (`STAT_VALUE`), as theme tokens. */
private enum class OverviewTone { Ink, Muted, Amber, Red, Blue, Green, Teal, Purple }

@Composable
private fun OverviewTone.ink(): Color {
    val colors = ZillitTheme.colors
    return when (this) {
        OverviewTone.Ink -> colors.textPrimary
        OverviewTone.Muted -> colors.textMuted
        OverviewTone.Amber -> colors.warning
        OverviewTone.Red -> colors.danger
        OverviewTone.Blue -> colors.info
        OverviewTone.Green -> colors.success
        OverviewTone.Teal -> colors.teal
        OverviewTone.Purple -> colors.violet
    }
}

private const val URGENT = "urgent"
private const val TILES = 6
private const val PERCENT = 100
private const val PENDING_WEIGHT = 1.2f
private const val HOLDER_WEIGHT = 1.4f
private const val SKELETON_ROWS = 4
private const val TAX_ROWS = 5
private const val TABLE_HOLDER = 2
private const val TABLE_SPENT = 3
private const val TABLE_LIMIT = 4
private const val LIGHT_TINT = 0.05f
private const val DARK_TINT = 0.14f
private const val BANNER_TINT = 0.1f
private const val BANNER_EDGE = 0.3f
private const val SKELETON_WIDE = 0.66f
private const val SKELETON_HALF = 0.5f
private val TILE_GAP = 12.dp
private val BLOCK_GAP = 18.dp
private val COUNT_CHIP = 30.dp
private val AVATAR = 40.dp
