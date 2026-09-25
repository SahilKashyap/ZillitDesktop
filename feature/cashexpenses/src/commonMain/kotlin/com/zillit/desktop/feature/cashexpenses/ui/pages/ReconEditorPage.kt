package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
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
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One period's count — the web's Cash Recon edit view, and its read-only view
 * once the period is signed off (`PCCashReconPage.jsx:678-1459`).
 *
 * The safe is counted note by note and coin by coin on the left; the
 * reconciling items and the reconciliation itself sit on the right. Save
 * keeps a draft; a non-senior submits it for review and a senior signs the
 * period off — both at once, as the web does. A signed-off period shows the
 * figures the server stored and its audit trail.
 */
@Composable
fun ReconEditorPage(state: CashUiState, draft: ReconDraft, onEvent: (CashEvent) -> Unit) {
    if (draft.isSignedOff) {
        SignedOffPeriod(state, draft, onEvent)
        return
    }
    fun edit(next: ReconDraft) = onEvent(CashEvent.EditReconciliation(next))

    ScrollingPage {
        EditHeader(state, draft, onEvent)
        TitledNotice(
            title = str(S.desktop_pc_cash_reconciliation),
            body = str(S.desktop_pc_recon_notice),
            icon = ZillitIcons.Calculator,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            FundsCard(modifier = Modifier.weight(1f)) {
                CountHeader(state.formatMoney(draft.physicalTotal, draft.currency), onReset = {
                    edit(draft.copy(denominations = draft.denominations.map { it.copy(count = "") }))
                })
                ZillitDivider()
                BookBalanceRow(draft, ::edit)
                ZillitDivider()
                DenominationTable(
                    state,
                    draft,
                    str(S.desktop_pc_banknotes),
                    draft.denominations.filter { it.isNote },
                    ::edit,
                )
                ZillitDivider(Modifier.padding(horizontal = 20.dp))
                DenominationTable(
                    state,
                    draft,
                    str(S.desktop_ce_coins),
                    draft.denominations.filterNot { it.isNote },
                    ::edit,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                ReconcilingItemsCard(state, draft, ::edit)
                SummaryCard(state, draft, ::edit)
            }
        }
    }
}

// -- the edit view ----------------------------------------------------------------

@Composable
private fun EditHeader(state: CashUiState, draft: ReconDraft, onEvent: (CashEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Crumb(title = monthLabel(draft.year to draft.month), onBack = { onEvent(CashEvent.CloseReconciliation) })
        if (draft.id != null) ReconStatusPill(draft.status)
        Box(Modifier.weight(1f))
        ZillitButton(
            text = str(S.cancel),
            onClick = { onEvent(CashEvent.CloseReconciliation) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = if (state.busy) str(S.ah_saving) else str(S.ah_save_draft),
            onClick = { onEvent(CashEvent.SaveReconciliation) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy && draft.id != null,
        )
        if (state.viewer.isSenior) {
            ZillitButton(
                text = if (state.busy) str(S.desktop_br_signing_off) else str(S.desktop_ce_sign_off_period),
                onClick = { onEvent(CashEvent.Funds(FundsAction.SignOffReconciliation)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Check,
                enabled = !state.busy && draft.id != null,
            )
        } else {
            ZillitButton(
                text = if (state.busy) str(S.ah_submitting) else "↑ ${str(S.desktop_submit_for_review)}",
                onClick = { onEvent(CashEvent.Funds(FundsAction.SubmitReconciliation)) },
                size = ButtonSize.Small,
                enabled = !state.busy && draft.id != null,
            )
        }
    }
}

/** Back, then `CASH RECON / Sep 2026`. */
@Composable
private fun Crumb(title: String, onBack: () -> Unit) {
    ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = str(S.desktop_ce_cash_recon), onClick = onBack)
    ZillitText(
        text = str(S.desktop_ce_cash_recon).uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.accent,
        modifier = Modifier.clickable(onClick = onBack),
    )
    ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
    ZillitText(
        text = title.ifBlank { str(S.desktop_pc_new_period) },
        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun CountHeader(total: String, onReset: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(S.desktop_pc_physical_cash_count),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = total,
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = ZillitTheme.colors.accent,
        )
        onReset?.let {
            ZillitButton(
                text = "↺ ${str(S.reset)}",
                onClick = it,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The book balance (the opening amount the server computes from) and the month, side by side. */
@Composable
private fun BookBalanceRow(draft: ReconDraft, edit: (ReconDraft) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Caption(str(S.desktop_ce_book_balance))
        ZillitTextField(
            value = draft.openingBalance,
            onValueChange = { edit(draft.copy(openingBalance = it)) },
            keyboardType = KeyboardType.Decimal,
            placeholder = "0.00",
            modifier = Modifier.width(BOOK_FIELD),
        )
        ZillitSelect(
            value = draft.year to draft.month,
            options = (listOf(draft.year to draft.month) + CashDates.recentMonths()).distinct(),
            onSelect = { (year, month) -> edit(draft.copy(year = year, month = month)) },
            label = { monthLabel(it) },
            modifier = Modifier.width(PERIOD_FIELD),
        )
    }
}

/**
 * Banknotes or Coins: Denomination, Qty, Subtotal. [edit] null reads only;
 * an empty group then shows nothing, as the signed-off view hides it.
 */
@Composable
private fun DenominationTable(
    state: CashUiState,
    draft: ReconDraft,
    title: String,
    rows: List<Denomination>,
    edit: ((ReconDraft) -> Unit)?,
) {
    if (rows.isEmpty() && edit == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Caption(title)
        TableHeader(
            str(S.desktop_pc_denomination) to null,
            str(S.ah_lbl_qty) to QTY_COLUMN,
            str(S.desktop_br_subtotal) to SUBTOTAL_COLUMN,
        )
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = denominationLabel(row, draft.currency ?: state.currencies.default),
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                if (edit != null) {
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
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(QTY_COLUMN),
                    )
                } else {
                    ZillitText(
                        text = row.count.ifBlank { "0" },
                        style = ZillitTheme.typography.numeric,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(QTY_COLUMN),
                    )
                }
                ZillitText(
                    text = state.formatMoney(row.total, draft.currency),
                    style = ZillitTheme.typography.numeric,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(SUBTOTAL_COLUMN),
                )
            }
        }
    }
}

@Composable
private fun TableHeader(vararg columns: Pair<String, androidx.compose.ui.unit.Dp?>) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.xxs)) {
        columns.forEachIndexed { index, (label, width) ->
            val modifier = if (width == null) Modifier.weight(1f) else Modifier.width(width)
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                textAlign = if (index == columns.lastIndex && width != null) TextAlign.End else TextAlign.Start,
                modifier = modifier,
            )
        }
    }
}

@Suppress("LongMethod") // Header, column heads, rows, total and footer of one card.
@Composable
private fun ReconcilingItemsCard(state: CashUiState, draft: ReconDraft, edit: (ReconDraft) -> Unit) {
    FundsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = str(S.desktop_ce_reconciling_items), style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = str(S.desktop_pc_reconciling_items_hint),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitButton(
                text = str(S.txt_add_item),
                onClick = { edit(draft.copy(items = draft.items + ReconItem())) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        ZillitDivider()
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TableHeader(
                str(S.description) to null,
                str(S.desktop_ref) to REF_WIDTH,
                str(S.type) to TYPE_WIDTH,
                str(S.amount) to AMOUNT_WIDTH,
                "" to REMOVE_WIDTH,
            )
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
                    onChange = { next ->
                        edit(draft.copy(items = draft.items.mapIndexed { i, old -> if (i == index) next else old }))
                    },
                    onRemove = { edit(draft.copy(items = draft.items.filterIndexed { i, _ -> i != index })) },
                )
            }
            ZillitDivider()
            SummaryLine(
                label = str(S.desktop_pc_total_adjustment),
                value = signed(state, draft.adjustment, draft.currency),
                strong = true,
            )
        }
        AdjustedBookFooter(state.formatMoney(draft.adjustedBook, draft.currency))
    }
}

@Composable
private fun ReconItemRow(item: ReconItem, onChange: (ReconItem) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = item.description,
            onValueChange = { onChange(item.copy(description = it)) },
            placeholder = str(S.description),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = item.reference,
            onValueChange = { onChange(item.copy(reference = it)) },
            placeholder = str(S.desktop_ref),
            modifier = Modifier.width(REF_WIDTH),
        )
        ZillitSelect(
            value = item.type,
            options = ReconItem.TYPES,
            onSelect = { onChange(item.copy(type = it)) },
            label = ::itemTypeLabel,
            modifier = Modifier.width(TYPE_WIDTH),
        )
        ZillitTextField(
            value = item.amount,
            onValueChange = { onChange(item.copy(amount = it)) },
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_ce_remove_item),
            onClick = onRemove,
            tint = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

@Composable
private fun AdjustedBookFooter(value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.warningSoft)
            .padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_pc_adjusted_book_balance),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = ZillitTheme.colors.warning,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.accent,
        )
    }
}

/** Book, items, adjusted book, count, and the variance they leave; the sign-off notes below. */
@Composable
private fun SummaryCard(state: CashUiState, draft: ReconDraft, edit: (ReconDraft) -> Unit) {
    FundsCard(modifier = Modifier.fillMaxWidth()) {
        ZillitText(
            text = str(S.desktop_card_reconciliation),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.lg),
        )
        ZillitDivider()
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md)) {
            if (draft.computingBook) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitIcon(ZillitIcons.Reload, tint = ZillitTheme.colors.textMuted, size = 14.dp)
                    ZillitText(
                        text = str(S.desktop_pc_computing_book),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            SummaryLines(
                state = state,
                currency = draft.currency,
                book = draft.bookBalance,
                adjustment = draft.adjustment,
                adjusted = draft.adjustedBook,
                physical = draft.physicalTotal,
            )
            VarianceCard(state, draft.variance, draft.currency)
            Column(
                modifier = Modifier.padding(top = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Caption(str(S.desktop_pc_sign_off_notes))
                ZillitTextField(
                    value = draft.notes,
                    onValueChange = { edit(draft.copy(notes = it)) },
                    placeholder = str(S.desktop_pc_recon_notes_hint),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SummaryLines(
    state: CashUiState,
    currency: String?,
    book: Double,
    adjustment: Double,
    adjusted: Double,
    physical: Double,
) {
    SummaryLine(str(S.desktop_ce_book_balance), state.formatMoney(book, currency))
    ZillitDivider()
    SummaryLine(
        str(S.desktop_pc_reconciling_items_net),
        signed(state, adjustment, currency),
        valueColor = ZillitTheme.colors.textMuted,
    )
    ZillitDivider()
    SummaryLine(
        str(S.desktop_pc_adjusted_book_balance),
        state.formatMoney(adjusted, currency),
        strong = true,
        valueColor = ZillitTheme.colors.accent,
        modifier = Modifier.background(ZillitTheme.colors.warningSoft.copy(alpha = ACCENT_WASH)),
    )
    ZillitDivider()
    SummaryLine(str(S.desktop_pc_physical_count), state.formatMoney(physical, currency), strong = true)
}

/** "Balanced", or "Variance — shortfall / overage" with the signed figure (`PCCashReconPage.jsx:1428-1438`). */
@Composable
private fun VarianceCard(state: CashUiState, variance: Double, currency: String?) {
    val colors = ZillitTheme.colors
    val balanced = abs(variance) < PENNY
    val (wash, ink) = when {
        balanced -> colors.successSoft to colors.success
        variance > 0 -> colors.warningSoft to colors.warning
        else -> colors.dangerSoft to colors.danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.large)
            .background(wash)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(if (balanced) ZillitIcons.Check else ZillitIcons.Warning, tint = ink)
        ZillitText(
            text = when {
                balanced -> str(S.desktop_card_balanced)
                variance < 0 -> str(S.desktop_pc_variance_shortfall)
                else -> str(S.desktop_pc_variance_overage)
            },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = if (balanced) state.formatMoney(0.0, currency) else signed(state, variance, currency),
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ink,
        )
    }
}

// -- the signed-off view ----------------------------------------------------------

/** A signed-off period: the stored count, items and figures, the notes and who did what. */
@Suppress("LongMethod") // The stored count, items, figures and trail of one period, as the web lays them out.
@Composable
private fun SignedOffPeriod(state: CashUiState, draft: ReconDraft, onEvent: (CashEvent) -> Unit) {
    val saved = draft.saved
    val book = saved?.bookBalance ?: draft.bookBalance
    val physical = saved?.countedBalance ?: draft.physicalTotal
    val variance = saved?.variance ?: draft.variance
    val adjusted = round2(book + draft.adjustment)
    val period = monthLabel(draft.year to draft.month)

    ScrollingPage {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Crumb(title = period, onBack = { onEvent(CashEvent.CloseReconciliation) })
            ReconStatusPill(draft.status)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            FundsCard(modifier = Modifier.weight(1f)) {
                CountHeader(state.formatMoney(physical, draft.currency), onReset = null)
                ZillitDivider()
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    Caption(str(S.desktop_ce_opening_balance))
                    ZillitText(
                        text = state.formatMoney(saved?.openingBalance ?: draft.opening, draft.currency),
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    )
                    ZillitText(
                        text = "· $period",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                ZillitDivider()
                DenominationTable(
                    state,
                    draft,
                    str(S.desktop_pc_banknotes),
                    draft.denominations.filter { it.isNote },
                    null,
                )
                DenominationTable(
                    state,
                    draft,
                    str(S.desktop_ce_coins),
                    draft.denominations.filterNot { it.isNote },
                    null,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                if (draft.items.isNotEmpty()) ReadOnlyItems(state, draft, adjusted)
                FundsCard(modifier = Modifier.fillMaxWidth()) {
                    ZillitText(
                        text = str(S.desktop_card_reconciliation),
                        style = ZillitTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
                    )
                    ZillitDivider()
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md)) {
                        SummaryLines(state, draft.currency, book, draft.adjustment, adjusted, physical)
                        ZillitDivider()
                        SummaryLine(
                            str(S.desktop_variance),
                            varianceText(state, variance, draft.currency),
                            strong = true,
                            valueColor = varianceColor(variance),
                        )
                        draft.notes.takeIf(String::isNotBlank)?.let {
                            Column(
                                modifier = Modifier.padding(top = ZillitTheme.spacing.md),
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                            ) {
                                Caption(str(S.desktop_pc_sign_off_notes))
                                ZillitText(text = it, style = ZillitTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                AuditTrail(draft)
            }
        }
    }
}

@Composable
private fun ReadOnlyItems(state: CashUiState, draft: ReconDraft, adjusted: Double) {
    FundsCard(modifier = Modifier.fillMaxWidth()) {
        ZillitText(
            text = str(S.desktop_ce_reconciling_items),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        )
        ZillitDivider()
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            TableHeader(
                str(S.description) to null,
                str(S.desktop_ref) to REF_WIDTH,
                str(S.type) to TYPE_WIDTH,
                str(S.amount) to AMOUNT_WIDTH,
            )
            draft.items.forEach { item ->
                Row {
                    ZillitText(
                        text = item.description.ifBlank { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = item.reference.ifBlank { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.width(REF_WIDTH),
                    )
                    ZillitText(
                        text = itemTypeLabel(item.type),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.width(TYPE_WIDTH),
                    )
                    ZillitText(
                        text = state.formatMoney(item.amount.trim().toDoubleOrNull() ?: 0.0, draft.currency),
                        style = ZillitTheme.typography.numeric,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(AMOUNT_WIDTH),
                    )
                }
            }
        }
        AdjustedBookFooter(state.formatMoney(adjusted, draft.currency))
    }
}

/**
 * Created, last updated, submitted and signed — each shown only when someone
 * did it (`PCCashReconPage.jsx:1001-1046`).
 */
@Composable
private fun AuditTrail(draft: ReconDraft) {
    val saved = draft.saved ?: return
    val people = LocalCashPeople.current
    val entries = listOf(
        Triple(str(S.cs_created_by), saved.createdBy, saved.createdAt),
        Triple(str(S.desktop_po_last_updated_by), saved.updatedBy, saved.updatedAt),
        Triple(str(S.desktop_pc_submitted_for_review_by), saved.submittedBy, saved.submittedAt),
        Triple(str(S.desktop_pc_signed_off_by), saved.signedBy, saved.signedAt),
    ).filter { !it.second.isNullOrBlank() }
    FundsCard(modifier = Modifier.fillMaxWidth()) {
        ZillitText(
            text = str(S.docusign_audit_trail),
            style = ZillitTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.md),
        )
        ZillitDivider()
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            entries.forEach { (label, userId, at) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Caption(label)
                        ZillitText(
                            text = people.nameOf(userId),
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    ZillitText(
                        text = EpochDate.dateTime(at).ifEmpty { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

// -- labels -----------------------------------------------------------------------

/** `£50 note`, `£2`, `50p` — the web's denomination labels. */
internal fun denominationLabel(row: Denomination, currency: String): String {
    val symbol = Money.symbol(currency)
    val value = row.value.toDoubleOrNull() ?: 0.0
    return when {
        row.isNote -> str(S.desktop_pc_denomination_note, "$symbol${row.value}")
        value >= 1 -> "$symbol${row.value}"
        else -> "${(value * PENCE).roundToInt()}p"
    }
}

private fun itemTypeLabel(type: String): String = when (type) {
    ReconItem.IN -> str(S.received_text)
    ReconItem.TIMING -> str(S.desktop_ce_timing)
    else -> str(S.desktop_ce_paid_out)
}

private fun signed(state: CashUiState, value: Double, currency: String?): String =
    if (value >= 0) "+${state.formatMoney(value, currency)}" else state.formatMoney(value, currency)

private fun round2(value: Double): Double = kotlin.math.round(value * PENCE) / PENCE

private const val PENCE = 100.0
private const val ACCENT_WASH = 0.6f
private val BOOK_FIELD = 140.dp
private val PERIOD_FIELD = 140.dp
private val QTY_COLUMN = 90.dp
private val SUBTOTAL_COLUMN = 110.dp
private val REF_WIDTH = 80.dp
private val TYPE_WIDTH = 120.dp
private val AMOUNT_WIDTH = 100.dp
private val REMOVE_WIDTH = 32.dp
