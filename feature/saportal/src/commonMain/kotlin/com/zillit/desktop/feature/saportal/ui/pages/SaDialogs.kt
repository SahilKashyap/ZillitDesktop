package com.zillit.desktop.feature.saportal.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.domain.VoucherDetail
import com.zillit.desktop.feature.saportal.ui.SaEvent
import com.zillit.desktop.feature.saportal.ui.SaUiState
import com.zillit.desktop.feature.saportal.ui.day
import com.zillit.desktop.feature.saportal.ui.money
import com.zillit.desktop.feature.saportal.ui.shift
import com.zillit.desktop.feature.saportal.ui.title
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One day, in full — and the place it is signed.
 *
 * The figures come before the signature block on purpose: signing is an
 * assertion that the day is right, so what is being asserted has to be on
 * screen above the thing that asserts it.
 */
@Composable
internal fun VoucherDialog(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    val detail = state.openVoucher ?: return
    val voucher = detail.voucher

    ZillitDialogShell(
        title = day(voucher.shootDate),
        subtitle = listOf(title(voucher), voucher.code).filter { it.isNotBlank() }.joinToString(" · "),
        visible = true,
        onDismiss = { onEvent(SaEvent.CloseVoucher) },
        icon = ZillitIcons.Info,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = shift(voucher), style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = money(voucher.gross, voucher.currency),
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = voucher.status.label, tone = StatusTone.Neutral)
        }

        VoucherBreakdown(detail)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.desktop_sa_something_looks_wrong),
                onClick = { onEvent(SaEvent.StartQuery(voucher)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(SaEvent.CloseVoucher) },
                variant = ButtonVariant.Tertiary,
            )
            // Only a submitted day may be signed; the server refuses the rest,
            // so the control is absent rather than disabled.
            if (voucher.signable) {
                ZillitButton(
                    text = str(S.desktop_sa_sign_this_day),
                    onClick = { onEvent(SaEvent.StartSigning(voucher)) },
                )
            }
        }
    }
}

/** The priced lines, the breaks, the notes, and the signature if there is one. */
@Composable
private fun ColumnScope.VoucherBreakdown(detail: VoucherDetail) {
    val currency = detail.voucher.currency
    if (detail.ratesAndOvertime.isNotEmpty()) {
        ZillitSectionLabel(str(S.desktop_sa_rates_and_overtime))
        detail.ratesAndOvertime.forEach { line -> LineRow(line.label, line.amount, currency) }
    }
    if (detail.allowances.isNotEmpty()) {
        ZillitSectionLabel(str(S.allowances_label))
        detail.allowances.forEach { line -> LineRow(line.label, line.amount, currency) }
    }
    if (detail.meals.isNotEmpty()) {
        ZillitSectionLabel(str(S.desktop_sa_meal_breaks))
        detail.meals.forEach { meal ->
            ZillitText(
                text = listOf(meal.label, "${meal.from} – ${meal.to}")
                    .filter { it.isNotBlank() && it != " – " }
                    .joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
    detail.notes.takeIf { it.isNotBlank() }?.let {
        ZillitSectionLabel(str(S.notes))
        ZillitText(text = it, style = ZillitTheme.typography.bodySmall)
    }
    detail.signature?.let { signature ->
        ZillitSectionLabel(str(S.signed))
        ZillitText(
            text = "${signature.typedName} · ${day(signature.signedAt)}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LineRow(label: String, amount: Double, currency: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label.ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = money(amount, currency), style = ZillitTheme.typography.numeric)
    }
}

/**
 * Signing.
 *
 * Both statements and a typed name are required together — that is what the
 * server records, and it is what makes the signature mean anything. The
 * wording is the artiste's own assertion rather than a tick-box: they are
 * confirming hours they were paid for.
 */
@Composable
internal fun SignDialog(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    val sign = state.sign ?: return

    ZillitDialogShell(
        title = str(S.desktop_sa_sign_title, day(sign.voucher.shootDate)),
        subtitle = money(sign.voucher.gross, sign.voucher.currency) + " · " + shift(sign.voucher),
        visible = true,
        onDismiss = { onEvent(SaEvent.CancelSign) },
        icon = ZillitIcons.Edit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitCheckbox(
                checked = sign.consentAccuracy,
                onCheckedChange = { onEvent(SaEvent.SignAccuracy(it)) },
                label = str(S.desktop_sa_consent_accuracy),
            )
            ZillitCheckbox(
                checked = sign.consentESign,
                onCheckedChange = { onEvent(SaEvent.SignESign(it)) },
                label = str(S.desktop_sa_consent_esign),
            )
            ZillitTextField(
                value = sign.typedName,
                onValueChange = { onEvent(SaEvent.SignName(it)) },
                label = str(S.desktop_sa_your_full_name),
                placeholder = str(S.desktop_sa_name_placeholder),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SaEvent.CancelSign) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.sign),
                onClick = { onEvent(SaEvent.ConfirmSign) },
                loading = sign.saving,
                enabled = sign.ready && !sign.saving,
            )
        }
    }
}

/** Raising a query about one day. */
@Composable
internal fun QueryDialog(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    val draft = state.queryDraft ?: return

    ZillitDialogShell(
        title = str(S.desktop_sa_raise_query),
        subtitle = draft.voucherCode.ifBlank { str(S.desktop_sa_about_this_day) },
        visible = true,
        onDismiss = { onEvent(SaEvent.CancelQuery) },
        icon = ZillitIcons.Info,
    ) {
        ZillitTextField(
            value = draft.text,
            onValueChange = { onEvent(SaEvent.QueryText(it)) },
            label = str(S.desktop_sa_what_looks_wrong),
            placeholder = str(S.desktop_sa_query_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SaEvent.CancelQuery) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(SaEvent.SubmitQuery) },
                loading = draft.saving,
                enabled = draft.ready && !draft.saving,
            )
        }
    }
}
