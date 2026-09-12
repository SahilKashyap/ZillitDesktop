package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.StickyViewport
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdAvatar
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCard
import com.zillit.desktop.feature.taxfiling.ui.components.MtdDropdown
import com.zillit.desktop.feature.taxfiling.ui.components.MtdFieldLabel
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.MtdOption
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.MtdRule
import com.zillit.desktop.feature.taxfiling.ui.components.MtdSectionHead
import com.zillit.desktop.feature.taxfiling.ui.components.MtdStripDivider
import com.zillit.desktop.feature.taxfiling.ui.components.MtdSummaryStat
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdEyebrow
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * One company's VAT return — the web's `VATReturnView`.
 *
 * The obligation card first, then one of three things under it: a prompt to
 * pick a period, the return already filed for a fulfilled one, or — for an
 * open period — the box mapping beside a summary rail that stays in view
 * while the boxes scroll.
 */
@Composable
internal fun ReturnPage(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    sticky: StickyViewport,
    onOpenLayers: (VatBox) -> Unit,
) {
    val returnState = state.returnState
    // Absent for the frame a closing return fades out on.
    val registration = returnState.registration ?: return
    DetailHeader(
        registration = registration,
        connecting = state.connectingId == registration.id,
        canReachAuthority = state.canReachAuthority,
        onEvent = onEvent,
    )
    Spacer(Modifier.height(22.dp))
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ObligationCard(returnState, state.canReachAuthority, onEvent)
        val period = returnState.period
        when {
            period == null -> SelectPeriodPrompt()
            !period.isOpen -> FiledReturnPanel(period, returnState.filedForPeriod)
            else -> MappingWithRail(state, onEvent, sticky, onOpenLayers)
        }
    }
}

/** Who is filing: the company, its number and cadence, and whether HMRC is connected. */
@Composable
private fun DetailHeader(
    registration: TaxRegistration,
    connecting: Boolean,
    canReachAuthority: Boolean,
    onEvent: (TaxFilingEvent) -> Unit,
) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MtdAvatar(name = registration.companyName, size = 52.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ZillitText(
                text = registration.companyName,
                style = mtdText(27.sp, FontWeight.ExtraBold, tracking = (-0.03).em, lineHeight = 30.sp),
                color = palette.ink,
                maxLines = 1,
            )
            RegistrationFacts(registration) {
                MtdPill(
                    text = registration.status,
                    tone = if (registration.isActive) PillTone.Active else PillTone.Neutral,
                )
            }
        }
        if (registration.connected) {
            MtdPill(text = "Connected to HMRC", tone = PillTone.Connected, leading = ZillitIcons.Shield)
        } else {
            MtdButton(
                text = if (connecting) "Connecting…" else "Connect to HMRC",
                onClick = { onEvent(TaxFilingEvent.Connect(registration)) },
                variant = MtdButtonVariant.Primary,
                icon = ZillitIcons.Link,
                loading = connecting,
                enabled = canReachAuthority,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObligationCard(state: ReturnState, canReachAuthority: Boolean, onEvent: (TaxFilingEvent) -> Unit) {
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 24.dp) {
        MtdSectionHead(
            title = "Obligation period",
            subtitle = "Sync HMRC obligations for this company, then pick the open period to file.",
            right = {
                MtdButton(
                    text = if (state.syncing) "Syncing…" else "Sync obligations",
                    onClick = { onEvent(TaxFilingEvent.SyncObligations) },
                    variant = MtdButtonVariant.Secondary,
                    icon = ZillitIcons.Reload,
                    loading = state.syncing,
                    enabled = canReachAuthority,
                )
            },
        )
        MtdRule(Modifier.padding(top = 20.dp, bottom = 22.dp))
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
            MtdFieldLabel("Obligation period")
            MtdDropdown(
                value = state.periodKey.takeIf { it.isNotBlank() },
                options = state.obligations.map { it.option() },
                onChange = { onEvent(TaxFilingEvent.SelectPeriod(it.orEmpty())) },
                placeholder = when {
                    state.obligationsLoading -> "Loading obligations…"
                    state.obligations.isNotEmpty() -> "Select an obligation…"
                    else -> "No obligations — sync to fetch from HMRC."
                },
                clearable = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.period?.let { period -> SummaryStrip(period, state.connected) }
    }
}

private fun FilingObligation.option() = MtdOption(
    value = periodKey,
    label = pickerLabel,
    sub = due.takeIf { it.isNotBlank() }?.let { "Due $it" },
    pill = if (isOpen) "Open" to PillTone.Open else "Fulfilled" to PillTone.Neutral,
)

/** The chosen period at a glance, and whether HMRC is connected to receive it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStrip(period: FilingObligation, connected: Boolean) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(12.dp)
    FlowRow(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface2)
            .border(1.dp, palette.border, shape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MtdSummaryStat(label = "Period", value = period.range, mono = true)
        MtdStripDivider()
        MtdSummaryStat(label = "Period key", value = period.periodKey, mono = true)
        MtdStripDivider()
        MtdSummaryStat(label = "Due", value = period.due.ifBlank { "—" })
        MtdStripDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(text = "OBLIGATION", style = mtdEyebrow(), color = palette.muted)
            MtdPill(
                text = if (period.isOpen) "Open" else "Fulfilled",
                tone = if (period.isOpen) PillTone.Open else PillTone.Fulfilled,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val tint = if (connected) palette.green else palette.ink3
            ZillitIcon(icon = if (connected) ZillitIcons.Shield else ZillitIcons.Link, tint = tint, size = 15.dp)
            ZillitText(
                text = if (connected) "Connected to HMRC" else "Not connected to HMRC",
                style = mtdText(12.5.sp, FontWeight.Medium),
                color = tint,
            )
        }
    }
}

@Composable
private fun SelectPeriodPrompt() {
    val palette = mtdPalette()
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 40.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MtdIconTile(icon = ZillitIcons.Info, size = 46.dp, iconSize = 20.dp, radius = 13.dp)
            ZillitText(
                text = "Select an obligation period",
                style = mtdText(16.sp, FontWeight.Bold),
                color = palette.ink,
            )
            ZillitText(
                text = "Sync HMRC obligations above, then pick a period to map its boxes and file the return.",
                style = mtdText(13.5.sp),
                color = palette.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 440.dp),
            )
        }
    }
}

/**
 * The boxes beside the summary rail — or above it, when the pane is too narrow
 * for both. Side by side, the rail rides down with the page so Calculate and
 * Submit stay in reach, stopping at the end of the boxes.
 */
@Composable
private fun MappingWithRail(
    state: TaxFilingUiState,
    onEvent: (TaxFilingEvent) -> Unit,
    sticky: StickyViewport,
    onOpenLayers: (VatBox) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < SIDE_BY_SIDE) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                BoxMappingSection(state, onEvent, onOpenLayers)
                SummaryRail(state, onEvent)
            }
        } else {
            StickyRow(
                sticky = sticky,
                main = { BoxMappingSection(state, onEvent, onOpenLayers) },
                rail = { SummaryRail(state, onEvent) },
            )
        }
    }
}

/**
 * CSS `position: sticky` for the rail.
 *
 * The row's top is measured once in content coordinates — its window position
 * plus the scroll, minus where the viewport starts — which does not change as
 * the page scrolls. The rail is then offset in the layout phase straight from
 * the scroll position, so it tracks the page with no frame of lag, clamped to
 * the row so it never rides past the last box.
 */
@Composable
private fun StickyRow(
    sticky: StickyViewport,
    main: @Composable () -> Unit,
    rail: @Composable () -> Unit,
) {
    val topGap = with(LocalDensity.current) { STICKY_GAP.toPx() }
    var rowTopInContent by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    var railHeight by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                rowTopInContent = coordinates.positionInWindow().y + sticky.scroll.value - sticky.viewportTop()
                rowHeight = coordinates.size.height
            },
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.weight(1f)) { main() }
        Box(
            modifier = Modifier
                .width(RAIL_WIDTH)
                .offset {
                    val travel = (rowHeight - railHeight).coerceAtLeast(0)
                    val wanted = sticky.scroll.value + topGap - rowTopInContent
                    IntOffset(0, wanted.toInt().coerceIn(0, travel))
                }
                .onSizeChanged { railHeight = it.height },
        ) { rail() }
    }
}

private val SIDE_BY_SIDE = 920.dp
private val RAIL_WIDTH = 360.dp
private val STICKY_GAP = 18.dp
