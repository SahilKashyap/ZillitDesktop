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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
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
                placeholder = str(S.desktop_card_search_register),
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
                text = if (rows.size == 1) {
                    str(S.desktop_card_count_one, rows.size)
                } else {
                    str(S.desktop_card_cards_count, rows.size)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.viewer.isAccountant && !approvalOnly) {
                // The accountant asks for money into a production bank
                // account — recording only until it is marked received.
                ZillitButton(
                    text = str(S.desktop_ce_funds),
                    onClick = { onEvent(CardEvent.OpenFunds) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Wallet,
                )
                ExportButtons(
                    busy = state.exporting,
                    onExport = { format -> onEvent(CardEvent.ExportCards(format)) },
                )
                ZillitButton(
                    text = str(S.desktop_card_issue_a_card),
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
                title = if (approvalOnly) str(S.desktop_card_awaiting_your_decision) else str(S.ah_card_register),
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
                    emptyTitle = if (approvalOnly) str(S.desktop_nothing_waiting) else str(S.desktop_card_no_cards),
                    emptyMessage = if (approvalOnly) {
                        str(S.desktop_card_approval_queue_empty)
                    } else {
                        str(S.desktop_card_register_empty)
                    },
                )
            }

            ZillitSectionCard(
                title = str(S.ah_my_cards),
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
            if (wire == ALL_STATUSES) str(S.desktop_all_statuses) else CardStatus.from(wire).label
        },
        modifier = Modifier.width(FILTER_WIDTH),
    )
}

/**
 * The export pair — the web's Export menu (PDF, Excel) as two small buttons.
 *
 * Two buttons rather than a menu because a menu here is one more click in
 * front of the one thing the control does.
 */
@Composable
internal fun ExportButtons(busy: Boolean, onExport: (ExportFormat) -> Unit) {
    ZillitButton(
        text = str(S.recce_export_pdf),
        onClick = { onExport(ExportFormat.Pdf) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !busy,
        loading = busy,
    )
    ZillitButton(
        text = str(S.desktop_dm_export_excel),
        onClick = { onExport(ExportFormat.Excel) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !busy,
    )
}

/**
 * The single action a card's status permits.
 *
 * A card lifecycle is linear — requested, pending, approved, active,
 * suspended — so one step per card is not a simplification, it is the shape
 * of the thing. A pending card's step belongs to whoever its chain's next tier
 * names (`adminUi.jsx:297-313`); anyone else sees Override, and only if they
 * hold the grant and the production allows it.
 *
 * Drawn in two places on purpose: the register's row, and the detail pane. In
 * the Account Hub the register is narrow enough that its action column scrolls
 * off the end, and a card that cannot be approved from the only place it is
 * visible is a card that cannot be approved.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The card lifecycle table: one branch per status.
@Composable
fun CardPrimaryAction(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val step = state.cardApproval(card)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        when {
            card.status == CardStatus.Pending && !step.canApprove && state.viewer.canOverrideCard -> ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.OverrideCard,
                                card.id,
                                str(S.desktop_card_override_chain),
                                str(S.desktop_card_override_card_note),
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Shield,
                enabled = !state.busy,
            )

            card.status == CardStatus.Pending && step.canApprove -> {
                ZillitButton(
                    text = str(S.approve),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.Confirm(
                                    CardConfirmAction.ApproveCard,
                                    card.id,
                                    str(S.desktop_card_approve_this_card),
                                    str(
                                        S.desktop_card_approve_limit_for,
                                        money(card.limit, card.currency),
                                        state.holderName(card).takeIf { it != "—" }
                                            ?: str(S.desktop_this_crew_member),
                                    ),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.reject),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.WithReason(
                                    CardReasonAction.RejectCard,
                                    card.id,
                                    str(S.desktop_card_reject_this_request),
                                    str(S.desktop_timecard_reject_label),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }

            (card.status == CardStatus.Approved || card.status == CardStatus.Override) &&
                state.viewer.isAccountant -> ZillitButton(
                text = str(S.dm_action_activate),
                onClick = { onEvent(CardEvent.OpenActivation(card.id)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.status == CardStatus.DigitalActive && state.viewer.isAccountant -> ZillitButton(
                text = str(S.desktop_card_assign_physical),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.WithCardNumber(card.id, str(S.desktop_card_assign_physical_card)),
                        ),
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.status == CardStatus.Active && state.viewer.isAccountant -> ZillitButton(
                text = str(S.desktop_card_suspend),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.SuspendCard,
                                card.id,
                                str(S.desktop_card_suspend_this_card),
                                str(S.desktop_card_suspend_note),
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.status == CardStatus.Suspended && state.viewer.isAccountant -> ZillitButton(
                text = str(S.desktop_card_reactivate),
                onClick = {
                    onEvent(
                        CardEvent.Ask(
                            CardPrompt.Confirm(
                                CardConfirmAction.ReactivateCard,
                                card.id,
                                str(S.desktop_card_reactivate_this_card),
                                str(S.desktop_card_reactivate_note),
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
                text = str(S.desktop_card_request_refused, card.rejectionReason),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(title = str(S.desktop_card_limit_and_headroom), icon = ZillitIcons.Wallet) {
                val headroom = state.headroom
                LedgerLine(str(S.desktop_card_card_limit), money(headroom.cardLimit, card.currency))
                LedgerLine(str(S.desktop_card_receipts_committed), money(headroom.receiptsCommit, card.currency))
                LedgerLine(str(S.desktop_card_available_to_upload), money(headroom.available, card.currency))
                if (headroom.exhausted) {
                    ZillitNotice(
                        text = str(S.desktop_card_limit_fully_committed),
                        tone = StatusTone.Rejected,
                        icon = ZillitIcons.Warning,
                        action = {
                            ZillitButton(
                                text = str(S.ah_card_extension),
                                onClick = { onEvent(CardEvent.Open(CardDestination.CardExtension)) },
                                variant = ButtonVariant.Secondary,
                                size = ButtonSize.Small,
                            )
                        },
                    )
                }
            }

        if (CardRules.canEditRequest(card, state.viewer.userId, isAccountant = false)) {
            ZillitSectionCard(title = str(S.desktop_card_correct_this_request), icon = ZillitIcons.Edit) {
                ZillitText(
                    text = str(S.desktop_card_correct_request_note),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitButton(
                    text = str(S.av_edit_request),
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
        ZillitSectionCard(title = str(S.desktop_card_request_a_card), icon = ZillitIcons.Add) {
            if (blocking != null) {
                ZillitNotice(
                    text = str(S.desktop_card_blocking_card_note, blocking.status.label.lowercase()),
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.CreditCard,
                )
            } else {
                ZillitText(
                    text = str(S.desktop_card_request_intro),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitButton(
                    text = str(S.desktop_card_request_a_card),
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
                text = str(S.desktop_card_no_card_to_extend),
                tone = StatusTone.Progress,
            )
            return@ScrollingPage
        }

        ZillitSectionCard(
            title = str(S.desktop_card_top_up_card, cardLabel(card)),
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
                        text = str(S.desktop_card_available_of, money(card.limit, card.currency)),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitButton(
                    text = str(S.desktop_card_request_top_up),
                    onClick = {
                        onEvent(
                            CardEvent.Ask(
                                CardPrompt.WithAmount(
                                    CardAmountAction.RequestTopUp,
                                    card.id,
                                    str(S.desktop_card_request_a_top_up),
                                    str(S.desktop_card_how_much_more),
                                ),
                            ),
                        )
                    },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !state.busy,
                )
            }
        }

        ZillitSectionCard(title = str(S.desktop_card_topups_on_this_card), icon = ZillitIcons.Ledger, padded = false) {
            ZillitDataTable(
                rows = state.topUps,
                columns = topUpColumns(showHolder = false),
                key = { it.id },
                loading = state.loading,
                emptyTitle = str(S.desktop_card_no_topups_requested),
                emptyMessage = str(S.desktop_card_topups_empty_hint),
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
                title = str(S.desktop_card_topup_requests),
                icon = ZillitIcons.Wallet,
                meta = str(S.desktop_card_pending_count, state.topUps.count { it.status == PENDING }),
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
                    emptyTitle = str(S.desktop_nothing_waiting),
                    emptyMessage = str(S.desktop_card_topup_queue_empty),
                )
            }

            ZillitSectionCard(
                title = str(S.docusign_request_access),
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(PANE_WEIGHT).fillMaxHeight(),
            ) {
                if (open == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_card_pick_a_request),
                        message = str(S.desktop_card_pick_request_hint),
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
                label = topUp.status.replaceFirstChar { it.uppercase() }.ifBlank { str(S.pending) },
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
        FieldGroupLabel(str(S.history))
        CardHistoryTrail(
            entries = state.topUpHistory,
            emptyMessage = str(S.desktop_card_no_request_history),
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
                        text = str(S.desktop_card_fund),
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.CompleteTopUp,
                                        row.id,
                                        str(S.desktop_card_complete_this_topup),
                                        str(
                                            S.desktop_card_topup_added_to,
                                            money(row.amount, row.currency),
                                            row.cardLastFour?.let { "•••• $it" }
                                                ?: str(S.desktop_card_the_card_fallback),
                                        ),
                                    ),
                                ),
                            )
                        },
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = str(S.desktop_card_part),
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.WithAmount(
                                        CardAmountAction.PartialTopUp,
                                        row.id,
                                        str(S.ah_partial_topup),
                                        str(S.desktop_card_amount_added),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                    ZillitButton(
                        text = str(S.skip),
                        onClick = {
                            onEvent(
                                CardEvent.Ask(
                                    CardPrompt.Confirm(
                                        CardConfirmAction.SkipTopUp,
                                        row.id,
                                        str(S.desktop_card_skip_this_topup),
                                        str(S.desktop_card_skip_topup_note),
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
            header = str(S.ah_holder),
            width = ColumnWidth.Weight(1.4f),
            cell = { PersonCell(holderName(it), userId = it.holderId) },
        ),
    )
    add(textColumn(str(S.ah_my_cards), ColumnWidth.Weight(1f), muted = true) { cardLabel(it) })
    if (!compact) {
        add(textColumn(str(S.type), ColumnWidth.Fixed(TYPE_COLUMN), muted = true) { it.type.label })
    }
    add(textColumn(str(S.ah_limit_label), ColumnWidth.Weight(1f), numeric = true) { money(it.limit, it.currency) })
    if (!compact) {
        add(textColumn(str(S.available), ColumnWidth.Weight(1f), numeric = true) { money(it.balance, it.currency) })
        add(
            TableColumn(
                header = str(S.desktop_used),
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
            header = str(S.status),
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
                header = str(S.ah_holder),
                width = ColumnWidth.Weight(1.3f),
                cell = { PersonCell(holderName(it), userId = it.holderId) },
            ),
        )
    }
    add(
        textColumn(str(S.ah_my_cards), ColumnWidth.Weight(1f), muted = true) {
            it.cardLastFour?.let { l -> "•••• $l" } ?: "—"
        },
    )
    add(textColumn(str(S.amount), ColumnWidth.Weight(1f), numeric = true) { money(it.amount, it.currency) })
    // Method and date are in the pane beside this table when it is compact,
    // and three row actions need the room more than they do.
    if (!compact) {
        add(textColumn(str(S.desktop_method), ColumnWidth.Weight(1f), muted = true) { it.method ?: "—" })
        add(textColumn(str(S.desktop_card_raised), ColumnWidth.Weight(1f), muted = true) { date(it.createdAt) })
    }
    add(
        TableColumn(
            header = str(S.status),
            width = ColumnWidth.Fixed(TOPUP_STATUS_COLUMN),
            cell = { row ->
                com.zillit.desktop.core.designsystem.component.ZillitStatusPill(
                    label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { str(S.pending) },
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

/**
 * Statuses a card request can be approved or rejected from — `pending` only:
 * a `requested` card is still with the accounts team to review and submit.
 */
private val APPROVABLE = setOf(CardStatus.Pending)

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
