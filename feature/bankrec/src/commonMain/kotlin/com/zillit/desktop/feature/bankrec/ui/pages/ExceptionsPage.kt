package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.ui.ALL_PERIODS
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrIconTile
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrToneButton
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle
import com.zillit.desktop.feature.bankrec.ui.resolvePeriodChoice

/**
 * The bank lines the reconciliation could not place, grouped as the web groups
 * them: not in Zillit, foreign payments, and what has already been actioned.
 *
 * What each offers follows the money: a credit is investigated, a debit is
 * quick-added to the ledger, and a foreign payment is a variance to look at on
 * its own tab. Ignoring any of them is one click.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The three groups and the states around them.
@Composable
fun ColumnScope.ExceptionsPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val page = state.exceptionsPage
    val rows = state.exceptions.forPeriod(state, page.periodChoice) { it.periodId }
    val openRows = rows.filter { it.status == ExceptionStatus.Open }
    val openFx = openRows.filter { it.isFx }
    val periodId = resolvePeriodChoice(page.periodChoice, state.openPeriods)

    ActionRow(
        leading = {
            BrBadge("${openRows.size} items", BrTone.Red)
            if (openFx.isNotEmpty()) BrBadge("${openFx.size} FX", BrTone.Teal)
            PeriodFilter(state, page.periodChoice) { onEvent(BankRecEvent.SetExceptionsPeriod(it)) }
        },
    ) {
        val single = periodId != ALL_PERIODS
        ZillitTooltip(if (single) "" else str(S.desktop_br_select_single_period_short)) {
            ZillitButton(
                text = if (page.exporting) str(S.desktop_exporting) else str(S.recce_export_pdf),
                onClick = { onEvent(BankRecEvent.ExportExceptionsPdf) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
                loading = page.exporting,
                enabled = single && !page.exporting,
            )
        }
    }

    when {
        !state.exceptionsLoading && !state.periodsLoading && state.openPeriods.isEmpty() ->
            NoActivePeriod(BankRecIcons.Exclaim)

        (state.exceptionsLoading || state.periodsLoading) && state.exceptions.isEmpty() -> BrSkeletonRows(5)
        rows.isEmpty() -> BrEmpty(
            title = str(S.desktop_br_no_exceptions_detected),
            message = str(S.desktop_br_no_exceptions_detail),
            icon = ZillitIcons.Check,
            tone = BrTone.Green,
        )

        else -> {
            val notInZillit = openRows.filterNot { it.isFx }
            val actioned = rows.filter { it.status != ExceptionStatus.Open }
            if (notInZillit.isNotEmpty()) {
                Group(
                    str(S.desktop_br_group_not_in_zillit),
                    BrTone.Red,
                    str(S.desktop_br_group_not_in_zillit_sub, notInZillit.size),
                ) {
                    notInZillit.forEach { ExceptionCard(it, state, onEvent) }
                }
            }
            if (openFx.isNotEmpty()) {
                Group(
                    str(S.desktop_br_group_fx_variations),
                    BrTone.Teal,
                    str(S.desktop_br_group_fx_variations_sub, openFx.size),
                ) {
                    openFx.forEach { ExceptionCard(it, state, onEvent) }
                }
            }
            if (actioned.isNotEmpty()) {
                Group(
                    str(S.ah_alert_filter_resolved),
                    BrTone.Gray,
                    str(S.desktop_br_group_previously_actioned, actioned.size),
                ) {
                    actioned.forEach { ExceptionCard(it, state, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun Group(chip: String, tone: BrTone, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(tone.bg()).border(
                    1.dp,
                    tone.edge(),
                    RoundedCornerShape(4.dp),
                )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                ZillitText(chip.uppercase(), style = mono(9.5.sp, FontWeight.Bold), color = tone.fg())
            }
            ZillitText(title, style = titleStyle(12.5.sp), color = ZillitTheme.colors.textSecondary)
        }
        ZillitDivider()
        content()
    }
}

/**
 * One exception: its type as a tinted glyph, what it is and when, what to do
 * about it, the amount — red out, green in — and the actions its state allows.
 */
@Suppress("LongMethod") // The web's card: glyph, words, figure and actions in one row.
@Composable
private fun ExceptionCard(item: BankException, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) colors.accent.copy(alpha = 0.45f) else colors.border)
    // The amount's own currency: on a foreign line the service's `currency`
    // column is the foreign code, while the debit is in the account's.
    val accountCurrency = state.currencyOf(state.period(item.periodId))
    val currency = when {
        item.isFx -> accountCurrency
        else -> item.transaction?.amountCurrency(accountCurrency) ?: item.currency ?: accountCurrency
    }
    val acting = state.exceptionsPage.acting?.takeIf { it.id == item.id }
    val date = item.transaction?.transactionDateMillis?.let(BankRecFormat::paddedDay)

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface)
            .border(1.dp, border, RoundedCornerShape(8.dp)).hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrIconTile(item.type.icon, if (item.type == ExceptionType.Unknown) BrTone.Purple else BrTone.Red)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(
                    listOfNotNull(item.title.ifBlank { item.type.label }, date).joinToString(" — "),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                item.status.badge?.let { (label, tone) -> BrBadge(label, tone) }
            }
            ZillitText(
                item.type.guidance,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }
        Column(Modifier.widthIn(min = 104.dp), horizontalAlignment = Alignment.End) {
            if (item.debit > 0) {
                ZillitText(
                    BankRecFormat.signedMoney(-item.debit, currency),
                    style = mono(12.5.sp, FontWeight.Medium),
                    color = colors.danger,
                )
            }
            if (item.credit > 0) {
                ZillitText(
                    BankRecFormat.signedMoney(item.credit, currency),
                    style = mono(12.5.sp, FontWeight.Medium),
                    color = colors.success,
                )
            }
            ZillitText(
                if (item.debit > 0) str(S.desktop_debit) else str(S.desktop_credit),
                style = mono(9.sp),
                color = colors.textMuted,
                textAlign = TextAlign.End,
            )
        }
        CardActions(item, acting?.status, onEvent)
    }
}

@Composable
private fun CardActions(item: BankException, acting: ExceptionStatus?, onEvent: (BankRecEvent) -> Unit) {
    val busy = acting != null
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            item.status == ExceptionStatus.UnderInvestigation -> ZillitButton(
                text = if (acting == ExceptionStatus.Investigated) "…" else str(S.desktop_investigated),
                onClick = { onEvent(BankRecEvent.SetExceptionStatus(item.id, ExceptionStatus.Investigated)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !busy,
            )

            item.status != ExceptionStatus.Open -> Unit
            item.isFx -> BrToneButton(
                text = str(S.desktop_br_view_fx),
                tone = BrTone.Teal,
                onClick = { onEvent(BankRecEvent.OpenTab(BankTab.FxVariances)) },
                icon = BankRecIcons.Swap,
            )

            else -> {
                ZillitButton(
                    text = if (acting == ExceptionStatus.Ignored) "…" else str(S.txt_ignore),
                    onClick = { onEvent(BankRecEvent.SetExceptionStatus(item.id, ExceptionStatus.Ignored)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                if (item.isCredit) {
                    BrToneButton(
                        text = if (acting == ExceptionStatus.UnderInvestigation) {
                            "…"
                        } else {
                            str(S.desktop_card_investigate)
                        },
                        tone = BrTone.Purple,
                        onClick = {
                            onEvent(BankRecEvent.SetExceptionStatus(item.id, ExceptionStatus.UnderInvestigation))
                        },
                        enabled = !busy,
                    )
                } else {
                    ZillitButton(
                        text = str(S.desktop_quick_add),
                        onClick = { onEvent(BankRecEvent.OpenExceptionQuickAdd(item.id)) },
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

internal val ExceptionType.icon: ImageVector
    get() = when (this) {
        ExceptionType.BankCharge -> ZillitIcons.Bank
        ExceptionType.VatReturn, ExceptionType.Interest, ExceptionType.PettyCash -> BankRecIcons.Coin
        ExceptionType.CardSettlement -> ZillitIcons.CreditCard
        ExceptionType.Payroll -> ZillitIcons.Users
        ExceptionType.FxPayment -> BankRecIcons.Swap
        ExceptionType.Insurance -> ZillitIcons.Shield
        ExceptionType.Unknown -> BankRecIcons.Question
    }
