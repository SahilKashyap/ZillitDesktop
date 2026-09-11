package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.VatBox

private const val GBP = "GBP"
private const val WHOLE_POUNDS = 0
private const val PENCE = 2

/**
 * A period that is done.
 *
 * Read-only by design: HMRC has the return, the period is fulfilled, and
 * offering a mapping or a calculate button here would be offering work that
 * cannot be submitted. [filed] is present only when Zillit filed it — a period
 * fulfilled elsewhere still shows as fulfilled, with no figures to show.
 */
@Composable
fun ColumnScope.FiledReturnPanel(period: FilingObligation, filed: FiledReturn?) {
    ZillitSectionCard(
        title = "Filed return",
        icon = ZillitIcons.Tick,
        meta = "${period.start} to ${period.end}",
        action = { ZillitStatusPill(label = "Fulfilled", tone = StatusTone.Done, dot = true) },
    ) {
        if (filed == null) {
            ZillitNotice(
                text = "This period was filed with HMRC, but not from Zillit — there is no " +
                    "receipt here to show. HMRC's own account has it.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.fillMaxWidth(),
            )
            return@ZillitSectionCard
        }

        VatBox.entries.forEach { box -> FiledFigure(box, filed) }

        ZillitNotice(
            text = filed.receiptLine(),
            tone = StatusTone.Done,
            icon = ZillitIcons.Shield,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** HMRC's form bundle number is the reference on any later query about it. */
private fun FiledReturn.receiptLine(): String = when {
    reference.isBlank() -> "Submitted to HMRC."
    processedAt.isBlank() -> "Submitted. HMRC receipt $reference."
    else -> "Submitted $processedAt. HMRC receipt $reference."
}

@Composable
private fun ColumnScope.FiledFigure(box: VatBox, filed: FiledReturn) {
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
            text = Money.format(
                filed.values[box],
                GBP,
                if (box.wholePounds) WHOLE_POUNDS else PENCE,
            ),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}
