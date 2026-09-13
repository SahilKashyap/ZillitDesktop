package com.zillit.desktop.feature.dealmemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDates
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.DealListRules
import com.zillit.desktop.feature.dealmemo.domain.DealQuickFilter
import com.zillit.desktop.feature.dealmemo.domain.DealSort
import com.zillit.desktop.feature.dealmemo.domain.DepartmentOption
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealsEvent
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.components.DmBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmCard
import com.zillit.desktop.feature.dealmemo.ui.components.DmCell
import com.zillit.desktop.feature.dealmemo.ui.components.DmColumn
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirm
import com.zillit.desktop.feature.dealmemo.ui.components.DmDropPanel
import com.zillit.desktop.feature.dealmemo.ui.components.DmEyebrow
import com.zillit.desktop.feature.dealmemo.ui.components.DmFilterPill
import com.zillit.desktop.feature.dealmemo.ui.components.DmHoverRow
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmLabelled
import com.zillit.desktop.feature.dealmemo.ui.components.DmMenuGroupTitle
import com.zillit.desktop.feature.dealmemo.ui.components.DmMenuItem
import com.zillit.desktop.feature.dealmemo.ui.components.DmMenuSeparator
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmPersonAvatar
import com.zillit.desktop.feature.dealmemo.ui.components.DmRoundIcon
import com.zillit.desktop.feature.dealmemo.ui.components.DmSearchPill
import com.zillit.desktop.feature.dealmemo.ui.components.DmSelectPill
import com.zillit.desktop.feature.dealmemo.ui.components.DmStatusBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmTableHeader
import com.zillit.desktop.feature.dealmemo.ui.components.DmTone
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.DmUnreadPill
import com.zillit.desktop.feature.dealmemo.ui.components.dm

/** All Deals — the posting user's working list (`DMDealsPage.jsx`). */
@Composable
fun AllDealsPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val deals = state.deals
    val labels = remember(state.people, state.catalogue) { state.labels }
    val rows = remember(deals.rows, deals.filter, deals.departmentId, deals.search, deals.sort, labels) {
        DealListRules.visible(deals.rows, deals.filter, deals.departmentId, deals.search, deals.sort, labels)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ActionsRow(state, onEvent)
        Spacer(Modifier.height(16.dp))
        QuickFilters(state, labels, onEvent)
        Spacer(Modifier.height(18.dp))
        val pending = if (state.viewer.isAccountant) deals.rows.count(DealListRules::nominalsPending) else 0
        if (pending > 0) {
            NominalBanner(pending)
            Spacer(Modifier.height(12.dp))
        }
        DealsTable(state, rows, labels, onEvent, modifier = Modifier.weight(1f))
    }
}

/** All Deals' dialogs, hosted over the whole window by the screen. */
@Composable
fun DealsDialogs(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    DeleteConfirmation(state, onEvent)
    SetupGate(state, onEvent)
}

@Composable
private fun ActionsRow(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DmSearchPill(
            value = state.deals.search,
            onValueChange = { onEvent(DealsEvent.Search(it)) },
            placeholder = "Search deal memos — by crew, reference, designation…",
            modifier = Modifier.weight(1f),
        )
        ExportTrigger(state, onEvent)
        DmButton(
            text = "Deal Memo Setup",
            onClick = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub())) },
            style = DmButtonStyle.Cta,
            icon = ZillitIcons.Settings,
        )
        CreateTrigger(state, onEvent)
    }
}

@Composable
private fun ExportTrigger(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val open = remember { mutableStateOf(false) }
    val exporting = state.deals.exporting
    val drop = with(LocalDensity.current) { 52.dp.roundToPx() }
    Box {
        DmButton(
            text = if (exporting != null) "Exporting…" else "Export",
            onClick = { open.value = !open.value },
            style = DmButtonStyle.Ghost,
            icon = DmIcons.Export,
            enabled = exporting == null,
        )
        DmDropPanel(open = open.value, onDismiss = { open.value = false }, offsetY = drop) {
            fun pick(kind: DealExport) {
                open.value = false
                onEvent(DealsEvent.Export(kind))
            }
            DmMenuGroupTitle("Deal Register")
            DmMenuItem(
                "Export PDF",
                "Formatted document, print-ready",
                "PDF",
                Color(0xFFFF7A59) to Color(0xFFE23B3B),
                { pick(DealExport.RegisterPdf) },
                ".pdf",
            )
            DmMenuItem(
                "Export Excel",
                "Editable spreadsheet with live data",
                "XLSX",
                Color(0xFF34C97A) to Color(0xFF138A52),
                { pick(DealExport.RegisterExcel) },
                ".xlsx",
            )
            DmMenuSeparator()
            DmMenuGroupTitle("Crew Documents")
            DmMenuItem(
                "Starter Forms",
                "One signed start form per crew — all deals in the project",
                "ZIP",
                Color(0xFF8B7FF5) to Color(0xFF5A4BD6),
                { pick(DealExport.StartForms) },
                ".zip",
            )
        }
    }
}

@Composable
private fun CreateTrigger(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val deals = state.deals
    val drop = with(LocalDensity.current) { 52.dp.roundToPx() }
    Box {
        DmButton(
            text = "Create Deal Memo",
            onClick = { onEvent(DealsEvent.RequestCreateMenu) },
            style = DmButtonStyle.Cta,
            icon = ZillitIcons.Add,
            loading = deals.checkingSetups,
        )
        DmDropPanel(open = deals.createMenuOpen, onDismiss = { onEvent(DealsEvent.CloseCreateMenu) }, offsetY = drop) {
            DmMenuGroupTitle("Create Deal Memo")
            DmMenuItem(
                "Union",
                "Start from one of the project's union setups",
                "U",
                Color(0xFFF8A03A) to Color(0xFFE8861A),
                { onEvent(DealsEvent.CreateFrom(SetupGroup.Union)) },
            )
            DmMenuItem(
                "Non-Union",
                "Start from one of the project's non-union setups",
                "NU",
                Color(0xFF5B8DEF) to Color(0xFF2862E0),
                { onEvent(DealsEvent.CreateFrom(SetupGroup.NonUnion)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFilters(state: DealMemoUiState, labels: DealCrewLabels, onEvent: (DealMemoEvent) -> Unit) {
    val deals = state.deals
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DmEyebrow("Quick filters:", modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
            DealQuickFilter.entries.forEach { filter ->
                DmFilterPill(
                    filter.label,
                    selected = deals.filter == filter,
                    onClick = { onEvent(DealsEvent.Filter(filter)) },
                )
            }
        }
        val options = remember(deals.rows, labels) {
            listOf<DepartmentOption?>(null) + DealListRules.departmentOptions(deals.rows, labels)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DmLabelled("Dept:") {
                DmSelectPill(
                    value = options.firstOrNull { it?.id == deals.departmentId },
                    options = options,
                    label = { it?.label ?: "All" },
                    onSelect = { onEvent(DealsEvent.Department(it?.id)) },
                )
            }
            DmLabelled("Sort:") {
                DmSelectPill(
                    value = deals.sort,
                    options = DealSort.entries,
                    label = { it.label },
                    onSelect = { onEvent(DealsEvent.Sort(it)) },
                    menuWidth = 160.dp,
                )
            }
        }
    }
}

/** Accountants only: deals whose nominal coding is still pending keep payroll out of the cost report. */
@Composable
private fun NominalBanner(count: Int) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(dm.amberSoft.copy(alpha = 0.7f))
            .border(1.dp, dm.amberRing, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(ZillitIcons.Warning, size = 14.dp, tint = dm.accent)
        val one = count == 1
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$count deal${if (one) "" else "s"}") }
                append(
                    " ${if (one) "has" else "have"} nominal coding pending — their payroll will not appear in the " +
                        "Cost Report's Payroll committed column until the codes are added. Open pending nominal " +
                        "deal and update the nominals.",
                )
            },
            style = DmType.sans(12.5.sp),
            color = if (ZillitTheme.colors.isDark) {
                Color(0xFFFCD34D)
            } else {
                Color(0xFF7A5B1E)
            },
        )
    }
}

private val COLUMNS = listOf(
    DmColumn("Reference", width = 124.dp),
    DmColumn("Crew Member", weight = 1.4f),
    DmColumn("Department / Designation", weight = 1.4f),
    DmColumn("Day Rate", width = 112.dp),
    DmColumn("Type", width = 118.dp),
    DmColumn("Period", width = 178.dp),
    DmColumn("Status", weight = 1.7f),
    DmColumn("", width = 206.dp, alignEnd = true),
)

@Composable
private fun DealsTable(
    state: DealMemoUiState,
    rows: List<DealDoc>,
    labels: DealCrewLabels,
    onEvent: (DealMemoEvent) -> Unit,
    modifier: Modifier,
) {
    DmCard(modifier = modifier.fillMaxWidth()) {
        DmTableHeader(COLUMNS)
        when {
            state.deals.loading && !state.deals.loaded -> TableMessage(loading = true, text = "Loading…")
            rows.isEmpty() -> TableMessage(loading = false, text = "No deal memos match this filter.")
            else -> ZillitLazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(rows, key = { it.id.ifBlank { it.hashCode().toString() } }) { deal ->
                    DealRow(state, deal, labels, onEvent)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
                }
            }
        }
    }
}

@Composable
internal fun TableMessage(loading: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            ZillitSpinner(size = 14.dp, color = dm.ink3)
            Spacer(Modifier.width(6.dp))
        }
        ZillitText(text = text, style = DmType.sans(12.sp), color = dm.ink3)
    }
}

@Suppress("LongMethod")
@Composable
private fun DealRow(state: DealMemoUiState, deal: DealDoc, labels: DealCrewLabels, onEvent: (DealMemoEvent) -> Unit) {
    val person = labels.labels(deal)
    DmHoverRow(
        onClick = { onEvent(DealsEvent.Open(deal)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        DmCell(COLUMNS[0]) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DmUnreadPill(state.badges.deal(DealBadgeUnit.AllDeals, deal.id))
                ZillitText(
                    text = deal.reference ?: "—",
                    style = DmType.mono(12.5.sp, FontWeight.SemiBold),
                    color = dm.ink2,
                    maxLines = 1,
                )
            }
        }
        DmCell(COLUMNS[1]) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DmPersonAvatar(person.name, deal.userId, size = 28.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ZillitText(
                        text = person.name,
                        style = DmType.sans(13.5.sp, FontWeight.Bold),
                        color = dm.ink,
                        maxLines = 1,
                    )
                    // Under the name rather than beside it, so a long name is not cut to fit the chip.
                    if (deal.externalFlag) DmBadge("External", DmTone.Amber)
                }
            }
        }
        DmCell(COLUMNS[2]) {
            Column {
                ZillitText(text = person.department, style = DmType.sans(13.sp), color = dm.ink2, maxLines = 1)
                ZillitText(
                    text = person.role,
                    style = DmType.sans(11.5.sp),
                    color = dm.ink3,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        DmCell(COLUMNS[3]) {
            ZillitText(
                text = DealListRules.dayRate(deal),
                style = DmType.mono(13.sp, FontWeight.Bold),
                color = dm.ink,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[4]) {
            ZillitText(
                text = DealListRules.dealTypeLabel(deal.dealType),
                style = DmType.sans(12.5.sp),
                color = dm.ink2,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[5]) {
            ZillitText(
                text = "${DealDates.short(deal.startDate)} → ${DealDates.short(deal.endDate)}",
                style = DmType.mono(11.5.sp),
                color = dm.ink3,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[6]) { StatusCell(state, deal) }
        DmCell(COLUMNS[7]) { RowActions(state, deal, onEvent) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCell(state: DealMemoUiState, deal: DealDoc) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DmStatusBadge(deal.status)
        if (deal.isDeactivating) DmBadge("Deactivating · ${DealDates.shortUtc(deal.lastPayDate)}", DmTone.Amber)
        if (state.viewer.isAccountant && DealListRules.nominalsPending(deal)) DmBadge("Pending Nominals", DmTone.Amber)
    }
}

/** The row's actions. Clicks here never open the row. */
@Composable
private fun RowActions(state: DealMemoUiState, deal: DealDoc, onEvent: (DealMemoEvent) -> Unit) {
    val deals = state.deals
    Row(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (DealListRules.canActivate(deal)) {
            val running = deals.activatingId == deal.id
            DmButton(
                text = if (running) "Activating…" else "Activate",
                onClick = { onEvent(DealsEvent.Activate(deal)) },
                style = DmButtonStyle.SmallGreen,
                icon = ZillitIcons.Check,
                enabled = !running,
                loading = running,
            )
        }
        if (DealListRules.canChase(deal, state.rights.canPost, state.viewer.userId)) {
            val running = deals.chasingId == deal.id
            val overdue = DealListRules.chaseOverdue(deal, deals.loadedAt)
            DmButton(
                text = when {
                    running -> "Chasing…"
                    overdue -> "Chase · Overdue"
                    else -> "Chase"
                },
                onClick = { onEvent(DealsEvent.Chase(deal)) },
                style = DmButtonStyle.SmallPrimary,
                icon = ZillitIcons.Bell,
                loading = running,
                tooltip = if (overdue) {
                    "Details overdue — chase the crew member to complete them"
                } else {
                    "Chase the crew member to complete their details"
                },
            )
        }
        DmRoundIcon(DmIcons.History, tooltip = "History", onClick = { onEvent(DealMemoEvent.OpenHistory(deal)) })
        if (DealListRules.canDelete(deal, state.rights.canPost, state.viewer.userId)) {
            DmRoundIcon(
                ZillitIcons.Trash,
                tooltip = if (deal.rawStatus == "draft") "Delete draft" else "Delete deal memo",
                onClick = { onEvent(DealsEvent.AskDelete(deal)) },
                danger = true,
            )
        }
    }
}

@Composable
private fun DeleteConfirmation(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val pending = state.deals.pendingDelete
    val shown = remember { mutableStateOf<DealDoc?>(null) }
    if (pending != null) shown.value = pending
    DmConfirm(
        visible = pending != null,
        title = "Delete Deal Memo",
        message = shown.value?.let(DealListRules::deleteMessage).orEmpty(),
        confirmLabel = "Delete",
        loadingLabel = "Deleting...",
        loading = state.deals.deleting,
        onConfirm = { onEvent(DealsEvent.ConfirmDelete) },
        onCancel = { onEvent(DealsEvent.CancelDelete) },
    )
}

/** "Set up Deal Memo first" — sends the user to the setup builder, not the hub. */
@Composable
private fun SetupGate(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val gate = state.deals.setupGate
    val shown = remember { mutableStateOf(gate) }
    if (gate != null) shown.value = gate
    val group = shown.value?.group
    DmModal(
        visible = gate != null,
        title = "Set up Deal Memo first",
        onDismiss = { onEvent(DealsEvent.CloseSetupGate) },
        footer = {
            DmButton(
                "Open Deal Memo Setup",
                onClick = { onEvent(DealsEvent.OpenSetupFromGate) },
                style = DmButtonStyle.ModalPrimary,
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            if (group != null) {
                ZillitText(
                    text = "${group.label} setup is pending.",
                    style = DmType.sans(14.sp, FontWeight.SemiBold),
                    color = dm.ink,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val which = when (group) {
                SetupGroup.Union -> "Union"
                SetupGroup.NonUnion -> "Non-union"
                null -> "Union or Non-union"
            }
            ZillitText(
                text = "You need to go to Deal Memo Setup to fill up Production Entity, select $which and fill " +
                    "accordingly.",
                style = DmType.sans(13.sp, FontWeight.Medium).copy(lineHeight = 20.sp),
                color = dm.ink2,
            )
        }
    }
}
