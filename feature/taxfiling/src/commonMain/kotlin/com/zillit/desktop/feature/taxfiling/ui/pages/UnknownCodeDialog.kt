package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.MtdModal
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText
import com.zillit.desktop.feature.taxfiling.ui.components.rememberLast

/**
 * "Code not in Chart of Accounts" — asked before a code the chart lacks joins a box.
 *
 * The web parks such a code behind its add-to-chart prompt; this dialog only
 * confirms it, since the chart is the accountant's to change elsewhere.
 */
@Composable
internal fun UnknownCodeDialog(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val pending = rememberLast(state.returnState.unknownCode)
    val palette = mtdPalette()

    MtdModal(
        visible = state.returnState.unknownCode != null,
        title = str(S.desktop_tax_code_not_in_chart_title),
        onDismiss = { onEvent(TaxFilingEvent.DismissUnknownCode) },
        width = 460.dp,
        footer = {
            MtdButton(
                text = str(S.cancel),
                onClick = { onEvent(TaxFilingEvent.DismissUnknownCode) },
                variant = MtdButtonVariant.Ghost,
            )
            MtdButton(
                text = str(S.desktop_add_code),
                onClick = { onEvent(TaxFilingEvent.ConfirmUnknownCode) },
                variant = MtdButtonVariant.Primary,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            MtdIconTile(
                icon = ZillitIcons.Info,
                size = 40.dp,
                iconSize = 18.dp,
                radius = 11.dp,
                tint = palette.amber,
                wash = palette.amberWash,
                edge = palette.amberBorder,
            )
            ZillitText(
                text = str(S.desktop_tax_code_not_in_chart_body, pending?.code.orEmpty(), pending?.box?.number ?: 0),
                style = mtdText(13.5.sp),
                color = palette.ink2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
