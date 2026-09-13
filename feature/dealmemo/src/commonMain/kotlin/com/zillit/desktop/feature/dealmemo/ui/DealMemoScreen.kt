package com.zillit.desktop.feature.dealmemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.dealmemo.ui.builder.RulesTarget
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.ProvideDealFaces
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.AllDealsPage
import com.zillit.desktop.feature.dealmemo.ui.pages.ApprovalQueuePage
import com.zillit.desktop.feature.dealmemo.ui.pages.DealsDialogs
import com.zillit.desktop.feature.dealmemo.ui.pages.HistoryPanel
import com.zillit.desktop.feature.dealmemo.ui.pages.MyDealPage
import com.zillit.desktop.feature.dealmemo.ui.pages.NoticeTemplatePage
import com.zillit.desktop.feature.dealmemo.ui.pages.NoticesDialogs
import com.zillit.desktop.feature.dealmemo.ui.pages.NoticesPage
import com.zillit.desktop.feature.dealmemo.ui.pages.OverviewPage
import com.zillit.desktop.feature.dealmemo.ui.pages.UnbuiltPage
import com.zillit.desktop.feature.dealmemo.ui.pages.builder.BuilderPage
import com.zillit.desktop.feature.dealmemo.ui.pages.builder.RuleImportModal
import com.zillit.desktop.feature.dealmemo.ui.pages.builder.SetupHubPage
import com.zillit.desktop.feature.dealmemo.ui.pages.crew.CrewDetailsPage
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.DealPreviewPage
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.PreviewDialogs
import com.zillit.desktop.feature.dealmemo.ui.pages.rates.GlobalRatesPage
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.RulesEditorPage

/**
 * The Deal Memo tool — one entry for everyone. Posting users manage the
 * production's deals; crew see their own; approvers get a queue. Which of
 * those a person sees is decided in [DealMemoUiState.page], never here.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun DealMemoScreen(
    state: DealMemoUiState,
    onEvent: (DealMemoEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(dm.page)) {
        when (val page = state.page) {
            is DealMemoRoute.Tab ->
                if (state.rights.isPending) PendingMyDeal(state, onEvent) else TabShell(state, page.tab, onEvent)
            DealMemoRoute.GlobalRates -> GlobalRatesPage(state, onEvent)
            DealMemoRoute.NoticeTemplate -> NoticeTemplatePage(state, onEvent)
            is DealMemoRoute.Deal -> DealPreviewPage(state, onEvent)
            DealMemoRoute.CompleteDetails -> CrewDetailsPage(state, onEvent)
            is DealMemoRoute.SetupHub -> if (page.isBuilder) {
                BuilderPage(state, onEvent)
            } else {
                SetupHubPage(state, page, onEvent)
            }
            else -> if (page.isBuilder) BuilderPage(state, onEvent) else UnbuiltPage(page, onEvent)
        }
        // The rules grid takes over the whole window, over whichever page opened it.
        state.preview?.let { preview ->
            preview.rules?.let {
                RulesEditorPage(state, it, "Deal Memos · ${preview.deal?.reference ?: "Deal Memo"}", onEvent)
            }
        }
        state.builder?.let { builder ->
            builder.rules?.let { editor ->
                val eyebrow = if (builder.rulesTarget == RulesTarget.Project) {
                    "Deal Memo Setup · Non-union pay rules"
                } else {
                    "Deal Memos · ${builder.dealReference ?: "New Deal Memo"}"
                }
                RulesEditorPage(state, editor, eyebrow, onEvent)
            }
            RuleImportModal(builder, onEvent)
        }
        // Dialogs sit over the whole window, header and tabs included, as the web's portals do.
        DealsDialogs(state, onEvent)
        NoticesDialogs(state, onEvent)
        PreviewDialogs(state, onEvent)
        HistoryPanel(state.history, state, onEvent)
        ToastHost(state, onEvent)
    }
}

/** The web's `DealMemoTabShell`: the header, the tab bar, and the page under them. */
@Composable
private fun TabShell(state: DealMemoUiState, tab: DealTab, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
            ModuleHeader(onBack = { onEvent(DealMemoEvent.LeaveTool) })
            TabBar(state, tab, onEvent)
        }
        Box(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)) {
            when (tab) {
                DealTab.Overview -> OverviewPage(state, onEvent)
                DealTab.Deals -> AllDealsPage(state, onEvent)
                DealTab.MyDeal -> MyDealPage(state, onEvent)
                DealTab.ApprovalQueue -> ApprovalQueuePage(state, onEvent)
                DealTab.Notices -> NoticesPage(state, onEvent)
            }
        }
    }
}

/** A member whose invitation is pending: My Deal alone, no header, no tabs. */
@Composable
private fun PendingMyDeal(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        BackSquare(onClick = { onEvent(DealMemoEvent.LeaveTool) })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) { MyDealPage(state, onEvent) }
    }
}

/** `PageHeader`: the amber `CONTRACTS` eyebrow and hairline, the Syne title, the description. */
@Composable
private fun ModuleHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            text = "CONTRACTS",
            style = DmType.sans(12.sp, FontWeight.SemiBold, 0.2.em),
            color = dm.brand,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(dm.brand.copy(alpha = 0.2f)))
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BackSquare(onClick = onBack, modifier = Modifier.padding(top = 4.dp))
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            ZillitText(
                text = "Deal Memo",
                style = DmType.display(30.sp, FontWeight.Bold, (-0.025).em).copy(lineHeight = 36.sp),
                color = dm.ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            ZillitText(
                text = "Create and manage crew deal memos — rates, allowances, contract periods, and signature " +
                    "workflows.",
                style = DmType.sans(14.sp),
                color = dm.ink2,
                modifier = Modifier.fillMaxWidth(DESCRIPTION_WIDTH),
            )
        }
    }
}

/** The 30 px back square — "Back to film tools". */
@Composable
internal fun BackSquare(onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 30, tooltip: String = "Back") {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(shape)
                .background(if (hovered) dm.controlHoverBg else dm.control)
                .border(1.dp, dm.controlBorder, shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.ChevronLeft, size = 14.dp, tint = dm.ink3)
        }
    }
}

/** The tabs with their unread counts, and Global Production Rates on the right for posting users. */
@Composable
private fun TabBar(state: DealMemoUiState, active: DealTab, onEvent: (DealMemoEvent) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(dm.hairline))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.visibleTabs.forEach { tab ->
                    TabItem(
                        label = tab.label,
                        count = tabCount(state, tab),
                        selected = tab == active,
                        onClick = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(tab))) },
                    )
                }
            }
            if (state.rights.canPost) {
                DmButton(
                    text = "Global Production Rates",
                    onClick = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.GlobalRates)) },
                    style = DmButtonStyle.GhostSmall,
                    icon = DmIcons.Globe,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

private fun tabCount(state: DealMemoUiState, tab: DealTab): Int = when (tab) {
    DealTab.Overview -> 0
    DealTab.Deals -> state.badges.tab(DealBadgeUnit.AllDeals)
    DealTab.MyDeal -> state.badges.tab(DealBadgeUnit.MyDeal)
    DealTab.ApprovalQueue -> state.badges.tab(DealBadgeUnit.ApprovalQueue)
    DealTab.Notices -> state.badges.tab(DealBadgeUnit.Notices)
}

@Composable
private fun TabItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val underline = dm.brand
    Box(
        modifier = Modifier
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            // Drawn, not laid out: a full-width child would stretch the tab over the whole row.
            .drawBehind {
                if (selected) {
                    val stroke = 2.dp.toPx()
                    drawRect(underline, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = label,
                style = DmType.sans(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = when {
                    selected -> dm.brand
                    hovered -> dm.ink
                    else -> dm.ink2
                },
                maxLines = 1,
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF4D4F))
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = if (count > MAX_TAB_COUNT) "99+" else count.toString(),
                        style = DmType.sans(11.sp, FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The web's antd message: success and error toasts, and the pinned progress note. */
@Composable
private fun ToastHost(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val toast = state.toast
    ZillitToast(
        message = toast?.message,
        onDismiss = { onEvent(DealMemoEvent.DismissToast) },
        tone = if (toast?.tone == DealToastTone.Error) ZillitToastTone.Danger else ZillitToastTone.Success,
    )
    state.progressToast?.let { note ->
        Box(modifier = Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(dm.card)
                    .border(1.dp, dm.cardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitSpinner(size = 14.dp, color = dm.brand)
                ZillitText(text = note, style = DmType.sans(13.sp), color = dm.ink, maxLines = 1)
            }
        }
    }
}

/** Deal Memo as a workspace window. */
class DealMemoToolProvider(
    private val viewModel: DealMemoViewModel,
    private val loadAvatar: suspend (String) -> ImageBitmap? = { null },
) : ToolProvider {

    override val path: String = DEAL_MEMO_PATH
    override val title: String = "Deal Memo"
    override val icon = ZillitToolIcons.DealMemo
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 900.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel, navigator) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    DealMemoEffect.LeaveTool -> navigator.navigate(WorkspaceRoute.Tool(TOOLS_GRID_PATH))
                }
            }
        }
        // A path below the tool names a page — a deep link or a restored window.
        LaunchedEffect(route.path) {
            if (route.path.startsWith(DEAL_MEMO_PATH) && route.path.length > DEAL_MEMO_PATH.length) {
                viewModel.onEvent(DealMemoEvent.OpenPath(route.path))
            }
        }
        LaunchedEffect(state.page) { navigator.setTitle("Deal Memo · ${pageTitle(state.page)}") }

        ProvideDealFaces(loadAvatar) {
            DealMemoScreen(state = state, onEvent = viewModel::onEvent)
        }
    }

    private fun pageTitle(page: DealMemoRoute): String = when (page) {
        is DealMemoRoute.Tab -> page.tab.label
        is DealMemoRoute.Deal -> "Deal"
        is DealMemoRoute.NewDeal, is DealMemoRoute.QuickDeal -> "New Deal"
        is DealMemoRoute.EditDeal -> "Edit Deal"
        is DealMemoRoute.TemplateWizard, is DealMemoRoute.TemplateBuilder, DealMemoRoute.FirstSetup,
        is DealMemoRoute.SetupHub,
        -> "Deal Memo Setup"
        DealMemoRoute.GlobalRates -> "Global Production Rates"
        DealMemoRoute.NoticeTemplate -> "Notice Template"
        DealMemoRoute.CompleteDetails -> "Complete your details"
    }

    private companion object {
        const val TOOLS_GRID_PATH = "/home/tools"
    }
}

private const val DESCRIPTION_WIDTH = 0.6f
private const val MAX_TAB_COUNT = 99
