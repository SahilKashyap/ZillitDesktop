package com.zillit.desktop.feature.bankrec.ui.pages.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.FxRates
import com.zillit.desktop.feature.bankrec.domain.QuickEntryType
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.QuickEntryState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceView
import com.zillit.desktop.feature.bankrec.ui.components.AmountField
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrFieldLabel
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.CostCentreSelect
import com.zillit.desktop.feature.bankrec.ui.components.LockedDateField
import com.zillit.desktop.feature.bankrec.ui.components.NominalCodeField
import com.zillit.desktop.feature.bankrec.ui.components.TaxField
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle

/** The foreign currencies the FX form offers — the web's own short list. */
private val FX_CURRENCIES = listOf("EUR", "USD", "CAD", "AUD", "CHF", "JPY")

/**
 * The quick-entry drawer: a tile per kind of entry, the form for it, and the
 * lines still unmatched under it.
 *
 * Every figure here is bank-side money, so it is in the statement's currency.
 */
@Composable
internal fun QuickEntryPanel(
    state: BankRecUiState,
    view: WorkspaceView,
    onEvent: (BankRecEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val entry = state.workspace.quickEntry
    Row(modifier.background(colors.surface)) {
        ZillitVerticalDivider()
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(str(S.desktop_br_quick_entry), style = titleStyle(13.5.sp), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(ZillitIcons.Add, tint = colors.textOnAccent, size = 13.dp)
                }
            }
            ZillitDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                TypeTiles(entry.type, onEvent)
                ZillitDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entry.type == QuickEntryType.FxPayment) {
                        FxForm(state, view, entry, onEvent)
                    } else {
                        GeneralForm(state, view, entry, onEvent)
                    }
                }
            }
            ZillitDivider()
            StillUnmatched(view)
        }
    }
}

@Composable
private fun TypeTiles(active: QuickEntryType, onEvent: (BankRecEvent) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickEntryType.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { type ->
                    TypeTile(type, active == type, Modifier.weight(1f)) {
                        onEvent(BankRecEvent.SetQuickEntryType(type))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTile(type: QuickEntryType, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val tone = when (type) {
        QuickEntryType.FxPayment -> BrTone.Teal
        QuickEntryType.FraudFlag -> BrTone.Red
        else -> null
    }
    val border by animateColorAsState(if (active) colors.accent else tone?.edge() ?: colors.border)
    val background by animateColorAsState(if (active) colors.accentSoft else tone?.bg() ?: colors.surfaceSunken)
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(background)
            .border(if (active) 1.5.dp else 1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitIcon(
            type.icon,
            tint = if (active) colors.accentText else tone?.fg() ?: colors.textSecondary,
            size = 15.dp,
        )
        ZillitText(
            type.label,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (active) colors.textPrimary else tone?.fg() ?: colors.textSecondary,
            maxLines = 1,
        )
    }
}

private val QuickEntryType.icon: ImageVector
    get() = when (this) {
        QuickEntryType.BankCharge -> ZillitIcons.Bank
        QuickEntryType.TaxReturn -> ZillitIcons.BarChart
        QuickEntryType.CardSettle -> ZillitIcons.CreditCard
        QuickEntryType.Payroll -> ZillitIcons.Users
        QuickEntryType.FxPayment -> BankRecIcons.Swap
        QuickEntryType.FraudFlag -> ZillitIcons.Warning
        QuickEntryType.Interest -> BankRecIcons.Rise
        QuickEntryType.Other -> ZillitIcons.MoreHorizontal
    }

/**
 * The general form — posts through the line's exception and matches it.
 *
 * "Add & Match" needs a line to add, and an amount; the web's own rule. The
 * fraud details under a Fraud Flag tile are the web's too, and like the web's
 * they are not sent anywhere: the service has no field for them.
 */
@Suppress("LongMethod") // A form: the drawer's fields, in the web's order.
@Composable
private fun ColumnScope.GeneralForm(
    state: BankRecUiState,
    view: WorkspaceView,
    entry: QuickEntryState,
    onEvent: (BankRecEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val form = entry.form
    val adding = view.bankRow(entry.transactionId)
    if (adding != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.infoSoft)
                .border(1.dp, BrTone.Blue.edge(), RoundedCornerShape(8.dp)).padding(
                    start = 10.dp,
                    end = 4.dp,
                    top = 5.dp,
                    bottom = 5.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    str(S.desktop_br_quick_adding),
                    style = mono(9.5.sp, FontWeight.SemiBold),
                    color = colors.info,
                )
                ZillitText(
                    adding.title,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_br_stop_quick_adding),
                onClick = { onEvent(BankRecEvent.ClearQuickAdd) },
            )
        }
    }
    Field(str(S.ah_lbl_eff_date)) {
        LockedDateField(
            value = form.effectiveDate,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntry(form.copy(effectiveDate = it))) },
            lockedThrough = state.lookups.lockedThrough,
        )
    }
    Field(str(S.description)) {
        ZillitTextField(
            value = form.description,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntry(form.copy(description = it))) },
            placeholder = str(S.desktop_br_description_hint),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Field(str(S.amount)) {
        AmountField(
            value = form.amount,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntry(form.copy(amount = it))) },
            currency = view.statementCurrency,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Field(str(S.ah_lbl_vat)) {
        TaxField(form, state.lookups.taxTypes, onChange = { onEvent(BankRecEvent.EditQuickEntry(it)) })
    }
    Field(str(S.dm_allow_nominal)) {
        NominalCodeField(
            value = form.nominal,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntry(form.copy(nominal = it))) },
            codes = state.lookups.nominalCodes,
            placeholder = str(S.desktop_br_select_nominal),
        )
    }
    Field(str(S.desktop_cost_centre)) {
        CostCentreSelect(
            value = form.costCentre,
            onSelect = { onEvent(BankRecEvent.EditQuickEntry(form.copy(costCentre = it))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (entry.type == QuickEntryType.FraudFlag) FraudDetails(entry, onEvent)
    ZillitButton(
        text = if (entry.adding) str(S.desktop_adding) else str(S.desktop_br_add_and_match),
        onClick = { onEvent(BankRecEvent.AddAndMatch) },
        size = ButtonSize.Small,
        loading = entry.adding,
        enabled = !entry.adding && adding?.txn?.exceptionId?.isNotBlank() == true && form.amount.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (adding == null) {
        ZillitText(
            str(S.desktop_br_choose_quick_add_hint),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    } else if (adding.txn.exceptionId.isBlank()) {
        ZillitText(
            str(S.desktop_br_no_exception_use_manual),
            style = ZillitTheme.typography.labelSmall,
            color = colors.warning,
        )
    }
}

@Composable
private fun FraudDetails(entry: QuickEntryState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BrTone.Red.bg())
            .border(1.dp, BrTone.Red.edge(), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(
            str(S.desktop_br_fraud_flag_details),
            style = mono(9.5.sp, FontWeight.SemiBold),
            color = colors.danger,
        )
        ZillitTextField(
            value = entry.fraudReason,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntryFraud(it, entry.fraudPriority)) },
            placeholder = str(S.desktop_br_describe_fraud_concern),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitSelect(
            value = entry.fraudPriority,
            options = listOf("High", "Medium", "Low"),
            onSelect = { onEvent(BankRecEvent.EditQuickEntryFraud(entry.fraudReason, it)) },
            label = { priority ->
                when (priority) {
                    "Medium" -> str(S.medium)
                    "Low" -> str(S.desktop_weather_uv_low)
                    else -> str(S.desktop_weather_uv_high)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The FX form: the foreign amount, the budget rate as Production Setup has it,
 * the bank's rate, and what each makes of the amount — then "Post Variance"
 * when the line carries a variance to post.
 */
@Suppress("LongMethod") // A form: the FX drawer's fields, in the web's order.
@Composable
private fun ColumnScope.FxForm(
    state: BankRecUiState,
    view: WorkspaceView,
    entry: QuickEntryState,
    onEvent: (BankRecEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val foreign = entry.fxForeignAmount.toDoubleOrNull() ?: 0.0
    val budget = entry.fxBudgetRate.toDoubleOrNull()
    val bank = entry.fxBankRate.toDoubleOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(str(S.asset_currency), Modifier.weight(1f)) {
            ZillitSelect(
                value = entry.fxCurrency,
                options = (FX_CURRENCIES + entry.fxCurrency).distinct(),
                onSelect = { onEvent(BankRecEvent.EditFxEntry(it, entry.fxForeignAmount, entry.fxBankRate)) },
                label = { it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field(str(S.desktop_br_amount_in_currency, entry.fxCurrency), Modifier.weight(1f)) {
            AmountField(
                value = entry.fxForeignAmount,
                onValueChange = { onEvent(BankRecEvent.EditFxEntry(entry.fxCurrency, it, entry.fxBankRate)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(str(S.desktop_budget_rate), Modifier.weight(1f)) {
            ZillitTextField(
                value = entry.fxBudgetRate,
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field(str(S.desktop_bank_rate), Modifier.weight(1f)) {
            ZillitTextField(
                value = entry.fxBankRate,
                onValueChange = { onEvent(BankRecEvent.EditFxEntry(entry.fxCurrency, entry.fxForeignAmount, it)) },
                placeholder = "1.0000",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(str(S.desktop_br_booked_at_budget), Modifier.weight(1f)) {
            ReadOnlyFigure(
                BankRecFormat.plainMoney(FxRates.converted(foreign, budget) ?: 0.0, view.statementCurrency),
                emphasised = false,
            )
        }
        Field(str(S.desktop_br_actually_paid), Modifier.weight(1f)) {
            ReadOnlyFigure(
                BankRecFormat.plainMoney(FxRates.converted(foreign, bank) ?: 0.0, view.statementCurrency),
                emphasised = true,
            )
        }
    }
    Field(str(S.dm_allow_nominal)) {
        NominalCodeField(
            value = entry.form.nominal,
            onValueChange = { onEvent(BankRecEvent.EditQuickEntry(entry.form.copy(nominal = it))) },
            codes = state.lookups.nominalCodes,
            placeholder = str(S.desktop_br_select_nominal),
        )
    }
    Field(str(S.desktop_cost_centre)) {
        CostCentreSelect(
            value = entry.form.costCentre,
            onSelect = { onEvent(BankRecEvent.EditQuickEntry(entry.form.copy(costCentre = it))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val fx = entry.fx
    when {
        fx == null || fx.varianceId.isBlank() -> ZillitText(
            str(S.desktop_br_choose_post_hint),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )

        entry.fxPosted -> PostedNote(str(S.ah_status_posted))
        fx.isPosted -> PostedNote(str(S.desktop_br_already_posted))
        else -> ZillitButton(
            text = if (entry.fxPosting) str(S.txt_posting) else str(S.desktop_br_post_variance),
            onClick = { onEvent(BankRecEvent.PostWorkspaceFx) },
            size = ButtonSize.Small,
            loading = entry.fxPosting,
            enabled = !entry.fxPosting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PostedNote(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(ZillitIcons.Check, tint = ZillitTheme.colors.success, size = 13.dp)
        ZillitText(
            text,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.success,
        )
    }
}

@Composable
private fun ReadOnlyFigure(text: String, emphasised: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(ZillitTheme.shapes.medium).background(colors.surfaceHover)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text,
            style = mono(12.sp, if (emphasised) FontWeight.SemiBold else FontWeight.Normal),
            color = if (emphasised) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BrFieldLabel(label)
        content()
    }
}

/** The lines on the statement still unmatched — what is left to do, always in view. */
@Composable
private fun StillUnmatched(view: WorkspaceView) {
    val colors = ZillitTheme.colors
    val unmatched = view.bankRows.filter { it.status == TxnStatus.Unmatched }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrFieldLabel(str(S.desktop_br_still_unmatched))
        if (unmatched.isEmpty()) {
            ZillitText(
                str(S.desktop_br_no_unmatched_transactions),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            return@Column
        }
        Column(
            Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            unmatched.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(BrTone.Red.bg())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        row.title,
                        style = ZillitTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        BankRecFormat.signedMoney(row.amount, row.amountCurrency ?: view.statementCurrency),
                        style = mono(10.5.sp),
                        color = colors.danger,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
