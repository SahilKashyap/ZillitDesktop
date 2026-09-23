package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.HubPageHeader
import com.zillit.desktop.feature.accounthub.ui.components.CoaIcons
import com.zillit.desktop.feature.accounthub.ui.components.CoaTabSpec
import com.zillit.desktop.feature.accounthub.ui.components.CoaUnderlineTabs
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog

/**
 * The Chart of Accounts — the web's `ChartOfAccountsModule`.
 *
 * ## One chart, three tabs
 *
 * Cost Accounts and Balance Sheet Codes are the same rows filtered by class,
 * not two resources: they share one fetch, and a code that changes class moves
 * between them. Layers are the analytical dimensions beside the chart, with
 * their own CRUD.
 *
 * ## Tree or table, and a grid
 *
 * The chart is drawn two ways — an indented tree with hover actions, and a
 * sortable table with a breadcrumb and an inline class select — and codes are
 * added on a full-page grid that saves itself row by row ([ChartBulkAddPage]).
 * The edit form, the deactivate confirmation and the budget import open over
 * the page, as they do on the web.
 */
@Composable
fun ChartOfAccountsPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    /** Whether the host wired storage; false hides Import Budget. */
    canImportBudget: Boolean = false,
) {
    val chart = state.chart
    val bulk = chart.bulk
    if (bulk != null) {
        ChartBulkAddPage(state, bulk, onEvent)
        return
    }

    HubPage {
        HubPageHeader(
            eyebrow = str(S.desktop_setup),
            title = str(S.desktop_chart_of_accounts),
            description = str(S.desktop_hub_the_nominal_taxonomy_that_drives_cost_report_every_line_item_dashes),
        )

        CoaUnderlineTabs(
            tabs = listOf(
                CoaTabSpec(ChartView.Expense.slug, ChartView.Expense.label, CoaIcons.Tree),
                CoaTabSpec(ChartView.BalanceSheet.slug, ChartView.BalanceSheet.label, CoaIcons.Table),
                CoaTabSpec(ChartView.Layers.slug, ChartView.Layers.label, CoaIcons.Sliders),
            ),
            activeId = chart.view.slug,
            onSelect = { slug ->
                ChartView.entries.firstOrNull { it.slug == slug }?.let { onEvent(AccountHubEvent.SwitchChartView(it)) }
            },
        )

        if (!state.viewer.canActAsAccountant) {
            // Named specifically. The rest of the console *is* editable by an
            // admin, so a silently read-only screen here reads as a bug rather
            // than as the rule it is.
            ZillitNotice(
                text = str(S.desktop_hub_the_chart_is_read_only_for_you_the_service_restricts),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (chart.view == ChartView.Layers) {
            ChartLayersTab(state, onEvent)
        } else {
            ChartAccountsTab(state, onEvent, canImportBudget)
        }
    }

    ChartAccountDialog(state, onEvent)
    HubConfirmDialog(
        visible = chart.confirmDeactivate != null,
        title = str(S.desktop_deactivate_code),
        message = chart.confirmDeactivate?.let { "Deactivate \"${it.label("·")}\"?" }.orEmpty(),
        confirmLabel = if (chart.deactivating) str(S.desktop_deactivating) else str(S.dm_notices_deactivate),
        loading = chart.deactivating,
        onConfirm = { onEvent(AccountHubEvent.ConfirmDeactivateAccount) },
        onDismiss = { onEvent(AccountHubEvent.DismissDeactivateAccount) },
    )
    ChartLayerDialogs(state, onEvent)
    // The web opens the import over the chart rather than sending the accountant
    // to the Budget page; closing it reads the chart again.
    if (canImportBudget) BudgetImportDialog(state, onEvent)
}
