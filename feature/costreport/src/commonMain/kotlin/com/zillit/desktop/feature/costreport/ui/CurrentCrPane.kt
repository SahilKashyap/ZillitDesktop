// One composable per piece of the Current CR tab.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.FigureMode
import com.zillit.desktop.feature.costreport.domain.figuresFor
import com.zillit.desktop.feature.costreport.domain.flattenRows
import com.zillit.desktop.feature.costreport.domain.isEmptyBesidesTotal

private val SEARCH_WIDTH = 260.dp
private val SELECT_WIDTH = 220.dp

/** The Current CR tab: filter bar, title row, and the live worksheet. */
@Composable
internal fun CurrentCrPane(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val current = state.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        when {
            state.referenceError != null -> ZillitNotice(
                text = state.referenceError,
                tone = StatusTone.Rejected,
                action = {
                    ZillitButton(text = "Retry", onClick = { onEvent(CostReportEvent.Refresh) },
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                },
            )
            current.unavailable -> ZillitEmptyState(
                title = "Cost report is unavailable",
                message = "The accountant needs to upload Chart of Accounts and/or a Budget for this project " +
                    "before the cost report can run.",
                icon = ZillitIcons.BarChart,
            )
            state.referenceLoading && !current.loaded -> Loader(current.phase ?: "Loading…")
            else -> {
                FilterBar(state, onEvent)
                TitleRow(state, onEvent)
                current.error?.let { message ->
                    ZillitNotice(
                        text = message,
                        tone = StatusTone.Rejected,
                        action = {
                            ZillitButton(text = "Retry", onClick = { onEvent(CostReportEvent.Refresh) },
                                variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                        },
                    )
                }
                if (!current.loaded) {
                    Loader(current.phase ?: "Computing live report")
                } else {
                    LiveGrid(state, onEvent, Modifier.weight(1f))
                }
            }
        }
        if (current.loaded && current.phase != null) {
            ZillitText(text = current.phase, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

@Composable
private fun Loader(caption: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSpinner()
        ZillitText(text = caption, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** Company · Budget version · Currency. */
@Composable
private fun FilterBar(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val current = state.current
    val allCompanies = CrCompany(id = "", name = "All companies")
    val companies = listOf(allCompanies) + current.companies
    val liveBudget = BudgetVersion(id = "", version = "", label = "Live budget", status = "")
    val budgets = if (current.budgets.isEmpty()) listOf(liveBudget) else current.budgets
    val currencies = state.currencyChoices
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = current.selectedCompany ?: allCompanies,
            options = companies,
            onSelect = { onEvent(CostReportEvent.SelectCompany(it.id.takeIf { id -> id.isNotBlank() })) },
            label = { it.name.ifBlank { it.id } },
            modifier = Modifier.width(SELECT_WIDTH),
            enabled = current.phase == null,
        )
        ZillitSelect(
            value = current.selectedBudget ?: budgets.first(),
            options = budgets,
            onSelect = { onEvent(CostReportEvent.SelectBudget(it.version.takeIf { v -> v.isNotBlank() })) },
            label = { it.display },
            modifier = Modifier.width(SELECT_WIDTH),
            enabled = current.phase == null,
        )
        if (currencies.isNotEmpty()) {
            val selected = currencies.firstOrNull { it.code.equals(current.currencyCode, ignoreCase = true) }
                ?: currencies.first()
            ZillitSelect(
                value = selected,
                options = currencies,
                onSelect = { onEvent(CostReportEvent.SelectCurrency(it.code)) },
                label = { currencyLabel(it, state) },
                modifier = Modifier.width(SELECT_WIDTH),
                enabled = current.phase == null,
            )
        }
    }
}

private fun currencyLabel(currency: CrCurrency, state: CostReportUiState): String {
    val symbol = currency.symbol.ifBlank { state.symbolFor(currency.code).trim() }
    val bare = symbol.isBlank() || symbol.equals(currency.code, ignoreCase = true)
    return if (bare) currency.code else "${currency.code} ($symbol)"
}

/** "Current Cost Report" · project · today · week, and the search box. */
@Composable
private fun TitleRow(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val current = state.current
    val subtitle = remember(state.projectName, current.week, current.todayMs) {
        listOfNotNull(
            state.projectName.takeIf { it.isNotBlank() },
            current.todayMs?.let { CrDates.weekdayDate(it) },
            current.week?.label,
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = "Current Cost Report", style = ZillitTheme.typography.titleMedium)
            ZillitText(text = subtitle, style = ZillitTheme.typography.bodySmall, color = colors.textMuted,
                maxLines = 1)
        }
        ZillitSearchField(
            value = current.query,
            onValueChange = { onEvent(CostReportEvent.SearchCurrent(it)) },
            placeholder = "Find code or name",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitButton(
            text = "Refresh",
            onClick = { onEvent(CostReportEvent.Refresh) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Reload,
            loading = current.phase != null && current.loaded,
        )
    }
}

@Composable
private fun LiveGrid(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit, modifier: Modifier) {
    val current = state.current
    val rows = remember(current.sections, current.priorVariance, current.hasPrior, current.query, current.toggles) {
        flattenRows(
            sections = current.sections,
            figuresOf = figuresFor(FigureMode.Live, current.priorVariance, current.hasPrior),
            query = current.query,
            toggles = current.toggles,
        )
    }
    if (rows.isEmptyBesidesTotal() && current.query.isNotBlank()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            ZillitEmptyState(title = "No codes or descriptions match “${current.query.trim()}”.")
        }
        return
    }
    val actions = remember(onEvent) {
        GridActions(
            onSection = { onEvent(CostReportEvent.ToggleSection(it)) },
            onHeader = { onEvent(CostReportEvent.ToggleHeader(it)) },
            onNominal = { onEvent(CostReportEvent.ToggleNominal(it)) },
            onLedger = { nominal, column -> onEvent(CostReportEvent.OpenLedger(nominal, column)) },
        )
    }
    WorksheetGrid(
        rows = rows,
        mode = FigureMode.Live,
        symbol = current.symbol,
        projectName = state.projectName,
        actions = actions,
        modifier = modifier,
    )
}
