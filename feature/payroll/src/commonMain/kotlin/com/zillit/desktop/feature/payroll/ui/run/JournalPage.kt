package com.zillit.desktop.feature.payroll.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalReference
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.JournalSplit
import com.zillit.desktop.feature.payroll.domain.JournalSplits
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
    // Where "Add tax" sits: the LAST line of each timecard that has none, which
    // is exactly where the tax line itself would be seated. The action belongs
    // to the timecard rather than to any one of its lines, so it is offered
    // once — the web merges a cell across the run to say the same thing.
    val taxAnchors = remember(rows) {
        val withoutTax = rows.filter { it.isTax }.mapNotNull { it.timecardId }.toSet()
            .let { withTax -> rows.mapNotNull { it.timecardId }.toSet() - withTax }
        rows.filter { it.timecardId in withoutTax }.groupBy { it.timecardId }
            .values.mapNotNull { it.lastOrNull()?.id }.toSet()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
    ) {
        // Nine columns of coding do not fit a laptop, and a clipped Actions
        // column is a Split button nobody can reach. The ledger scrolls
        // sideways below its own width, as the web's table does.
        val width = maxOf(maxWidth, TABLE_MIN_WIDTH)
        Column(
            Modifier.fillMaxSize()
                .clip(ZillitTheme.shapes.large)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .horizontalScroll(rememberScrollState()),
        ) {
            Column(Modifier.width(width)) {
                HeaderLine()
                ZillitLazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    groups.forEach { (title, list) ->
                        item(key = "group-$title") { GroupLine(title, list.size) }
                        items(list, key = { it.id }) { row ->
                            JournalLine(state, row, row.id in taxAnchors, onEvent)
                        }
                    }
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
        Header(str(S.desktop_layers), Modifier.width(LAYERS_WIDTH))
        Header(str(S.drive_tags), Modifier.width(TAGS_WIDTH))
        Header(str(S.ah_lbl_vat), Modifier.width(TAX_WIDTH))
        Header(str(S.desktop_debit), Modifier.width(AMOUNT_WIDTH), TextAlign.End)
        Header(str(S.desktop_credit), Modifier.width(AMOUNT_WIDTH), TextAlign.End)
        Header(str(S.dd_actions), Modifier.width(ACTIONS_WIDTH))
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
private fun JournalLine(
    state: PayrollUiState,
    row: JournalRow,
    offersTax: Boolean,
    onEvent: (PayrollEvent) -> Unit,
) {
    val edit = state.run.journal.edits[row.id]
    val editable = row.editable(state.lockedDate) && state.viewer.seesAccountantViews
    val flagged = row.id in state.run.journal.flagged
    val code = Journal.codeOf(row, edit)
    val date = Journal.dateOf(row, edit).orEmpty()
    val amount = Journal.amountOf(row, edit)
    val currency = state.run.timecards.firstOrNull { it.id == row.timecardId }?.currency
    val splits = JournalSplits.splitsOf(row, edit)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            DescriptionCell(row, edit, editable, Modifier.weight(1f), onEvent)
            CodeCell(row, code, editable, flagged, onEvent)
            ZillitTextField(
                value = date,
                onValueChange = { onEvent(JournalEvent.Date(row.id, it)) },
                placeholder = "YYYY-MM-DD",
                enabled = editable,
                errorText = if (flagged && date.isBlank()) str(S.docusign_field_edit_required) else null,
                modifier = Modifier.width(DATE_WIDTH),
            )
            LayersCell(
                sets = state.run.journal.reference.trackingSets,
                picked = Journal.layersOf(row, edit),
                editable = editable,
                modifier = Modifier.width(LAYERS_WIDTH),
            ) { onEvent(JournalEvent.Layers(row.id, it)) }
            TagsCell(
                reference = state.run.journal.reference,
                selected = Journal.tagsOf(row, edit),
                editable = editable,
                modifier = Modifier.width(TAGS_WIDTH),
            ) { onEvent(JournalEvent.Tags(row.id, it)) }
            if (row.isTax) TaxCell(row, edit, editable, onEvent) else Spacer(Modifier.width(TAX_WIDTH))
            if (row.isCredit) {
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.numeric,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(AMOUNT_WIDTH),
                )
                AmountCell(row, edit, amount, editable, onEvent)
            } else {
                AmountCell(row, edit, amount, editable && row.amountEditable, onEvent, currency)
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.numeric,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(AMOUNT_WIDTH),
                )
            }
            RowActions(state, row, editable, offersTax, onEvent)
        }
        splits.forEach { child ->
            SplitLine(row, child, editable, currency, state.run.journal.reference, onEvent)
        }
    }
}

/**
 * The line's wording. Derived from the week, the crew member and the pay break
 * until the accountant types their own, which is then what posts.
 */
@Composable
private fun DescriptionCell(
    row: JournalRow,
    edit: JournalEdit?,
    editable: Boolean,
    modifier: Modifier,
    onEvent: (PayrollEvent) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!editable) {
            ZillitIcon(icon = ZillitIcons.Lock, size = LOCK_ICON, tint = ZillitTheme.colors.textMuted)
            Spacer(Modifier.width(ZillitTheme.spacing.xs))
            ZillitText(text = row.description, style = ZillitTheme.typography.bodySmall, maxLines = 2)
            return@Row
        }
        ZillitTextField(
            value = Journal.descriptionOf(row, edit),
            onValueChange = { onEvent(JournalEvent.Describe(row.id, it)) },
            placeholder = str(S.description),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A payroll account's code comes from Payroll Entry Setup, so it reads as text
 * even on an otherwise editable row — and the save ignores the edit buffer for
 * it, which is what keeps a bulk edit from reaching it.
 */
@Composable
private fun CodeCell(
    row: JournalRow,
    code: String,
    editable: Boolean,
    flagged: Boolean,
    onEvent: (PayrollEvent) -> Unit,
) {
    if (row.codeLocked) {
        ZillitTooltip(text = str(S.desktop_payroll_code_set_in_setup)) {
            ZillitText(
                text = code.ifBlank { "—" },
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.width(CODE_WIDTH),
            )
        }
    } else {
        ZillitTextField(
            value = code,
            onValueChange = { onEvent(JournalEvent.Code(row.id, it)) },
            placeholder = str(S.code),
            enabled = editable,
            errorText = if (flagged && code.isBlank()) str(S.docusign_field_edit_required) else null,
            modifier = Modifier.width(CODE_WIDTH),
        )
    }
}

/**
 * The tax line's rate, and the money it derives. Picking a rate re-derives the
 * figure; typing a figure is an override that stands on its own.
 */
@Composable
private fun TaxCell(row: JournalRow, edit: JournalEdit?, editable: Boolean, onEvent: (PayrollEvent) -> Unit) {
    ZillitTextField(
        value = Journal.rateOf(row, edit).percent(),
        onValueChange = { onEvent(JournalEvent.TaxRate(row.id, it)) },
        placeholder = str(S.desktop_payroll_tax_percentage),
        keyboardType = KeyboardType.Decimal,
        enabled = editable,
        trailingContent = {
            ZillitText(text = "%", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        },
        modifier = Modifier.width(TAX_WIDTH),
    )
}

/** A whole rate reads as `20`, not `20.0`. */
private fun Double?.percent(): String = when {
    this == null -> ""
    this == toLong().toDouble() -> toLong().toString()
    else -> toString()
}

/** A typed figure — an account's credit, or an override of a tax line's derived debit. */
@Composable
private fun AmountCell(
    row: JournalRow,
    edit: JournalEdit?,
    amount: Double?,
    editable: Boolean,
    onEvent: (PayrollEvent) -> Unit,
    currency: String? = null,
) {
    if (!editable) {
        ZillitText(
            text = amount?.let { Money.format(it, currency) } ?: "—",
            style = ZillitTheme.typography.numeric,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        return
    }
    ZillitTextField(
        value = when {
            edit?.amountCleared == true && !row.isTax -> ""
            edit?.amount != null -> edit.amount.toString()
            else -> amount?.toString().orEmpty()
        },
        onValueChange = { onEvent(JournalEvent.Credit(row.id, it)) },
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        modifier = Modifier.width(AMOUNT_WIDTH),
    )
}

/**
 * Split, and the tax line's own removal.
 *
 * A tax line is one line by definition, so it is never split — splitting it
 * would give the timecard two of them.
 */
@Composable
private fun RowActions(
    state: PayrollUiState,
    row: JournalRow,
    editable: Boolean,
    offersTax: Boolean,
    onEvent: (PayrollEvent) -> Unit,
) {
    Row(
        modifier = Modifier.width(ACTIONS_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!editable) return@Row
        val timecardId = row.timecardId
        // The tax line is one line by definition, so it is never split — it
        // carries its own removal instead.
        if (row.isTax) {
            if (timecardId != null) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_payroll_remove_tax_line),
                    onClick = { onEvent(JournalEvent.RemoveTax(timecardId)) },
                )
            }
            return@Row
        }
        // Split belongs to the LINE and Add tax to the TIMECARD, so a row can
        // offer both — the anchor row does. Making them exclusive cost every
        // timecard's last line its split.
        if (row.splittable) SplitButton(state, row, onEvent)
        if (offersTax && timecardId != null) AddTaxButton(timecardId, onEvent)
    }
}

@Composable
private fun SplitButton(state: PayrollUiState, row: JournalRow, onEvent: (PayrollEvent) -> Unit) {
    val split = JournalSplits.splitsOf(row, state.run.journal.edits[row.id]).isNotEmpty()
    ZillitTooltip(text = str(if (split) S.desktop_payroll_split_more_hint else S.desktop_payroll_split_hint)) {
        ZillitButton(
            text = str(S.desktop_payroll_split_line),
            onClick = { onEvent(JournalEvent.Split(row.id)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/** One tax line per timecard: it posts against the week's total, not per pay break. */
@Composable
private fun AddTaxButton(timecardId: String, onEvent: (PayrollEvent) -> Unit) {
    ZillitTooltip(text = str(S.desktop_payroll_add_tax_line)) {
        ZillitButton(
            text = str(S.desktop_payroll_add_tax),
            onClick = { onEvent(JournalEvent.AddTax(timecardId)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/**
 * One allocation of a split line. It sits on its parent's side, and its
 * siblings re-spread whenever its amount changes, so they always sum to the
 * parent's derived total — the server does not rebalance them.
 */
@Composable
private fun SplitLine(
    row: JournalRow,
    child: JournalSplit,
    editable: Boolean,
    currency: String?,
    reference: JournalReference,
    onEvent: (PayrollEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(
                start = ZillitTheme.spacing.xl,
                end = ZillitTheme.spacing.md,
                top = ZillitTheme.spacing.xxs,
                bottom = ZillitTheme.spacing.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = "↳", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        ZillitTextField(
            value = child.description,
            onValueChange = { onEvent(JournalEvent.SplitDescribe(row.id, child.id, it)) },
            placeholder = str(S.desktop_payroll_allocation_description),
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        // A locked code is configuration: an allocation inherits it and cannot
        // be repointed, or Split would be a way around the lock.
        if (row.codeLocked) {
            ZillitText(
                text = row.code.ifBlank { "—" },
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.width(CODE_WIDTH),
            )
        } else {
            ZillitTextField(
                value = child.nominalCode,
                onValueChange = { onEvent(JournalEvent.SplitCode(row.id, child.id, it)) },
                placeholder = str(S.code),
                enabled = editable,
                modifier = Modifier.width(CODE_WIDTH),
            )
        }
        ZillitTextField(
            value = child.effectiveDate.orEmpty(),
            onValueChange = { onEvent(JournalEvent.SplitDate(row.id, child.id, it)) },
            placeholder = "YYYY-MM-DD",
            enabled = editable,
            modifier = Modifier.width(DATE_WIDTH),
        )
        SplitCoding(row, child, editable, reference, onEvent)
        // Tax is a per-timecard line, never a per-allocation one.
        Spacer(Modifier.width(TAX_WIDTH))
        SplitAmounts(row, child, editable, currency, onEvent)
        Row(Modifier.width(ACTIONS_WIDTH)) {
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_payroll_remove_allocation),
                    onClick = { onEvent(JournalEvent.RemoveSplit(row.id, child.id)) },
                )
            }
        }
    }
}

/** An allocation's own layers and tags — the reason to split a line at all. */
@Composable
private fun RowScope.SplitCoding(
    row: JournalRow,
    child: JournalSplit,
    editable: Boolean,
    reference: JournalReference,
    onEvent: (PayrollEvent) -> Unit,
) {
    LayersCell(
        sets = reference.trackingSets,
        picked = child.trackingCodes,
        editable = editable,
        modifier = Modifier.width(LAYERS_WIDTH),
    ) { onEvent(JournalEvent.SplitLayers(row.id, child.id, it)) }
    TagsCell(
        reference = reference,
        selected = child.tags,
        editable = editable,
        modifier = Modifier.width(TAGS_WIDTH),
    ) { onEvent(JournalEvent.SplitTags(row.id, child.id, it)) }
}

/** The allocation's money, on whichever side its parent sits. */
@Composable
private fun SplitAmounts(
    row: JournalRow,
    child: JournalSplit,
    editable: Boolean,
    currency: String?,
    onEvent: (PayrollEvent) -> Unit,
) {
    val field: @Composable () -> Unit = {
        if (editable) {
            ZillitTextField(
                value = child.amount.toString(),
                onValueChange = { onEvent(JournalEvent.SplitAmount(row.id, child.id, it)) },
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(AMOUNT_WIDTH),
            )
        } else {
            ZillitText(
                text = Money.format(child.amount, currency),
                style = ZillitTheme.typography.numeric,
                textAlign = TextAlign.End,
                modifier = Modifier.width(AMOUNT_WIDTH),
            )
        }
    }
    val dash: @Composable () -> Unit = {
        ZillitText(
            text = "—",
            style = ZillitTheme.typography.numeric,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
    }
    if (row.isCredit) {
        dash()
        field()
    } else {
        field()
        dash()
    }
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
/** Description + code + date + layers + tags + tax + both money columns + actions. */
private val TABLE_MIN_WIDTH = 1320.dp
private val LAYERS_WIDTH = 150.dp
private val TAGS_WIDTH = 150.dp
private val TAX_WIDTH = 96.dp
private val ACTIONS_WIDTH = 210.dp
private val CODE_WIDTH = 120.dp
private val DATE_WIDTH = 140.dp
private val AMOUNT_WIDTH = 120.dp
private val LOCK_ICON = 14.dp
private val DIALOG_WIDTH = 520.dp
