package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitFileBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.FeeBasis
import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.PaymentMode
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentContractBody
import com.zillit.desktop.feature.talentdeals.domain.TalentDates
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentIds
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.domain.TalentRoles
import com.zillit.desktop.feature.talentdeals.domain.TalentSchedule
import com.zillit.desktop.feature.talentdeals.domain.TalentStatus
import com.zillit.desktop.feature.talentdeals.domain.TalentWithholding
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import com.zillit.desktop.feature.talentdeals.ui.ContractSection
import com.zillit.desktop.feature.talentdeals.ui.LabelledValue
import com.zillit.desktop.feature.talentdeals.ui.PageColumn
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState
import com.zillit.desktop.feature.talentdeals.ui.TalentHeader
import com.zillit.desktop.feature.talentdeals.ui.ValueText

/**
 * One talent contract, read-only (`TalentContractViewPage.jsx`): a masthead, then
 * sections of labelled values. Nothing here edits — Edit in the header opens the
 * SAME form Add Contract uses, prefilled, and shows only with `canPost`. Every
 * figure derives from the row through the form's own shape, so these are the
 * ones the form would show: one arithmetic, not two.
 */
@Composable
internal fun ContractViewPage(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    val contract = detail.contract
    Column(modifier.fillMaxSize()) {
        TalentHeader(
            backLabel = str(S.desktop_td_back_to_talent_deals),
            onBack = { onEvent(TalentDealsEvent.Back) },
            eyebrow = str(S.desktop_td_title),
            onEyebrow = { onEvent(TalentDealsEvent.Back) },
            current = contract?.reference?.takeIf { it.isNotBlank() } ?: str(S.contract_text),
            actions = {
                if (contract != null && state.viewer.canPost) {
                    ZillitButton(
                        text = str(S.edit),
                        leadingIcon = ZillitIcons.Edit,
                        size = ButtonSize.Small,
                        onClick = { onEvent(TalentDealsEvent.OpenEdit(contract.id)) },
                    )
                }
            },
        )
        ZillitScrollColumn(Modifier.fillMaxSize()) {
            when {
                detail.loading -> PageColumn {
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 24.dp) }
                    }
                }
                detail.error != null || contract == null -> PageColumn {
                    ZillitText(
                        text = detail.error ?: str(S.desktop_td_contract_not_found),
                        color = ZillitTheme.colors.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxxl),
                    )
                }
                else -> ContractCard(state, contract, onEvent, signatureLoader)
            }
        }
    }
}

@Suppress("LongMethod") // A page laid out section by section.
@Composable
private fun ContractCard(
    state: TalentDealsUiState,
    contract: TalentContract,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
) {
    val colors = ZillitTheme.colors
    val ids = remember { TalentIds() }
    val form = remember(contract) { TalentContractBody.fromContract(contract, ids) }
    val symbol = state.reference.currencies.get(contract.currency).symbol.ifBlank { contract.currency }
    val basis = TalentWithholding.basis(form)
    val summary = TalentWithholding.summary(
        form.fee,
        form.expenses,
        basis,
        feeLabel = str(S.desktop_dm_fee),
        expenseLabel = str(S.desktop_expense),
    )
    val totals = TalentSchedule.totals(form.tranches, form.fee, form.paidAs)
    val open = { attachment: TalentAttachment -> onEvent(TalentDealsEvent.OpenAttachment(attachment)) }

    PageColumn {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .border(1.dp, colors.border, ZillitTheme.shapes.large),
        ) {
            Masthead(contract)
            Column(Modifier.padding(horizontal = ZillitTheme.spacing.xl).padding(bottom = ZillitTheme.spacing.xl)) {
                ContractSection(str(S.contract_text)) {
                    FieldRow(
                        left = { LabelledValue(str(S.name)) { ValueText(contract.person) } },
                        right = { LabelledValue(str(S.recce_field_role)) { ValueText(contract.role) } },
                    )
                    FieldRow(
                        left = { LabelledValue(str(S.dm_section_territory)) { ValueText(territoryLabel(state,
                            contract)) } },
                        right = { LabelledValue(str(S.dm_label_union)) { ValueText(unionLabel(state, contract)) } },
                    )
                    LabelledValue(str(S.desktop_td_lbl_long_form)) { AttachmentLine(contract.longFormContract, open) }
                }

                ContractSection(str(S.desktop_dm_fee)) {
                    FieldRow(
                        left = {
                            LabelledValue(str(S.desktop_td_lbl_total_fee)) {
                                ValueText("${TalentNumbers.formatMoney(contract.fee, symbol)} ${contract.currency}",
                                    mono = true)
                            }
                        },
                        right = { LabelledValue(str(S.payment_label)) { ValueText(paymentLabel(form.paymentMode)) } },
                    )
                    FieldRow(
                        left = { LabelledValue(str(S.desktop_covers)) { ValueText(basisLabel(form.feeBasis)) } },
                        right = {
                            LabelledValue(str(S.desktop_td_lbl_time_cards)) {
                                ValueText(
                                    if (contract.autoTimeCards) {
                                        str(S.desktop_auto_generated)
                                    } else {
                                        str(S.docusign_chip_mode_manual)
                                    },
                                )
                            }
                        },
                    )
                }

                ContractSection(
                    title = str(S.desktop_td_sec_withholding),
                    note = if (form.withholding) null else str(S.desktop_td_note_not_apply),
                ) {
                    if (form.withholding) WithholdingFacts(form, symbol, open)
                }

                ContractSection(title = str(S.desktop_td_sec_schedule), note = scheduleNote(form, totals.balanced,
                    totals.remaining, symbol)) {
                    if (form.weekly) TrancheTable(form, symbol)
                    if (TalentRoles.exhibitGApplies(contract.role, contract.unionIdentifier)) {
                        ExhibitToggle(state.exhibit.open) { onEvent(TalentDealsEvent.ToggleExhibit) }
                        if (state.exhibit.open) {
                            ExhibitGPanel(
                                performer = contract.person,
                                character = contract.role,
                                fee = form.fee.let(TalentNumbers::num),
                                weeks = if (form.weekly) TalentSchedule.scheduleWeeks(form.tranches) else 0.0,
                                symbol = symbol,
                                exhibit = state.exhibit,
                                onEvent = onEvent,
                                signatureLoader = signatureLoader,
                            )
                        }
                    }
                }

                ContractSection(
                    title = str(S.desktop_td_sec_expenses),
                    note = if (form.expenses.isEmpty()) str(S.none) else null,
                ) {
                    if (form.expenses.isNotEmpty()) ExpenseTable(form, symbol)
                    if (TalentNumbers.num(form.fee) > 0 || form.expenses.isNotEmpty()) {
                        WithholdingBreakdown(summary, symbol, authorised = form.withholdingAuthorised)
                    }
                }
            }
        }
    }
}

@Composable
private fun Masthead(contract: TalentContract) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(contract.person, style = ZillitTheme.typography.titleLarge, maxLines = 1)
            ZillitText("${contract.role} · ${contract.reference}", style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted, maxLines = 1)
        }
        ZillitStatusPill(label = contract.status.uppercase(), tone = statusTone(contract))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

private fun statusTone(contract: TalentContract): StatusTone = when (contract.statusKind) {
    TalentStatus.Issued -> StatusTone.Ready
    TalentStatus.Draft -> StatusTone.Pending
    TalentStatus.Cancelled -> StatusTone.Rejected
    TalentStatus.Deleted, TalentStatus.Unknown -> StatusTone.Neutral
}

/** Two labelled values side by side, as the web's two-column grid has them. */
@Composable
private fun FieldRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

/** A stored attachment as a full-width file block: type badge, name, and View. */
@Composable
internal fun AttachmentLine(attachment: TalentAttachment?, onView: (TalentAttachment) -> Unit) {
    if (attachment == null || !attachment.isPresent) {
        ValueText(null)
        return
    }
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = ATTACHMENT_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitFileBadge(fileName = attachment.displayName)
        ZillitText(
            text = attachment.displayName,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.view),
            leadingIcon = ZillitIcons.Eye,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            onClick = { onView(attachment) },
        )
    }
}

@Composable
private fun WithholdingFacts(form: TalentForm, symbol: String, open: (TalentAttachment) -> Unit) {
    val amount = form.withholdingType == WithholdingType.Amount
    FieldRow(
        left = {
            LabelledValue(if (amount) str(S.desktop_flat_amount) else str(S.av_rate)) {
                ValueText(
                    if (amount) {
                        TalentNumbers.formatMoney(TalentNumbers.num(form.withholdingValue), symbol)
                    } else {
                        "${TalentNumbers.plain(TalentNumbers.num(form.withholdingValue)).ifEmpty { "0" }}%"
                    },
                    mono = true,
                )
            }
        },
        right = {
            LabelledValue(str(S.desktop_td_lbl_preauth_rate)) {
                ValueText(
                    if (form.withholdingAuthorised) {
                        "${TalentNumbers.plain(TalentNumbers.num(form.authorisedRate)).ifEmpty { "0" }}%"
                    } else {
                        null
                    },
                    mono = true,
                )
            }
        },
    )
    LabelledValue(str(S.desktop_td_lbl_certificate)) {
        AttachmentLine((form.certificateFile as? TalentFileSlot.Stored)?.attachment, open)
    }
}

@Composable
private fun ExhibitToggle(open: Boolean, onToggle: () -> Unit) {
    ZillitButton(
        text = if (open) str(S.desktop_td_hide_exhibit) else str(S.desktop_td_view_exhibit),
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        onClick = onToggle,
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
    )
}

@Composable
private fun TrancheTable(form: TalentForm, symbol: String) {
    val flat = form.paidAs == PaidAs.Flat
    ReadTable(
        header = listOf(
            str(S.desktop_td_th_tranche),
            str(S.start),
            str(S.end),
            str(S.dm_ds_unit_weeks),
            if (flat) str(S.amount) else str(S.share),
        ),
        rows = form.tranches.map { t ->
            val value = TalentNumbers.num(t.value)
            listOf(
                t.label.ifBlank { "—" },
                if (t.start.isNotEmpty()) TalentDates.display(t.start) else "—",
                if (t.end.isNotEmpty()) TalentDates.display(t.end) else "—",
                t.weeks.ifEmpty { "—" },
                if (flat) {
                    TalentNumbers.formatMoney(value, symbol)
                } else {
                    val share = TalentNumbers.formatMoney(TalentNumbers.num(form.fee) * value / PERCENT, symbol)
                    "${TalentNumbers.plain(value).ifEmpty { "0" }}% · $share"
                },
            )
        },
    )
}

@Composable
private fun ExpenseTable(form: TalentForm, symbol: String) {
    val taxed = form.withholding
    ReadTable(
        header = buildList {
            add(str(S.desktop_expense))
            add(str(S.amount))
            if (taxed) {
                add(str(S.desktop_td_th_taxable))
                add(str(S.desktop_td_th_in_kind))
            }
        },
        rows = form.expenses.map { e ->
            buildList {
                add(e.label.ifBlank { "—" })
                add(TalentNumbers.formatMoney(TalentNumbers.num(e.amount), symbol))
                if (taxed) {
                    add(if (e.withhold) str(S.yes) else str(S.no))
                    add(if (e.grossUp) str(S.desktop_td_grossed_up_cell) else "—")
                }
            }
        },
    )
}

/** The read-only page's table: a sunken header, then rows, the first column wide. */
@Composable
private fun ReadTable(header: List<String>, rows: List<List<String>>) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        TableLine(header, header = true)
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            TableLine(row)
        }
    }
}

@Composable
private fun TableLine(cells: List<String>, header: Boolean = false) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (header) colors.surfaceSunken else colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        cells.forEachIndexed { index, cell ->
            ZillitText(
                text = if (header) cell.uppercase() else cell,
                style = if (header) ZillitTheme.typography.columnHeader else ZillitTheme.typography.bodyMedium,
                color = if (header) colors.textMuted else colors.textPrimary,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(if (index == 0) FIRST_COLUMN else 1f),
                maxLines = 1,
            )
        }
    }
}

// -- derived text -------------------------------------------------------------------------

private fun territoryLabel(state: TalentDealsUiState, contract: TalentContract): String =
    state.reference.territories.firstOrNull { it.id == contract.territoryCode }?.label
        ?: contract.territoryCode.uppercase()

/**
 * The role's own guild reads as the picker shows it ("DGA - Directors Guild of
 * America"); anything else takes the lookup's name, then the slug.
 */
private fun unionLabel(state: TalentDealsUiState, contract: TalentContract): String? =
    TalentRoles.baseUnionName(contract.unionIdentifier)
        ?: state.detail.unionName.takeIf { it.isNotBlank() }
        ?: TalentRoles.UNION_LABELS[contract.unionIdentifier]
        ?: contract.unionIdentifier.takeIf { it.isNotBlank() }

private fun paymentLabel(mode: PaymentMode?): String? = when (mode) {
    PaymentMode.Escrow -> str(S.desktop_td_mode_escrow)
    PaymentMode.FirstDay -> str(S.desktop_td_mode_first_day)
    null -> null
}

private fun basisLabel(basis: FeeBasis?): String? = when (basis) {
    FeeBasis.Salary -> str(S.desktop_td_basis_salary)
    FeeBasis.Fringes -> str(S.desktop_td_basis_fringes)
    null -> null
}

private fun scheduleNote(form: TalentForm, balanced: Boolean, remaining: Double, symbol: String): String {
    if (!form.weekly) return str(S.desktop_td_note_no_weekly)
    val count = form.tranches.size
    val tranches = if (count == 1) str(S.desktop_td_tranche_one, count) else str(S.desktop_td_tranche_other, count)
    val allocation = if (balanced) {
        str(S.desktop_td_note_fully_allocated)
    } else {
        str(S.desktop_td_note_unallocated, TalentNumbers.formatMoney(remaining, symbol))
    }
    return str(S.desktop_td_note_weekly, tranches, allocation)
}

private const val SKELETON_ROWS = 8
private const val PERCENT = 100.0
private const val FIRST_COLUMN = 2f
private const val ATTACHMENT_BORDER_ALPHA = 0.35f
