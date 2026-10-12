package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.FeeBasis
import com.zillit.desktop.feature.talentdeals.domain.PaidAs
import com.zillit.desktop.feature.talentdeals.domain.PaymentMode
import com.zillit.desktop.feature.talentdeals.domain.ScheduleTotals
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentField
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.domain.TalentSchedule
import com.zillit.desktop.feature.talentdeals.domain.TrancheDraft
import com.zillit.desktop.feature.talentdeals.domain.WithholdingType
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState

/**
 * The Payment Schedule section: escrow or first day (ONE choice) and what it
 * covers, withholding tax and a pre-authorised rate with its certificate, and
 * the weekly term with its tranches, allocation bar and Exhibit G.
 */
@Composable
internal fun PaymentSection(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
) {
    val page = state.form
    val form = page.form
    val shown = page.shownErrors
    val edit = { change: TalentForm.() -> TalentForm -> onEvent(TalentDealsEvent.FormEdit(change)) }

    FormSection(str(S.desktop_td_sec_payment_schedule)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitCheckbox(
                checked = form.paymentMode == PaymentMode.Escrow,
                onCheckedChange = { edit { pickMode(PaymentMode.Escrow) } },
                label = str(S.desktop_td_chk_escrow),
            )
            ZillitCheckbox(
                checked = form.paymentMode == PaymentMode.FirstDay,
                onCheckedChange = { edit { pickMode(PaymentMode.FirstDay) } },
                label = str(S.desktop_td_chk_first_day),
            )
            if (TalentField.PaymentMode in shown) FieldError(str(S.desktop_td_err_payment_mode))
            if (form.paymentMode != null) {
                Nested {
                    ZillitCheckbox(
                        checked = form.feeBasis == FeeBasis.Salary,
                        onCheckedChange = { edit { pickBasis(FeeBasis.Salary) } },
                        label = str(S.desktop_td_chk_only_salary),
                    )
                    ZillitCheckbox(
                        checked = form.feeBasis == FeeBasis.Fringes,
                        onCheckedChange = { edit { pickBasis(FeeBasis.Fringes) } },
                        label = str(S.desktop_td_chk_salary_fringes),
                    )
                    if (TalentField.FeeBasis in shown) FieldError(str(S.desktop_td_err_fee_basis))
                }
            }
        }

        WithholdingBlock(state, onEvent, edit)

        ZillitCheckbox(
            checked = form.weekly,
            onCheckedChange = { onEvent(TalentDealsEvent.ToggleWeekly(it)) },
            label = str(S.desktop_td_chk_weekly),
        )
        if (form.weekly) ScheduleBlock(state, onEvent, edit, signatureLoader)
    }
}

@Suppress("LongMethod") // A page laid out section by section.
@Composable
private fun WithholdingBlock(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    edit: (TalentForm.() -> TalentForm) -> Unit,
) {
    val form = state.form.form
    val shown = state.form.shownErrors
    ZillitCheckbox(
        checked = form.withholding,
        onCheckedChange = { on -> edit { copy(withholding = on) } },
        label = str(S.desktop_td_chk_withholding),
    )
    if (!form.withholding) return
    Nested {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            FormField(label = str(S.desktop_td_withholding_as), modifier = Modifier.width(WITHHOLDING_AS_WIDTH)) {
                ZillitSelect(
                    value = form.withholdingType,
                    options = WithholdingType.entries,
                    onSelect = { type -> edit { setWithholdingType(type) } },
                    label = { if (it == WithholdingType.Percent) str(S.desktop_td_opt_percentage) else str(S.amount) },
                    showInitials = false,
                    searchable = false,
                )
            }
            FormField(
                label = if (form.withholdingType == WithholdingType.Percent) str(S.desktop_rate_pct) else str(S.amount),
                required = true,
                error = if (TalentField.Withholding in shown) str(S.desktop_td_err_withholding) else null,
                modifier = Modifier.width(WITHHOLDING_VALUE_WIDTH),
            ) {
                // A rate is a bare figure; an amount is money and gets the calculator field.
                if (form.withholdingType == WithholdingType.Percent) {
                    ZillitTextField(
                        value = form.withholdingValue,
                        onValueChange = { v -> edit { copy(withholdingValue = v) } },
                        placeholder = "0",
                        keyboardType = KeyboardType.Decimal,
                    )
                } else {
                    MoneyInput(form.withholdingValue, state.symbol,
                        onCommit = { v -> edit { copy(withholdingValue = v) } })
                }
            }
        }

        ZillitCheckbox(
            checked = form.withholdingAuthorised,
            onCheckedChange = { on -> edit { setAuthorised(on) } },
            label = str(S.desktop_td_chk_preauthorised),
        )
        if (form.withholdingAuthorised) {
            Nested {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                    verticalAlignment = Alignment.Top) {
                    FormField(
                        label = str(S.desktop_td_authorised_rate),
                        required = true,
                        error = if (TalentField.AuthorisedRate in shown) {
                            str(S.desktop_td_err_authorised_rate)
                        } else {
                            null
                        },
                        modifier = Modifier.width(AUTHORISED_WIDTH),
                    ) {
                        ZillitTextField(
                            value = form.authorisedRate,
                            onValueChange = { v -> edit { copy(authorisedRate = v) } },
                            placeholder = "0",
                            keyboardType = KeyboardType.Decimal,
                        )
                    }
                    FileField(
                        label = str(S.desktop_td_lbl_certificate),
                        cta = str(S.desktop_td_choose_certificate),
                        file = form.certificateFile,
                        onPick = { onEvent(TalentDealsEvent.PickCertificateFile) },
                        onRemove = { edit { copy(certificateFile = null) } },
                        modifier = Modifier.width(CERTIFICATE_WIDTH),
                    )
                }
                Hint(str(S.desktop_td_authorised_hint))
            }
        }
    }
}

@Suppress("LongMethod") // A page laid out section by section.
@Composable
private fun ScheduleBlock(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    edit: (TalentForm.() -> TalentForm) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
) {
    val page = state.form
    val form = page.form
    val colors = ZillitTheme.colors
    val totals = TalentSchedule.totals(form.tranches, form.fee, form.paidAs)
    val labels = (TalentSchedule.TRANCHE_TYPES + form.tranches.map { it.label }).filter { it.isNotBlank() }.distinct()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitText(
                text = str(S.desktop_td_payment_tranche).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
                modifier = Modifier.weight(1f),
            )
            ZillitText(str(S.desktop_td_paid_as), style = ZillitTheme.typography.label, color = colors.textSecondary)
            ZillitSelect(
                value = form.paidAs,
                options = PaidAs.entries,
                onSelect = { value -> edit { copy(paidAs = value) } },
                label = { if (it == PaidAs.Percent) str(S.desktop_td_opt_percentage) else str(S.amount) },
                showInitials = false,
                searchable = false,
                modifier = Modifier.width(PAID_AS_WIDTH),
            )
            ZillitButton(
                text = str(S.desktop_td_add_tranche),
                leadingIcon = ZillitIcons.Add,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                onClick = { onEvent(TalentDealsEvent.AddTranche) },
            )
        }

        TrancheHeader(form.paidAs)
        form.tranches.forEachIndexed { index, tranche ->
            TrancheRow(index, tranche, labels, form.paidAs, state.symbol, edit)
        }

        AllocationBar(totals, state.symbol)
        if (TalentField.Schedule in page.shownErrors) FieldError(str(S.desktop_td_err_schedule))

        ZillitCheckbox(
            checked = form.autoTimeCards,
            onCheckedChange = { on -> edit { copy(autoTimeCards = on) } },
            label = str(S.desktop_td_chk_auto_time_cards),
        )
        if (page.exhibitApplies) {
            ZillitButton(
                text = if (state.exhibit.open) str(S.desktop_td_hide_exhibit) else str(S.desktop_td_view_exhibit),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(TalentDealsEvent.ToggleExhibit) },
            )
        }
    }
    if (page.exhibitApplies && state.exhibit.open) {
        ExhibitGPanel(
            performer = page.lookupName,
            character = page.role,
            fee = TalentNumbers.num(form.fee),
            weeks = TalentSchedule.scheduleWeeks(form.tranches),
            symbol = state.symbol,
            exhibit = state.exhibit,
            onEvent = onEvent,
            signatureLoader = signatureLoader,
        )
    }
}

@Composable
private fun TrancheHeader(paidAs: PaidAs) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        listOf(
            str(S.desktop_td_th_tranche) to LABEL_WEIGHT,
            str(S.desktop_csync_field_start_date) to DATE_WEIGHT,
            str(S.desktop_csync_field_end_date) to DATE_WEIGHT,
            str(S.dm_ds_unit_weeks) to WEEKS_WEIGHT,
            (if (paidAs == PaidAs.Percent) str(S.desktop_rate_pct) else str(S.amount)) to VALUE_WEIGHT,
        ).forEach { (title, weight) ->
            ZillitText(title.uppercase(), style = ZillitTheme.typography.columnHeader, color = colors.textMuted,
                maxLines = 1, modifier = Modifier.weight(weight))
        }
        Box(Modifier.width(REMOVE_WIDTH))
    }
}

/**
 * One tranche. Start, end and weeks describe the same span, so editing one
 * fills the others in rather than leaving the row contradicting itself.
 */
@Composable
private fun TrancheRow(
    index: Int,
    tranche: TrancheDraft,
    labels: List<String>,
    paidAs: PaidAs,
    symbol: String,
    edit: (TalentForm.() -> TalentForm) -> Unit,
) {
    fun patch(change: (TrancheDraft) -> TrancheDraft) = edit {
        copy(tranches = tranches.map { if (it.id == tranche.id) change(it) else it })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top) {
        LabelPicker(tranche.label, labels, Modifier.weight(LABEL_WEIGHT)) { label -> patch { it.copy(label = label) } }
        ZillitDateField(
            value = tranche.start,
            onValueChange = { value -> patch { TalentSchedule.withStart(it, value) } },
            modifier = Modifier.weight(DATE_WEIGHT),
        )
        ZillitDateField(
            value = tranche.end,
            onValueChange = { value -> patch { TalentSchedule.withEnd(it, value) } },
            modifier = Modifier.weight(DATE_WEIGHT),
        )
        ZillitTextField(
            value = tranche.weeks,
            onValueChange = { value -> patch { TalentSchedule.withWeeks(it, value) } },
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(WEEKS_WEIGHT),
        )
        // A percentage is a bare figure; an amount is money and gets the calculator field.
        Box(Modifier.weight(VALUE_WEIGHT)) {
            if (paidAs == PaidAs.Percent) {
                ZillitTextField(
                    value = tranche.value,
                    onValueChange = { value -> patch { it.copy(value = value) } },
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                )
            } else {
                MoneyInput(tranche.value, symbol, { value -> patch { it.copy(value = value) } })
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_td_remove_tranche, index + 1),
            onClick = { edit { copy(tranches = tranches.filter { it.id != tranche.id }) } },
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

/**
 * The stages a schedule is normally split across, plus whatever the rows are
 * using, with anything else typed in: a name matching nothing offers "Create".
 */
@Composable
internal fun LabelPicker(value: String, options: List<String>, modifier: Modifier = Modifier,
    onChange: (String) -> Unit) {
    val all = if (value.isNotBlank() && value !in options) options + value else options
    ZillitSearchSelect(
        value = value.takeIf { it.isNotBlank() },
        options = all,
        onSelect = onChange,
        label = { it },
        onCreate = onChange,
        placeholder = "— Select —",
        showInitials = false,
        modifier = modifier,
    )
}

/**
 * How much of the fee is allocated. The bar is the point: a schedule that
 * quietly adds up to 90% of the fee is the failure this table exists to make
 * visible.
 */
@Composable
private fun AllocationBar(totals: ScheduleTotals, symbol: String) {
    val colors = ZillitTheme.colors
    val fraction = if (totals.fee > 0) (totals.allocated / totals.fee).coerceIn(0.0, 1.0).toFloat() else 0f
    val tone = when {
        totals.over -> colors.danger
        totals.balanced -> colors.success
        else -> colors.accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).clip(RoundedCornerShape(BAR_HEIGHT)).background(colors.border)) {
            Box(Modifier.fillMaxWidth(fraction).height(BAR_HEIGHT).background(tone))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ZillitText(
                str(S.desktop_td_alloc_of, TalentNumbers.formatMoney(totals.allocated, symbol),
                    TalentNumbers.formatMoney(totals.fee, symbol)),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            ZillitText(
                text = when {
                    totals.balanced -> str(S.desktop_td_alloc_full)
                    totals.over -> str(S.desktop_inv_amount_over,
                        TalentNumbers.formatMoney(kotlin.math.abs(totals.remaining), symbol))
                    else -> str(S.desktop_td_note_unallocated, TalentNumbers.formatMoney(totals.remaining, symbol))
                },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (totals.balanced) colors.success else colors.textSecondary,
            )
        }
    }
}

private val WITHHOLDING_AS_WIDTH = 170.dp
private val WITHHOLDING_VALUE_WIDTH = 180.dp
private val AUTHORISED_WIDTH = 160.dp
private val CERTIFICATE_WIDTH = 320.dp
private val PAID_AS_WIDTH = 170.dp
private val REMOVE_WIDTH = 36.dp
private val BAR_HEIGHT = 8.dp
private const val LABEL_WEIGHT = 0.26f
private const val DATE_WEIGHT = 0.21f
private const val WEEKS_WEIGHT = 0.11f
private const val VALUE_WEIGHT = 0.21f
