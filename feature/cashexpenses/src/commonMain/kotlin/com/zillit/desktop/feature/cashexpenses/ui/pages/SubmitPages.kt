package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CrewDates
import com.zillit.desktop.feature.cashexpenses.domain.CrewInput
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.CrewRules
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Submit Receipts — the crew member's form, for both pipelines
 * (`PCSubmitClaimPage.jsx`, `OOPSubmitPage.jsx`, `ReceiptSubmitForm.jsx`).
 *
 * Petty cash settles against a float: the page names it on the left and the
 * settlement section works out, as rows are typed, whether the batch reduces
 * it or comes back to the person. Out of pocket has no float, so it asks for
 * the currency and how to be paid.
 */
@Composable
fun SubmitReceiptsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val outOfPocket = state.destination.expenseType == ExpenseType.OutOfPocket
    val activeFloat = state.submittableFloat

    // Never flash "No active float" before the floats have been read.
    if (!outOfPocket && activeFloat == null && state.loading) {
        ScrollingPage {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitSpinner(size = SPINNER)
                ZillitText(
                    text = str(S.ah_loading),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
                )
            }
        }
        return
    }

    ScrollingPage {
        if (outOfPocket) {
            OutOfPocketSubmit(state, onEvent)
        } else if (activeFloat == null) {
            NoActiveFloat(state)
        } else {
            CrewNotice(
                title = crewPortalTitle(state),
                body = listOfNotNull(
                    activeFloat.requestNumber.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_float_number, it) },
                    str(S.desktop_pc_submit_against_float),
                ).joinToString(" · "),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.width(SIDE_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    ActiveFloatCard(state, activeFloat, onEvent)
                    HowItWorks()
                    CrewInfoBox(title = str(S.desktop_pc_heads_up), text = str(S.desktop_pc_heads_up_48_hours))
                }
                Column(modifier = Modifier.weight(1f)) {
                    ReceiptForm(state, onEvent) { SettlementSection(state, activeFloat, onEvent) }
                }
            }
        }
    }
}

/** "Crew Portal — Ada Lovelace (Gaffer)". */
@Composable
private fun crewPortalTitle(state: CashUiState): String {
    val name = LocalCashPeople.current.nameOrNull(state.viewer.userId) ?: str(S.user_label)
    val designation = state.assignees.firstOrNull { it.userId == state.viewer.userId }?.designation.orEmpty()
    val who = if (designation.isNotBlank()) "$name ($designation)" else name
    return str(S.desktop_pc_crew_portal, who)
}

@Composable
private fun NoActiveFloat(state: CashUiState) {
    CrewNotice(title = crewPortalTitle(state), body = str(S.desktop_pc_no_active_float_now))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) {
        ZillitEmptyState(
            title = str(S.desktop_pc_no_active_float),
            message = str(S.desktop_pc_no_active_float_body),
            icon = ZillitIcons.Wallet,
        )
    }
}

/** Out of pocket: one currency for the batch, then the same form with the reimbursement step. */
@Composable
private fun OutOfPocketSubmit(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    val designation = state.assignees.firstOrNull { it.userId == state.viewer.userId }?.designation.orEmpty()
    CrewNotice(
        title = str(S.desktop_pc_oop_title, people.nameOrNull(state.viewer.userId) ?: str(S.user_label)),
        body = listOf(designation, str(S.desktop_pc_oop_subtitle)).filter { it.isNotBlank() }.joinToString(" · "),
    )
    Column(
        modifier = Modifier.widthIn(max = OOP_WIDTH),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(CARD_PADDING),
        ) {
            CrewField(label = str(S.asset_currency), required = true) {
                CurrencyPicker(
                    currencies = state.currencies,
                    value = state.crew.submitCurrency,
                    onPick = { onEvent(CrewEvent.PickSubmitCurrency(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ReceiptForm(state, onEvent) {
            StepCard(2, str(S.desktop_pc_reimbursement_method), str(S.desktop_pc_how_paid_back)) {
                ReimbursementPanel(
                    state = state,
                    amount = state.draft.total,
                    currency = state.currencies.codeFor(state.crew.submitCurrency),
                    onEvent = onEvent,
                )
            }
        }
    }
}

/** Step 1 (receipts), step 2 ([settlement]), the notes, the error and the submit — `ReceiptSubmitForm`. */
@Composable
private fun ReceiptForm(state: CashUiState, onEvent: (CashEvent) -> Unit, settlement: @Composable () -> Unit) {
    val outOfPocket = state.destination.expenseType == ExpenseType.OutOfPocket
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        StepCard(1, str(S.desktop_pc_add_your_receipts), str(S.desktop_pc_upload_image_each)) {
            state.draft.receipts.forEachIndexed { index, receipt ->
                ReceiptCard(state, index, receipt, onEvent)
            }
            ZillitButton(
                text = str(S.desktop_ce_add_another_receipt),
                onClick = { onEvent(CashEvent.AddReceipt) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        settlement()
        CrewField(label = str(S.hint_notes)) {
            ZillitTextField(
                value = state.draft.notes,
                onValueChange = { onEvent(CashEvent.EditSubmitNotes(it)) },
                placeholder = str(S.desktop_pc_notes_placeholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CrewErrorBox(state.crew.submitError)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ZillitButton(
                text = if (outOfPocket) str(S.ah_submit_oop_claim_for_coding) else str(S.ah_submit_claim_for_coding),
                onClick = { onEvent(CashEvent.SubmitReceipts) },
                trailingIcon = ZillitIcons.ArrowRight,
                loading = state.busy,
            )
        }
        CrewInfoBox(text = if (outOfPocket) str(S.desktop_pc_oop_info) else str(S.desktop_pc_claim_info))
    }
}

// -- the left column -----------------------------------------------------------

@Suppress("LongMethod") // The float, its picker and its two ledgers, as the web's card lays them out.
@Composable
private fun ActiveFloatCard(state: CashUiState, float: CashFloat, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val spendable = CrewRules.submittable(state.myFloats)
    val money = { amount: Double -> state.currencies.format(amount, float.currency) }
    val settlement = state.settlement
    val people = LocalCashPeople.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        CrewHeading(if (spendable.size > 1) str(S.desktop_pc_submitting_against) else str(S.desktop_pc_active_float))
        if (spendable.size > 1) {
            // The balance rides in the option: a float number alone tells
            // someone holding two cash envelopes nothing about which is which.
            ZillitSelect(
                value = float,
                options = spendable,
                onSelect = { onEvent(CrewEvent.PickSubmitFloat(it.id)) },
                label = {
                    str(
                        S.desktop_pc_float_option,
                        it.requestNumber.ifBlank { it.id },
                        state.currencies.format(it.balance, it.currency),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZillitText(text = "#${float.requestNumber.ifBlank { "—" }}", style = ZillitTheme.typography.numeric)
        }
        val designation = state.assignees.firstOrNull { it.userId == state.viewer.userId }?.designation.orEmpty()
        ZillitText(
            text = listOfNotNull(people.nameOrNull(state.viewer.userId), designation.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        CrewRule(Modifier.padding(vertical = ZillitTheme.spacing.xs))
        val issued = float.issuedAmount.takeIf { it > 0 } ?: float.requestedAmount
        LedgerLine(str(S.desktop_issued), money(issued))
        LedgerLine(str(S.ah_spent_label), "−" + money((issued - float.balance).coerceAtLeast(0.0)))
        LedgerLine(str(S.desktop_ce_float_balance), money(float.balance), strong = true, tone = colors.success)
        // The same two figures the settlement bar shows, from the same
        // computation, appearing and disappearing together.
        settlement.receiptsCommits?.let { committed ->
            CrewRule(Modifier.padding(vertical = ZillitTheme.spacing.xs))
            LedgerLine(str(S.desktop_cr_committed), money(committed), tone = colors.warning)
            LedgerLine(str(S.desktop_pc_available_to_spend), money(settlement.headroom), strong = true)
        }
    }
}

@Composable
internal fun LedgerLine(
    label: String,
    value: String,
    strong: Boolean = false,
    tone: androidx.compose.ui.graphics.Color? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = if (strong) ZillitTheme.typography.label else ZillitTheme.typography.bodySmall,
            color = if (strong) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric,
            color = tone ?: ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** "How submission works" — four steps, the first active. */
@Composable
private fun HowItWorks() {
    val steps = listOf(
        Triple(str(S.desktop_card_add_receipts), str(S.desktop_pc_step_add_receipts_sub), ZillitIcons.Upload),
        Triple(str(S.desktop_pc_step_settlement), str(S.desktop_pc_step_settlement_sub), ZillitIcons.Wallet),
        Triple(str(S.desktop_pc_step_coordinator), str(S.desktop_pc_step_coordinator_sub), ZillitIcons.Ledger),
        Triple(str(S.desktop_pc_step_accountant), str(S.desktop_pc_step_accountant_sub), ZillitIcons.Check),
    )
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        CrewHeading(str(S.desktop_pc_how_submission_works))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            steps.forEachIndexed { index, (label, sub, icon) -> StepLine(label, sub, icon, active = index == 0) }
        }
    }
}

@Composable
internal fun StepLine(label: String, sub: String, icon: ImageVector?, active: Boolean, number: Int? = null) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(STEP_ICON)
                .clip(CircleShape)
                .background(if (active) colors.accentSoft else colors.surfaceSunken)
                .border(CREW_HAIRLINE, if (active) colors.accent else colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                ZillitIcon(icon = icon, tint = if (active) colors.accentText else colors.textMuted, size = STEP_GLYPH)
            } else {
                ZillitText(
                    text = number?.toString().orEmpty(),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (active) colors.accentText else colors.textMuted,
                )
            }
        }
        Column {
            ZillitText(
                text = label,
                style = if (active) ZillitTheme.typography.label else ZillitTheme.typography.bodySmall,
                color = if (active) colors.textPrimary else colors.textSecondary,
            )
            ZillitText(text = sub, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

// -- one receipt ---------------------------------------------------------------

@Suppress("LongMethod") // One receipt card; splitting the fields hides the shape.
@Composable
private fun ReceiptCard(state: CashUiState, index: Int, receipt: DraftReceipt, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val edit = { changed: DraftReceipt -> onEvent(CashEvent.EditReceipt(index, changed)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        UploadTile(
            receipt = receipt,
            uploading = state.attachingReceipt == index,
            onAttach = { onEvent(CashEvent.AttachReceipt(index)) },
            onRemove = { edit(receipt.copy(attachment = null, attachmentKey = null, attachmentName = null)) },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrewHeading(str(S.desktop_ce_receipt_number, index + 1), Modifier.weight(1f))
                if (state.draft.receipts.size > 1) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(CashEvent.RemoveReceipt(index)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                CrewField(str(S.desktop_ce_date_of_purchase), Modifier.weight(1f), required = true) {
                    val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
                    ZillitDateField(
                        value = CrewDates.utcIso(receipt.date),
                        onValueChange = { iso ->
                            // No later than today, as the web's picker allows.
                            if (!CrewDates.isAfter(iso, today)) edit(receipt.copy(date = CrewDates.utcMillis(iso)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CrewField(str(S.amount), Modifier.weight(1f), required = true) {
                    ZillitTextField(
                        value = receipt.amount,
                        onValueChange = { edit(receipt.copy(amount = CrewInput.amount(it))) },
                        placeholder = "0.00",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // The vendor travels as `description`; the coding's description is another field.
            CrewField(str(S.ah_lbl_vendor), required = true) {
                ZillitTextField(
                    value = receipt.description,
                    onValueChange = { edit(receipt.copy(description = it)) },
                    placeholder = str(S.cash_receipt_vendor_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CrewField(str(S.av_category)) {
                ZillitSelect(
                    value = ExpenseCategory.entries.firstOrNull { it.wire == receipt.category }
                        ?: ExpenseCategory.Other,
                    options = ExpenseCategory.entries,
                    onSelect = { edit(receipt.copy(category = it.wire)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BudgetCoding(state, receipt, edit, onEvent)
        }
    }
}

@Composable
private fun UploadTile(receipt: DraftReceipt, uploading: Boolean, onAttach: () -> Unit, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    val attached = receipt.attachment?.name?.takeIf { it.isNotBlank() }
        ?: receipt.attachmentName?.takeIf { it.isNotBlank() }
    val tile = Modifier.width(TILE_WIDTH).height(TILE_HEIGHT).clip(ZillitTheme.shapes.large)
    when {
        uploading -> Box(tile.background(colors.surface), contentAlignment = Alignment.Center) {
            ZillitSpinner(size = SPINNER)
        }
        attached != null -> Column(
            modifier = tile.background(colors.successSoft)
                .border(CREW_HAIRLINE, colors.success, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterVertically),
        ) {
            ZillitIcon(icon = ZillitIcons.Paperclip, tint = colors.success)
            ZillitText(text = attached, style = ZillitTheme.typography.labelSmall, color = colors.success, maxLines = 3)
            ZillitButton(
                text = str(S.remove),
                onClick = onRemove,
                variant = ButtonVariant.Danger,
                size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
            )
        }
        else -> Column(
            modifier = tile.background(colors.surface)
                .border(CREW_HAIRLINE, colors.borderStrong, ZillitTheme.shapes.large)
                .clickable(onClick = onAttach).padding(ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.CenterVertically),
        ) {
            ZillitIcon(icon = ZillitIcons.Upload, tint = colors.textMuted)
            // The attachment is mandatory: the orange asterisk every required field wears.
            Row {
                ZillitText(text = str(S.desktop_pc_drop_or_click), style = ZillitTheme.typography.label)
                ZillitText(text = " *", style = ZillitTheme.typography.label, color = colors.accentText)
            }
            ZillitText(
                text = str(S.desktop_pc_file_kinds),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/** Budget Coding — optional, collapsed until opened, as the web's receipt card has it. */
@Suppress("LongMethod") // The header row and its three fields.
@Composable
private fun BudgetCoding(
    state: CashUiState,
    receipt: DraftReceipt,
    edit: (DraftReceipt) -> Unit,
    onEvent: (CashEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    if (open) LaunchedEffect(Unit) { onEvent(CashEvent.LoadChartAccounts) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken).clickable { open = !open }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                tint = colors.textMuted,
                size = CHEVRON,
            )
            Box(Modifier.size(DOT).clip(CircleShape).background(colors.warning))
            ZillitText(text = str(S.ah_budget_coding), style = ZillitTheme.typography.label)
            ZillitText(
                text = str(S.desktop_pc_optional_leave_blank),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (receipt.costCode.isNotBlank()) {
                ZillitText(text = receipt.costCode, style = ZillitTheme.typography.numeric, color = colors.success)
            }
        }
        if (!open) return@Column
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CrewField(str(S.desktop_pc_cost_code)) {
                SuggestField(
                    value = receipt.costCode,
                    onValueChange = { edit(receipt.copy(costCode = it)) },
                    suggestions = state.chartAccounts.orEmpty().filter { it.postable && !it.balanceSheet },
                    text = { it.label },
                    pick = { it.code },
                    placeholder = str(S.desktop_po_search_or_enter_code),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                CrewField(str(S.episode), Modifier.weight(1f)) {
                    ZillitTextField(
                        value = receipt.episode,
                        onValueChange = { edit(receipt.copy(episode = it)) },
                        placeholder = str(S.ah_episode_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CrewField(str(S.description), Modifier.weight(1f)) {
                    ZillitTextField(
                        value = receipt.codedDescription,
                        onValueChange = { edit(receipt.copy(codedDescription = it)) },
                        placeholder = str(S.desktop_pc_coding_description_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CrewRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(CREW_HAIRLINE).background(ZillitTheme.colors.divider))
}

private val SIDE_WIDTH = 260.dp
private val OOP_WIDTH = 700.dp
private val TILE_WIDTH = 160.dp
private val TILE_HEIGHT = 213.dp
private val SPINNER = 16.dp
private val STEP_ICON = 22.dp
private val STEP_GLYPH = 12.dp
private val CHEVRON = 10.dp
private val DOT = 6.dp
