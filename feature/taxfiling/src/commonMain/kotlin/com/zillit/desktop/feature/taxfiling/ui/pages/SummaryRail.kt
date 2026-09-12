package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.CardShape
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonSize
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.MtdRule
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdEyebrow
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * "Return summary" — the web's `SummaryRail`.
 *
 * The period, the figures grouped as the return groups them, box 5 large with
 * which way the money goes, and the three things to do: calculate, export,
 * submit. Before anything is calculated every figure reads "—".
 */
@Composable
internal fun SummaryRail(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val palette = mtdPalette()
    val returnState = state.returnState
    val shown = returnState.shown
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, CardShape, clip = false, ambientColor = RailShade, spotColor = RailShade)
            .clip(CardShape)
            .background(palette.surface)
            .border(1.dp, palette.border, CardShape),
    ) {
        RailHeader(returnState)
        MtdRule()
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)) {
            RailGroup("VAT due") {
                RailLine(VatBox.DueOnSales, shown)
                RailLine(VatBox.DueOnAcquisitions, shown)
                RailLine(VatBox.TotalDue, shown, bold = true, sub = "Box 1 + 2")
            }
            RailGroup("VAT reclaimed") {
                RailLine(VatBox.ReclaimedOnPurchases, shown)
            }
        }
        NetBlock(shown)
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
            RailGroup("Return totals (ex-VAT)") {
                listOf(VatBox.SalesExVat, VatBox.PurchasesExVat, VatBox.GoodsSuppliedExVat, VatBox.AcquisitionsExVat)
                    .forEach { RailLine(it, shown, muted = true) }
            }
        }
        MtdRule()
        RailActions(state, onEvent)
    }
}

private val RailShade = Color.Black.copy(alpha = 0.14f)

@Composable
private fun RailHeader(state: ReturnState) {
    val palette = mtdPalette()
    val period = state.period
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = "Return summary",
                style = mtdText(14.5.sp, FontWeight.Bold, tracking = (-0.015).em),
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            if (period != null) {
                MtdPill(
                    text = if (period.isOpen) "Open" else "Fulfilled",
                    tone = if (period.isOpen) PillTone.Open else PillTone.Fulfilled,
                )
            }
        }
        if (period != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(text = period.range, style = mtdText(11.5.sp, mono = true), color = palette.ink3)
                ZillitText(text = "·", style = mtdText(11.5.sp), color = palette.faint)
                ZillitText(
                    text = period.periodKey,
                    style = mtdText(11.5.sp, FontWeight.Bold, mono = true),
                    color = palette.accentText,
                )
            }
            if (period.due.isNotBlank()) {
                ZillitText(
                    text = "Due ${period.due}",
                    style = mtdText(12.sp),
                    color = palette.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun RailGroup(label: String, lines: @Composable ColumnScope.() -> Unit) {
    val palette = mtdPalette()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = mtdEyebrow(10.sp),
            color = palette.muted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = lines)
    }
}

/** `BOX 1   VAT on sales ………… £1,000.00`. */
@Composable
private fun RailLine(
    box: VatBox,
    shown: VatReturn?,
    bold: Boolean = false,
    muted: Boolean = false,
    sub: String? = null,
) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(
            text = "BOX ${box.number}",
            style = mtdText(10.5.sp, FontWeight.Bold, mono = true),
            color = palette.muted,
            modifier = Modifier.width(38.dp),
        )
        ZillitText(
            text = if (sub == null) box.short else "${box.short} · $sub",
            style = mtdText(12.5.sp, if (bold) FontWeight.Bold else FontWeight.Medium),
            color = when {
                bold -> palette.ink
                muted -> palette.ink3
                else -> palette.ink2
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = TaxFormat.gbp(shown?.get(box), box.decimals),
            style = mtdText(
                if (bold) 13.5.sp else 12.5.sp,
                if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
                mono = true,
                tracking = (-0.01).em,
            ),
            color = if (muted) palette.ink3 else palette.ink,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Box 5, large: the amount, whether it is paid to HMRC or reclaimed from it,
 * and the arithmetic it comes from. HMRC takes the size; the pill says which way.
 */
@Composable
private fun NetBlock(shown: VatReturn?) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(12.dp)
    val payable = shown?.isPayable ?: true
    Column(
        modifier = Modifier
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(palette.accentWash)
            .border(1.dp, palette.accentBorder, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = "BOX 5 · NET VAT",
                style = mtdEyebrow(),
                color = palette.accentText,
                modifier = Modifier.weight(1f),
            )
            MtdPill(text = if (payable) "To pay" else "To reclaim", tone = PillTone.Open)
        }
        ZillitText(
            text = shown?.let { TaxFormat.gbp(kotlin.math.abs(it.netSigned)) } ?: "—",
            style = mtdText(26.sp, FontWeight.ExtraBold, mono = true, tracking = (-0.03).em, lineHeight = 32.sp),
            color = palette.ink,
            modifier = Modifier.padding(top = 8.dp),
        )
        ZillitText(
            text = "${if (payable) "Payable to HMRC" else "Reclaimable from HMRC"} · Box 3 − Box 4",
            style = mtdText(12.sp),
            color = palette.accentText,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Calculate, export and submit — each offered only when it can be done. */
@Composable
private fun RailActions(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    val returnState = state.returnState
    val period = returnState.period
    val registration = returnState.registration
    Column(
        modifier = Modifier.fillMaxWidth().background(mtdPalette().surface2).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatusLine(returnState)
        MtdButton(
            text = when {
                returnState.calculating -> "Calculating…"
                returnState.draft != null -> "Recalculate from ledger"
                else -> "Calculate from ledger"
            },
            onClick = { onEvent(TaxFilingEvent.Calculate) },
            variant = MtdButtonVariant.Primary,
            size = MtdButtonSize.Large,
            icon = ZillitIcons.Calculator,
            loading = returnState.calculating,
            enabled = period != null && !returnState.savingMapping,
            full = true,
        )
        MtdButton(
            text = if (returnState.exporting) "Exporting…" else "Export ledger (.xlsx)",
            onClick = { onEvent(TaxFilingEvent.ExportLedger) },
            variant = MtdButtonVariant.Secondary,
            size = MtdButtonSize.Large,
            icon = ZillitIcons.Download,
            loading = returnState.exporting,
            enabled = period != null,
            full = true,
        )
        MtdButton(
            text = if (returnState.submitting) "Submitting…" else "Submit to HMRC",
            onClick = { onEvent(TaxFilingEvent.AskSubmit) },
            variant = MtdButtonVariant.Green,
            size = MtdButtonSize.Large,
            icon = ZillitIcons.Shield,
            loading = returnState.submitting,
            enabled = returnState.canSubmit && state.canReachAuthority,
            full = true,
        )
        if (registration != null && !registration.connected) {
            ConnectNowLine(
                connecting = state.connectingId == registration.id,
                canReachAuthority = state.canReachAuthority,
                onConnect = { onEvent(TaxFilingEvent.Connect(registration)) },
            )
        }
    }
}

/** "Connect to HMRC to submit. Connect now" — under a Submit that cannot yet be pressed. */
@Composable
private fun ConnectNowLine(connecting: Boolean, canReachAuthority: Boolean, onConnect: () -> Unit) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = "Connect to HMRC to submit. ", style = mtdText(12.sp), color = palette.ink3)
        ZillitText(
            text = if (connecting) "Connecting…" else "Connect now",
            style = mtdText(12.sp, FontWeight.SemiBold),
            color = palette.accent,
            modifier = Modifier.clickable(
                enabled = !connecting && canReachAuthority,
                role = Role.Button,
                onClick = onConnect,
            ),
        )
    }
}

/** What the figures above are: nothing yet, fresh from the ledger, or out of date. */
@Composable
private fun StatusLine(state: ReturnState) {
    val palette = mtdPalette()
    val stale = state.draft != null && state.draftStale
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(
            icon = if (stale) ZillitIcons.Warning else ZillitIcons.Info,
            tint = if (stale) palette.amber else palette.muted,
            size = 14.dp,
        )
        ZillitText(
            text = when {
                state.period == null -> "Select an obligation period to calculate."
                stale -> "Mapping changed — recalculate before submitting."
                state.draft != null -> "Calculated just now from the ledger"
                else -> "Calculate to fill from the ledger"
            },
            style = mtdText(11.5.sp, if (stale) FontWeight.SemiBold else FontWeight.Normal),
            color = if (stale) palette.amber else palette.muted,
        )
    }
}
