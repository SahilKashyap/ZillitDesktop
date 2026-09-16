package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.ALL_STATUSES
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
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.DetailPanePadding
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.PersonCell
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/** The standard page body. See the cash module's equivalent. */
@Composable
fun ScrollingPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
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
                CardLimitsTotal(overview?.cards.orEmpty())
            }
        }

        ZillitSectionCard(title = "Cards", icon = ZillitIcons.CreditCard, padded = false) {
            ZillitDataTable(
                rows = overview?.cards.orEmpty(),
                columns = cardColumns(holderName = state::holderShortName),
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

/**
 * What the production has granted, and how much of it is gone.
 *
 * **Only where the cards agree on a currency.** A production can hold cards in
 * yen and in pounds, and adding those gives a number that is not money — the
 * dashboard was printing "¥41.56 of ¥66,959.62" over a mixed set, which is
 * both wrong and confidently wrong. Converting needs the project's exchange
 * rates, which this tool does not carry; naming the mix is honest and costs
 * the reader nothing they had.
 */
@Composable
private fun CardLimitsTotal(cards: List<ExpenseCard>) {
    val currencies = cards.mapNotNull { it.currency?.takeIf(String::isNotBlank) }.distinct()
    if (currencies.size > 1) {
        ZillitText(
            text = "${cards.size} cards in ${currencies.sorted().joinToString(", ")}",
            style = ZillitTheme.typography.titleMedium,
        )
        ZillitText(
            text = "Held in more than one currency, so there is no single total. " +
                "Open the register to see each card's own.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }

    val currency = currencies.firstOrNull()
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
@Suppress("LongMethod") // The toolbar, the table and the pane it fills; one screen.
@Composable
fun CardRegisterPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val approvalOnly = state.destination == CardDestination.CardsForApproval
    val rows = state.cards
        .filter { !approvalOnly || it.status in APPROVABLE }
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { it.matches(state.search) }
    val selected = state.selectedCard

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = "Search by holder, last four or code",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (!approvalOnly) {
                StatusFilter(
                    selected = state.statusFilter,
                    options = state.cards.map { it.status }.distinct(),
                    onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                )
            }
            ZillitText(
                text = "${rows.size} card${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isAccountant && !approvalOnly) {
                ZillitButton(
                    text = "Issue a card",
                    onClick = { onEvent(CardEvent.OpenNewCard(null)) },
                    leadingIcon = ZillitIcons.Add,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = if (approvalOnly) "Cards awaiting your decision" else "Card register",
                icon = ZillitIcons.CreditCard,
                padded = false,
                modifier = Modifier.weight(REGISTER_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    // No action column: the pane beside this table carries it, and
                    // eight columns in a half-width table is what pushed it off the end.
                    columns = cardColumns(compact = true, holderName = state::holderShortName),
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

            ZillitSectionCard(
                title = "Card",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(PANE_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) CardDetailPlaceholder() else CardDetailPane(state, selected, onEvent)
            }
        }
    }
}

/**
 * The status filter, offering only the statuses actually present.
 *
 * A fixed list of eleven would show ten empty options on a production with one
 * card, and picking an empty one reads as a broken screen rather than as an
 * empty result.
 */
@Composable
private fun StatusFilter(
    selected: String,
    options: List<CardStatus>,
    onSelect: (String) -> Unit,
) {
    if (options.size < 2) return
    val all = listOf(ALL_STATUSES) + options.map { it.wire }
    ZillitSelect(
        value = selected,
        options = all,
        onSelect = onSelect,
        label = { wire ->
            if (wire == ALL_STATUSES) "All statuses" else CardStatus.from(wire).label
        },
        modifier = Modifier.width(FILTER_WIDTH),
    )
}

/**
 * The single action a card's status permits.
 *
 * A card lifecycle is linear — requested, approved, active, suspended — so one
 * button per card is not a simplification, it is the shape of the thing.
 *
 * Drawn in two places on purpose: the register's row, and the detail pane. In
 * the Account Hub the register is narrow enough that its action column scrolls
 * off the end, and a card that cannot be approved from the only place it is
 * visible is a card that cannot be approved.
 */
@Suppress("LongMethod") // The card lifecycle table: one branch per status.
@Composable
fun CardPrimaryAction(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
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
                                        (state.holderName(card).takeIf { it != "—" }
                                            ?: "this crew member") + ".",
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
}

/**
 * The cardholder's own card, and the request form when they have none.
 *
 * The one-card rule is explained before it is enforced: a disabled button with
 * no reason is how people end up emailing the accounts office.
 */
@Composable
fun MyCardPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val card = state.myCard

    ScrollingPage {
        if (card != null) MyCardSummary(state, card, onEvent)
        RequestCardSection(state, onEvent)
    }
}

/** The card this person holds: its face, its headroom, and a way to correct it. */
@Composable
private fun MyCardSummary(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
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

        if (CardRules.canEditRequest(card, state.viewer.userId, isAccountant = false)) {
            ZillitSectionCard(title = "Correct this request", icon = ZillitIcons.Edit) {
                ZillitText(
                    text = "A request that has not been approved yet can still be changed. " +
                        "Saving sends it back through the approval chain from the top.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitButton(
                    text = "Edit request",
                    onClick = { onEvent(CardEvent.OpenCardEdit(card.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                    enabled = !state.busy,
                )
            }
        }
    }
}

/**
 * Asking for a card, or being told why you cannot.
 *
 * The one-card rule is explained before it is enforced: a disabled button with
 * no reason is how people end up emailing the accounts office.
 */
@Composable
private fun RequestCardSection(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    Column {
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
                ZillitText(
                    text = "Say what you need the card for and what limit it should carry. " +
                        "The accounts team set the figure they authorise.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitButton(
                    text = "Request a card",
                    onClick = { onEvent(CardEvent.OpenNewCard(state.viewer.userId)) },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
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
    val open = state.topUps.firstOrNull { it.id == state.openTopUpId }

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = "Top-up requests",
                icon = ZillitIcons.Wallet,
                meta = "${state.topUps.count { it.status == PENDING }} pending",
                padded = false,
                modifier = Modifier.weight(QUEUE_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = state.topUps,
                    columns = topUpColumns(
                        showHolder = true,
                        compact = true,
                        holderName = { state.personName(it.holderId, it.holderName) },
                    ) + topUpActions(state, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(CardEvent.OpenTopUpHistory(it.id)) },
                    isSelected = { it.id == state.openTopUpId },
                    emptyTitle = "Nothing waiting",
                    emptyMessage = "Cardholder top-up requests land here as they are raised.",
                )
            }

            ZillitSectionCard(
                title = "Request",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(PANE_WEIGHT).fillMaxHeight(),
            ) {
                if (open == null) {
                    ZillitEmptyState(
                        title = "Pick a request",
                        message = "Who asked, what for, and what has been done about it so far.",
                        icon = ZillitIcons.Wallet,
                    )
                } else {
                    TopUpDetail(state, open)
                }
            }
        }
    }
}

/**
 * One top-up request and its trail.
 *
 * The trail is the point: a partial payment, a skip and a completion all leave
 * the row saying something different, and the funding queue is worked by more
 * than one person. Without it "why is this only half funded" has no answer on
 * screen.
 */
@Composable
private fun TopUpDetail(state: CardUiState, topUp: CardTopUp) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = DetailPanePadding,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = money(topUp.amount, topUp.currency),
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = listOfNotNull(
                        state.personName(topUp.holderId, topUp.holderName).takeIf { it != "—" },
                        topUp.cardLastFour?.let { "•••• $it" },
                        date(topUp.createdAt).takeIf { it != "—" },
                        topUp.method?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitStatusPill(
                label = topUp.status.replaceFirstChar { it.uppercase() }.ifBlank { "Pending" },
                tone = when (topUp.status) {
                    "completed" -> StatusTone.Done
                    "skipped" -> StatusTone.Neutral
                    "partial" -> StatusTone.Progress
                    else -> StatusTone.Pending
                },
                dot = true,
            )
        }

        ZillitDivider()
        FieldGroupLabel("History")
        CardHistoryTrail(
            entries = state.topUpHistory,
            emptyMessage = "Nothing has happened to this request yet.",
        )
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
/**
 * The card table's columns.
 *
 * [compact] drops the three the detail pane repeats — type, remaining balance
 * and the meter. Eight columns in the half-width table beside an open card
 * pushed the status off the end, so the one thing a register is read for was
 * the one thing behind a horizontal scroll.
 */
fun cardColumns(
    compact: Boolean = false,
    /** Names the holder through the crew directory; see `CardUiState.holderName`. */
    holderName: (ExpenseCard) -> String = { it.holderName.ifBlank { "—" } },
): List<TableColumn<ExpenseCard>> = buildList {
    add(
        TableColumn(
            header = "Holder",
            width = ColumnWidth.Weight(1.4f),
            cell = { PersonCell(holderName(it), userId = it.holderId) },
        ),
    )
    add(textColumn("Card", ColumnWidth.Weight(1f), muted = true) { cardLabel(it) })
    if (!compact) {
        add(textColumn("Type", ColumnWidth.Fixed(TYPE_COLUMN), muted = true) { it.type.label })
    }
    add(textColumn("Limit", ColumnWidth.Weight(1f), numeric = true) { money(it.limit, it.currency) })
    if (!compact) {
        add(textColumn("Available", ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) })
        add(
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
        )
    }
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { CardStatusPill(it.status) },
        ),
    )
}

@Suppress("MagicNumber") // Column proportions.
fun topUpColumns(
    showHolder: Boolean,
    compact: Boolean = false,
    /** Names the holder through the crew directory; see `CardUiState.holderName`. */
    holderName: (CardTopUp) -> String = { it.holderName.ifBlank { "—" } },
): List<TableColumn<CardTopUp>> = buildList {
    if (showHolder) {
        add(
            TableColumn(
                header = "Holder",
                width = ColumnWidth.Weight(1.3f),
                cell = { PersonCell(holderName(it), userId = it.holderId) },
            ),
        )
    }
    add(textColumn("Card", ColumnWidth.Weight(1f), muted = true) { it.cardLastFour?.let { l -> "•••• $l" } ?: "—" })
    add(textColumn("Amount", ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    // Method and date are in the pane beside this table when it is compact,
    // and three row actions need the room more than they do.
    if (!compact) {
        add(textColumn("Method", ColumnWidth.Weight(1f), muted = true) { it.method ?: "—" })
        add(textColumn("Raised", ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
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

private const val QUEUE_WEIGHT = 1.5f
private const val REGISTER_WEIGHT = 1.55f
private const val PANE_WEIGHT = 1f
private val SEARCH_WIDTH = 320.dp
private val FILTER_WIDTH = 190.dp
private val TOPUP_ACTION_COLUMN = 200.dp
private val TOPUP_STATUS_COLUMN = 120.dp
private val STATUS_COLUMN = 140.dp
private val TYPE_COLUMN = 90.dp
private val METER_COLUMN = 110.dp
private val METER_WIDTH = 90.dp
private val CARD_FACE_WIDTH = 420.dp
