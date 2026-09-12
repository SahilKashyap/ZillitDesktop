package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FxRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.FxPostState
import com.zillit.desktop.feature.bankrec.ui.components.AmountField
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrFigure
import com.zillit.desktop.feature.bankrec.ui.components.BrIconTile
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.CostCentreSelect
import com.zillit.desktop.feature.bankrec.ui.components.LockedDateField
import com.zillit.desktop.feature.bankrec.ui.components.NominalCodeField
import com.zillit.desktop.feature.bankrec.ui.components.TaxField
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.lockProblem
import com.zillit.desktop.feature.bankrec.ui.pages.currencyTag
import com.zillit.desktop.feature.bankrec.ui.pages.icon

/**
 * Posting a bank line the ledger has no entry for — the web's Quick Add.
 *
 * Seeded from the line: its date, its reference as the invoice number, the
 * type's own guidance as the description and the amount it moved. The
 * effective date may not fall on or before the cost report's lock.
 */
@Suppress("LongMethod") // A form: the web's Quick Add fields, in its order.
@Composable
internal fun ExceptionQuickAddDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val dialog = rememberLast(state.exceptionsPage.quickAdd) ?: return
    val exception = state.exceptions.firstOrNull { it.id == dialog.exceptionId }
    val form = dialog.form
    val lookups = state.lookups
    val currency = exception?.transaction?.currency ?: exception?.currency
        ?: state.currencyOf(state.period(exception?.periodId))
    val problem = lockProblem(form.effectiveDate, lookups.lockedThrough)
        ?: "Enter the amount to add.".takeIf { form.amountValue == null }
    fun edit(change: (QuickAddForm) -> QuickAddForm) = onEvent(BankRecEvent.EditExceptionQuickAdd(change(form)))

    ZillitDialogShell(
        title = "Quick Add to Zillit Ledger",
        onDismiss = { if (!dialog.saving) onEvent(BankRecEvent.CloseExceptionQuickAdd) },
        visible = state.exceptionsPage.quickAdd != null,
        icon = ZillitIcons.Ledger,
        width = 680.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BankRecEvent.CloseExceptionQuickAdd) },
                variant = ButtonVariant.Tertiary,
                enabled = !dialog.saving,
            )
            ZillitTooltip(problem.orEmpty()) {
                ZillitButton(
                    text = if (dialog.saving) "Adding…" else "Add Entry",
                    onClick = { onEvent(BankRecEvent.SubmitExceptionQuickAdd) },
                    leadingIcon = ZillitIcons.Add,
                    loading = dialog.saving,
                    enabled = problem == null && !dialog.saving,
                )
            }
        },
    ) {
        if (exception != null) {
            val shape = RoundedCornerShape(8.dp)
            val tone = if (exception.type == ExceptionType.Unknown) BrTone.Purple else BrTone.Red
            Row(
                Modifier.fillMaxWidth().clip(shape).background(tone.bg()).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrIconTile(exception.type.icon, tone, size = 26.dp)
                ZillitText(
                    exception.title.ifBlank { exception.type.label },
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
        FieldPair(
            left = {
                Field("Date") {
                    ZillitDateField(value = form.date, onValueChange = { v -> edit { it.copy(date = v) } })
                }
            },
            right = {
                Field("Invoice Number") {
                    ZillitTextField(
                        value = form.invoiceNumber,
                        onValueChange = { v -> edit { it.copy(invoiceNumber = v) } },
                        placeholder = "e.g. INV-2026-001",
                    )
                }
            },
        )
        Field("Description") {
            ZillitTextField(
                value = form.description,
                onValueChange = { v -> edit { it.copy(description = v) } },
                placeholder = "e.g. Bank charges Feb 25",
            )
        }
        FieldPair(
            left = {
                Field("Effective Date") {
                    LockedDateField(
                        value = form.effectiveDate,
                        onValueChange = { v -> edit { it.copy(effectiveDate = v) } },
                        lockedThrough = lookups.lockedThrough,
                    )
                }
            },
            right = {},
        )
        FieldPair(
            left = {
                Field("Amount") {
                    AmountField(
                        value = form.amount,
                        onValueChange = { v -> edit { it.copy(amount = v) } },
                        currency = currency,
                    )
                }
            },
            right = {
                Field("Tax") {
                    TaxField(form = form, options = lookups.taxTypes, onChange = { next -> edit { next } })
                }
            },
        )
        FieldPair(
            left = {
                Field("Nominal Code") {
                    NominalCodeField(
                        value = form.nominal,
                        onValueChange = { v -> edit { it.copy(nominal = v) } },
                        codes = lookups.nominalCodes,
                        placeholder = "e.g. 7600",
                    )
                }
            },
            right = {
                Field("Cost Centre") {
                    CostCentreSelect(
                        value = form.costCentre,
                        onSelect = { v -> edit { it.copy(costCentre = v) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

/**
 * Posting one FX variance.
 *
 * The amounts are recomputed from the rates in the form — foreign ÷ rate — so
 * the figures confirmed are the ones derived from what will be posted, not
 * the service's stored ones. A budget rate from Production Setup is shown
 * locked; only a missing one may be typed, and both are needed to post.
 */
@Suppress("LongMethod") // The web's post dialog, band by band.
@Composable
internal fun FxPostDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val post = rememberLast(state.fxPage.post) ?: return
    val row = state.fxVariances.firstOrNull { it.id == post.varianceId }
    val resolved = row?.let { FxRates.resolve(it, state.rates) }
    val budgetLocked = resolved?.budgetEditable == false
    ZillitDialogShell(
        title = "Post FX Variance",
        onDismiss = { if (!post.posting) onEvent(BankRecEvent.CloseFxPost) },
        visible = state.fxPage.post != null,
        icon = BankRecIcons.Swap,
        width = 560.dp,
        actions = {
            if (post.posted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitIcon(ZillitIcons.Check, tint = ZillitTheme.colors.success, size = 16.dp)
                    ZillitText(
                        "Posted successfully",
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.success,
                    )
                }
            } else {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(BankRecEvent.CloseFxPost) },
                    variant = ButtonVariant.Tertiary,
                    enabled = !post.posting,
                )
                ZillitTooltip(if (post.ready) "" else "Enter both rates to post") {
                    ZillitButton(
                        text = if (post.posting) "Posting…" else "Confirm & Post",
                        onClick = { onEvent(BankRecEvent.ConfirmFxPost) },
                        loading = post.posting,
                        enabled = post.ready && !post.posting,
                    )
                }
            }
        },
    ) {
        val code = state.projectCurrency
        val invoiceCurrency = row?.invoiceCurrency.orEmpty()
        FigureRow {
            BrFigure("Supplier", row?.supplierLabel ?: BankRecFormat.DASH, Modifier.weight(1f))
            BrFigure(
                "Reference",
                row?.reference?.ifBlank { null } ?: BankRecFormat.DASH,
                Modifier.weight(1f),
                valueStyle = mono(13.sp, FontWeight.SemiBold),
            )
            BrFigure("Currency", currencyTag(invoiceCurrency).ifBlank { BankRecFormat.DASH }, Modifier.weight(1f))
        }
        ZillitDivider()
        FigureRow {
            BrFigure(
                "Foreign Amount",
                row?.let { BankRecFormat.wholeMoney(it.foreignAmount, it.invoiceCurrency) } ?: BankRecFormat.DASH,
                Modifier.weight(1f),
                valueStyle = mono(14.sp, FontWeight.Bold),
            )
            Spacer(Modifier.weight(2f))
        }
        ZillitDivider()
        PostedFigures(post, row?.foreignAmount ?: 0.0, code)
        ZillitDivider()
        RateFields(post, invoiceCurrency, budgetLocked, onEvent)
        if (!post.ready) RateNeeded(post, invoiceCurrency, code)
        FieldPair(
            left = {
                Field("Nominal Code") {
                    NominalCodeField(
                        value = post.nominalCode,
                        onValueChange = { v -> onEvent(post.edit(nominalCode = v)) },
                        codes = state.lookups.nominalCodes,
                        placeholder = "e.g. 7850",
                    )
                }
            },
            right = {
                Field("Cost Centre") {
                    ZillitTextField(
                        value = post.costCentre,
                        onValueChange = { v -> onEvent(post.edit(costCentre = v)) },
                        placeholder = "e.g. PROD-001",
                    )
                }
            },
        )
    }
}

@Composable
private fun FigureRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Budget, paid and the variance between them, from the rates as typed. */
@Suppress("CyclomaticComplexMethod") // Each figure is blank until its rate is.
@Composable
private fun PostedFigures(post: FxPostState, foreign: Double, code: String) {
    val colors = ZillitTheme.colors
    val budget = FxRates.converted(foreign, post.budgetRateValue.takeIf { it > 0 })
    val paid = FxRates.converted(foreign, post.bankRateValue.takeIf { it > 0 })
    val variance = if (budget != null && paid != null) budget - paid else null
    FigureRow {
        BrFigure(
            "Budget $code",
            budget?.let { BankRecFormat.money(it, code) } ?: "-",
            Modifier.weight(1f),
            valueStyle = mono(13.sp, FontWeight.SemiBold),
        )
        BrFigure(
            "$code Paid",
            paid?.let { BankRecFormat.money(it, code) } ?: "-",
            Modifier.weight(1f),
            valueStyle = mono(13.sp, FontWeight.Bold),
        )
        BrFigure(
            "FX ${when { variance == null -> "Variance"; variance >= 0 -> "Gain"; else -> "Loss" }}",
            variance?.let { BankRecFormat.signedMoney(it, code) } ?: "-",
            Modifier.weight(1f),
            valueStyle = mono(13.sp, FontWeight.Bold),
            valueColor = when {
                variance == null -> colors.textMuted
                variance >= 0 -> colors.success
                else -> colors.danger
            },
        )
    }
}

@Composable
private fun RateFields(
    post: FxPostState,
    invoiceCurrency: String,
    budgetLocked: Boolean,
    onEvent: (BankRecEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    FieldPair(
        left = {
            Field("Budget Rate") {
                if (budgetLocked) {
                    val shape = ZillitTheme.shapes.medium
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(shape).background(colors.surfaceSunken)
                            .border(1.dp, colors.border, shape).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            BankRecFormat.rate(post.budgetRateValue.takeIf { it > 0 }),
                            style = mono(13.sp),
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            "Production Setup",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                } else {
                    RateInput(post.budgetRate, "e.g. 1.1650") { onEvent(post.edit(budgetRate = it)) }
                    ZillitText(
                        "${invoiceCurrency.ifBlank { "This currency" }} isn’t in Production Setup → " +
                            "Project Currencies. Enter its rate to post.",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        },
        right = {
            Field("Bank Rate") {
                RateInput(post.bankRate, "e.g. 1.2010") { onEvent(post.edit(bankRate = it)) }
            }
        },
    )
}

/** A rate as typed: digits and one point. Amber while it is still missing. */
@Composable
private fun RateInput(value: String, placeholder: String, onChange: (String) -> Unit) {
    val missing = (value.trim().toDoubleOrNull() ?: 0.0) <= 0
    ZillitTextField(
        value = value,
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() || it == '.' }
            val firstPoint = cleaned.indexOf('.')
            onChange(
                if (firstPoint < 0) cleaned else cleaned.take(firstPoint + 1) + cleaned.drop(firstPoint + 1).replace(
                    ".",
                    "",
                ),
            )
        },
        placeholder = placeholder,
        keyboardType = KeyboardType.Decimal,
        containerColor = if (missing) ZillitTheme.colors.warningSoft else null,
    )
}

/** Why the post is unavailable, naming which rate is missing. */
@Composable
private fun RateNeeded(post: FxPostState, invoiceCurrency: String, code: String) {
    val parts = buildList {
        if (post.budgetRateValue <= 0) {
            add(
                "No budget rate for ${invoiceCurrency.ifBlank { "this currency" }} in Production Setup → Project " +
                    "Currencies. Enter it here to post this variance (it is saved with the posting).",
            )
        }
        if (post.bankRateValue <= 0) add("No bank rate on this transaction — enter the rate the bank actually used.")
        add("Both rates are required: the variance is $code at budget minus $code actually paid.")
    }
    BrBanner(
        tone = BrTone.Amber,
        icon = ZillitIcons.Warning,
        title = "Rate needed",
        message = parts.joinToString(" "),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** This dialog's edit, with the fields not named left as they are. */
private fun FxPostState.edit(
    nominalCode: String = this.nominalCode,
    costCentre: String = this.costCentre,
    budgetRate: String = this.budgetRate,
    bankRate: String = this.bankRate,
) = BankRecEvent.EditFxPost(nominalCode, costCentre, budgetRate, bankRate)
