package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Everything that has been posted, with what it came to.
 *
 * The three figures at the top are the reason this is not just another queue:
 * history is read to answer "how much has gone through this card programme",
 * and making somebody add up a table to find out is the difference between a
 * record and a report. They ride above the standard receipt queue —
 * which already knows how to read History: an accountant's Edit that opens
 * the process editor in save-only mode (no second post, no top-up), the
 * nominal code in place of the match column, and each receipt's full trail in
 * the pane beside it.
 */
@Composable
fun HistoryPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val posted = state.receipts
    val currency = posted.firstOrNull { it.currency != null }?.currency
    val holders = posted.mapNotNull { it.holderId?.takeIf { id -> id.isNotBlank() } }.distinct().size

    ReceiptQueuePage(state, onEvent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitStatTile(
                label = str(S.desktop_card_posted_to_date),
                value = money(posted.sumOf { it.amount }, currency),
                sub = if (posted.size == 1) {
                    str(S.desktop_card_receipt_count_one, posted.size)
                } else {
                    str(S.desktop_card_receipt_count_other, posted.size)
                },
                tone = StatusTone.Done,
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_card_cardholders),
                value = holders.toString(),
                sub = str(S.desktop_card_with_posted_spend),
                icon = ZillitIcons.Users,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_card_average_receipt),
                value = money(
                    posted.takeIf { it.isNotEmpty() }?.let { rows -> rows.sumOf { it.amount } / rows.size },
                    currency,
                ),
                sub = str(S.desktop_card_across_everything_posted),
                icon = ZillitIcons.Receipt,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
