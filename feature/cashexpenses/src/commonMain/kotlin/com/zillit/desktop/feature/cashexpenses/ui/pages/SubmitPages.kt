package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.money

/**
 * Submit Receipts — the crew member's form, for both pipelines.
 *
 * ## The settlement panel is the point
 *
 * As rows are typed the panel on the right recomputes what the float absorbs
 * and what comes back to the person — before they submit, not after an
 * accountant tells them. That is the whole reason `FloatSettlement` exists as
 * one computation: the four figures shown here are its four fields.
 *
 * Out-of-pocket has no float, so the panel becomes a plain total: those claims
 * are always reimbursed.
 */
@Composable
fun SubmitReceiptsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val outOfPocket = state.destination.expenseType == ExpenseType.OutOfPocket
    val activeFloat = state.submittableFloat

    if (!outOfPocket && activeFloat == null) {
        ScrollingPage {
            ZillitNotice(
                text = "You have no float to spend against, so there is nothing to submit receipts to.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
                action = {
                    ZillitButton(
                        text = "Request a float",
                        onClick = { onEvent(CashEvent.Open(CashDestination.FloatRequest)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            )
            ZillitText(
                text = "Paid for something yourself? Claim it under Out of Pocket instead.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        return
    }

    ScrollingPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(FORM_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                state.draft.receipts.forEachIndexed { index, receipt ->
                    ReceiptCard(
                        index = index,
                        receipt = receipt,
                        removable = state.draft.receipts.size > 1,
                        onChange = { onEvent(CashEvent.EditReceipt(index, it)) },
                        onRemove = { onEvent(CashEvent.RemoveReceipt(index)) },
                    )
                }
                ZillitButton(
                    text = "Add another receipt",
                    onClick = { onEvent(CashEvent.AddReceipt) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Add,
                )
            }

            SettlementPanel(state, outOfPocket, onEvent, modifier = Modifier.weight(PANEL_WEIGHT))
        }
    }
}

@Suppress("LongMethod") // One receipt form; splitting the fields hides the shape.
@Composable
private fun ReceiptCard(
    index: Int,
    receipt: DraftReceipt,
    removable: Boolean,
    onChange: (DraftReceipt) -> Unit,
    onRemove: () -> Unit,
) {
    ZillitSectionCard(
        title = "Receipt ${index + 1}",
        icon = ZillitIcons.Receipt,
        action = {
            if (removable) {
                ZillitButton(
                    text = "Remove",
                    onClick = onRemove,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        },
    ) {
        ZillitTextField(
            value = receipt.description,
            onValueChange = { onChange(receipt.copy(description = it)) },
            label = "What was bought",
            placeholder = "Gaffer tape and cable ties",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = receipt.supplier,
                onValueChange = { onChange(receipt.copy(supplier = it)) },
                label = "Supplier",
                placeholder = "Where it was bought",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = receipt.amount,
                onValueChange = { onChange(receipt.copy(amount = it)) },
                label = "Amount",
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = receipt.vat,
                onValueChange = { onChange(receipt.copy(vat = it)) },
                label = "VAT",
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = "Category",
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = ExpenseCategory.entries.firstOrNull { it.wire == receipt.category }
                        ?: ExpenseCategory.Other,
                    options = ExpenseCategory.entries,
                    onSelect = { onChange(receipt.copy(category = it.wire)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ZillitDateField(
                value = receipt.date?.let { EpochDate.isoDate(it) }.orEmpty(),
                onValueChange = { onChange(receipt.copy(date = parseIsoDate(it))) },
                label = "Date of purchase",
                modifier = Modifier.weight(1f),
            )
        }

        ZillitDivider()
        // An attachment is mandatory — a receipt with no evidence reaches an
        // accountant who can only query it back. Named here rather than
        // discovered on submit.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = receipt.attachmentName ?: "No attachment yet — required",
                style = ZillitTheme.typography.bodySmall,
                color = if (receipt.attachmentKey == null) {
                    ZillitTheme.colors.danger
                } else {
                    ZillitTheme.colors.textSecondary
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitTextField(
                value = receipt.attachmentKey.orEmpty(),
                onValueChange = {
                    onChange(
                        receipt.copy(
                            attachmentKey = it.takeIf(String::isNotBlank),
                            attachmentName = it.substringAfterLast('/').takeIf(String::isNotBlank),
                        ),
                    )
                },
                label = "Receipt file",
                placeholder = "Paste or drop the uploaded file reference",
                leadingIcon = ZillitIcons.Paperclip,
                modifier = Modifier.width(ATTACHMENT_FIELD_WIDTH),
            )
        }
    }
}

/**
 * What this batch will do to the float, live.
 *
 * Every figure comes from one [com.zillit.desktop.feature.cashexpenses.domain.FloatSettlement],
 * so "float consumed", "reimbursed to you" and "cash to return" always add up
 * — which they did not, on the web, before that computation was extracted.
 */
@Suppress("LongMethod") // The settlement figures and the submit control they gate.
@Composable
private fun SettlementPanel(
    state: CashUiState,
    outOfPocket: Boolean,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settlement = state.settlement
    val activeFloat = state.submittableFloat
    val currency = activeFloat?.currency

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitSectionCard(title = "This batch", icon = ZillitIcons.Ledger) {
            ZillitText(
                text = money(state.draft.total, currency),
                style = ZillitTheme.typography.displayLarge,
            )
            ZillitText(
                text = "${state.draft.receipts.size} receipt(s)",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )

            if (!outOfPocket && activeFloat != null) {
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                ZillitDivider()
                Spacer(Modifier.padding(ZillitTheme.spacing.xs))
                SettlementLine("Float headroom", money(settlement.headroom, currency))
                SettlementLine("Absorbed by the float", money(settlement.floatConsumed, currency))
                if (settlement.reimburses) {
                    SettlementLine(
                        label = "Reimbursed to you",
                        value = money(settlement.overdraft, currency),
                        tone = StatusTone.Ready,
                    )
                } else {
                    SettlementLine(
                        label = "Cash still to return",
                        value = money(settlement.returnAmount, currency),
                        tone = StatusTone.Neutral,
                    )
                }
            }
        }

        if (!outOfPocket && settlement.reimburses) {
            ZillitNotice(
                text = "This batch is over the float's headroom. " +
                    "${money(settlement.overdraft, currency)} will come back to you rather than reduce the float.",
                tone = StatusTone.Progress,
                icon = ZillitIcons.Info,
            )
        }

        ZillitSectionCard(title = "Anything else", icon = ZillitIcons.Info) {
            ZillitTextField(
                value = state.draft.notes,
                onValueChange = { onEvent(CashEvent.EditSubmitNotes(it)) },
                label = "Note for the accounts team (optional)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            ZillitButton(
                text = "Submit ${state.draft.receipts.size} receipt(s)",
                onClick = { onEvent(CashEvent.SubmitReceipts) },
                leadingIcon = ZillitIcons.Send,
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettlementLine(label: String, value: String, tone: StatusTone? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric,
            color = when (tone) {
                StatusTone.Ready -> ZillitTheme.colors.success
                else -> ZillitTheme.colors.textPrimary
            },
            maxLines = 1,
        )
    }
}

/** What the crew member has submitted, and where each batch got to. */
@Composable
fun ReceiptsHistoryPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val wanted = state.destination.expenseType
    val rows = state.myBatches.filter { it.expenseType == wanted }
    val needsAction = rows.filter { it.status.needsSubmitterAction }

    FixedPage {
        if (needsAction.isNotEmpty()) {
            ZillitNotice(
                text = "${needsAction.size} batch(es) came back to you — open one to see what needs correcting.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(
            title = "Your ${wanted.label.lowercase()} receipts",
            icon = ZillitIcons.Receipt,
            meta = "${rows.size} batch${if (rows.size == 1) "" else "es"}",
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = batchColumns(accountant = false),
                key = { it.id },
                loading = state.loading,
                onRowClick = { onEvent(CashEvent.SelectBatch(it.id)) },
                isSelected = { it.id == state.selectedBatchId },
                emptyTitle = "Nothing submitted yet",
                emptyMessage = "Batches you submit appear here with their progress through the workflow.",
            )
        }
    }
}

/**
 * Reads `YYYY-MM-DD` into epoch millis, or null.
 *
 * Typed rather than picked because the desktop has no date picker in this
 * design system yet, and a free-text field that silently accepts nonsense is
 * worse than one that simply does not fill the value in — the submit gate
 * then reports the missing date by name.
 */
@Suppress("ReturnCount") // One early return per malformed part; nesting them reads worse.
private fun parseIsoDate(text: String): Long? {
    val parts = text.trim().split('-')
    if (parts.size != ISO_PARTS) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return runCatching {
        LocalDate(year, month, day)
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
    }.getOrNull()
}

private const val ISO_PARTS = 3
private const val FORM_WEIGHT = 1.6f
private const val PANEL_WEIGHT = 1f
private val ATTACHMENT_FIELD_WIDTH = 320.dp
