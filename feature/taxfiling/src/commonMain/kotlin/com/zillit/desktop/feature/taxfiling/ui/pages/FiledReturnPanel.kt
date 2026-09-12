package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCard
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.MtdRule
import com.zillit.desktop.feature.taxfiling.ui.components.MtdSectionHead
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * A period that is done — the web's `FiledReturnPanel`.
 *
 * Read-only by design: HMRC has the return, the period is fulfilled, and a
 * mapping or a calculate button here would offer work that cannot be
 * submitted. [filed] is present only when Zillit filed it — a period fulfilled
 * elsewhere still shows as fulfilled, with no figures stored to show.
 */
@Composable
internal fun FiledReturnPanel(period: FilingObligation, filed: FiledReturn?) {
    val palette = mtdPalette()
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 24.dp) {
        MtdSectionHead(
            title = "Filed return",
            subtitle = "This period is fulfilled — ${period.periodKey} (${period.range}).",
            right = { MtdPill(text = "Fulfilled", tone = PillTone.Fulfilled) },
        )
        MtdRule(Modifier.padding(vertical = 20.dp))
        if (filed != null && filed.hasFigures) {
            FiledFigures(filed)
            Receipt(filed)
        } else {
            ZillitText(
                text = buildAnnotatedString {
                    append("This period is already ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = palette.ink)) { append("fulfilled") }
                    append(" with HMRC. It wasn’t filed from here, so the box figures aren’t stored locally.")
                },
                style = mtdText(13.5.sp),
                color = palette.ink3,
            )
        }
    }
}

/** The nine boxes as filed, three across — two when the pane is narrow. */
@Composable
private fun FiledFigures(filed: FiledReturn) {
    val palette = mtdPalette()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val across = if (maxWidth >= THREE_ACROSS) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VatBox.entries.chunked(across).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { box ->
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(palette.surface2)
                                .border(1.dp, palette.border, shape)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            ZillitText(
                                text = "Box ${box.number} · ${box.label}",
                                style = mtdText(11.sp),
                                color = palette.muted,
                                maxLines = 1,
                            )
                            ZillitText(
                                text = TaxFormat.gbp(filed.values[box], box.decimals),
                                style = mtdText(14.sp, FontWeight.Bold, mono = true),
                                color = palette.ink,
                            )
                        }
                    }
                    repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** HMRC's form bundle number is the reference on any later query about the return. */
@Composable
private fun Receipt(filed: FiledReturn) {
    val palette = mtdPalette()
    val mono = mtdText(12.5.sp, mono = true).fontFamily
    Row(
        modifier = Modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Shield, tint = palette.green, size = 15.dp)
        ZillitText(
            text = buildAnnotatedString {
                if (filed.reference.isBlank()) {
                    append("Submitted to HMRC.")
                } else {
                    append("Submitted · receipt ")
                    withStyle(SpanStyle(color = palette.ink2, fontFamily = mono)) {
                        append(filed.reference)
                    }
                    if (filed.processedAt.isNotBlank()) append(" · ${filed.processedAt}")
                }
            },
            style = mtdText(12.5.sp),
            color = palette.green,
        )
    }
}

private val THREE_ACROSS = 560.dp
