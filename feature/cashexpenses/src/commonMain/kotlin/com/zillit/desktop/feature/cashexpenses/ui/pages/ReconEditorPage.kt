package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.Denomination
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.ReconItem
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.money
import kotlin.math.abs

/**
 * One period's count — the web's Cash Recon edit view.
 *
 * The safe is counted note by note and coin by coin; the reconciling items
 * explain what the ledger has that the safe does not; the variance is what
 * neither accounts for. Save keeps a draft; a non-senior submits it for review
 * and a senior signs the period off. A signed-off period reads only.
 */
@Suppress("LongMethod") // Figures, the count, the items and the actions — one page.
@Composable
fun ReconEditorPage(state: CashUiState, draft: ReconDraft, onEvent: (CashEvent) -> Unit) {
    val editable = !draft.isSignedOff
    fun edit(next: ReconDraft) = onEvent(CashEvent.EditReconciliation(next))

    ScrollingPage {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitButton(
                text = str(S.desktop_ce_reconciliations),
                onClick = { onEvent(CashEvent.CloseReconciliation) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(
                text = monthLabel(draft.year to draft.month),
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ReconStatusPill(draft.status)
            if (editable) ReconActions(state, draft, onEvent)
        }

        StatRow(
            listOf(
                StatTileSpec(
                    label = str(S.desktop_ce_book_balance),
                    value = money(draft.bookBalance, draft.currency),
                    icon = ZillitIcons.Ledger,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_adjusted_book),
                    value = money(draft.adjustedBook, draft.currency),
                    icon = ZillitIcons.Calculator,
                ),
                StatTileSpec(
                    label = str(S.desktop_ce_physical_cash),
                    value = money(draft.physicalTotal, draft.currency),
                    icon = ZillitIcons.Wallet,
                ),
                StatTileSpec(
                    label = str(S.desktop_variance),
                    value = money(draft.variance, draft.currency),
                    tone = if (abs(draft.variance) < PENNY) StatusTone.Done else StatusTone.Rejected,
                    icon = ZillitIcons.Warning,
                ),
            ),
        )

        ZillitSectionCard(title = str(S.desktop_ce_period_and_opening), icon = ZillitIcons.Calendar) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitTextField(
                    value = draft.openingBalance,
                    onValueChange = { edit(draft.copy(openingBalance = it)) },
                    label = str(S.desktop_ce_opening_balance),
                    keyboardType = KeyboardType.Decimal,
                    enabled = editable,
                    modifier = Modifier.width(FIELD_WIDTH),
                )
                Column {
                    ZillitText(
                        text = str(S.cr_meta_period),
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitSelect(
                        value = draft.year to draft.month,
                        options = (listOf(draft.year to draft.month) + CashDates.recentMonths()).distinct(),
                        onSelect = { (year, month) -> edit(draft.copy(year = year, month = month)) },
                        label = { monthLabel(it) },
                        enabled = editable,
                        modifier = Modifier.width(FIELD_WIDTH),
                    )
                }
            }
        }

        ZillitSectionCard(
            title = str(S.desktop_ce_cash_count),
            icon = ZillitIcons.Wallet,
            meta = money(draft.physicalTotal, draft.currency),
        ) {
            Denominations(str(S.notes), draft, draft.denominations.filter { it.isNote }, editable, ::edit)
            ZillitDivider()
            Denominations(str(S.desktop_ce_coins), draft, draft.denominations.filterNot { it.isNote }, editable, ::edit)
        }

        ZillitSectionCard(
            title = str(S.desktop_ce_reconciling_items),
            icon = ZillitIcons.Ledger,
            meta = money(draft.adjustment, draft.currency),
            action = if (editable) {
                {
                    ZillitButton(
                        text = str(S.txt_add_item),
                        onClick = { edit(draft.copy(items = draft.items + ReconItem())) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            } else {
                null
            },
        ) {
            if (draft.items.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ce_no_reconciling_items),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            draft.items.forEachIndexed { index, item ->
                ReconItemRow(
                    item = item,
                    editable = editable,
                    onChange = { next ->
                        edit(draft.copy(items = draft.items.mapIndexed { i, old -> if (i == index) next else old }))
                    },
                    onRemove = { edit(draft.copy(items = draft.items.filterIndexed { i, _ -> i != index })) },
                )
            }
        }

        ZillitSectionCard(title = str(S.notes), icon = ZillitIcons.Edit) {
            ZillitTextField(
                value = draft.notes,
                onValueChange = { edit(draft.copy(notes = it)) },
                singleLine = false,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Save Draft, then Submit for Review (non-senior) or Sign Off Period (senior). */
@Composable
private fun ReconActions(state: CashUiState, draft: ReconDraft, onEvent: (CashEvent) -> Unit) {
    ZillitButton(
        text = str(S.ah_save_draft),
        onClick = { onEvent(CashEvent.SaveReconciliation) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = !state.busy && draft.id != null,
    )
    if (state.viewer.isSenior) {
        ZillitButton(
            text = str(S.desktop_ce_sign_off_period),
            onClick = {
                onEvent(
                    CashEvent.Ask(
                        CashPrompt.Confirm(
                            ConfirmAction.SignOffReconciliation,
                            draft.id.orEmpty(),
                            str(S.desktop_ce_sign_off_reconciliation),
                            str(S.desktop_ce_sign_off_note),
                        ),
                    ),
                )
            },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
            enabled = !state.busy && draft.id != null,
        )
    } else {
        ZillitButton(
            text = str(S.desktop_submit_for_review),
            onClick = {
                onEvent(
                    CashEvent.Ask(
                        CashPrompt.Confirm(
                            ConfirmAction.SubmitReconciliation,
                            draft.id.orEmpty(),
                            str(S.desktop_submit_for_review),
                            str(S.desktop_ce_submit_recon_note),
                        ),
                    ),
                )
            },
            size = ButtonSize.Small,
            enabled = !state.busy && draft.id != null && draft.status != ReconDraft.UNDER_REVIEW,
        )
    }
}

@Composable
private fun Denominations(
    title: String,
    draft: ReconDraft,
    rows: List<Denomination>,
    editable: Boolean,
    edit: (ReconDraft) -> Unit,
) {
    ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
    rows.chunked(PER_ROW).forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            line.forEach { row ->
                ZillitTextField(
                    value = row.count,
                    onValueChange = { typed ->
                        val count = typed.filter(Char::isDigit)
                        edit(
                            draft.copy(
                                denominations = draft.denominations.map { cell ->
                                    if (cell.id == row.id) cell.copy(count = count) else cell
                                },
                            ),
                        )
                    },
                    label = money(row.value.toDoubleOrNull() ?: 0.0, draft.currency),
                    helperText = money(row.total, draft.currency),
                    keyboardType = KeyboardType.Number,
                    enabled = editable,
                    modifier = Modifier.width(COUNT_WIDTH),
                )
            }
        }
    }
}

@Composable
private fun ReconItemRow(item: ReconItem, editable: Boolean, onChange: (ReconItem) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = item.description,
            onValueChange = { onChange(item.copy(description = it)) },
            placeholder = str(S.desktop_ce_item_description),
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = item.reference,
            onValueChange = { onChange(item.copy(reference = it)) },
            placeholder = str(S.desktop_reference),
            enabled = editable,
            modifier = Modifier.width(REF_WIDTH),
        )
        ZillitSelect(
            value = item.type,
            options = ReconItem.TYPES,
            onSelect = { onChange(item.copy(type = it)) },
            label = { type ->
                when (type) {
                    ReconItem.IN -> str(S.received_text)
                    ReconItem.TIMING -> str(S.desktop_ce_timing)
                    else -> str(S.desktop_ce_paid_out)
                }
            },
            enabled = editable,
            modifier = Modifier.width(TYPE_WIDTH),
        )
        ZillitTextField(
            value = item.amount,
            onValueChange = { onChange(item.copy(amount = it)) },
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            enabled = editable,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_ce_remove_item),
                onClick = onRemove,
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

private const val PER_ROW = 4
private val FIELD_WIDTH = 220.dp
private val COUNT_WIDTH = 150.dp
private val REF_WIDTH = 140.dp
private val TYPE_WIDTH = 150.dp
private val AMOUNT_WIDTH = 130.dp
