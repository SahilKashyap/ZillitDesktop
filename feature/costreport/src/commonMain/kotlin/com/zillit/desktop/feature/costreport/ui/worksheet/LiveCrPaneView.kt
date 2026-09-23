package com.zillit.desktop.feature.costreport.ui.worksheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrTableSpec
import com.zillit.desktop.feature.costreport.domain.buildWorksheetTable
import com.zillit.desktop.feature.costreport.ui.CrGridActions
import com.zillit.desktop.feature.costreport.ui.CrHistoryCard
import com.zillit.desktop.feature.costreport.ui.CrLoaderOverlay
import com.zillit.desktop.feature.costreport.ui.CrPalette
import com.zillit.desktop.feature.costreport.ui.CrReportHeading
import com.zillit.desktop.feature.costreport.ui.CrWorksheetGrid

/**
 * The Live CR pane — the web's `LiveCR` inside the worksheet: its own filter
 * row, then Current CR (the week read-only, with a saved version's forecast
 * laid over it when one is computed) or CR History (the posted timeline).
 */
@Composable
internal fun LiveCrPaneView(
    state: WorksheetUiState,
    onEvent: (WorksheetEvent) -> Unit,
    nowMillis: () -> Long,
    resolveUser: (String) -> String?,
    modifier: Modifier,
) {
    val live = state.live
    Column(modifier.fillMaxWidth()) {
        PaneFilterRow(state, WorksheetPane.Live, onEvent, nowMillis)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.reference.metaMissing) {
                Unavailable(str(S.desktop_cr_live_unavailable), state.reference.metaMissingMessage)
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                if (state.liveTab == LiveTab.Current) {
                    CrReportHeading(
                        projectName = state.projectName,
                        todayMs = nowMillis(),
                        weekLabel = live.week?.label,
                        query = state.liveView.search,
                        onQuery = { onEvent(WorksheetEvent.Search(WorksheetPane.Live, it)) },
                        trailing = { LiveTabs(state, onEvent) },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f))
                        LiveTabs(state, onEvent)
                    }
                }
                when (state.liveTab) {
                    LiveTab.Current -> if (live.loadedOnce) LiveCurrentTable(state, onEvent, Modifier.weight(1f))
                    LiveTab.History -> Box(
                        Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    ) {
                        CrHistoryCard(
                            rows = state.snapshots.rows,
                            loading = state.snapshots.loading,
                            filter = state.snapshots.filter,
                            symbolFor = { state.reference.symbolFor(it.currency ?: live.applied.currency) },
                            resolveUser = resolveUser,
                            onFilter = { onEvent(WorksheetEvent.SetHistoryFilter(it)) },
                            onRefresh = { onEvent(WorksheetEvent.RefreshHistory) },
                            onOpen = { onEvent(WorksheetEvent.OpenSnapshot(it)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            CrLoaderOverlay(
                visible = state.liveTab == LiveTab.Current && live.loadedOnce && live.loading && !live.silent,
                message = live.loaderMessage,
            )
        }
    }
}

/** The week read-only, with a computed version's forecast laid over it and the ledger a click away. */
@Composable
private fun LiveCurrentTable(state: WorksheetUiState, onEvent: (WorksheetEvent) -> Unit, modifier: Modifier) {
    val live = state.live
    val view = state.liveView
    val table = remember(live.sections, live.overrides, live.baseline, view.search, view.toggles) {
        buildWorksheetTable(live.sections, live.forecast, CrTableSpec(search = view.search, toggles = view.toggles))
    }
    val actions = remember(onEvent) {
        CrGridActions(
            onSection = { onEvent(WorksheetEvent.ToggleSection(WorksheetPane.Live, it)) },
            onHeader = { onEvent(WorksheetEvent.ToggleHeader(WorksheetPane.Live, it)) },
            onNominal = { onEvent(WorksheetEvent.ToggleNominal(WorksheetPane.Live, it)) },
            onLedger = { nominal, column -> onEvent(WorksheetEvent.OpenLedger(WorksheetPane.Live, nominal, column)) },
        )
    }
    CrWorksheetGrid(
        table = table,
        symbol = state.symbolFor(live),
        decimals = 0,
        projectName = state.projectName,
        sort = CrSort(),
        locked = false,
        actions = actions,
        modifier = modifier,
    )
}

/** Current CR · CR History, in the web's floating paper pill. */
@Composable
private fun LiveTabs(state: WorksheetUiState, onEvent: (WorksheetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LiveTab.entries.forEach { tab ->
            val active = state.liveTab == tab
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            active -> CrPalette.CTA.copy(alpha = 0.10f)
                            hovered -> colors.surfaceHover
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(hover)
                    .clickable { onEvent(WorksheetEvent.SelectLiveTab(tab)) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ZillitIcon(
                    if (tab == LiveTab.Current) ZillitIcons.Monitor else ZillitIcons.Clock,
                    tint = if (active) CrPalette.cta else colors.textMuted,
                    size = 13.dp,
                )
                ZillitText(
                    tab.label,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                    color = if (active) CrPalette.cta else colors.textSecondary,
                )
                val count = state.snapshots.rows.size
                if (tab == LiveTab.History && count > 0) {
                    ZillitText(
                        text = count.toString(),
                        style = ZillitTheme.typography.numeric.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (active) CrPalette.cta else colors.textSecondary,
                        modifier = Modifier
                            .background(
                                if (active) CrPalette.CTA.copy(alpha = 0.18f) else colors.surfaceSunken,
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
