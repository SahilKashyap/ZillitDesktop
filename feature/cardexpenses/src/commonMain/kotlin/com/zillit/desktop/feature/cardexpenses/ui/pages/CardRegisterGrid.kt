package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardSearch
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.ui.ALL_STATUSES
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.CardTile
import com.zillit.desktop.feature.cardexpenses.ui.components.SeamlessBar
import com.zillit.desktop.feature.cardexpenses.ui.components.StatusPills
import com.zillit.desktop.feature.cardexpenses.ui.components.TileGrid
import com.zillit.desktop.feature.cardexpenses.ui.components.TileSkeletons
import com.zillit.desktop.feature.cardexpenses.ui.openCard

/**
 * The Card Register — the web's `CardRegisterPage.jsx:872-1030`.
 *
 * One joined bar (search, Funds, Export, Request New Card), the status pills,
 * and every card as a gradient tile carrying its own lifecycle. A tile opens
 * the card full-page in place of the grid.
 *
 * Also stands in for the cardholder's approval queue until that page has its
 * own: the same tiles, narrowed to the requests awaiting a decision.
 */
@Composable
fun CardRegisterPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val approvalOnly = state.destination == CardDestination.CardsForApproval
    val rows = state.cards
        .filter { !approvalOnly || it.status == CardStatus.Pending }
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { state.matches(it) }

    ScrollingPage {
        if (!approvalOnly) {
            SeamlessBar(
                search = state.search,
                onSearch = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_ce_cards_search_register),
                exporting = state.exporting,
                onFunds = { onEvent(CardEvent.OpenFunds) },
                onExport = { pdf -> onEvent(CardEvent.ExportCards(if (pdf) ExportFormat.Pdf else ExportFormat.Excel)) },
                requestLabel = str(S.desktop_ce_cards_request_new_card),
                onRequest = { onEvent(CardEvent.OpenNewCard(null)) },
            )
            StatusPills(
                options = REGISTER_FILTERS.map { (value, key) -> value to str(key) },
                active = state.statusFilter,
                onSelect = { onEvent(CardEvent.FilterStatus(it)) },
            )
        }
        CardGrid(state, rows, console = true, onEvent = onEvent, emptyYet = S.desktop_ce_cards_none_yet_accountant)
    }
}

/**
 * The cardholder's Card tab — the web's `UserCardsPage.jsx:302-396`.
 *
 * Every card of theirs as a tile, a search and a fixed status filter, and
 * "Request New Card" only while no card of theirs blocks a new one — with no
 * explanation when it does, as on the web.
 */
@Composable
fun MyCardPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.cards
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { state.matches(it) }
    val mayRequest = state.cards.none { it.holderId == state.viewer.userId && CardRules.blocksNewRequest(it) }

    ScrollingPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_ce_cards_search_mine),
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = state.statusFilter,
                options = CREW_FILTERS.map { it.first },
                onSelect = { onEvent(CardEvent.FilterStatus(it)) },
                label = { value -> CREW_FILTERS.firstOrNull { it.first == value }?.second?.let { str(it) }.orEmpty() },
                modifier = Modifier.width(FILTER_WIDTH),
            )
            if (mayRequest) {
                ZillitButton(
                    text = str(S.desktop_ce_cards_request_new_card),
                    onClick = { onEvent(CardsEvent.OpenCrewRequest) },
                    leadingIcon = ZillitIcons.Add,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }
        CardGrid(state, rows, console = false, onEvent = onEvent, emptyYet = S.desktop_ce_cards_none_yet_crew)
    }
}

@Composable
private fun CardGrid(
    state: CardUiState,
    rows: List<ExpenseCard>,
    console: Boolean,
    onEvent: (CardEvent) -> Unit,
    emptyYet: String,
) {
    when {
        state.loading && state.cards.isEmpty() -> TileSkeletons()
        rows.isEmpty() -> ZillitText(
            text = if (state.search.isNotBlank() || state.statusFilter != ALL_STATUSES) {
                str(S.desktop_ce_cards_none_match)
            } else {
                str(emptyYet)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = EMPTY_PADDING),
        )

        else -> TileGrid(rows) { card -> CardTile(state, card, console, onEvent) }
    }
}

/** The register's search, over the holder the tile shows and the card fields it prints. */
private fun CardUiState.matches(card: ExpenseCard): Boolean = CardSearch.matches(
    card = card,
    query = search,
    holder = people.firstOrNull { it.id == card.holderId },
    providerName = providers.firstOrNull { it.id == card.providerId }?.name,
)

/** `STATUS_FILTERS` (`CardRegisterPage.jsx:90-99`). */
private val REGISTER_FILTERS = listOf(
    ALL_STATUSES to S.all,
    CardStatus.Active.wire to S.active,
    CardStatus.Approved.wire to S.approved,
    CardStatus.Override.wire to S.dm_nom_table_override,
    CardStatus.Pending.wire to S.desktop_ce_cards_pending_approval,
    CardStatus.Requested.wire to S.av_chip_requested,
    CardStatus.Rejected.wire to S.rejected,
    CardStatus.Suspended.wire to S.desktop_suspended,
)

/** The Card tab's fixed filter (`UserCardsPage.jsx:37-44`); approved reads "In-Progress" to a cardholder. */
private val CREW_FILTERS = listOf(
    ALL_STATUSES to S.desktop_ce_cards_all_status,
    CardStatus.Active.wire to S.active,
    CardStatus.Approved.wire to S.desktop_ce_cards_in_progress,
    CardStatus.Pending.wire to S.desktop_ce_cards_pending_approval,
    CardStatus.Requested.wire to S.av_chip_requested,
    CardStatus.Rejected.wire to S.rejected,
)

private val FILTER_WIDTH = 180.dp
private val EMPTY_PADDING = 48.dp

/** Whether the register is showing a card full-page rather than the grid. */
internal val CardUiState.showingCard: Boolean get() = openCard != null
