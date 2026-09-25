package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMode
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Bulk Process — the web's `BulkProcessPage`: the process queue's approved
 * receipts, to batch-post together or open one by one in the full editor.
 *
 * A row may be ticked when it is out of the closed period and this
 * accountant's to post — a senior's any, everyone else's only their own.
 * While anything is ticked a row click ticks; otherwise it opens the editor.
 *
 * ## Blank means "keep"
 *
 * The override fields in the bar apply to every ticked row, and one left on
 * "Keep each" leaves each row's own coding alone — the difference between
 * correcting forty rows and overwriting forty rows.
 */
@Suppress("LongMethod") // The grid and the override bar are one screen; splitting hides the link.
@Composable
fun BulkProcessPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    var merchant by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    val rows = state.receipts
    val merchants = rows.map { it.description }.filter { it.isNotBlank() }.distinct()
    val holders = rows.mapNotNull { row -> state.people.firstOrNull { it.id == row.holderId }?.name }.distinct()
    val filtered = rows.filter { row ->
        (merchant.isEmpty() || row.description == merchant) &&
            (holder.isEmpty() || state.people.firstOrNull { it.id == row.holderId }?.name == holder)
    }
    val ticked = rows.filter { it.id in state.selection }
    val busy = state.processPages.bulkBusy

    Box(Modifier.fillMaxSize()) {
        FixedPage {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitSelect(
                    value = merchant,
                    options = listOf("") + merchants,
                    onSelect = { merchant = it },
                    label = { it.ifEmpty { str(S.desktop_ce_process_all_merchants) } },
                    modifier = Modifier.width(FILTER_WIDTH),
                )
                ZillitSelect(
                    value = holder,
                    options = listOf("") + holders,
                    onSelect = { holder = it },
                    label = { it.ifEmpty { str(S.desktop_ce_process_all_holders) } },
                    modifier = Modifier.width(FILTER_WIDTH),
                )
            }

            if (!state.loading && filtered.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ce_process_no_bulk),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val count = if (filtered.size == 1) {
                    str(S.desktop_ce_process_one_item)
                } else {
                    str(S.desktop_docdist_items_count, filtered.size)
                }
                ZillitSectionCard(
                    title = str(S.desktop_ce_process_batch_queue),
                    icon = ZillitIcons.Grid,
                    meta = count,
                    padded = false,
                    action = { ZillitStatusPill(label = count, tone = StatusTone.Pending) },
                    modifier = Modifier.weight(1f),
                ) {
                    ZillitDataTable(
                        rows = filtered,
                        columns = bulkColumns(state, filtered, onEvent),
                        key = { it.id },
                        loading = state.loading,
                        isSelected = { it.id in state.selection },
                        onRowClick = { row ->
                            if (state.selection.isNotEmpty()) {
                                onEvent(ProcessPageEvent.ToggleRow(row.id))
                            } else {
                                onEvent(CardEvent.OpenProcess(row.id, ProcessMode.Process))
                            }
                        },
                    )
                }
            }

            if (ticked.isNotEmpty()) OverrideBar(state, ticked, onEvent)
        }
        // The page waits while the batch goes (`BulkProcessPage.jsx:274-278`).
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(ZillitTheme.colors.surface.copy(alpha = OVERLAY_ALPHA)),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
        }
    }
}

@Suppress("LongMethod") // The five fields and the post, as one bar.
@Composable
private fun OverrideBar(state: CardUiState, ticked: List<CardReceipt>, onEvent: (CardEvent) -> Unit) {
    val coding = state.bulkCoding
    val refs = state.processPages.refs
    val departments = state.people
        .filter { it.departmentId.isNotBlank() }
        .distinctBy { it.departmentId }
        .map { it.departmentId to it.department.ifBlank { it.departmentId } }
        .sortedBy { it.second.lowercase() }
    fun edit(change: (BulkCoding) -> BulkCoding) = onEvent(CardEvent.EditBulkCoding(change(coding)))

    SelectionBar {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZillitText(
                    text = ticked.size.toString(),
                    style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = ZillitTheme.colors.accentText,
                )
                FieldGroupLabel(str(S.selected))
            }
            ZillitVerticalDivider(Modifier.width(1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldGroupLabel(str(S.desktop_ce_process_bulk_override_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        FieldGroupLabel(str(S.ah_lbl_account_code))
                        CoaCodeInput(
                            value = coding.nominalCode.orEmpty(),
                            onValueChange = { code -> edit { it.copy(nominalCode = code.takeIf(String::isNotBlank)) } },
                            accounts = refs.accounts,
                            placeholder = str(S.desktop_ce_process_keep_each),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FieldGroupLabel(str(S.department))
                        ZillitSelect(
                            value = coding.departmentId,
                            options = listOf(null) + departments.map { it.first },
                            onSelect = { id -> edit { it.copy(departmentId = id) } },
                            label = { id ->
                                id?.let { departments.firstOrNull { d -> d.first == it }?.second }
                                    ?: str(S.desktop_ce_process_keep_each)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.viewer.isTelevision) {
                        Column(Modifier.weight(1f)) {
                            FieldGroupLabel(str(S.episode))
                            ZillitSelect(
                                value = coding.episode,
                                options = listOf(null) + EPISODES,
                                onSelect = { value -> edit { it.copy(episode = value) } },
                                label = { it ?: str(S.desktop_ce_process_keep_each) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldGroupLabel(str(S.ah_lbl_tax_rate))
                        ZillitSelect(
                            value = coding.taxType,
                            options = listOf(null) + refs.taxTypes.map { it.identifier },
                            onSelect = { id ->
                                val type = refs.taxTypes.firstOrNull { it.identifier == id }
                                edit { it.copy(taxType = id, taxRate = type?.let { t -> t.rate ?: 0.0 }) }
                            },
                            label = { id ->
                                id?.let { refs.taxTypes.firstOrNull { t -> t.identifier == it }?.optionLabel ?: it }
                                    ?: str(S.desktop_ce_process_per_receipt)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FieldGroupLabel(str(S.desktop_card_top_up))
                        ZillitSelect(
                            value = coding.topUp,
                            options = TopUpMode.entries,
                            onSelect = { mode -> edit { it.copy(topUp = mode) } },
                            label = { it.bulkLabel() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            ZillitVerticalDivider(Modifier.width(1.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = money(ticked.sumOf { it.amount }, ticked.firstNotNullOfOrNull { it.currency }),
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ZillitTheme.colors.accentText,
                )
                ZillitButton(
                    text = if (ticked.size == 1) {
                        str(S.desktop_ce_process_batch_post_one)
                    } else {
                        str(S.desktop_ce_process_batch_post_n, ticked.size)
                    },
                    onClick = { onEvent(ProcessPageEvent.BatchPost) },
                    leadingIcon = ZillitIcons.Check,
                    loading = state.processPages.bulkBusy,
                    enabled = !state.processPages.bulkBusy,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_ce_process_bulk_tip),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(ZillitTheme.spacing.sm))
            ZillitButton(
                text = str(S.ah_clear_selection),
                onClick = { onEvent(CardEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The bar's own wording for the top-up choices (`BulkProcessPage.jsx:461-466`). */
private fun TopUpMode.bulkLabel(): String = when (this) {
    TopUpMode.Keep -> str(S.desktop_ce_process_per_receipt)
    TopUpMode.Restore -> str(S.desktop_card_restore_float)
    TopUpMode.ByExpense -> str(S.desktop_ce_process_by_expense_amt)
    TopUpMode.None -> str(S.desktop_card_no_top_up)
}

@Suppress("MagicNumber") // Column proportions.
private fun bulkColumns(
    state: CardUiState,
    rows: List<CardReceipt>,
    onEvent: (CardEvent) -> Unit,
): List<TableColumn<CardReceipt>> {
    val lock = state.processPages.refs.lock
    val viewer = state.viewer
    val refs = state.processPages.refs
    return listOf(
        TableColumn(
            header = "",
            width = ColumnWidth.Fixed(44.dp),
            headerContent = {
                val selectable = rows.filter { ProcessRules.bulkSelectable(viewer, it, lock) }.map { it.id }
                ZillitCheckbox(
                    checked = selectable.isNotEmpty() && state.selection.containsAll(selectable),
                    onCheckedChange = { onEvent(ProcessPageEvent.ToggleAllRows(rows.map { it.id })) },
                    enabled = selectable.isNotEmpty(),
                )
            },
        ) { row ->
            val canTick = ProcessRules.bulkSelectable(viewer, row, lock)
            val box = @Composable {
                ZillitCheckbox(
                    checked = row.id in state.selection,
                    onCheckedChange = { onEvent(ProcessPageEvent.ToggleRow(row.id)) },
                    enabled = canTick,
                )
            }
            val locked = lock.isLocked(ProcessRules.lockDate(row))
            if (locked) ZillitTooltip(str(S.desktop_inv_locked_period, lock.lockedThrough), box) else box()
        },
        TableColumn(str(S.date), ColumnWidth.Fixed(110.dp)) { DateCell(it) },
        TableColumn(str(S.ah_receipt_details), ColumnWidth.Weight(2.2f)) { ReceiptDetailsCell(state, it) },
        TableColumn(str(S.desktop_card_card_holder), ColumnWidth.Weight(1.2f)) {
            HolderCell(state, it.holderId, it.holderName)
        },
        TableColumn(str(S.code), ColumnWidth.Weight(1.2f)) { row ->
            CodeCell(
                row.nominalCode?.takeIf { it.isNotBlank() }?.let(refs::accountLabel),
                row.episode,
                viewer.isTelevision,
            )
        },
        TableColumn(str(S.amount), ColumnWidth.Fixed(120.dp), numeric = true) { AmountCell(it) },
    )
}

/** The web's fixed episode choices for a bulk override (`BulkProcessPage.jsx:434-440`). */
private val EPISODES = listOf("Ep 2", "Ep 3", "Series", "Prep")
private const val OVERLAY_ALPHA = 0.6f
private val FILTER_WIDTH = 200.dp
