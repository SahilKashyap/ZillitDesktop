package com.zillit.desktop.feature.bankrec.ui.pages.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitPaneSplitter
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.WorkspaceView
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.workspaceView

/**
 * The reconciliation itself: the statement on the left, the ledger on the
 * right, a divider to drag between them, and the quick-entry drawer beside.
 *
 * Laid out to the window, each panel scrolling itself — a list inside a
 * scrolling page is handed an infinite height, which Compose refuses. Picking a
 * row lights what it is matched to on the other side and brings it into view.
 */
@Composable
fun WorkspacePage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val ws = state.workspace
    val view = remember(ws, state.periods, state.bankAccounts, state.rates, state.exceptions, state.lookups) {
        state.workspaceView()
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        if (ws.expanded) ExpandedBar(view, loading = ws.loading, onEvent = onEvent)
        when {
            ws.loading || (state.periodsLoading && ws.periodId.isBlank()) -> WorkspaceSkeleton()
            ws.periodId.isBlank() || view.period == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                BrEmpty(
                    title = str(S.desktop_br_no_active_period),
                    message = str(S.desktop_br_no_active_period_workspace),
                    icon = ZillitIcons.Bank,
                )
            }

            else -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Toolbar(state, view, onEvent)
                    BalanceBar(state, view, onEvent = { onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts)) })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SplitPanels(state, view, onEvent)
                        if (ws.rerunning) RerunOverlay()
                    }
                    SummaryBar(view)
                }
                AnimatedVisibility(
                    visible = ws.showQuickEntry,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    QuickEntryPanel(state, view, onEvent, Modifier.width(QUICK_ENTRY_WIDTH).fillMaxHeight())
                }
            }
        }
    }
}

/** The two panels and the divider between them, which may be dragged from a quarter to three quarters. */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Two panels that scroll, filter and light each other together.
@Composable
private fun SplitPanels(state: BankRecUiState, view: WorkspaceView, onEvent: (BankRecEvent) -> Unit) {
    val ws = state.workspace
    var fraction by remember { mutableFloatStateOf(HALF) }
    val bankList = rememberLazyListState()
    val ledgerList = rememberLazyListState()

    // The other side follows a pick: its first linked row comes into view.
    LaunchedEffect(view.linked) {
        val first = view.linked.firstOrNull() ?: return@LaunchedEffect
        view.visibleLedger.indexOfFirst { it.id == first }.takeIf { it >= 0 }
            ?.let { ledgerList.animateScrollToItem(it) }
        view.visibleBank.indexOfFirst { it.id == first }.takeIf { it >= 0 }?.let { bankList.animateScrollToItem(it) }
    }
    // "View ›" on a suggestion brings the invoice itself into view.
    LaunchedEffect(ws.flashId) {
        val id = ws.flashId ?: return@LaunchedEffect
        view.visibleLedger.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { ledgerList.animateScrollToItem(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(fraction).fillMaxHeight().background(ZillitTheme.colors.surface)) {
                PanelHeader(
                    icon = ZillitIcons.Bank,
                    title = str(S.desktop_br_bank_statement),
                    subtitle = "${view.account?.displayName?.ifBlank { null } ?: BankRecFormat.DASH} · " +
                        view.period?.let(BankRecFormat::periodLabel).orEmpty(),
                    total = view.bankTotal,
                    caption = str(S.desktop_br_bank_statement_caption),
                )
                PanelList(bankList, Modifier.weight(1f)) {
                    items(view.visibleBank, key = { it.id }) { row ->
                        BankRowItem(
                            row = row,
                            light = when {
                                ws.quickEntry.transactionId == row.id -> RowLight.QuickAdding
                                ws.selectedId == row.id || row.id in view.linked -> RowLight.Selected
                                else -> RowLight.None
                            },
                            fallbackCurrency = view.statementCurrency,
                            actions = rowActions(row.id, onEvent),
                        )
                    }
                    if (view.visibleBank.isEmpty()) {
                        item {
                            PanelNote(
                                if (view.bankRows.isEmpty()) {
                                    str(S.desktop_br_no_transactions_imported)
                                } else {
                                    str(S.desktop_br_no_transactions_match)
                                },
                            )
                        }
                    }
                }
            }
            ZillitPaneSplitter(
                onDrag = { delta -> fraction = (fraction + delta / widthPx).coerceIn(MIN_SPLIT, MAX_SPLIT) },
                modifier = Modifier.fillMaxHeight(),
            )
            Column(Modifier.weight(1f - fraction).fillMaxHeight().background(ZillitTheme.colors.surface)) {
                PanelHeader(
                    icon = ZillitIcons.Ledger,
                    title = str(S.desktop_br_zillit_ledger),
                    subtitle = str(
                        S.desktop_br_ready_to_pay,
                        view.period?.let(BankRecFormat::periodLabel).orEmpty(),
                    ),
                    total = view.ledgerTotal,
                    caption = str(S.desktop_br_ledger_caption),
                )
                PanelList(ledgerList, Modifier.weight(1f)) {
                    items(view.visibleLedger, key = { it.id }) { row ->
                        LedgerRowItem(
                            row = row,
                            light = if (ws.selectedId == row.id || row.id in view.linked || ws.flashId == row.id) {
                                RowLight.Selected
                            } else {
                                RowLight.None
                            },
                            fallbackCurrency = state.projectCurrency,
                            onClick = { onEvent(BankRecEvent.SelectRow(row.id)) },
                        )
                    }
                    if (view.visibleLedger.isEmpty()) {
                        item {
                            PanelNote(
                                if (view.ledgerRows.isEmpty()) {
                                    str(S.desktop_br_no_invoices_ready)
                                } else {
                                    str(S.desktop_br_no_invoices_match)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun rowActions(id: String, onEvent: (BankRecEvent) -> Unit) = BankRowActions(
    onClick = { onEvent(BankRecEvent.SelectRow(id)) },
    onQuickAdd = { onEvent(BankRecEvent.OpenQuickAdd(id)) },
    onManualMatch = { onEvent(BankRecEvent.OpenManualMatch(id)) },
    onAccept = { invoiceId -> onEvent(BankRecEvent.ProposeMatch(id, invoiceId)) },
    onViewInvoice = { invoiceId -> onEvent(BankRecEvent.ViewInvoice(invoiceId)) },
    onReviewFraud = { onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts)) },
    onPostFx = { onEvent(BankRecEvent.OpenFxEntry(id)) },
)

@Composable
private fun PanelList(
    state: LazyListState,
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), content = content)
        ZillitScrollRail(state, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun PanelNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        ZillitText(text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
    }
}

/** A veil over the panels while the matching rules run again — the rows under it are about to change. */
@Composable
private fun RerunOverlay() {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.surface.copy(alpha = OVERLAY_ALPHA)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        ZillitSpinner(size = 28.dp, color = colors.accent)
        ZillitText(
            str(S.desktop_br_rerunning_auto_match),
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/** The workspace's shape while its period loads — toolbar, balances, two panels of rows. */
@Composable
private fun WorkspaceSkeleton() {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitSkeletonBar(Modifier.width(160.dp), height = 22.dp)
            ZillitSkeletonBar(Modifier.width(260.dp), height = 22.dp)
            Box(Modifier.weight(1f))
            ZillitSkeletonBar(Modifier.width(110.dp), height = 26.dp)
        }
        ZillitDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            repeat(2) { index ->
                Column(Modifier.weight(1f).fillMaxHeight().background(colors.surface)) {
                    Box(Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(14.dp)) {
                        ZillitSkeletonBar(Modifier.width(180.dp))
                    }
                    repeat(SKELETON_ROWS) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ZillitSkeletonBar(Modifier.width(40.dp))
                            ZillitSkeletonBar(Modifier.weight(1f))
                            ZillitSkeletonBar(Modifier.width(72.dp))
                        }
                        ZillitDivider()
                    }
                }
                if (index == 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
            }
        }
    }
}

private const val HALF = 0.5f
private const val MIN_SPLIT = 0.25f
private const val MAX_SPLIT = 0.75f
private const val OVERLAY_ALPHA = 0.72f
private const val SKELETON_ROWS = 7
internal val QUICK_ENTRY_WIDTH = 276.dp
