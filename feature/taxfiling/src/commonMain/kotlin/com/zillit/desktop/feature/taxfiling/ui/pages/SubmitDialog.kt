package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdModal
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText
import com.zillit.desktop.feature.taxfiling.ui.components.rememberLast

/**
 * The last step before a legal return leaves the building.
 *
 * The web submits on the first press; here the press shows HMRC's own
 * declaration and the figures that will be filed, and only the confirmation
 * sends them. HMRC requires software to put that declaration in front of the
 * person filing — `finalised: true` is the record that they saw it — and a
 * dialog that repeats the figures is one that gets read rather than clicked
 * through.
 */
@Composable
internal fun SubmitDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val returnState = rememberLast(state.returnState.takeIf { it.confirmingSubmit }) ?: state.returnState
    val palette = mtdPalette()
    val period = returnState.period
    val company = returnState.registration?.companyName.orEmpty()

    MtdModal(
        visible = state.returnState.confirmingSubmit,
        title = str(S.desktop_tax_submit_title),
        subtitle = period?.let { "$company · ${it.periodKey} (${it.range})" },
        icon = ZillitIcons.Shield,
        iconTone = palette.green,
        iconWash = palette.greenWash,
        iconEdge = palette.greenBorder,
        width = 520.dp,
        onDismiss = { onEvent(TaxFilingEvent.DismissSubmit) },
        footer = {
            MtdButton(
                text = str(S.cancel),
                onClick = { onEvent(TaxFilingEvent.DismissSubmit) },
                variant = MtdButtonVariant.Ghost,
                enabled = !returnState.submitting,
            )
            MtdButton(
                text = if (returnState.submitting) str(S.ah_submitting) else str(S.desktop_tax_submit_to_hmrc),
                onClick = { onEvent(TaxFilingEvent.ConfirmSubmit) },
                variant = MtdButtonVariant.Green,
                icon = ZillitIcons.Shield,
                loading = returnState.submitting,
                enabled = state.returnState.canSubmit,
            )
        },
    ) {
        Declaration()
        Figures(returnState)
        ZillitText(
            text = str(S.desktop_tax_submit_warning),
            style = mtdText(12.sp),
            color = palette.muted,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/** HMRC's declaration, in the words its guidance gives software to show. */
@Composable
private fun Declaration() {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.amberWash)
            .border(1.dp, palette.amberBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = palette.amber, size = 16.dp)
        ZillitText(
            text = str(S.desktop_tax_submit_declaration),
            style = mtdText(13.sp, FontWeight.Medium),
            color = palette.ink,
        )
    }
}

/** The nine boxes that will be filed, box 5 with which way it goes. */
@Composable
private fun Figures(state: ReturnState) {
    val palette = mtdPalette()
    val shown = state.shown ?: return
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        VatBox.entries.forEach { box ->
            val net = box == VatBox.NetDue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (net) palette.accentWash else palette.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitText(
                    text = str(S.desktop_tax_box_number, box.number),
                    style = mtdText(10.5.sp, FontWeight.Bold, mono = true),
                    color = if (net) palette.accentText else palette.muted,
                    modifier = Modifier.width(40.dp),
                )
                ZillitText(
                    text = box.label,
                    style = mtdText(13.sp, if (net) FontWeight.Bold else FontWeight.Medium),
                    color = palette.ink2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (net) {
                    MtdPill(
                        text = if (shown.isPayable) str(S.desktop_tax_to_pay) else str(S.desktop_tax_to_reclaim),
                        tone = PillTone.Open,
                    )
                }
                ZillitText(
                    text = TaxFormat.gbp(shown[box], box.decimals),
                    style = mtdText(13.sp, if (net) FontWeight.ExtraBold else FontWeight.SemiBold, mono = true),
                    color = palette.ink,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
