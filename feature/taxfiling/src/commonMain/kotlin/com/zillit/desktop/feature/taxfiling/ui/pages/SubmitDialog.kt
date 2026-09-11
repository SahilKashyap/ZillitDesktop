package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent

private const val GBP = "GBP"
private const val WHOLE_POUNDS = 0
private const val PENCE = 2

/**
 * The last step before a legal return leaves the building.
 *
 * Repeats the figures rather than saying "are you sure": the accountant is
 * confirming a declaration to HMRC, and a dialog that shows nothing to check
 * is a dialog they learn to click through.
 */
@Composable
fun SubmitDialog(state: ReturnState, onEvent: (TaxFilingEvent) -> Unit) {
    val shown = state.shown
    ZillitDialogShell(
        title = "File this return with HMRC?",
        subtitle = state.period?.let { "${it.start} to ${it.end}" },
        icon = ZillitIcons.Send,
        visible = state.confirmingSubmit,
        onDismiss = { onEvent(TaxFilingEvent.DismissSubmit) },
        actions = {
            ZillitButton(
                text = "Not yet",
                onClick = { onEvent(TaxFilingEvent.DismissSubmit) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "File it",
                onClick = { onEvent(TaxFilingEvent.ConfirmSubmit) },
                loading = state.submitting,
                enabled = state.canSubmit,
            )
        },
    ) {
        if (shown == null) return@ZillitDialogShell

        ZillitText(
            text = "You are declaring that the information is true and complete. HMRC treats " +
                "a false declaration as a legal matter.",
            style = ZillitTheme.typography.bodyMedium,
        )

        VatBox.entries.forEach { box -> FigureRow(box, shown) }

        ZillitNotice(
            text = "Once filed, this period is fulfilled and cannot be filed again. A " +
                "correction afterwards is a separate process with HMRC.",
            tone = StatusTone.Escalated,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.FigureRow(box: VatBox, shown: VatReturn) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = "Box ${box.number} · ${box.label}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = Money.format(shown[box], GBP, if (box.wholePounds) WHOLE_POUNDS else PENCE),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
