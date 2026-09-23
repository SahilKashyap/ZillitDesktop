package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import com.zillit.desktop.feature.cardexpenses.ui.AssignDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.ProcessDraft
import com.zillit.desktop.feature.cardexpenses.ui.ProcessMode
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * The accountant's process editor — the web's `ProcessReceiptModal`.
 *
 * Top to bottom as the web reads: the receipt's facts, the three header
 * corrections (vendor, cost code, the ledger date posting needs), the rule
 * banners, the coded lines with their running totals against the receipt,
 * and the top-up decision when the holder asked for one. The buttons are the
 * web's header row; which of them show is [ProcessRules]' decision, and the
 * view model checks the same rules again on the way in.
 */
@Suppress("LongMethod") // One editor, read top to bottom; the order is the web's.
@Composable
fun ProcessEditorDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.process ?: return
    val receipt = draft.receipt
    val processing = receipt.processing
    val figures = draft.figures
    val viewer = state.viewer
    val history = draft.mode == ProcessMode.History
    val idle = !draft.loading && !state.busy

    ZillitDialogShell(
        title = receipt.description.ifBlank { receipt.merchant ?: str(S.desktop_receipt) },
        subtitle = listOf(
            if (history) str(S.history) else str(S.ah_process),
            state.personName(receipt.holderId, receipt.holderName),
        ).joinToString(" · "),
        icon = ZillitIcons.Ledger,
        visible = true,
        width = EDITOR_WIDTH,
        maxHeight = EDITOR_HEIGHT,
        onDismiss = { onEvent(CardEvent.CloseProcess) },
        actions = { ProcessActions(state, draft, onEvent) },
    ) {
        ReceiptFacts(state, draft)

        if (draft.loading) {
            Row(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.lg), Arrangement.Center) {
                ZillitSpinner()
            }
            return@ZillitDialogShell
        }

        HeaderFields(draft, onEvent)
        RuleBanners(state, draft)

        ZillitDivider()
        FieldGroupLabel(str(S.ah_line_items))
        LineHeader()
        draft.lines.forEachIndexed { index, line ->
            LineRow(
                line = line,
                currency = receipt.currency,
                removable = draft.lines.size > 1,
                onChange = { onEvent(CardEvent.EditProcessLine(index, it)) },
                onRemove = { onEvent(CardEvent.RemoveProcessLine(index)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitButton(
                text = str(S.desktop_po_add_line),
                onClick = { onEvent(CardEvent.AddProcessLine) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = idle,
            )
            Spacer(Modifier.weight(1f))
            Totals(figures.net, figures.tax, figures.gross, receipt.amount, receipt.currency, figures.mismatch)
        }

        if (!history && processing.requestTopUp) {
            ZillitDivider()
            TopUpDecision(draft, onEvent)
        }
        val flagged = processing.needsQuery || processing.needsReview
        if (!viewer.isSenior && !history && flagged) {
            ZillitText(
                text = str(S.desktop_card_post_needs_senior),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }

    AssignDialog(state, draft, onEvent)
    EscalateDialog(state, draft, onEvent)
}

/** Amount, date, holder and card — the facts the coding is checked against. */
@Composable
private fun ReceiptFacts(state: CardUiState, draft: ProcessDraft) {
    val receipt = draft.receipt
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Fact(str(S.amount), money(receipt.amount, receipt.currency), Modifier.weight(1f))
        Fact(str(S.date), date(receipt.date), Modifier.weight(1f))
        Fact(
            str(S.desktop_card_card_holder),
            state.personName(receipt.holderId, receipt.holderName),
            Modifier.weight(1f),
        )
        Fact(
            str(S.ah_my_cards),
            (receipt.cardLastFour ?: receipt.transactionCardLastFour)?.let { "•••• $it" } ?: "—",
            Modifier.weight(1f),
        )
        StatusBadge(receipt.status, receipt.urgent)
    }
}

@Composable
private fun StatusBadge(status: CardWorkflowStatus, urgent: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs), horizontalAlignment = Alignment.End) {
        ZillitStatusPill(label = status.label, tone = StatusTone.Ready, dot = true)
        if (urgent) ZillitStatusPill(label = str(S.ah_topup_filter_urgent), tone = StatusTone.Escalated, dot = true)
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.titleSmall, maxLines = 1)
    }
}

/** Vendor, cost code and ledger date: intake values an accountant may correct before posting. */
@Composable
private fun HeaderFields(draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitTextField(
            value = draft.description,
            onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(description = it))) },
            label = str(S.ah_lbl_vendor),
            placeholder = str(S.cash_receipt_vendor_hint),
            modifier = Modifier.weight(2f),
        )
        ZillitTextField(
            value = draft.nominalCode,
            onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(nominalCode = it))) },
            label = str(S.ah_cost_code_label),
            placeholder = "4100",
            modifier = Modifier.weight(1f),
        )
        ZillitDateField(
            value = draft.effectiveDate,
            onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(effectiveDate = it))) },
            label = str(S.ah_lbl_eff_date),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The receipt's standing: escalated (and why), under review, the rules it
 * tripped, and whether the lines add up to it.
 */
@Composable
private fun RuleBanners(state: CardUiState, draft: ProcessDraft) {
    val receipt = draft.receipt
    val processing = receipt.processing
    val figures = draft.figures
    if (draft.mode == ProcessMode.Process && receipt.status.wire == ESCALATED) {
        ZillitNotice(
            text = listOfNotNull(
                str(S.ah_escalated_to_senior_toast),
                processing.escalationReason?.let { "“$it”" },
            ).joinToString(" "),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )
    }
    if (draft.mode == ProcessMode.Process && receipt.status.wire == UNDER_REVIEW) {
        ZillitNotice(text = str(S.ah_under_review), tone = StatusTone.Progress, icon = ZillitIcons.Info)
    }
    processing.flags.mapNotNull { it.banner() }.forEach { (text, tone) ->
        ZillitNotice(text = text, tone = tone, icon = ZillitIcons.Info)
    }
    if (figures.mismatch) {
        val total = money(figures.gross, receipt.currency)
        val amount = money(receipt.amount, receipt.currency)
        ZillitNotice(
            text = if (figures.gross < receipt.amount) {
                str(S.desktop_card_lines_lower_note, total, amount)
            } else {
                str(S.desktop_card_lines_mismatch_note, total, amount)
            },
            tone = if (figures.gross < receipt.amount) StatusTone.Pending else StatusTone.Rejected,
            icon = ZillitIcons.Warning,
        )
    }
    if (state.viewer.metadata.postingLimit?.let { it < figures.effectiveAmount } == true) {
        ZillitNotice(text = str(S.desktop_card_above_posting_limit), tone = StatusTone.Pending, icon = ZillitIcons.Info)
    }
}

private fun String.banner(): Pair<String, StatusTone>? = when (this) {
    ReceiptProcessing.QUERY -> str(S.desktop_card_flag_query) to StatusTone.Rejected
    ReceiptProcessing.REVIEW -> str(S.desktop_card_flag_review) to StatusTone.Progress
    DEDUCT -> str(S.desktop_card_flag_deduct) to StatusTone.Pending
    else -> null
}

@Composable
private fun LineHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        HeaderCell(str(S.description), Modifier.weight(DESCRIPTION_WEIGHT))
        HeaderCell(str(S.ah_account_label), Modifier.weight(1f))
        HeaderCell(str(S.desktop_net), Modifier.weight(1f))
        HeaderCell(str(S.ah_lbl_tax_rate), Modifier.width(RATE_WIDTH))
        HeaderCell(str(S.desktop_gross), Modifier.width(GROSS_WIDTH))
        Spacer(Modifier.width(REMOVE_WIDTH))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

/** One coded line: what it covers, where it goes, net, rate — and the gross that follows. */
@Composable
private fun LineRow(
    line: ProcessLine,
    currency: String?,
    removable: Boolean,
    onChange: (ProcessLine) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = line.description,
            onValueChange = { onChange(line.copy(description = it)) },
            modifier = Modifier.weight(DESCRIPTION_WEIGHT),
        )
        ZillitTextField(
            value = line.account,
            onValueChange = { onChange(line.copy(account = it)) },
            placeholder = "4100",
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = line.net,
            onValue = { onChange(line.copy(net = it ?: 0.0)) },
            modifier = Modifier.weight(1f),
        )
        NumberField(
            value = line.taxRate,
            onValue = { onChange(line.copy(taxRate = it?.takeIf { rate -> rate != 0.0 })) },
            placeholder = "0",
            modifier = Modifier.width(RATE_WIDTH),
        )
        ZillitText(
            text = money(line.gross, currency),
            style = ZillitTheme.typography.numeric,
            maxLines = 1,
            modifier = Modifier.width(GROSS_WIDTH),
        )
        if (removable) {
            ZillitButton(
                text = "",
                onClick = onRemove,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
                modifier = Modifier.width(REMOVE_WIDTH),
            )
        } else {
            Spacer(Modifier.width(REMOVE_WIDTH))
        }
    }
}

/**
 * A figure typed as text.
 *
 * The text is the field's own: parsing on every keystroke and writing the
 * number back would turn "12." into "12.0" under the cursor. The figure
 * outside only re-seeds the text when it changes to something the text does
 * not already say.
 */
@Composable
private fun NumberField(
    value: Double?,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
) {
    var text by remember { mutableStateOf(value.asField()) }
    LaunchedEffect(value) {
        if (text.trim().toDoubleOrNull() != value) text = value.asField()
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            val kept = typed.filter { it.isDigit() || it == '.' }
            text = kept
            onValue(kept.toDoubleOrNull())
        },
        placeholder = placeholder,
        keyboardType = KeyboardType.Decimal,
        modifier = modifier,
    )
}

private fun Double?.asField(): String = when {
    this == null || this == 0.0 -> ""
    this == kotlin.math.floor(this) -> toLong().toString()
    else -> toString()
}

@Composable
private fun Totals(net: Double, tax: Double, gross: Double, receipt: Double, currency: String?, mismatch: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Bottom) {
        Fact(str(S.desktop_net), money(net, currency))
        Fact(str(S.desktop_vat), money(tax, currency))
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldGroupLabel(str(S.desktop_gross))
            ZillitText(
                text = "${money(gross, currency)} / ${money(receipt, currency)}",
                style = ZillitTheme.typography.titleSmall,
                color = if (mismatch) ZillitTheme.colors.danger else ZillitTheme.colors.success,
            )
        }
    }
}

/**
 * Restore the card to its limit, top up by what was spent, or leave it: the
 * three the web offers, each with the balance it leaves and the task it raises.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopUpDecision(draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val figures = draft.figures
    val receipt = draft.receipt
    val currency = receipt.currency
    val processing = receipt.processing
    val limit = processing.cardLimit ?: 0.0
    val balance = processing.cardBalance ?: limit
    FieldGroupLabel(str(S.desktop_card_top_up_decision))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        Fact(str(S.desktop_card_card_limit), money(limit, currency), Modifier.weight(1f))
        Fact(str(S.desktop_card_card_balance), money(balance, currency), Modifier.weight(1f))
        Fact(str(S.desktop_card_this_expense), "−${money(figures.effectiveAmount, currency)}", Modifier.weight(1f))
        Fact(str(S.desktop_card_balance_after), money(figures.balanceAfter, currency), Modifier.weight(1f))
    }
    FieldGroupLabel(str(S.desktop_card_top_up_method))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        TopUpMethod.entries.forEach { method ->
            val after = when (method) {
                TopUpMethod.Restore -> limit
                TopUpMethod.Expense -> balance
                TopUpMethod.None -> figures.balanceAfter
            }
            val up = figures.topUpAmount(method)
            TopUpOption(
                label = method.label,
                balance = money(after, currency),
                note = if (method == TopUpMethod.None) {
                    str(S.desktop_card_no_balance_change)
                } else {
                    str(S.desktop_card_top_up_task, money(up, currency))
                },
                selected = draft.topUp == method,
                onClick = { onEvent(CardEvent.EditProcess(draft.copy(topUp = method))) },
            )
        }
    }
}

@Composable
private fun TopUpOption(label: String, balance: String, note: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(OPTION_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(OPTION_BORDER, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = if (selected) colors.accentText else colors.textSecondary,
        )
        ZillitText(text = balance, style = ZillitTheme.typography.titleMedium)
        ZillitText(text = note, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
    }
}

/**
 * The web's header buttons, as this viewer may use them.
 *
 * History corrects a posted receipt: Save only. The queue: Save, Assign or
 * Reassign, and — for a non-senior — Escalate and Submit for Review; Post
 * wherever [ProcessRules.canPost] allows it.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ProcessActions(
    state: CardUiState,
    draft: ProcessDraft,
    onEvent: (CardEvent) -> Unit,
) {
    val viewer = state.viewer
    val figures = draft.figures
    val idle = !draft.loading && !state.busy
    val queue = draft.mode == ProcessMode.Process
    ZillitButton(
        text = str(S.close),
        onClick = { onEvent(CardEvent.CloseProcess) },
        variant = ButtonVariant.Tertiary,
    )
    Spacer(Modifier.weight(1f))
    if (queue) {
        // Red while a query rule is on the receipt, as the web pulses it.
        ZillitButton(
            text = str(S.ah_query_label),
            onClick = { onEvent(CardEvent.OpenQuery(draft.receipt.id)) },
            variant = if (draft.receipt.processing.needsQuery) ButtonVariant.Danger else ButtonVariant.Tertiary,
            enabled = !draft.loading,
        )
    }
    ZillitButton(
        text = str(S.save),
        onClick = { onEvent(CardEvent.SaveProcess) },
        variant = ButtonVariant.Secondary,
        enabled = idle,
    )
    if (!queue) return
    if (viewer.isAccountant) {
        ZillitButton(
            text = if (draft.receipt.assignedTo.isNullOrBlank()) str(S.assign) else str(S.desktop_po_reassign),
            onClick = { onEvent(CardEvent.EditProcess(draft.copy(assign = AssignDraft()))) },
            variant = ButtonVariant.Secondary,
            enabled = idle,
        )
    }
    if (ProcessRules.canHandUp(viewer)) {
        ZillitButton(
            text = str(S.desktop_ce_escalate),
            onClick = { onEvent(CardEvent.EditProcess(draft.copy(escalation = ""))) },
            variant = ButtonVariant.Secondary,
            enabled = idle,
        )
        ZillitButton(
            text = str(S.desktop_submit_for_review),
            onClick = { onEvent(CardEvent.SubmitProcessForReview) },
            variant = ButtonVariant.Secondary,
            enabled = idle,
        )
    }
    if (ProcessRules.canPost(viewer, draft.receipt.processing, figures.effectiveAmount)) {
        val topUp = draft.receipt.processing.requestTopUp && draft.topUp != TopUpMethod.None
        ZillitButton(
            text = if (topUp) str(S.desktop_card_post_and_top_up) else str(S.ah_post_to_ledger),
            onClick = { onEvent(CardEvent.PostProcess) },
            leadingIcon = ZillitIcons.Ledger,
            enabled = idle,
            loading = state.busy,
        )
    }
}

/** Assigning or reassigning, over the editor. The accounts team, never the current assignee. */
@Suppress("LongMethod") // One small form; splitting it hides the order.
@Composable
private fun AssignDialog(state: CardUiState, draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val choice = draft.assign ?: return
    val receipt = draft.receipt
    val reassign = !receipt.assignedTo.isNullOrBlank()
    val team = state.people.filter { it.isAccountsTeam && it.id != receipt.assignedTo }
    ZillitDialogShell(
        title = if (reassign) str(S.desktop_card_reassign_receipt) else str(S.desktop_card_assign_receipt),
        icon = ZillitIcons.Users,
        visible = true,
        width = SMALL_DIALOG,
        onDismiss = { onEvent(CardEvent.EditProcess(draft.copy(assign = null))) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (reassign) str(S.desktop_po_reassign) else str(S.assign),
                onClick = { onEvent(CardEvent.ConfirmAssign) },
                enabled = choice.complete && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        receipt.assignedTo?.takeIf { it.isNotBlank() }?.let { current ->
            val name = if (current == state.viewer.userId) str(S.txt_me) else state.personName(current)
            ZillitNotice(text = str(S.desktop_card_currently_assigned_to, name), tone = StatusTone.Neutral)
        }
        FieldGroupLabel(str(S.desktop_assign_to))
        ZillitSelect(
            value = team.firstOrNull { it.id == choice.userId },
            options = team,
            onSelect = { person ->
                onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(userId = person?.id.orEmpty()))))
            },
            label = { person -> person.optionLabel(state.viewer.userId) },
            modifier = Modifier.fillMaxWidth(),
        )
        FieldGroupLabel(str(S.reason))
        val reasons = AssignDraft.reasons + AssignDraft.CUSTOM
        ZillitSelect(
            value = choice.reason.takeIf { it.isNotBlank() },
            options = reasons,
            onSelect = { reason ->
                onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(reason = reason.orEmpty()))))
            },
            label = { reason ->
                when (reason) {
                    null -> str(S.desktop_select_a_reason)
                    AssignDraft.CUSTOM -> str(S.desktop_other_custom_reason)
                    else -> reason
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (choice.reason == AssignDraft.CUSTOM) {
            ZillitTextField(
                value = choice.custom,
                onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(assign = choice.copy(custom = it)))) },
                placeholder = str(S.desktop_card_enter_reason),
                singleLine = false,
                maxLength = MAX_REASON,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A team member as the assign list names them — "(Me)" for the viewer. */
private fun CardPerson?.optionLabel(me: String): String = when {
    this == null -> str(S.desktop_select_team_member)
    id == me -> str(S.desktop_card_person_me, name)
    else -> name
}

/** Handing a receipt up to a senior, with the reason on the record. */
@Composable
private fun EscalateDialog(state: CardUiState, draft: ProcessDraft, onEvent: (CardEvent) -> Unit) {
    val reason = draft.escalation ?: return
    ZillitDialogShell(
        title = str(S.ah_escalate_to_senior),
        icon = ZillitIcons.Warning,
        visible = true,
        width = SMALL_DIALOG,
        onDismiss = { onEvent(CardEvent.EditProcess(draft.copy(escalation = null))) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.desktop_ce_escalate),
                onClick = { onEvent(CardEvent.ConfirmEscalation) },
                enabled = reason.isNotBlank() && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_card_escalating_note,
                draft.receipt.description.ifBlank { str(S.desktop_receipt) },
            ),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = reason,
            onValueChange = { onEvent(CardEvent.EditProcess(draft.copy(escalation = it))) },
            label = str(S.desktop_card_reason_for_escalation),
            placeholder = str(S.desktop_card_escalation_hint),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ESCALATED = "escalated"
private const val UNDER_REVIEW = "under_review"
private const val DEDUCT = "deduct"
private const val DESCRIPTION_WEIGHT = 2f
private const val MAX_REASON = 500
private val EDITOR_WIDTH = 1040.dp
private val EDITOR_HEIGHT = 880.dp
private val SMALL_DIALOG = 480.dp
private val RATE_WIDTH = 84.dp
private val GROSS_WIDTH = 110.dp
private val REMOVE_WIDTH = 40.dp
private val OPTION_WIDTH = 220.dp
private val OPTION_BORDER = 1.5.dp
