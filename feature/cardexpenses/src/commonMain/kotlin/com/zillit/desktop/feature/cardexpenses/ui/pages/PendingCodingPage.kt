package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.money
import kotlin.time.Clock

/**
 * Pending Coding — the web's `PendingCodingPage`: transactions still waiting
 * on a receipt or a code from their holder, one collapsible card per holder.
 *
 * A read-only view: no row opens and nothing is decided here, so there is no
 * detail pane. The filters live in a popover that edits a draft until Done.
 */
@Suppress("LongMethod") // One page, top to bottom in the web's order.
@Composable
fun PendingCodingPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var applied by remember { mutableStateOf(PendingFilters()) }
    var expanded by remember { mutableStateOf<Set<String>?>(null) }
    val now = remember(state.receipts) { Clock.System.now().toEpochMilliseconds() }

    val groups = state.receipts.groupBy { it.holderId.orEmpty() }
    val holders = groups.keys.toList()
    val shown = groups
        .filterKeys { applied.holder.isEmpty() || it == applied.holder }
        .mapValues { (_, rows) -> rows.filter { it.passes(state.search, applied, now) } }
        .filterValues { it.isNotEmpty() }
    // The first group opens on arrival (`PendingCodingPage.jsx:99-104`).
    val open = expanded ?: setOfNotNull(groups.keys.firstOrNull())

    FixedPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CardEvent.Search(it)) },
                placeholder = str(S.desktop_ce_process_search_pending),
                modifier = Modifier.weight(1f),
            )
            FiltersButton(state, holders, applied) { applied = it }
        }

        when {
            state.loading && state.receipts.isEmpty() ->
                Row(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl), Arrangement.Center) { ZillitSpinner() }

            shown.isEmpty() -> ZillitEmptyState(
                title = str(S.desktop_ce_process_no_pending),
                icon = ZillitIcons.Receipt,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                shown.forEach { (holderId, rows) ->
                    HolderGroup(
                        state = state,
                        holderId = holderId,
                        rows = rows,
                        now = now,
                        expanded = holderId in open,
                        onToggle = { expanded = if (holderId in open) open - holderId else open + holderId },
                    )
                }
            }
        }
    }
}

/** The applied filters (`EMPTY_FILTERS`): holder, age bucket, and a date window. */
private data class PendingFilters(
    val holder: String = "",
    val age: AgeFilter? = null,
    val from: String = "",
    val to: String = "",
) {
    val count: Int get() = listOf(holder.isNotEmpty(), age != null, from.isNotEmpty(), to.isNotEmpty()).count { it }
}

private enum class AgeFilter(private val labelKey: String) {
    Overdue(S.desktop_ce_process_age_overdue),
    ThisWeek(S.desktop_cr_this_week),
    LastWeek(S.desktop_drive_bucket_last_week),
    ;

    val label: String get() = str(labelKey)
}

/** Whole days since the receipt was created; an undated one is a day old at most (`age` = 0). */
private fun CardReceipt.ageDays(now: Long): Long = createdAt?.let { (now - it) / DAY } ?: 0

/**
 * The web's filter over one row: description or amount for the search
 * (`PendingCodingPage.jsx:129`), the date window on the row's date — the end
 * day inclusive — and the age buckets on its creation.
 */
private fun CardReceipt.passes(search: String, filters: PendingFilters, now: Long): Boolean {
    val needle = search.trim().lowercase()
    if (needle.isNotEmpty() && !description.lowercase().contains(needle) && !amountText().contains(needle)) {
        return false
    }
    val from = CardDates.toMillis(filters.from.takeIf { it >= DATE_FLOOR })
    val to = CardDates.toMillis(filters.to.takeIf { it >= DATE_FLOOR })
    val day = date
    if (from != null && day != null && day < from) return false
    if (to != null && day != null && day > to + DAY) return false
    val age = ageDays(now)
    return when (filters.age) {
        AgeFilter.Overdue -> age >= OVERDUE_DAYS
        AgeFilter.ThisWeek -> age <= OVERDUE_DAYS
        AgeFilter.LastWeek -> age in OVERDUE_DAYS..TWO_WEEKS
        null -> true
    }
}

/** `String(r.amount)`: the figure as JavaScript prints it — no trailing `.0`. */
private fun CardReceipt.amountText(): String =
    if (amount == kotlin.math.floor(amount)) amount.toLong().toString() else amount.toString()

@Suppress("LongMethod") // The popover, draft to Done.
@Composable
private fun FiltersButton(
    state: CardUiState,
    holders: List<String>,
    applied: PendingFilters,
    onApply: (PendingFilters) -> Unit,
) {
    var draft by remember { mutableStateOf<PendingFilters?>(null) }
    val colors = ZillitTheme.colors
    Box {
        ZillitButton(
            text = if (applied.count > 0) {
                str(S.desktop_ce_process_filters_count, applied.count)
            } else {
                str(S.ah_filters_header)
            },
            onClick = { draft = if (draft == null) applied else null },
            variant = if (applied.count > 0) ButtonVariant.Secondary else ButtonVariant.Tertiary,
            leadingIcon = ZillitIcons.Filter,
        )
        val editing = draft ?: return@Box
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, FILTER_DROP),
            onDismissRequest = { draft = null },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .width(FILTER_WIDTH)
                    .shadow(POPOVER_ELEVATION, ZillitTheme.shapes.large)
                    .clip(ZillitTheme.shapes.large)
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, ZillitTheme.shapes.large),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = str(S.desktop_ce_process_filter_pending_title),
                        style = ZillitTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = str(S.desktop_reset_all),
                        onClick = { draft = PendingFilters() },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = editing.count > 0,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    FilterLabel(str(S.desktop_card_card_holder))
                    ZillitSelect(
                        value = editing.holder,
                        options = listOf("") + holders,
                        onSelect = { draft = editing.copy(holder = it) },
                        label = { id ->
                            if (id.isEmpty()) str(S.desktop_ce_process_all_holders) else state.personName(id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilterLabel(str(S.desktop_pc_age))
                    ZillitSelect(
                        value = editing.age,
                        options = listOf(null) + AgeFilter.entries,
                        onSelect = { draft = editing.copy(age = it) },
                        label = { it?.label ?: str(S.desktop_ce_process_all_ages) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilterLabel(str(S.cs_date_range))
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        ZillitDateField(
                            value = editing.from,
                            onValueChange = { draft = editing.copy(from = it) },
                            errorText = str(S.desktop_ce_process_date_floor).takeIf {
                                editing.from.isNotEmpty() && editing.from < DATE_FLOOR
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ZillitDateField(
                            value = editing.to,
                            onValueChange = { draft = editing.copy(to = it) },
                            errorText = str(S.desktop_ce_process_date_floor).takeIf {
                                editing.to.isNotEmpty() && editing.to < maxOf(DATE_FLOOR, editing.from)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = if (editing.count == 1) {
                            str(S.desktop_ce_process_filter_selected_one)
                        } else {
                            str(S.desktop_ce_process_filters_selected, editing.count)
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = str(S.ah_done),
                        onClick = {
                            onApply(editing)
                            draft = null
                        },
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/** One holder's card: avatar, name and department, "N pending", their total — and, open, their rows. */
@Suppress("LongMethod") // The header and its table are one card.
@Composable
private fun HolderGroup(
    state: CardUiState,
    holderId: String,
    rows: List<CardReceipt>,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val person = state.people.firstOrNull { it.id == holderId }
    val name = state.personName(holderId, rows.firstOrNull()?.holderName.orEmpty())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = name, userId = holderId.takeIf { it.isNotBlank() }, size = GROUP_AVATAR)
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                )
                person?.department?.takeIf { it.isNotBlank() }?.let {
                    ZillitText(
                        text = "— $it",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            ZillitStatusPill(label = str(S.desktop_card_pending_count, rows.size), tone = StatusTone.Pending)
            ZillitText(
                text = money(rows.sumOf { it.amount }, rows.firstNotNullOfOrNull { it.currency }),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
            ZillitIcon(
                icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = colors.textMuted,
            )
        }
        if (expanded) {
            ZillitDataTable(
                rows = rows,
                columns = pendingColumns(state, now),
                key = { it.id },
                virtualised = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun pendingColumns(state: CardUiState, now: Long): List<TableColumn<CardReceipt>> = listOf(
    TableColumn(str(S.date), ColumnWidth.Fixed(130.dp)) { DateCell(it) },
    TableColumn(str(S.ah_merchant), ColumnWidth.Weight(1.8f)) { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = row.description.ifBlank { EM_DASH },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.urgent) UrgentPill()
        }
    },
    TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(1.3f)) {
        HolderCell(state, it.holderId, it.holderName)
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(120.dp), numeric = true) { row ->
        ZillitText(
            text = money(row.amount, row.currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    },
    TableColumn(str(S.desktop_pc_age), ColumnWidth.Fixed(64.dp)) { row ->
        val age = row.ageDays(now)
        ZillitText(
            text = str(S.desktop_pc_age_days, age),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = if (age >= OVERDUE_DAYS) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
        )
    },
    TableColumn(str(S.status), ColumnWidth.Fixed(150.dp)) { row ->
        val (label, tone) = when {
            row.ageDays(now) >= OVERDUE_DAYS -> str(S.desktop_overdue) to StatusTone.Rejected
            row.status == CardWorkflowStatus.PendingReceipt ->
                str(S.ah_txn_filter_pending_receipt) to StatusTone.Pending

            row.status == CardWorkflowStatus.PendingCode -> str(S.ah_txn_filter_pending_code) to StatusTone.Progress
            row.status == CardWorkflowStatus.Unknown -> str(S.pending) to StatusTone.Pending
            else -> row.status.label to StatusTone.Pending
        }
        ZillitStatusPill(label = label, tone = tone)
    },
)

/** The date filter's floor — before any production this serves (`DATE_FILTER_MIN`). */
private const val DATE_FLOOR = "2000-01-01"
private const val DAY = 86_400_000L
private const val OVERDUE_DAYS = 7L
private const val TWO_WEEKS = 14L
private const val FILTER_DROP = 44
private val FILTER_WIDTH = 340.dp
private val POPOVER_ELEVATION = 12.dp
private val GROUP_AVATAR = 32.dp
