package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrSegmentBar
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.mono

/*
 * The placeholders a withheld portal section is drawn as: the section's shape
 * with made-up figures, blurred behind a lock. Nothing real is ever composed
 * into them — a blur is not redaction.
 */

/** A permitted section as itself; a withheld one as its placeholder. */
@Composable
internal fun Permitted(allowed: Boolean, locked: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (allowed) content() else LockedSection(locked)
}

@Composable
internal fun LockedSection(placeholder: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().blur(3.dp).alpha(LOCKED_ALPHA)) { placeholder() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceHover),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(BankRecIcons.Lock, tint = colors.textMuted, size = 22.dp)
            }
            ZillitText(
                str(S.desktop_br_not_included),
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.textMuted,
            )
        }
    }
}

@Composable
internal fun LockedBalances(defaultCode: String) {
    val masked = "${Money.symbol(defaultCode)}XX,XXX.XX"
    Row(Modifier.fillMaxWidth()) {
        listOf(
            str(S.desktop_br_opening_bank_balance),
            str(S.desktop_br_closing_bank_balance),
            str(S.desktop_br_unreconciled_difference),
        ).forEach {
            BalanceCell(it, masked, " ")
        }
    }
}

@Composable
internal fun LockedRecStatus() {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(
            str(S.desktop_br_reconciliation_status),
            ZillitIcons.BarChart,
            colors.textSecondary,
            uppercase = true,
        )
        BrSegmentBar(
            segments = listOf(0.6f to colors.success, 0.17f to colors.warning, 0.17f to colors.danger),
            gap = 4.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                str(S.desktop_matched),
                str(S.desktop_suggested),
                str(S.desktop_br_no_entry),
                str(S.desktop_fraud),
            ).forEach { StatusTile(null, it, BrTone.Gray) }
        }
    }
}

@Composable
internal fun LockedExceptions(defaultCode: String) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(
            str(S.desktop_br_exceptions_noted),
            BankRecIcons.Exclaim,
            colors.textPrimary,
            iconTint = colors.warning,
        )
        listOf(
            str(S.desktop_br_exc_bank_charges),
            str(S.desktop_br_tax_payment),
            str(S.desktop_br_exc_card_settlement),
        ).forEach {
            MaskedLine(it, "-${Money.symbol(defaultCode)}X,XXX.XX")
        }
    }
}

@Composable
internal fun LockedFraud() {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(str(S.desktop_fraud_alerts), ZillitIcons.Shield, colors.danger)
        repeat(2) { MaskedLine(str(S.desktop_br_vendor_redacted), "") }
    }
}

@Composable
internal fun LockedFx(defaultCode: String) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            str(S.desktop_br_foreign_currency_payments),
            BankRecIcons.Swap,
            colors.textPrimary,
            iconTint = colors.teal,
        )
        Row(Modifier.fillMaxWidth()) {
            listOf(
                str(S.desktop_br_eur_paid),
                str(S.desktop_budget_rate),
                str(S.desktop_br_currency_paid, defaultCode),
                str(S.desktop_fx_variance),
            ).forEach { label ->
                Column(Modifier.weight(1f)) {
                    ZillitText(label.uppercase(), style = eyebrow(9.sp), color = colors.textMuted)
                    ZillitText("XX,XXX", style = mono(13.sp, FontWeight.SemiBold), color = colors.textMuted)
                }
            }
        }
    }
}

@Composable
internal fun LockedTransactions(defaultCode: String) {
    val masked = "${Money.symbol(defaultCode)}X,XXX.XX"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        listOf(str(S.desktop_bank_transactions), str(S.desktop_ledger_entries)).forEach { title ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(title.uppercase(), style = eyebrow(11.sp), color = ZillitTheme.colors.textMuted)
                repeat(PLACEHOLDER_ROWS) { MaskedLine(str(S.desktop_br_transaction_n, it + 1), masked) }
            }
        }
    }
}

@Composable
private fun MaskedLine(label: String, figure: String) {
    val muted = ZillitTheme.colors.textMuted
    Row(Modifier.fillMaxWidth()) {
        ZillitText(label, style = ZillitTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
        if (figure.isNotBlank()) ZillitText(figure, style = mono(12.sp), color = muted)
    }
}

private const val LOCKED_ALPHA = 0.4f
private const val PLACEHOLDER_ROWS = 3
