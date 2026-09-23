package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCard
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * A filing this client has no screen for — the web's `FilingRouter`
 * fallback, reached from an old link or a catalogue entry ahead of the client.
 */
@Composable
internal fun FilingUnavailablePage(onEvent: (TaxFilingEvent) -> Unit) {
    val palette = mtdPalette()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MtdCard(modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(), padding = 0.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZillitText(
                    text = str(S.desktop_tax_not_available_yet),
                    style = mtdText(19.sp, FontWeight.Bold, tracking = (-0.015).em),
                    color = palette.ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                ZillitText(
                    text = str(S.desktop_tax_not_available_detail),
                    style = mtdText(14.sp),
                    color = palette.ink3,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                MtdButton(
                    text = str(S.desktop_tax_back_to_tax_filing),
                    onClick = { onEvent(TaxFilingEvent.ShowCatalog) },
                    variant = MtdButtonVariant.Secondary,
                    icon = ZillitIcons.ChevronLeft,
                )
            }
        }
    }
}
