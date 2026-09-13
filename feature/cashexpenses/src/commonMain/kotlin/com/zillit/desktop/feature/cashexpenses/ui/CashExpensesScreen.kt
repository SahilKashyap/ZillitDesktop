package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.pages.ActiveFloatsPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.CashExtensionPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.CashSettingsPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.CodingEditorDialog
import com.zillit.desktop.feature.cashexpenses.ui.pages.DepartmentOverviewPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.FloatRequestPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.MyOverviewPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.OutOfPocketOverviewPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.PaymentRoutingPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.PettyCashOverviewPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.QueuePage
import com.zillit.desktop.feature.cashexpenses.ui.pages.ReceiptsHistoryPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.ReconciliationPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.SubmitReceiptsPage
import com.zillit.desktop.feature.cashexpenses.ui.pages.TopUpsPage

/**
 * The Cash Expenses tool.
 *
 * ## Chrome, then one page
 *
 * The frame is constant — a header, the pipeline switcher, the shared queues,
 * and the sub-navigation for whichever pipeline is showing — and the body is
 * whichever [CashDestination] is open. Keeping the chrome outside the page is
 * what lets an accountant move between nine surfaces without the window
 * appearing to reload.
 *
 * ## Every tab here is one this viewer may use
 *
 * The tab lists come from [CashUiState.sectionDestinations] and
 * [CashUiState.sharedDestinations], which filter by rights. A destination the
 * viewer cannot open is never drawn, so there is no path to a "you do not have
 * access" page inside the tool.
 */
@Composable
fun CashExpensesScreen(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    /**
     * The crew photo for a user id — every name in this tool is shown with one.
     *
     * Defaulted to nothing so tests and previews compose without a host; a
     * missing face falls back to initials rather than a gap.
     */
    loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
) {
    // The cash service sends people as ids; the crew list is what names them.
    val people = remember(state.assignees) { CashPeople(state.assignees) }
    ProvideCashFaces(loadAvatar, people) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            CashHeader(state, onEvent)
            ZillitDivider()
            Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                CashBody(state, onEvent)
            }
        }

        CashPromptDialog(
            prompt = state.prompt,
            assignees = state.assignees,
            batch = (state.prompt as? CashPrompt.Assign)?.let { open ->
                state.queueBatches.firstOrNull { it.id == open.batchId }
            },
            onEvent = onEvent,
        )

        // Over the page rather than inside it: coding is a focused task, and
        // the queue behind stays where it was so the next row is one click away.
        CodingEditorDialog(state, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(CashEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
    }
}

/**
 * The cross-pipeline queues, at the trailing end of the pipeline switcher.
 *
 * As wide as its tabs and no wider. A tab strip fills whatever width it is
 * given, and as the switcher's trailing content that was the whole header: the
 * switcher measured to nothing, so a shared queue had no way back to the
 * pipeline pages and Out of Pocket could not be opened at all (seen live
 * 2026-09-13).
 */
@Composable
private fun SharedQueueTabs(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    ZillitTabStrip(
        tabs = state.sharedDestinations.map { ZillitTab(it.slug, it.label) },
        activeId = state.destination.slug,
        onSelect = { slug ->
            CashDestination.fromSlug(slug)?.let { onEvent(CashEvent.Open(it)) }
        },
        modifier = androidx.compose.ui.Modifier.width(IntrinsicSize.Max),
    )
}

@Composable
private fun CashHeader(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = "Finance",
            title = "Cash Expenses",
            description = if (state.viewer.isAccountant) {
                "Petty cash floats, out-of-pocket claims, receipt auditing and cash reconciliation."
            } else {
                "Request a float, submit your receipts, and follow what happens to them."
            },
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(CashEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            },
        )

        // Pipeline switcher on the left, cross-pipeline queues on the right —
        // the same arrangement as the web, so someone moving between the two
        // clients finds the Audit Queue in the same corner.
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(ExpenseType.PettyCash.wire, ExpenseType.PettyCash.label),
                ZillitTab(ExpenseType.OutOfPocket.wire, ExpenseType.OutOfPocket.label),
            ),
            activeId = if (state.onSharedPage) null else state.pipeline.wire,
            onSelect = { onEvent(CashEvent.SwitchPipeline(ExpenseType.from(it))) },
            size = TabStripSize.Primary,
            trailing = { SharedQueueTabs(state, onEvent) },
        )

        if (!state.onSharedPage) {
            val pages = state.sectionDestinations
            if (pages.isNotEmpty()) {
                ZillitTabStrip(
                    tabs = pages.map { ZillitTab(it.slug, it.label) },
                    activeId = state.destination.slug,
                    onSelect = { slug ->
                        CashDestination.fromSlug(slug)?.let { onEvent(CashEvent.Open(it)) }
                    },
                )
            }
        }
    }
}

/**
 * Dispatches to the open page.
 *
 * A load failure replaces the body rather than the whole tool, so the tabs
 * stay usable and the reader can move to a page that does work — which on a
 * location connection is often the difference between "the app is down" and
 * "that queue is down".
 */
@Suppress("CyclomaticComplexMethod") // One branch per page; a map would hide the routing.
@Composable
private fun CashBody(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val error = state.error
    if (error != null) {
        ZillitErrorState(
            message = error.localised(),
            onRetry = { onEvent(CashEvent.Refresh) },
        )
        return
    }

    when (state.destination) {
        CashDestination.PettyCashOverview -> PettyCashOverviewPage(state, onEvent)
        CashDestination.OutOfPocketOverview -> OutOfPocketOverviewPage(state, onEvent)
        CashDestination.MyOverview -> MyOverviewPage(state, onEvent)
        CashDestination.DepartmentOverview -> DepartmentOverviewPage(state)
        CashDestination.ActiveFloats -> ActiveFloatsPage(state, onEvent)
        CashDestination.TopUps -> TopUpsPage(state, onEvent)
        CashDestination.FloatRequest -> FloatRequestPage(state, onEvent)
        CashDestination.CashExtension -> CashExtensionPage(state, onEvent)
        CashDestination.SubmitReceipts, CashDestination.OutOfPocketSubmit ->
            SubmitReceiptsPage(state, onEvent)

        CashDestination.ReceiptsHistory, CashDestination.OutOfPocketHistory ->
            ReceiptsHistoryPage(state, onEvent)

        CashDestination.PaymentRouting -> PaymentRoutingPage(state)
        CashDestination.CashReconciliation -> ReconciliationPage(state, onEvent)
        CashDestination.Settings -> CashSettingsPage(state, onEvent)

        // Every remaining destination is a queue of batches; they differ in
        // which rows arrive and which actions a row offers, both of which
        // QueuePage derives from the destination rather than duplicating.
        else -> QueuePage(state, onEvent)
    }
}
