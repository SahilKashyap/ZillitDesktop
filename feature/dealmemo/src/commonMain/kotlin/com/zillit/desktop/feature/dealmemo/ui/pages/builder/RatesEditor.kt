@file:Suppress("MatchingDeclarationName") // The rates editor; RatesView is only its derived state.

package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DgaFee
import com.zillit.desktop.feature.dealmemo.domain.authoring.EffectiveRates
import com.zillit.desktop.feature.dealmemo.domain.authoring.HolidayPayItem
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateResolve
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateTables
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleSections
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the Rates editor reads, worked out once per change of the form or the references under it. */
internal class RatesView(state: DealMemoUiState, builder: BuilderState) {
    val form: DealForm = builder.form
    val agreement: JsonObject? = builder.reference.selectedUnionForSteps
    val resolvedRates: List<JsonObject> = builder.reference.resolvedRates
    val resolvedRate: JsonObject? = builder.reference.resolvedRate
    val loading: Boolean = builder.reference.rateLoading
    val agreementLoading: Boolean = builder.reference.agreementLoading
    val chosen: JsonObject? = RateResolve.chosenRate(resolvedRates, form)
    val effective: EffectiveRates = RateResolve.effective(chosen, agreement)
    val sym: String = RateFormat.currencySymbol(form.text("currency"))
    val dayRate: Double = RateResolve.number(form["dayRate"])
    val nonUnion: Boolean = isNonUnionId(form.text("union"))
    val missingBand: Boolean = RateResolve.missingBand(form, agreement)
    val otLabel: String = RateTables.agreementName(form.text("union"), agreement)
    val designationName: String? = RateTables.designationName(form, state.catalogue)
    val fringe: JsonObject? = RateTables.fringePackage(agreement, form.text("employmentStatus"))
    val holidayPay: HolidayPayItem? = RateTables.holidayPay(fringe)
    val hpMode: String = form.text("hpMode")
    val sections: RuleSections =
        RateTables.ruleSections(form, agreement, state.projectSettings.view.nonUnionPaybreakdown)
    val dga: DgaFee? = RateTables.dgaFee(form, agreement)
    val settings: ProjectSettingsView = state.projectSettings.view

    private val tierCurrency =
        agreement?.get("currency")?.takeUnless(Js::isNullish)?.let(Js::text) ?: form.text("currency")
    val hourlyText: String? = RateFormat.formatTier(effective.hourly?.entry, tierCurrency)
    val dailyText: String? = RateFormat.formatTier(effective.daily?.entry, tierCurrency)
    val weeklyText: String? = RateFormat.formatTier(effective.weekly?.entry, tierCurrency)

    /** `Short label · Band X · Designation`. */
    val scaleLabel: String = listOfNotNull(
        agreement?.get("short_label")?.takeIf(Js::truthy)?.let(Js::text),
        form.text("pactBand").takeIf { it.isNotEmpty() }?.let { str(S.desktop_dm_band_x, it.uppercase()) },
        designationName,
    ).joinToString(" · ")
}

/**
 * Rates & Compensation (`Step5Rates.jsx`): the agreed pay for the deal type —
 * a picture fee, a buy-out rate, or day and weekly rates against the
 * published scale — and the agreement's reference tables beneath.
 */
@Composable
internal fun RatesEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val view = remember(builder.form, builder.reference, state.catalogue, state.projectSettings) {
        RatesView(state, builder)
    }
    val dealType = view.form.text("dealType")
    when (dealType) {
        "picture" -> PictureFee(view, ops)
        "buyout" -> BuyOutRate(view, ops)
        else -> {
            RateEntryCard(view, ops, onEvent)
            RateSummaryCard(view)
            view.dga?.let { DgaCard(view, it, ops) }
            OvertimeCard(builder, view, onEvent)
        }
    }
    view.fringe?.let { FringesCard(view, it) }
    if (dealType != "picture" && dealType != "buyout") {
        RateTables.agreementAllowances(view.agreement)?.let { AllowancesCard(view, it) }
    }
    RateConditionsCard(view.form, ops)
}

// -- picture and buy-out ----------------------------------------------------------------------

@Composable
private fun PictureFee(view: RatesView, ops: FormOps) {
    val p = bp
    BuilderAlert(
        text = bold(
            str(S.desktop_dm_deal_type_picture_deal),
            " " + str(S.desktop_dm_picture_deal_note),
        ),
        modifier = Modifier.padding(bottom = 16.dp),
    )
    CardBlock(title = str(S.dm_rates_card_picture)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ZillitText(
                text = str(S.dm_rates_picture_fee),
                style = DmType.display(11.sp, FontWeight.Bold, 0.4.sp),
                color = p.ink2,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PictureFeeInput(view.form["pictureFee"]) { ops.set("pictureFee", it) }
            ZillitText(
                text = str(S.desktop_dm_payable_across_the_full_production_schedule),
                style = DmType.sans(11.sp),
                color = p.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The all-in fee: centred on an amber underline. */
@Composable
private fun PictureFeeInput(value: JsonElement?, onCommit: (JsonElement) -> Unit) {
    val p = bp
    val calc = rememberCalcState(onCommit)
    val focus = LocalFocusManager.current
    val style = DmType.mono(20.sp, FontWeight.Medium).copy(color = p.cta, textAlign = TextAlign.Center)
    val shown = calc.shown(value)
    Column(Modifier.width(208.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        p.cta,
                        Offset(0f, size.height - stroke / 2),
                        Offset(size.width, size.height - stroke / 2),
                        stroke,
                    )
                }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (shown.isEmpty()) ZillitText(text = "0.00", style = style.copy(color = p.placeholder))
            BasicTextField(
                value = shown,
                onValueChange = calc::type,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(p.cta),
                visualTransformation = calc.visualTransformation,
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) calc.leave() },
            )
        }
        CalcPreview(calc)
    }
}

@Suppress("LongMethod")
@Composable
private fun BuyOutRate(view: RatesView, ops: FormOps) {
    val form = view.form
    val mode = form.text("buyoutRateMode").ifEmpty { "weekly" }
    BuilderAlert(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.desktop_dm_buy_out_deal)) }
            append(" " + str(S.desktop_dm_buyout_deal_note) + " ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(str(S.desktop_dm_no_overtime_will_be_generated))
            }
        },
        modifier = Modifier.padding(bottom = 16.dp),
    )
    CardBlock(title = str(S.dm_rates_buyout_rate)) {
        BuilderGrid(columns = 3) {
            cell {
                Field(str(S.dm_step2_bank_currency), hint = LOCKED_CURRENCY) {
                    CurrencySelect(view.settings, form.text("currency"), form, enabled = false) {}
                }
            }
            cell {
                Field(str(S.dm_rates_buyout_basis), hint = str(S.dm_rates_buyout_basis_helper)) {
                    NativeSelect(
                        value = mode,
                        options = BUYOUT_MODES,
                        onPick = { next ->
                            // The mode is rebuilt from the saved rates on load, so the rate it hides is cleared.
                            ops.edit { current ->
                                current.with(
                                    mapOf(
                                        "buyoutRateMode" to JsonPrimitive(next),
                                        "buyoutRate" to if (next == "daily") {
                                            JsonPrimitive("")
                                        } else {
                                            current["buyoutRate"] ?: JsonPrimitive("")
                                        },
                                        "buyoutDailyRate" to if (next == "weekly") {
                                            JsonPrimitive("")
                                        } else {
                                            current["buyoutDailyRate"] ?: JsonPrimitive("")
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            if (mode != "daily") {
                cell {
                    Field(str(S.dm_rates_buyout_rate_weekly), hint = str(S.dm_rates_buyout_rate_helper)) {
                        CalcInput(form["buyoutRate"], { ops.set("buyoutRate", it) })
                    }
                }
            }
            if (mode != "weekly") {
                cell {
                    Field(str(S.dm_rates_buyout_daily_rate), hint = str(S.dm_rates_buyout_rate_helper)) {
                        CalcInput(form["buyoutDailyRate"], { ops.set("buyoutDailyRate", it) })
                    }
                }
            }
            cell {
                Field(str(S.dm_rates_buyout_covers)) {
                    NativeSelect(
                        value = form.text("buyoutCovers").ifEmpty { "10" },
                        options = COVERS,
                        onPick = { ops.set("buyoutCovers", it) },
                    )
                }
            }
        }
    }
}

// -- rate entry -------------------------------------------------------------------------------

@Composable
private fun RateEntryCard(view: RatesView, ops: FormOps, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val form = view.form
    CardBlock(
        title = str(S.dm_rates_card_rate_entry),
        headerTrailing = {
            ZillitText(text = str(S.dm_rates_card_rate_entry_hint), style = DmType.sans(11.sp), color = p.ink2)
            BuilderSwitch(
                checked = form.flag("phaseRatesOn"),
                onChange = { ops.set("phaseRatesOn", !form.flag("phaseRatesOn")) },
            )
        },
    ) {
        LoadingCover(
            view.loading,
            str(S.desktop_dm_resolving_rate_for, view.designationName ?: str(S.desktop_dm_designation_fallback_word)),
        ) {
            Column {
                CurrencyRow(view, ops)
                val payment = form.text("paymentCurrency")
                if (payment.isNotEmpty() && payment != form.text("currency")) {
                    BuilderAlert(
                        text = str(S.dm_rates_fx_mismatch_warning),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                ScaleBox(view)
                ScaleState(view)
                ScheduleSelector(view, ops)
                AgreedRates(view, onEvent)
                BasicHours(view, ops)
                if (RateResolve.isManual(form)) ManualOverride(onEvent)
                ConstraintsBar(view)
                if (form.flag("phaseRatesOn")) PhaseRates(view, ops)
                view.holidayPay?.let { HolidayPayBlock(view, it, ops) }
            }
        }
    }
}

@Composable
private fun CurrencyRow(view: RatesView, ops: FormOps) {
    val form = view.form
    BuilderGrid(columns = 2, modifier = Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        cell {
            Column {
                FieldLabel(str(S.dm_rates_currency))
                CurrencySelect(view.settings, form.text("currency"), form, enabled = false) {}
                HintText(LOCKED_CURRENCY, Modifier.padding(top = 6.dp))
            }
        }
        cell {
            Column {
                FieldLabel(str(S.dm_rates_payment_currency))
                CurrencySelect(view.settings, form.text("paymentCurrency"), form, enabled = true) {
                    ops.set("paymentCurrency", it.orEmpty())
                }
                HintText(str(S.dm_rates_payment_currency_hint), Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The project's currencies — the default and the form's own always among them — as `GBP £ — British Pound`. */
@Composable
private fun CurrencySelect(
    settings: ProjectSettingsView,
    value: String,
    form: DealForm,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    val options = remember(settings, form.text("currency"), form.text("paymentCurrency")) {
        currencyOptions(settings, listOf(form.text("currency"), form.text("paymentCurrency")))
    }
    val selected = value.ifEmpty { settings.defaultCurrency.orEmpty() }
    RichSelect(
        options = options,
        selectedKey = selected.ifEmpty { null },
        onPick = onPick,
        placeholder = str(S.desktop_dm_select_project_currency),
        enabled = enabled,
        clearable = enabled,
        dropdownWidth = 300.dp,
        triggerText = options.firstOrNull { it.key == selected }?.sub,
        rowLeading = { option ->
            CurrencyMonogram(RateFormat.currencySymbol(option.key).ifEmpty { option.key.take(1) })
        },
    )
}

@Composable
private fun CurrencyMonogram(text: String) {
    val p = bp
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(p.tile),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text.trim(), style = DmType.sans(13.sp, FontWeight.Bold), color = p.ink2, maxLines = 1) }
}

/** The published scale: the agreement, band and role, the card's status, and each tier's figure. */
// One @Suppress, not two: a second annotation of the same type is ignored,
// which is why the length finding survived the first attempt.
@Suppress("CyclomaticComplexMethod", "LongMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleBox(view: RatesView) {
    val p = bp
    val status = view.effective.status?.takeIf { it.isNotEmpty() }
    val dayType = view.effective.dayType?.takeIf { it.isNotEmpty() }
    val notes = view.resolvedRate?.get("notes")?.takeIf(Js::truthy)?.let(Js::text)
    val anyTier = view.hourlyText != null || view.dailyText != null || view.weeklyText != null
    if (!anyTier && listOf(status, notes, dayType).all { it == null }) return
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.scaleBg)
            .border(1.dp, p.scaleBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        FlowRow(
            modifier = Modifier.padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitText(
                text = str(S.dm_rates_union_scale),
                style = DmType.display(12.sp, FontWeight.Bold),
                color = p.gold,
            )
            if (view.scaleLabel.isNotEmpty()) {
                ZillitText(text = view.scaleLabel, style = DmType.sans(12.sp), color = p.muted)
            }
            status?.let { StatusBadge(it) }
            dayType?.let { ZillitText(text = it, style = DmType.sans(12.sp), color = p.muted) }
            if (view.effective.usedUnionFallback) {
                ZillitText(
                    text = if (view.resolvedRate != null) str(S.desktop_dm_union_default_fills_gaps) else str(
                        S.desktop_dm_from_union_defaults,
                    ),
                    style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
                    color = p.muted,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            view.hourlyText?.let { TierFigure(str(S.desktop_dm_hourly), it, null) }
            view.dailyText?.let { TierFigure(str(S.dm_rates_buyout_mode_daily), it, view.effective.daily?.hours) }
            view.weeklyText?.let { TierFigure(str(S.dm_rates_buyout_mode_weekly), it, view.effective.weekly?.hours) }
            if (!anyTier) {
                ZillitText(
                    text = str(S.dm_rates_no_rate_inline),
                    style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
                    color = p.muted,
                )
            }
        }
        notes?.let {
            ZillitText(
                text = it,
                style = DmType.sans(12.sp).copy(lineHeight = 19.sp),
                color = p.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TierFigure(label: String, figure: String, hours: JsonElement?) {
    val p = bp
    ZillitText(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = p.muted)) { append("$label ") }
            withStyle(
                SpanStyle(fontFamily = DmType.mono(12.sp).fontFamily, fontWeight = FontWeight.SemiBold, color = p.ink),
            ) {
                append(figure)
            }
            hours?.let { withStyle(SpanStyle(color = p.muted)) { append(" / ${Js.text(it)}h") } }
        },
        style = DmType.sans(12.sp),
        color = p.ink,
    )
}

/** `StatusBadge`: agreed green, recommended blue, negotiated amber, scale purple, anything else grey. */
@Composable
private fun StatusBadge(status: String) {
    val tone = when (status) {
        "agreed" -> BuilderTone.Green
        "recommended" -> BuilderTone.Blue
        "negotiated" -> BuilderTone.Gold
        "scale" -> BuilderTone.Purple
        else -> BuilderTone.Dim
    }
    val dark = ZillitTheme.colors.isDark
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.wash?.copy(alpha = TAG_WASH_ALPHA) ?: bp.tile)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text = status,
            style = DmType.mono(12.sp, FontWeight.Bold),
            color = if (dark) tone.dark else tone.light,
            maxLines = 1,
        )
    }
}

/** Band missing, the rate still resolving, or nothing published for the role. */
@Suppress("CyclomaticComplexMethod")
@Composable
private fun ScaleState(view: RatesView) {
    val p = bp
    val form = view.form
    val designation = form.flag("designation")
    when {
        view.missingBand && designation -> BuilderAlert(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.desktop_dm_budget_band_required)) }
                append(" " + str(S.desktop_dm_budget_band_required_note, view.otLabel))
            },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        view.loading && view.resolvedRate == null && !view.missingBand -> Row(
            modifier = Modifier.padding(bottom = 12.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitSpinner(size = 12.dp, color = p.cta)
            ZillitText(text = str(S.desktop_dm_resolving_scale_rate), style = DmType.sans(11.sp), color = p.muted)
        }
        !view.loading && view.resolvedRate == null && designation && !view.missingBand &&
            view.hourlyText == null && view.dailyText == null && view.weeklyText == null &&
            view.effective.status.isNullOrEmpty() && view.effective.dayType.isNullOrEmpty() -> {
            val band = form.text("pactBand").takeIf { it.isNotEmpty() }
                ?.let { " " + str(S.desktop_dm_band_suffix, it.uppercase()) }
                .orEmpty()
            ZillitText(
                text = str(S.desktop_dm_no_rate_under_label, view.otLabel + band),
                style = DmType.sans(11.sp).copy(fontStyle = FontStyle.Italic),
                color = p.muted,
                modifier = Modifier.padding(bottom = 12.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Several schedules on the card for the role: the one picked drives the rates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSelector(view: RatesView, ops: FormOps) {
    if (view.resolvedRates.size <= 1) return
    val p = bp
    Column(Modifier.padding(bottom = 14.dp)) {
        ZillitText(
            text = str(S.desktop_dm_work_schedule),
            style = DmType.sans(10.sp, FontWeight.Bold, 0.05.em),
            color = p.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.resolvedRates.forEach { rate ->
                val active = rate === view.chosen
                val (source, hovered) = rememberHover()
                val shape = RoundedCornerShape(6.dp)
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (active) p.scheduleActive else p.inputBg)
                        .border(
                            1.dp,
                            when {
                                active -> p.scheduleActive
                                hovered -> p.brand.copy(alpha = HOVER_BORDER_ALPHA)
                                else -> p.cardBorder
                            },
                            shape,
                        )
                        .hoverable(source)
                        .clickable(interactionSource = source, indication = null) {
                            ops.set(
                                "selectedScheduleKey",
                                rate["schedule_key"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
                            )
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    ZillitText(
                        text = RateResolve.scheduleLabel(rate),
                        style = DmType.sans(11.sp, FontWeight.Medium),
                        color = if (active) Color.White else p.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Day and weekly rates, each deriving the other through the scale's days per week. */
@Composable
private fun AgreedRates(view: RatesView, onEvent: (DealMemoEvent) -> Unit) {
    val form = view.form
    val days = Js.number(view.effective.daysPerWeek)
    BuilderGrid(columns = 2, modifier = Modifier.padding(bottom = 14.dp)) {
        cell {
            Column {
                RateLabel(
                    str(S.desktop_dm_day_rate_agreed),
                    view.effective.daily?.hours,
                    str(S.desktop_dm_weekly_auto_calculates, days),
                )
                CalcInput(
                    value = form["dayRate"],
                    onCommit = { onEvent(BuilderEvent.DayRate(it)) },
                    placeholder = view.effective.daily?.min?.let { str(S.desktop_dm_eg_value, Js.text(it)) } ?: str(
                        S.dm_rates_day_rate_hint,
                    ),
                )
            }
        }
        cell {
            Column {
                RateLabel(
                    str(S.dm_rates_weekly_rate),
                    view.effective.weekly?.hours,
                    str(S.desktop_dm_daily_auto_calculates, days),
                )
                CalcInput(
                    value = form["weeklyRate"],
                    onCommit = { onEvent(BuilderEvent.WeeklyRate(it)) },
                    placeholder = view.effective.weekly?.min?.let { str(S.desktop_dm_eg_value, Js.text(it)) } ?: str(
                        S.dm_rates_weekly_rate_hint,
                    ),
                )
            }
        }
    }
}

/** A required rate label carrying the tier's hours and how its partner follows. */
@Composable
private fun RateLabel(text: String, hours: JsonElement?, note: String) {
    val p = bp
    val deal = LocalDealLabels.current
    fun cased(value: String) = if (deal) value.uppercase() else value
    ZillitText(
        text = buildAnnotatedString {
            append(if (deal) text.uppercase() else capitalizeWords(text))
            withStyle(SpanStyle(color = p.cta)) { append(" *") }
            withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = p.muted)) {
                hours?.let { append("  ${cased("/ ${Js.text(it)}h")}") }
                append("  ${cased(note)}")
            }
        },
        style = DmType.sans(if (deal) 12.5.sp else 14.sp, FontWeight.SemiBold, 0.04.em),
        color = p.ink2,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

/** Hours per day, asked only when a union scale publishes none. */
@Composable
private fun BasicHours(view: RatesView, ops: FormOps) {
    if (view.effective.daily?.hours != null || view.nonUnion) return
    BuilderGrid(columns = 2, modifier = Modifier.padding(bottom = 14.dp)) {
        cell {
            Field(str(S.dm_rates_basic_hrs_day), hint = str(S.desktop_dm_standard_contracted_hours_per_working_day)) {
                BuilderInput(
                    value = view.form.text("basicWorkingHoursPerDay"),
                    onValueChange = { typed ->
                        ops.set("basicWorkingHoursPerDay", typed.filter { it.isDigit() || it == '.' })
                    },
                    placeholder = str(S.dm_rates_basic_hrs_day_hint),
                )
            }
        }
    }
}

@Composable
private fun ManualOverride(onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val shape = RoundedCornerShape(5.dp)
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.overrideBg)
            .border(1.dp, p.overrideBorder, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_dm_rates_manually_overridden_differs_from_published_scale),
            style = DmType.sans(10.sp).copy(fontStyle = FontStyle.Italic),
            color = p.muted,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .alpha(if (hovered) HOVER_ALPHA else 1f)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { onEvent(BuilderEvent.ResetToScale) }
                .pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(ZillitIcons.Reload, size = 10.dp, tint = p.gold)
            ZillitText(
                text = str(S.dm_rates_reset_to_scale),
                style = DmType.display(10.sp, FontWeight.SemiBold),
                color = p.gold,
            )
        }
    }
}

/** The agreement's constraints, as chips: the hourly rate, the OT floor and cap, the OT rates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstraintsBar(view: RatesView) {
    val p = bp
    val chips =
        RateTables.constraintChips(view.dayRate, view.effective.daily, view.sections.overtime?.rows.orEmpty(), view.sym)
    val shape = RoundedCornerShape(5.dp)
    FlowRow(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.tableHead)
            .border(1.dp, p.cardBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(
            text = str(S.dm_rates_card_constraints),
            style = DmType.display(10.sp, FontWeight.SemiBold, 0.4.sp),
            color = p.muted,
        )
        chips.forEach { (main, sub) ->
            ZillitText(
                text = buildAnnotatedString {
                    append(main)
                    sub?.let { withStyle(SpanStyle(color = p.muted)) { append(" $it") } }
                },
                style = DmType.sans(10.sp),
                color = p.ink2,
            )
        }
    }
}

@Composable
private fun PhaseRates(view: RatesView, ops: FormOps) {
    val p = bp
    val form = view.form
    val phases = listOf(
        Triple(
            str(S.desktop_dm_prep_day_rate),
            "prepRate",
            BuilderSeeds.inclusiveDays(form.text("schedPrepStart"), form.text("schedPrepEnd")),
        ),
        Triple(
            str(S.desktop_dm_shoot_day_rate),
            "shootRate",
            BuilderSeeds.inclusiveDays(form.text("schedShootStart"), form.text("schedShootEnd")),
        ),
        Triple(
            str(S.desktop_dm_wrap_day_rate),
            "wrapRate",
            BuilderSeeds.inclusiveDays(form.text("schedWrapStart"), form.text("schedWrapEnd")),
        ),
    )
    val total = phases.sumOf { (_, key, days) -> RateResolve.number(form[key]) * days }
    Column(Modifier.padding(bottom = 14.dp)) {
        ZillitText(
            text = str(S.desktop_dm_phase_specific_rates),
            style = DmType.sans(11.sp, FontWeight.SemiBold),
            color = p.ink2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        phases.forEachIndexed { index, (label, key, days) ->
            PhaseRow(label, form[key], days, view.sym, last = index == phases.lastIndex) { ops.set(key, it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.desktop_dm_total_phase_labour_ex_ot_allow),
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = p.ink2,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${view.sym}${RateTables.localeAmount(total)}",
                style = DmType.mono(14.sp, FontWeight.Bold),
                color = p.gold,
            )
        }
    }
}

@Composable
private fun PhaseRow(
    label: String,
    value: JsonElement?,
    days: Int,
    sym: String,
    last: Boolean,
    onCommit: (JsonElement) -> Unit,
) {
    val p = bp
    val rule = p.tableRule
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(text = label, style = DmType.sans(11.sp), color = p.ink2, modifier = Modifier.weight(1f))
        CalcInput(value, onCommit, modifier = Modifier.width(128.dp), height = 32.dp, textSize = 12.5f, mono = true)
        ZillitText(
            text = str(
                S.desktop_dm_times_days_equals,
                days,
                "$sym${RateFormat.groupAmountAuto(RateResolve.number(value) * days)}",
            ),
            style = DmType.sans(10.sp),
            color = p.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(112.dp),
        )
    }
}

/** Holiday pay the agreement publishes: paid on top of the day rate, or inside it. */
@Composable
private fun HolidayPayBlock(view: RatesView, hp: HolidayPayItem, ops: FormOps) {
    val p = bp
    val day = view.dayRate
    val inclusive = view.hpMode == "incl"
    val base = if (inclusive) day / (1 + hp.rate) else day
    val element = if (inclusive) day - base else day * hp.rate
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_dm_holiday_pay_hp_treatment),
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = p.ink2,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BuilderTag(hp.tag, BuilderTone.Red)
                HpToggle(view.hpMode) { ops.set("hpMode", it) }
            }
        }
        val sym = view.sym
        BuilderAlert(
            text = if (inclusive) {
                str(
                    S.desktop_dm_hp_alert_inclusive,
                    Js.toFixed(1 + hp.rate, HP_DIVISOR_DIGITS),
                    "$sym${RateFormat.groupAmount(base)}",
                    "$sym${RateFormat.groupAmount(element)}",
                )
            } else {
                str(S.desktop_dm_hp_on_top_note, hp.percentText, "$sym${RateFormat.groupAmount(day * (1 + hp.rate))}")
            },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** `HpToggle`: Exclusive or Inclusive; neither lit while unset. */
@Composable
private fun HpToggle(value: String, onChange: (String) -> Unit) {
    val p = bp
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(p.segmentTrack)
            .border(1.dp, p.hairline, CircleShape)
            .padding(3.dp),
    ) {
        HP_MODES.forEach { (id, label) ->
            val active = value == id
            val (source, hovered) = rememberHover()
            Box(
                modifier = Modifier
                    .then(if (active) Modifier.shadow(1.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(if (active) p.segmentActive else Color.Transparent)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onChange(id) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                ZillitText(
                    text = label,
                    style = DmType.sans(12.sp, FontWeight.Bold),
                    color = when {
                        active -> p.gold
                        hovered -> p.ink2
                        else -> p.muted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

// -- summary and DGA ----------------------------------------------------------------------------

private data class SummaryLine(val label: String, val value: String, val teal: Boolean = false, val min: String? = null)

@Composable
private fun RateSummaryCard(view: RatesView) {
    CardBlock(title = str(S.dm_rates_card_summary)) {
        LoadingCover(view.loading, str(S.desktop_dm_resolving_scale_rate)) {
            val lines = summaryLines(view)
            Column {
                lines.forEachIndexed { index, line -> SummaryRow(line, last = index == lines.lastIndex) }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun summaryLines(view: RatesView): List<SummaryLine> = buildList {
    val form = view.form
    val sym = view.sym
    val hp = view.holidayPay
    val rate = hp?.rate ?: 0.0
    val pct = hp?.percentText.orEmpty()
    fun money(value: Double) = "$sym${RateFormat.groupAmount(value)}"
    val dayTyped = Js.truthy(form["dayRate"])
    add(
        SummaryLine(
            str(S.dm_rates_summary_day),
            if (dayTyped) "$sym${AgreementFormat.groupAmount(form["dayRate"])}" else "$sym—",
            min = view.effective.daily?.min
                ?.let { str(S.desktop_dm_min_amount, "$sym${AgreementFormat.groupAmount(it)}") },
        ),
    )
    val day = view.dayRate
    if (hp != null && dayTyped && view.hpMode == "incl") {
        val base = day / (1 + rate)
        add(SummaryLine(str(S.desktop_dm_base_rate_ex_hp), money(base), teal = true))
        add(SummaryLine(str(S.desktop_dm_hp_element_pct, pct), money(day - base), teal = true))
    }
    if (hp != null && dayTyped && view.hpMode == "excl") {
        add(SummaryLine(str(S.desktop_dm_hp_element_pct_on_top, pct), money(day * rate), teal = true))
        add(SummaryLine(str(S.desktop_dm_total_day_rate_with_hp), money(day + day * rate), teal = true))
    }
    val weeklyTyped = Js.truthy(form["weeklyRate"])
    add(
        SummaryLine(
            str(S.dm_rates_summary_weekly),
            if (weeklyTyped) "$sym${AgreementFormat.groupAmount(form["weeklyRate"])}" else "$sym—",
            min = view.effective.weekly?.min
                ?.let { str(S.desktop_dm_min_amount, "$sym${AgreementFormat.groupAmount(it)}") },
        ),
    )
    val weekly = RateResolve.number(form["weeklyRate"])
    if (hp != null && weeklyTyped && view.hpMode == "incl") {
        val base = weekly / (1 + rate)
        add(SummaryLine(str(S.desktop_dm_weekly_base_ex_hp), money(base), teal = true))
        add(SummaryLine(str(S.desktop_dm_weekly_hp_element_pct, pct), money(weekly - base), teal = true))
    }
    if (hp != null && weeklyTyped && view.hpMode == "excl") {
        add(SummaryLine(str(S.desktop_dm_weekly_hp_element_pct_on_top, pct), money(weekly * rate), teal = true))
        add(SummaryLine(str(S.desktop_dm_total_weekly_rate_with_hp), money(weekly + weekly * rate), teal = true))
    }
}

@Composable
private fun SummaryRow(line: SummaryLine, last: Boolean) {
    val p = bp
    val rule = p.tableRule
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = line.label, style = DmType.sans(12.sp), color = p.ink2, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                text = line.value,
                style = DmType.mono(13.sp, FontWeight.Medium),
                color = if (line.teal) p.tealInk else p.ink,
            )
            line.min?.let { ZillitText(text = it, style = DmType.sans(10.sp), color = p.muted) }
        }
    }
}

/** The DGA production fee: a separate weekly payment through principal photography, and the COA. */
@Composable
private fun DgaCard(view: RatesView, dga: DgaFee, ops: FormOps) {
    val p = bp
    val form = view.form
    val weeks = dga.ppWeeks
    val fee = RateResolve.number(form["dgaWeeklyFee"])
    CardBlock(
        title = str(S.dm_rates_card_dga),
        tag = str(S.desktop_dm_principal_photography_only),
        tone = BuilderTone.Purple,
    ) {
        BuilderAlert(
            text = buildAnnotatedString {
                append(str(S.desktop_dm_production_fee_is_a) + " ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.desktop_dm_separate_weekly_payment)) }
                append(" " + str(S.desktop_dm_production_fee_note_tail))
                dga.note?.let { withStyle(SpanStyle(fontSize = 10.sp, color = p.muted)) { append("\n$it") } }
            },
            modifier = Modifier.padding(bottom = 16.dp),
        )
        BuilderGrid(columns = 3, modifier = Modifier.padding(bottom = 12.dp)) {
            cell {
                Field(
                    str(S.desktop_dm_weekly_production_fee),
                    hint = str(S.desktop_dm_auto_filled_from_ep_paymaster_2025_26),
                ) {
                    CalcInput(
                        form["dgaWeeklyFee"],
                        { ops.set("dgaWeeklyFee", it) },
                        placeholder = "$${Js.text(dga.prodFee)}",
                    )
                }
            }
            cell {
                Field(str(S.desktop_dm_pp_weeks), hint = str(S.desktop_dm_shoot_days_div_5, dga.shootDays)) {
                    ValueBox(if (weeks > 0) weeks.toString() else RateFormat.DASH)
                }
            }
            cell {
                Field(str(S.desktop_dm_total_pp_fee)) {
                    val shown = if (weeks > 0 && Js.truthy(form["dgaWeeklyFee"])) {
                        "$${RateTables.localeAmount(fee * weeks)}"
                    } else {
                        RateFormat.DASH
                    }
                    ValueBox(shown, mono = true, color = p.purpleInk)
                }
            }
        }
        if (weeks > 0 && Js.truthy(form["weeklyRate"])) DgaTotals(view, weeks, fee)
        CoaBlock(view, ops)
    }
}

@Composable
private fun DgaTotals(view: RatesView, weeks: Int, fee: Double) {
    val p = bp
    val weekly = RateResolve.number(view.form["weeklyRate"])
    val salary = RateTables.round2(weekly * weeks)
    val production = RateTables.round2(fee * weeks)
    val total = RateTables.round2(weekly * weeks + fee * weeks)
    val shape = RoundedCornerShape(5.dp)
    Column(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.purpleSoft)
            .border(1.dp, p.purpleRing, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TotalLine(
            str(
                S.desktop_dm_pp_salary_line,
                weeks,
                "${view.sym}${AgreementFormat.groupAmount(view.form["weeklyRate"])}",
            ),
            "$${RateTables.localeAmount(salary)}",
            p.ink,
        )
        TotalLine(str(S.desktop_dm_pp_production_fee), "$${RateTables.localeAmount(production)}", p.purpleInk)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.purpleRing))
        TotalLine(str(S.desktop_dm_total_pp_compensation), "$${RateTables.localeAmount(total)}", p.gold, strong = true)
    }
}

@Composable
private fun TotalLine(label: String, value: String, valueColor: Color, strong: Boolean = false) {
    val p = bp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = DmType.sans(12.sp, if (strong) FontWeight.SemiBold else FontWeight.Normal),
            color = if (strong) p.ink else p.ink2,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = DmType.mono(13.sp, if (strong) FontWeight.SemiBold else FontWeight.Normal),
            color = valueColor,
        )
    }
}

/** Completion of Assignment: the agreement's bases as radio cards, and the amount the chosen one pays. */
@Composable
@Suppress("LongMethod") // Layout in one place; the sweep's wrapped calls added the lines.
private fun CoaBlock(view: RatesView, ops: FormOps) {
    val p = bp
    val form = view.form
    val basis = form.text("dgaCOABasis")
    Column(Modifier.fillMaxWidth()) {
        Rule(p.tableRule)
        ZillitText(
            text = str(S.desktop_dm_completion_of_assignment_coa),
            style = DmType.sans(11.sp, FontWeight.SemiBold),
            color = p.ink2,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            view.dga?.coaBasis?.forEach { option ->
                val id = option["id"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
                CoaOption(option, active = basis == id) { ops.set("dgaCOABasis", id) }
            }
        }
        if (basis == "negotiated") {
            Field(str(S.dm_rates_dga_negotiated_coa), modifier = Modifier.padding(bottom = 12.dp)) {
                CalcInput(
                    form["dgaNegotiatedCOA"],
                    { ops.set("dgaNegotiatedCOA", it) },
                    placeholder = str(S.desktop_dm_e_g_4500_00),
                )
            }
        }
        if (basis.isNotEmpty() && basis != "none" && Js.truthy(form["weeklyRate"])) {
            val amount = RateTables.coaAmount(
                basis,
                RateResolve.number(form["weeklyRate"]),
                RateResolve.number(form["dgaNegotiatedCOA"]),
            )
            val shape = RoundedCornerShape(5.dp)
            ZillitText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = p.ink2, fontSize = 11.sp)) {
                        append(str(S.desktop_dm_coa_amount_colon) + " ")
                    }
                    withStyle(
                        SpanStyle(
                            color = p.purpleInk,
                            fontFamily = DmType.mono(12.sp).fontFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append("$${RateTables.localeAmount(amount)}")
                    }
                },
                style = DmType.sans(12.sp),
                color = p.ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(p.tableHead)
                    .border(1.dp, p.cardBorder, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CoaOption(option: JsonObject, active: Boolean, onClick: () -> Unit) {
    val p = bp
    val shape = RoundedCornerShape(5.dp)
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) p.purpleSoft else Color.Transparent)
            .border(1.dp, if (active) p.purpleRing else if (hovered) p.menuBorder else p.cardBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(if (active) p.purple else Color.Transparent)
                .border(2.dp, if (active) p.purple else p.divider, CircleShape),
        )
        Column {
            ZillitText(
                text = option["label"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty(),
                style = DmType.sans(11.sp, FontWeight.SemiBold),
                color = if (active) p.purpleInk else p.ink2,
            )
            option["desc"]?.takeUnless(Js::isNullish)?.let {
                ZillitText(
                    text = Js.text(it),
                    style = DmType.sans(10.sp),
                    color = p.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A read-only figure in an input's clothes. */
@Composable
private fun ValueBox(text: String, mono: Boolean = false, color: Color = bp.ink) {
    val p = bp
    val shape = RoundedCornerShape(RADIUS)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CONTROL_HEIGHT)
            .clip(shape)
            .background(p.inputBg)
            .border(1.dp, p.inputBorder, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text = text,
            style = if (mono) DmType.mono(13.5.sp) else DmType.sans(14.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/** A card body under a veil while its rate resolves; the veil takes the clicks. */
@Composable
internal fun LoadingCover(visible: Boolean, label: String, content: @Composable () -> Unit) {
    val p = bp
    Box {
        content()
        if (visible) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(p.card.copy(alpha = VEIL_ALPHA))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitSpinner(size = 14.dp, color = p.cta)
                    ZillitText(text = label, style = DmType.sans(12.sp), color = p.ink2)
                }
            }
        }
    }
}

private fun bold(lead: String, rest: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }
    append(rest)
}

/** `useCurrencyOptions`: the project's picks with its default, and every code the form holds. */
private fun currencyOptions(settings: ProjectSettingsView, ensure: List<String>): List<PickOption> {
    val rows = settings.currencies.mapNotNull { row ->
        row["code"]?.takeIf(Js::truthy)?.let(Js::text)?.trim()?.uppercase()?.let { it to row }
    }.toMap()
    val codes = buildList {
        settings.defaultCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it !in rows }?.let(::add)
        addAll(rows.keys)
    }
    val all = ensure.map { it.trim().uppercase() }.filter { it.isNotEmpty() && it !in codes }.distinct() + codes
    return all.map { code ->
        val row = rows[code]
        val symbol = row?.get("symbol")?.takeIf(Js::truthy)?.let(Js::text)
            ?: RateFormat.currencySymbol(code).takeIf { it != code }
        val country = (row?.get("country")?.takeIf(Js::truthy) ?: row?.get("name")?.takeIf(Js::truthy))?.let(Js::text)
        val head = if (symbol.isNullOrEmpty()) code else "$code $symbol"
        PickOption(
            key = code,
            label = code,
            sub = if (country != null) "$head — $country" else head,
            search = listOfNotNull(code, row?.get("name")?.takeIf(Js::truthy)?.let(Js::text), symbol).joinToString(" "),
        )
    }
}

private val LOCKED_CURRENCY: String get() = str(S.desktop_dm_locked_set_by_the_union_agreement_territory)
private val BUYOUT_MODES get() =
    listOf(
        PickOption("weekly", str(S.dm_rates_buyout_mode_weekly)),
        PickOption("daily", str(S.dm_rates_buyout_mode_daily)),
        PickOption("both", str(S.dm_rates_buyout_mode_both)),
    )
private val COVERS get() = listOf(
    PickOption("8", str(S.desktop_dm_hrs_per_day, 8)),
    PickOption("10", str(S.desktop_dm_hrs_per_day, 10)),
    PickOption("12", str(S.desktop_dm_hrs_per_day, 12)),
    PickOption("unlimited", str(S.dm_rates_buyout_covers_unlimited)),
)
private val HP_MODES get() = listOf(
    "excl" to str(S.desktop_dm_exclusive_added_on_top),
    "incl" to str(S.desktop_dm_inclusive_within_rate),
)
private const val HP_DIVISOR_DIGITS = 4
private const val VEIL_ALPHA = 0.7f
private const val HOVER_ALPHA = 0.8f
private const val HOVER_BORDER_ALPHA = 0.6f
private const val TAG_WASH_ALPHA = 0.15f
