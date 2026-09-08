package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/**
 * What has been sent, and who has opened it.
 *
 * Expanding a row is what fetches its open status — see
 * `DocDistViewModel.refreshOpenStatus`. The list itself carries whatever the
 * doc-dist service last wrote down, which for a send made minutes ago is
 * "unknown" for everyone.
 */
@Composable
fun HistoryPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    FixedPage {
        ZillitSearchField(
            value = state.historySearch,
            onValueChange = { onEvent(DocDistEvent.SearchHistory(it)) },
            placeholder = "Search subject or recipient",
            modifier = Modifier.width(SEARCH_WIDTH.dp),
        )
        SentByMenu(state, onEvent)

        // Weighted so the expanded detail below has room. Without this the
        // table takes its natural height, the detail card is laid out past the
        // bottom of a page that does not scroll, and expanding a row appears to
        // do nothing at all.
        ZillitSectionCard(
            title = "Sent",
            icon = ZillitIcons.Send,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = state.history,
                key = { it.id },
                loading = state.loading,
                columns = historyColumns(onEvent),
                onRowClick = { row ->
                    // Click toggles: clicking the open row closes it, which is
                    // the only way back to the full list without a second
                    // control nobody would look for.
                    val next = if (state.expandedDistributionId == row.id) null else row.id
                    onEvent(DocDistEvent.ExpandDistribution(next))
                },
                isSelected = { it.id == state.expandedDistributionId },
                emptyTitle = "Nothing sent yet",
                emptyMessage = "Distributions sent from the library appear here with " +
                    "per-recipient open status.",
            )
        }

        state.history.firstOrNull { it.id == state.expandedDistributionId }?.let { expanded ->
            RecipientDetail(expanded, onEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecipientDetail(
    distribution: Distribution,
    onEvent: (DocDistEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(
        modifier = modifier,
        title = distribution.subject,
        icon = ZillitIcons.Users,
        meta = distribution.openSummary,
        padded = false,
        action = {
            ZillitButton(
                text = "Send again",
                onClick = { onEvent(DocDistEvent.DuplicateDistribution(distribution.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
            )
        },
    ) {
        if (distribution.attachmentNames.isNotEmpty()) {
            Column(modifier = Modifier.padding(ZillitTheme.spacing.lg)) {
                ZillitText(
                    text = "Attached: ${distribution.attachmentNames.joinToString(", ")}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
        ZillitDataTable(
            rows = distribution.recipients,
            key = { it.recipient.email + (it.uniqueId ?: "") },
            columns = recipientColumns(),
            emptyTitle = "No recipients recorded",
        )
    }
}

private fun historyColumns(
    onEvent: (DocDistEvent) -> Unit,
): List<TableColumn<Distribution>> = listOf(
    TableColumn(
        header = "Subject",
        width = ColumnWidth.Weight(NAME_WEIGHT),
        cell = { row ->
            Column {
                ZillitText(text = row.subject, maxLines = 1)
                val subtitle = buildList {
                    if (row.sentByName.isNotBlank()) add(row.sentByName)
                    if (row.listsUsed.isNotEmpty()) add(row.listsUsed.joinToString(", "))
                }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    ZillitText(
                        text = subtitle,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        },
    ),
    textColumn("Sent", ColumnWidth.Fixed(SENT_COLUMN.dp), muted = true) {
        EpochDate.dateTime(it.sentAt)
    },
    textColumn("Recipients", ColumnWidth.Fixed(COUNT_COLUMN.dp), numeric = true) {
        it.recipients.size.toString()
    },
    TableColumn(
        header = "Opened",
        width = ColumnWidth.Fixed(OPENED_COLUMN.dp),
        cell = { row ->
            ZillitStatusPill(
                label = row.openSummary,
                tone = when {
                    row.recipients.isEmpty() -> StatusTone.Neutral
                    row.openedCount == row.recipients.size -> StatusTone.Done
                    row.openedCount > 0 -> StatusTone.Progress
                    else -> StatusTone.Pending
                },
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN.dp),
        cell = { row ->
            ZillitButton(
                text = "Duplicate",
                onClick = { onEvent(DocDistEvent.DuplicateDistribution(row.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ),
)

private fun recipientColumns(): List<TableColumn<DeliveryStatus>> = listOf(
    TableColumn(
        header = "Recipient",
        width = ColumnWidth.Weight(2f),
        cell = { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.zillit.desktop.core.designsystem.component.ZillitAvatar(
                    name = row.recipient.name.ifBlank { row.recipient.email },
                )
                Column {
                    ZillitText(
                        text = row.recipient.name.ifBlank { row.recipient.email },
                        maxLines = 1,
                    )
                    if (row.recipient.name.isNotBlank()) {
                        ZillitText(
                            text = row.recipient.email,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    ),
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN.dp),
        cell = { row ->
            ZillitStatusPill(
                label = when (row.state) {
                    OpenState.Opened -> "Opened"
                    OpenState.NotOpened -> "Not opened"
                    // Deliberately not "not opened": no evidence is not the
                    // same news, and a coordinator chasing someone who did read
                    // it with images off is the cost of conflating them.
                    OpenState.Unknown -> "No read receipt"
                },
                tone = when (row.state) {
                    OpenState.Opened -> StatusTone.Done
                    OpenState.NotOpened -> StatusTone.Pending
                    OpenState.Unknown -> StatusTone.Neutral
                },
                dot = true,
            )
        },
    ),
    textColumn("First opened", ColumnWidth.Fixed(SENT_COLUMN.dp), muted = true) {
        EpochDate.dateTime(it.openedAt).ifBlank { "—" }
    },
    textColumn("Opens", ColumnWidth.Fixed(COUNT_COLUMN.dp), numeric = true) {
        if (it.openCount > 0) it.openCount.toString() else "—"
    },
)

/** The name column takes three shares of what the fixed columns leave. */
private const val NAME_WEIGHT = 3f
private const val SEARCH_WIDTH = 320
private const val SENT_COLUMN = 190
private const val COUNT_COLUMN = 90
private const val OPENED_COLUMN = 150
private const val STATUS_COLUMN = 160
private const val ACTION_COLUMN = 110

/**
 * The "Sent by" filter (ZL-21138): a button that says All or how many are
 * ticked, opening a checkbox list of every sender; a search box appears once
 * the list is long enough to need one. Multi-select, OR semantics, the same
 * shape as the web's drawer control.
 */
@Composable
private fun SentByMenu(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val picked = state.historySenderIds.size
    Box {
        ZillitButton(
            text = if (picked == 0) "Sent by: All" else "Sent by: $picked selected",
            onClick = { onEvent(DocDistEvent.HistorySenderMenu(!state.historySenderMenuOpen)) },
            variant = ButtonVariant.Secondary,
            enabled = state.historySenders.isNotEmpty(),
        )
        DropdownMenu(
            expanded = state.historySenderMenuOpen,
            onDismissRequest = { onEvent(DocDistEvent.HistorySenderMenu(false)) },
        ) {
            Column(
                Modifier
                    .width(SENDER_MENU_WIDTH)
                    .heightIn(max = SENDER_MENU_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                if (state.historySenders.size > SENDER_SEARCH_THRESHOLD) {
                    ZillitSearchField(
                        value = state.historySenderQuery,
                        onValueChange = { onEvent(DocDistEvent.SearchHistorySenders(it)) },
                        placeholder = "Search senders",
                    )
                }
                val query = state.historySenderQuery.trim()
                val shown = state.historySenders.filter {
                    query.isBlank() || it.name.contains(query, ignoreCase = true)
                }
                if (picked > 0) {
                    ZillitButton(
                        text = "Clear",
                        onClick = { onEvent(DocDistEvent.ClearHistorySenders) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
                shown.forEach { sender ->
                    ZillitCheckbox(
                        checked = sender.id in state.historySenderIds,
                        onCheckedChange = { onEvent(DocDistEvent.ToggleHistorySender(sender.id)) },
                        label = sender.name.ifBlank { sender.id } +
                            sender.designation.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    )
                }
                if (shown.isEmpty()) {
                    ZillitText(
                        text = "No sender matches.",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

private val SENDER_MENU_WIDTH = 320.dp
private val SENDER_MENU_HEIGHT = 360.dp
private const val SENDER_SEARCH_THRESHOLD = 6
