package com.zillit.desktop.feature.payroll.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.ui.JournalEvent
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.RunEvent
import com.zillit.desktop.feature.payroll.ui.components.DialogActions
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.journalRows

/**
 * The Journal Ledger — the week's pay breaks as debit lines, the payroll
 * accounts as credit lines, grouped by the company that pays them. Codes and
 * dates are typed here; a closed period's lines are read-only. Save stores
 * the coding; Post sends the ready timecards to the ledger.
 */
@Composable
internal fun JournalPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val rows = state.journalRows()
    Column(Modifier.fillMaxSize()) {
        PayrollTopBar(
            crumb = str(S.desktop_payroll_journal_ledger),
            onBack = { onEvent(RunEvent.Journal(open = false)) },
        ) { JournalHeaderActions(state, rows, onEvent) }
        BalanceBar(state, rows)
        if (rows.isEmpty()) {
            val allPosted = state.run.timecards.isNotEmpty() && state.run.timecards.all { it.status.isPosted }
            ZillitEmptyState(
                title = str(if (allPosted) S.desktop_payroll_all_posted else S.desktop_payroll_no_pay_breaks),
                icon = ZillitIcons.Ledger,
            )
        } else {
            JournalTable(state, rows, onEvent)
        }
    }
}

/**
 * The processing week, the default effective date that fills every open
 * line, the unsaved count, Save and Post.
 */
@Composable
private fun JournalHeaderActions(state: PayrollUiState, rows: List<JournalRow>, onEvent: (PayrollEvent) -> Unit) {
    val journal = state.run.journal
    state.run.weekStarting?.let {
        ZillitStatusPill(
            label = str(S.desktop_payroll_week_ending, PayPeriod.compactRangeLabel(it)),
            tone = StatusTone.InTransit,
        )
    }
    ZillitDateField(
        value = journal.headerDate,
        onValueChange = { onEvent(JournalEvent.HeaderDate(it)) },
        placeholder = str(S.desktop_payroll_effective_date_title),
        helperText = state.earliestEffectiveDate?.let { str(S.desktop_payroll_earliest_date, it) },
        modifier = Modifier.width(HEADER_DATE_WIDTH),
    )
    if (journal.edits.isNotEmpty()) {
        ZillitText(
            text = str(S.desktop_payroll_unsaved_count, journal.edits.size),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.warning,
        )
    }
    ZillitButton(
        text = if (journal.saving) str(S.ah_saving) else str(S.save),
        onClick = { onEvent(JournalEvent.Save) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = journal.edits.isNotEmpty() && !journal.saving,
        loading = journal.saving,
    )
    if (rows.any { it.timecardId != null }) {
        ZillitButton(
            text = str(S.desktop_payroll_post_count, state.run.timecards.count { Journal.isPostable(it.status) }),
            onClick = { onEvent(JournalEvent.Post) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
        )
    }
}

/**
 * The two sides, the variance, and whether they balance — informational, as
 * on the web: an unbalanced journal can still be posted.
 */
@Composable
private fun BalanceBar(state: PayrollUiState, rows: List<JournalRow>) {
    val balance = Journal.balance(rows, state.run.journal.edits)
    val currency = state.run.timecards.firstNotNullOfOrNull { it.currency }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitStatusPill(
            label = if (balance.balanced) str(S.desktop_card_balanced) else str(S.desktop_payroll_non_balanced),
            tone = when {
                balance.balanced -> StatusTone.Ready
                balance.variance > 0 -> StatusTone.Pending
                else -> StatusTone.Rejected
            },
            dot = true,
        )
        Figure(str(S.desktop_variance), Money.format(balance.variance, currency))
        Figure(str(S.desktop_debit), Money.format(balance.debit, currency))
        Figure(str(S.desktop_credit), Money.format(balance.credit, currency))
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold))
    }
}

/** One lazy list: a heading per company, then its lines; the payroll accounts last. */
@Composable
private fun JournalTable(state: PayrollUiState, rows: List<JournalRow>, onEvent: (PayrollEvent) -> Unit) {
    val groups = remember(rows, state.companies) {
        val debit = rows.filter { it.timecardId != null }.groupBy { it.companyId.orEmpty() }.map { (id, list) ->
            val name = state.companies.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
                ?: str(S.desktop_payroll_unassigned_company)
            name to list
        }
        val credit = rows.filter { it.timecardId == null }
        debit + if (credit.isEmpty()) emptyList() else listOf(str(S.desktop_payroll_accounts) to credit)
    }
    Box(Modifier.fillMaxSize().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm)) {
        Column(
            Modifier.fillMaxSize()
                .clip(ZillitTheme.shapes.large)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface),
        ) {
            HeaderLine()
            ZillitLazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (title, list) ->
                    item(key = "group-$title") { GroupLine(title, list.size) }
                    items(list, key = { it.id }) { row -> JournalLine(state, row, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun HeaderLine() {
    Row(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Header(str(S.description), Modifier.weight(1f))
        Header(str(S.code), Modifier.width(CODE_WIDTH))
        Header(str(S.desktop_payroll_effective_date_title), Modifier.width(DATE_WIDTH))
        Header(str(S.desktop_debit), Modifier.width(AMOUNT_WIDTH), TextAlign.End)
        Header(str(S.desktop_credit), Modifier.width(AMOUNT_WIDTH), TextAlign.End)
    }
}

@Composable
private fun Header(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun GroupLine(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.canvas)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        ZillitText(
            text = str(S.desktop_payroll_lines_count, count),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * One line. The code is locked on an account row (Payroll Entry Setup owns
 * it); the credit is typed only there; a line whose date falls in a closed
 * period is read-only throughout.
 */
@Suppress("LongMethod") // One row of five cells, each with its own edit rule.
@Composable
private fun JournalLine(state: PayrollUiState, row: JournalRow, onEvent: (PayrollEvent) -> Unit) {
    val edit = state.run.journal.edits[row.id]
    val editable = row.editable(state.lockedDate) && state.viewer.seesAccountantViews
    val flagged = row.id in state.run.journal.flagged
    val code = Journal.codeOf(row, edit)
    val date = Journal.dateOf(row, edit).orEmpty()
    val amount = Journal.amountOf(row, edit)
    val currency = state.run.timecards.firstOrNull { it.id == row.timecardId }?.currency
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!editable) {
                ZillitIcon(icon = ZillitIcons.Lock, size = LOCK_ICON, tint = ZillitTheme.colors.textMuted)
                Spacer(Modifier.width(ZillitTheme.spacing.xs))
            }
            ZillitText(text = row.description, style = ZillitTheme.typography.bodySmall, maxLines = 2)
        }
        ZillitTextField(
            value = code,
            onValueChange = { onEvent(JournalEvent.Code(row.id, it)) },
            placeholder = str(S.code),
            enabled = editable && !row.codeLocked,
            errorText = if (flagged && code.isBlank()) str(S.docusign_field_edit_required) else null,
            modifier = Modifier.width(CODE_WIDTH),
        )
        ZillitTextField(
            value = date,
            onValueChange = { onEvent(JournalEvent.Date(row.id, it)) },
            placeholder = "YYYY-MM-DD",
            enabled = editable,
            errorText = if (flagged && date.isBlank()) str(S.docusign_field_edit_required) else null,
            modifier = Modifier.width(DATE_WIDTH),
        )
        ZillitText(
            text = if (row.isCredit) "" else amount?.let { Money.format(it, currency) } ?: "—",
            style = ZillitTheme.typography.numeric,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        if (row.isCredit) CreditCell(row, edit, editable, onEvent) else Spacer(Modifier.width(AMOUNT_WIDTH))
    }
}

/** An account row's typed credit — blank until the accountant types one; it never defaults to zero. */
@Composable
private fun CreditCell(row: JournalRow, edit: JournalEdit?, editable: Boolean, onEvent: (PayrollEvent) -> Unit) {
    ZillitTextField(
        value = when {
            edit?.amountCleared == true -> ""
            edit?.amount != null -> edit.amount.toString()
            else -> row.amount?.toString().orEmpty()
        },
        onValueChange = { onEvent(JournalEvent.Credit(row.id, it)) },
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        enabled = editable,
        modifier = Modifier.width(AMOUNT_WIDTH),
    )
}

/** Why a post cannot go yet — and, where this viewer can fix it, the fix. */
@Composable
internal fun JournalAlertDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val alert = state.run.journal.alert
    val shown = remember(alert != null) { alert } ?: alert
    ZillitDialogShell(
        title = shown?.title.orEmpty(),
        icon = ZillitIcons.Warning,
        visible = alert != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(JournalEvent.DismissAlert) },
    ) {
        val open = alert ?: shown ?: return@ZillitDialogShell
        ZillitText(
            text = open.message,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        val fix = open.fix
        if (fix == null) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ZillitButton(text = str(S.ok), onClick = { onEvent(JournalEvent.DismissAlert) })
            }
        } else {
            DialogActions(
                cancel = { onEvent(JournalEvent.DismissAlert) },
                confirmText = str(S.desktop_payroll_and_post, "${fix.action.label} ${fix.ids.size}"),
                confirm = { onEvent(JournalEvent.FixAndPost) },
                busy = false,
            )
        }
    }
}

/** Post to Ledger — the web's `PostToLedgerModal`: the scope, and the default effective date. */
@Composable
internal fun JournalPostDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val post = state.run.journal.post
    val shown = remember(post != null) { post } ?: post
    val week = state.run.weekStarting
    ZillitDialogShell(
        title = str(S.desktop_payroll_post_to_ledger_week, week?.let(PayPeriod::compactRangeLabel).orEmpty()),
        subtitle = shown?.let {
            str(S.desktop_payroll_post_scope, it.timecardIds.size, it.lineCount, Money.format(it.gross, it.currency))
        },
        icon = ZillitIcons.Ledger,
        visible = post != null,
        width = DIALOG_WIDTH,
        onDismiss = { if (post?.saving != true) onEvent(JournalEvent.DismissPost) },
    ) {
        val open = post ?: shown ?: return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_payroll_post_ledger_explainer),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitDateField(
            value = open.effectiveDate,
            onValueChange = { onEvent(JournalEvent.PostDate(it)) },
            label = str(S.desktop_payroll_default_effective_date),
            helperText = str(S.desktop_payroll_default_date_hint),
            modifier = Modifier.fillMaxWidth(),
        )
        state.earliestEffectiveDate?.let {
            ZillitText(
                text = str(S.desktop_payroll_earliest_date, it),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        open.error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
        DialogActions(
            cancel = { onEvent(JournalEvent.DismissPost) },
            confirmText = if (open.saving) str(S.ah_posting_btn) else str(
                S.desktop_payroll_post_n,
                open.timecardIds.size,
            ),
            confirm = { onEvent(JournalEvent.ConfirmPost) },
            busy = open.saving,
            enabled = open.timecardIds.isNotEmpty(),
        )
    }
}

private val HEADER_DATE_WIDTH = 170.dp
private val CODE_WIDTH = 120.dp
private val DATE_WIDTH = 140.dp
private val AMOUNT_WIDTH = 120.dp
private val LOCK_ICON = 14.dp
private val DIALOG_WIDTH = 520.dp
