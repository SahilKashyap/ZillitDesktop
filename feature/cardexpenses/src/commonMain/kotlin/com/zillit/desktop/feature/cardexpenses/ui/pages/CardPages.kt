package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardAmountAction
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardFace
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardReasonAction
import com.zillit.desktop.feature.cardexpenses.ui.CardStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.cardLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/** The standard page body. See the cash module's equivalent. */
@Composable
fun ScrollingPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .zillitVerticalScroll()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** For a page whose own content scrolls — a table, a queue. */
@Composable
fun FixedPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/**
 * The accountant's dashboard.
 *
 * Every tile is a link into the queue it counts, for the reason the cash
 * overview gives: figures nobody can act on are figures nobody returns to.
 */
@Suppress("LongMethod") // A dashboard: tiles, notices and a table, each read in place.
@Composable
fun CardOverviewPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val overview = state.overview
    val currency = overview?.cards?.firstOrNull()?.currency

    ScrollingPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitStatTile(
                label = "Active cards",
                value = overview?.activeCards?.toString() ?: "—",
                sub = "${overview?.requestedCards ?: 0} awaiting approval",
                tone = StatusTone.Done,
                icon = ZillitIcons.CreditCard,
                onClick = { onEvent(CardEvent.Open(CardDestination.CardRegister)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Receipt inbox",
                value = overview?.inbox?.toString() ?: "—",
                sub = "Uploaded, not yet processed",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Receipt,
                onClick = { onEvent(CardEvent.Open(CardDestination.ReceiptInbox)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "In approval",
                value = overview?.inApproval?.toString() ?: "—",
                sub = "${overview?.pendingCoding ?: 0} still to code",
                tone = StatusTone.Progress,
                icon = ZillitIcons.Shield,
                onClick = { onEvent(CardEvent.Open(CardDestination.ApprovalQueue)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Total spend",
                value = money(overview?.totalSpend, currency),
                sub = "${overview?.transactionCount ?: 0} transactions",
                icon = ZillitIcons.BarChart,
                onClick = { onEvent(CardEvent.Open(CardDestination.Analytics)) },
                modifier = Modifier.weight(1f),
            )
        }

        if (!overview?.pendingTopUps.isNullOrEmpty()) {
            ZillitNotice(
                text = "${overview.pendingTopUps.size} card top-up(s) waiting to be funded.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
                action = {
                    ZillitButton(
                        text = "Open top-ups",
                        onClick = { onEvent(CardEvent.Open(CardDestination.TopUpQueue)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(title = "Ledger", icon = ZillitIcons.Ledger, modifier = Modifier.weight(1f)) {
                LedgerLine("Posted to date", money(overview?.postedTotal, currency))
                LedgerLine("Estimated VAT", money(overview?.vatEstimate, currency))
                LedgerLine("Approved, unposted", (overview?.approved ?: 0).toString())
                LedgerLine("Posted items", (overview?.posted ?: 0).toString())
            }

            ZillitSectionCard(
                title = "Card limits",
                icon = ZillitIcons.CreditCard,
                meta = "${overview?.cards?.size ?: 0} cards",
                modifier = Modifier.weight(1f),
            ) {
                val cards = overview?.cards.orEmpty()
                val totalLimit = cards.sumOf { it.limit }
                val totalSpent = cards.sumOf { it.spent }
                ZillitText(
                    text = "${money(totalSpent, currency)} of ${money(totalLimit, currency)}",
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitMeter(
                    fraction = if (totalLimit > 0) (totalSpent / totalLimit).toFloat() else 0f,
                    tone = StatusTone.Progress,
                )
            }
        }

        ZillitSectionCard(title = "Cards", icon = ZillitIcons.CreditCard, padded = false) {
            ZillitDataTable(
                rows = overview?.cards.orEmpty(),
                columns = cardColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No cards issued",
                emptyMessage = "Card requests appear here as crew raise them.",
                onRowClick = { onEvent(CardEvent.Open(CardDestination.CardRegister)) },
                virtualised = false,
            )
        }
    }
}

@Composable
private fun LedgerLine(label: String, value: String) {
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

/**
 * The card register — every card, and the one action its status allows.
 *
 * Also serves the cardholder's approval queue, which is the same table with a
 * narrower row set and the approve/reject pair enabled.
 */
@Composable
fun CardRegisterPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val approvalOnly = state.destination == CardDestination.CardsForApproval
    val rows = state.cards
        .filter { !approvalOnly || it.status in APPROVABLE }
        .filter { it.matches(state.search) }

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = "Search by holder or last four",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = "${rows.size} card${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }

        ZillitSectionCard(
            title = if (approvalOnly) "Cards awaiting your decision" else "Card register",
            icon = ZillitIcons.CreditCard,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = cardColumns() + cardActionColumn(state, onEvent),
                key = { it.id },
                loading = state.loading,
                onRowClick = { onEvent(CardEvent.SelectCard(it.id)) },
                isSelected = { it.id == state.selectedCardId },
                emptyTitle = if (approvalOnly) "Nothing waiting" else "No cards",
                emptyMessage = if (approvalOnly) {
                    "Card requests routed to you for approval appear here."
                } else {
                    "Cards appear here once crew request them."
                },
            )
        }
    }
}

/**
 * The single action a card's status permits.
 *
 * A card lifecycle is linear — requested, approved, active, suspended — so one
 * button per row is not a simplification, it is the shape of the thing.
 */
@Suppress("LongMethod") // The card lifecycle table: one branch per status.
private fun cardActionColumn(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): TableColumn<ExpenseCard> = TableColumn(
    header = "",
    width = ColumnWidth.Fixed(ACTION_COLUMN),
    cell = { card ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            when {
                card.status in APPROVABLE && (state.viewer.isApprover || state.viewer.isAccountant) -> {
                    ZillitButton(
                        text = "Approve",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.ApproveCard,
                                        card.id,
                                        "Approve this card",
                                        "${money(card.limit, card.currency)} limit for " +
                                            card.holderName.ifBlank { "this crew member" } + ".",
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Reject",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.WithReason(
                                        CardReasonAction.RejectCard,
                                        card.id,
                                        "Reject this card request",
                                        "Why it is being refused",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }

                card.status == CardStatus.Approved && state.viewer.isAccountant -> ZillitButton(
                    text = "Activate",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.ActivateCard,
                                    card.id,
                                    "Activate this card",
                                    "The holder can start spending against it immediately.",
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                card.status == CardStatus.DigitalActive && state.viewer.isAccountant -> ZillitButton(
                    text = "Assign physical",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.WithCardNumber(card.id, "Assign a physical card"),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                card.status == CardStatus.Active && state.viewer.isAccountant -> ZillitButton(
                    text = "Suspend",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.SuspendCard,
                                    card.id,
                                    "Suspend this card",
                                    "Spending stops at once. The holder is not told by this app.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                card.status == CardStatus.Suspended && state.viewer.isAccountant -> ZillitButton(
                    text = "Reactivate",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.ReactivateCard,
                                    card.id,
                                    "Reactivate this card",
                                    "Spending resumes against the remaining limit.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                else -> ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
    },
)

/**
 * The cardholder's own card, and the request form when they have none.
 *
 * The one-card rule is explained before it is enforced: a disabled button with
 * no reason is how people end up emailing the accounts office.
 */
@Suppress("LongMethod") // One screen: the card, its headroom, and the request form.
@Composable
fun MyCardPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val card = state.myCard
    var limit by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CardType.Physical) }

    ScrollingPage {
        if (card != null) {
            CardFace(card, modifier = Modifier.width(CARD_FACE_WIDTH))

            if (card.status == CardStatus.Rejected && !card.rejectionReason.isNullOrBlank()) {
                ZillitNotice(
                    text = "This request was refused: ${card.rejectionReason}",
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Warning,
                )
            }

            ZillitSectionCard(title = "Limit and headroom", icon = ZillitIcons.Wallet) {
                val headroom = state.headroom
                LedgerLine("Card limit", money(headroom.cardLimit, card.currency))
                LedgerLine("Receipts committed", money(headroom.receiptsCommit, card.currency))
                LedgerLine("Available to upload against", money(headroom.available, card.currency))
                if (headroom.exhausted) {
                    ZillitNotice(
                        text = "The card's limit is fully committed. " +
                            "Ask for a top-up before uploading more receipts.",
                        tone = StatusTone.Rejected,
                        icon = ZillitIcons.Warning,
                        action = {
                            ZillitButton(
                                text = "Card Extension",
                                onClick = { onEvent(CardEvent.Open(CardDestination.CardExtension)) },
                                variant = ButtonVariant.Secondary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
            }
        }

        val blocking = state.cards.firstOrNull {
            it.holderId == state.viewer.userId && CardRules.blocksNewRequest(it)
        }
        ZillitSectionCard(title = "Request a card", icon = ZillitIcons.Add) {
            if (blocking != null) {
                ZillitNotice(
                    text = "You already hold a ${blocking.status.label.lowercase()} card. " +
                        "One card per person — this one has to be closed or suspended first, " +
                        "unless its limit is fully spent.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.CreditCard,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ZillitTextField(
                        value = limit,
                        onValueChange = { limit = it },
                        label = "Limit needed",
                        placeholder = "0.00",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = "Card type",
                            style = ZillitTheme.typography.label,
                            color = ZillitTheme.colors.textSecondary,
                        )
                        ZillitSelect(
                            value = type,
                            options = CardType.entries,
                            onSelect = { type = it },
                            label = { it.label },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                ZillitTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = "What it is for",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitButton(
                    text = "Send request",
                    onClick = { onEvent(CardEvent.RequestCard(limit, type, reason)) },
                    leadingIcon = ZillitIcons.Send,
                    loading = state.busy,
                )
            }
        }
    }
}

/** Card Extension — the holder asking for more on the card they hold. */
@Composable
fun CardExtensionPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val card = state.myCard

    ScrollingPage {
        if (card == null) {
            ZillitNotice(
                text = "You have no card to extend. Request one first.",
                tone = StatusTone.Progress,
            )
            return@ScrollingPage
        }

        ZillitSectionCard(
            title = "Top up ${cardLabel(card)}",
            icon = ZillitIcons.Wallet,
            meta = card.status.label,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = money(card.balance ?: card.limit, card.currency),
                        style = ZillitTheme.typography.displayLarge,
                    )
                    ZillitText(
                        text = "available of ${money(card.limit, card.currency)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitButton(
                    text = "Request top-up",
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.WithAmount(
                                    CardAmountAction.RequestTopUp,
                                    card.id,
                                    "Request a top-up",
                                    "How much more do you need",
                                ),
                            ),
                        )
                    },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            }
        }

        ZillitSectionCard(title = "Top-ups on this card", icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.topUps,
                columns = topUpColumns(showHolder = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No top-ups requested",
                emptyMessage = "Every request you raise appears here with what the accounts team did with it.",
                virtualised = false,
            )
        }
    }
}

/** The accountant's top-up inbox. */
@Composable
fun TopUpQueuePage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    FixedPage {
        ZillitSectionCard(
            title = "Top-up requests",
            icon = ZillitIcons.Wallet,
            meta = "${state.topUps.count { it.status == PENDING }} pending",
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.topUps,
                columns = topUpColumns(showHolder = true) + topUpActions(state, onEvent),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "Nothing waiting",
                emptyMessage = "Cardholder top-up requests land here as they are raised.",
            )
        }
    }
}

@Suppress("LongMethod") // Three row actions, each with its own confirmation.
private fun topUpActions(
    state: CardUiState,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardTopUp>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(TOPUP_ACTION_COLUMN),
        cell = { row ->
            if (row.status != PENDING) {
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = "Fund",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.CompleteTopUp,
                                        row.id,
                                        "Complete this top-up",
                                        "${money(row.amount, row.currency)} added to " +
                                            "${row.cardLastFour?.let { "•••• $it" } ?: "the card"}.",
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Part",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.WithAmount(
                                        CardAmountAction.PartialTopUp,
                                        row.id,
                                        "Partial top-up",
                                        "Amount added",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = "Skip",
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.SkipTopUp,
                                        row.id,
                                        "Skip this top-up",
                                        "The request closes without funds moving.",
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            }
        },
    ),
)

// -- shared columns ----------------------------------------------------------

@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
fun cardColumns(): List<TableColumn<ExpenseCard>> = listOf(
    textColumn("Holder", ColumnWidth.Weight(1.4f)) { it.holderName.ifBlank { it.holderId } },
    textColumn("Card", ColumnWidth.Weight(1f), muted = true) { cardLabel(it) },
    textColumn("Type", ColumnWidth.Fixed(TYPE_COLUMN), muted = true) { it.type.label },
    textColumn("Limit", ColumnWidth.Weight(1f), numeric = true) { money(it.limit, it.currency) },
    textColumn("Available", ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) },
    TableColumn(
        header = "Used",
        width = ColumnWidth.Fixed(METER_COLUMN),
        cell = { row ->
            ZillitMeter(
                fraction = row.consumedFraction,
                tone = if (row.consumedFraction > NEARLY_SPENT) StatusTone.Rejected else StatusTone.Ready,
                modifier = Modifier.width(METER_WIDTH),
            )
        },
    ),
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { CardStatusPill(it.status) },
    ),
)

@Suppress("MagicNumber") // Column proportions.
fun topUpColumns(showHolder: Boolean): List<TableColumn<CardTopUp>> = buildList {
    if (showHolder) {
        add(textColumn("Holder", ColumnWidth.Weight(1.3f)) { it.holderName.ifBlank { it.holderId.orEmpty() } })
    }
    add(textColumn("Card", ColumnWidth.Weight(1f), muted = true) { it.cardLastFour?.let { l -> "•••• $l" } ?: "—" })
    add(textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    add(textColumn("Method", ColumnWidth.Weight(1f), muted = true) { it.method ?: "—" })
    add(textColumn("Raised", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(TOPUP_STATUS_COLUMN),
            cell = { row ->
                com.zillit.desktop.core.designsystem.component.ZillitStatusPill(
                    label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { "Pending" },
                    tone = when (row.status) {
                        COMPLETED -> StatusTone.Done
                        PARTIAL -> StatusTone.Progress
                        SKIPPED -> StatusTone.Neutral
                        else -> StatusTone.Pending
                    },
                    dot = true,
                )
            },
        ),
    )
}

private fun ExpenseCard.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return holderName.lowercase().contains(needle) ||
        lastFour?.contains(needle) == true ||
        issuer?.lowercase()?.contains(needle) == true
}

/** Statuses a card request can be approved or rejected from. */
private val APPROVABLE = setOf(CardStatus.Requested, CardStatus.Pending)

private const val PENDING = "pending"
private const val COMPLETED = "completed"
private const val PARTIAL = "partial"
private const val SKIPPED = "skipped"
private const val NEARLY_SPENT = 0.85f

private val SEARCH_WIDTH = 320.dp
private val ACTION_COLUMN = 190.dp
private val TOPUP_ACTION_COLUMN = 200.dp
private val TOPUP_STATUS_COLUMN = 120.dp
private val STATUS_COLUMN = 140.dp
private val TYPE_COLUMN = 90.dp
private val METER_COLUMN = 110.dp
private val METER_WIDTH = 90.dp
private val CARD_FACE_WIDTH = 420.dp
