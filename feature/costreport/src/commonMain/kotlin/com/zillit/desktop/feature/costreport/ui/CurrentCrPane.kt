// One composable per piece of the crew tool's Current CR tab.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrForecast
import com.zillit.desktop.feature.costreport.domain.CrRow
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrTableSpec
import com.zillit.desktop.feature.costreport.domain.buildWorksheetTable
import com.zillit.desktop.feature.costreport.domain.CrOverrides
import kotlinx.coroutines.launch

private val SEARCH_WIDTH = 230.dp
private val SELECT_WIDTH = 200.dp

/**
 * The crew tool's Current CR — the web's `LiveCRTabContent` over `LiveCR`:
 * Company · Budget · Currency that apply as they change, the report heading,
 * and the week read-only, with the ledger a click away.
 */
@Composable
internal fun CurrentCrPane(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val current = state.current
    Column(Modifier.fillMaxSize()) {
        when {
            state.referenceError != null -> ZillitNotice(
                text = state.referenceError,
                tone = StatusTone.Rejected,
                modifier = Modifier.padding(24.dp),
                action = {
                    ZillitButton(text = str(S.retry), onClick = { onEvent(CostReportEvent.Refresh) },
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                },
            )
            current.unavailable -> ZillitEmptyState(
                title = str(S.desktop_cr_unavailable),
                message = str(S.desktop_cr_unavailable_detail),
                icon = ZillitIcons.BarChart,
            )
            else -> {
                FilterBar(state, onEvent)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        LiveHeading(state, onEvent)
                        current.error?.let { message ->
                            ZillitNotice(
                                text = message,
                                tone = StatusTone.Rejected,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                action = {
                                    ZillitButton(text = str(S.retry), onClick = { onEvent(CostReportEvent.Refresh) },
                                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                                },
                            )
                        }
                        if (current.loaded) LiveGrid(state, onEvent, Modifier.weight(1f))
                    }
                    CrLoaderOverlay(
                        visible = state.referenceLoading || current.phase != null,
                        message = current.phase ?: str(S.ah_loading),
                    )
                }
            }
        }
    }
}

/** Company · Budget · Currency, on the sunken strip under the header. */
@Composable
private fun FilterBar(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val current = state.current
    val allCompanies = CrCompany(id = "", name = str(S.cr_all_companies))
    val companies = listOf(allCompanies) + current.companies
    val liveBudget = BudgetVersion(id = "", version = "", label = str(S.cr_live_budget), status = "")
    val budgets = current.budgets.ifEmpty { listOf(liveBudget) }
    val currencies = state.currencyChoices
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineFilter(str(S.company)) {
            ZillitSelect(
                value = current.selectedCompany ?: allCompanies,
                options = companies,
                onSelect = { onEvent(CostReportEvent.SelectCompany(it.id.takeIf { id -> id.isNotBlank() })) },
                label = { it.name.ifBlank { it.id } },
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        InlineFilter(str(S.budget_text)) {
            ZillitSelect(
                value = current.selectedBudget ?: budgets.first(),
                options = budgets,
                onSelect = { onEvent(CostReportEvent.SelectBudget(it.version.takeIf { v -> v.isNotBlank() })) },
                label = { it.display },
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        if (currencies.isNotEmpty()) {
            InlineFilter(str(S.asset_currency)) {
                ZillitSelect(
                    value = currencies.firstOrNull { it.code.equals(current.currencyCode, ignoreCase = true) }
                        ?: currencies.first(),
                    options = currencies,
                    onSelect = { onEvent(CostReportEvent.SelectCurrency(it.code)) },
                    label = { currencyLabel(it, state) },
                    modifier = Modifier.width(SELECT_WIDTH),
                )
            }
        }
    }
}

@Composable
private fun InlineFilter(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = ZillitTheme.colors.textMuted,
        )
        content()
    }
}

private fun currencyLabel(currency: CrCurrency, state: CostReportUiState): String {
    val symbol = currency.symbol.ifBlank { state.symbolFor(currency.code).trim() }
    val bare = symbol.isBlank() || symbol.equals(currency.code, ignoreCase = true)
    return if (bare) currency.code else "${currency.code} ($symbol)"
}

/** "Current Cost Report" · project · today · week, the search, and the stale pill. */
@Composable
private fun LiveHeading(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit) {
    val current = state.current
    CrReportHeading(
        projectName = state.projectName,
        todayMs = current.todayMs,
        weekLabel = current.week?.label,
        query = current.query,
        onQuery = { onEvent(CostReportEvent.SearchCurrent(it)) },
        trailing = {
            if (state.sourceStale) {
                ZillitButton(
                    text = str(S.desktop_cr_source_updated),
                    onClick = { onEvent(CostReportEvent.Refresh) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                )
            }
        },
    )
}

/** The Live CR heading both surfaces share: title and subtitle, then the search. */
@Composable
internal fun CrReportHeading(
    projectName: String,
    todayMs: Long?,
    weekLabel: String?,
    query: String,
    onQuery: (String) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val colors = ZillitTheme.colors
    val subtitle = remember(projectName, todayMs, weekLabel) {
        listOfNotNull(
            projectName.ifBlank { str(S.dm_step2_external_off) },
            todayMs?.let { com.zillit.desktop.feature.costreport.domain.CrDates.weekdayDate(it).replace(",", "") },
            weekLabel,
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            ZillitText(
                str(S.desktop_cr_current_cost_report),
                style = ZillitTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.ExtraBold),
            )
            ZillitText(subtitle, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
        }
        ZillitSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = str(S.desktop_cr_find_code_or_name),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        Box(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LiveGrid(state: CostReportUiState, onEvent: (CostReportEvent) -> Unit, modifier: Modifier) {
    val current = state.current
    val forecast = remember(current.baseline) { CrForecast(CrOverrides.NONE, current.baseline) }
    val table = remember(current.sections, forecast, current.query, current.toggles) {
        buildWorksheetTable(current.sections, forecast, CrTableSpec(search = current.query, toggles = current.toggles))
    }
    val actions = remember(onEvent) {
        CrGridActions(
            onSection = { onEvent(CostReportEvent.ToggleSection(it)) },
            onHeader = { onEvent(CostReportEvent.ToggleHeader(it)) },
            onNominal = { onEvent(CostReportEvent.ToggleNominal(it)) },
            onLedger = { nominal, column -> onEvent(CostReportEvent.OpenLedger(nominal, column)) },
        )
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        val uncoded = current.sections.firstOrNull { it.isUncoded }
        val uncodedShown = table.rows.any { it is CrRow.Section && it.section.isUncoded }
        val showBanner = uncoded != null && (!table.search.active || uncodedShown)
        if (showBanner) {
            CrUncodedBanner(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                onView = {
                    if (!current.toggles.sectionOpen(CrSection.UNCODED_SECTION_ID)) {
                        onEvent(CostReportEvent.ToggleSection(CrSection.UNCODED_SECTION_ID))
                    }
                    scope.launch {
                        val index = table.rows.indexOfFirst { it is CrRow.Section && it.section.isUncoded }
                        if (index >= 0) list.scrollToItem(index)
                    }
                },
            )
        }
        CrWorksheetGrid(
            table = table,
            symbol = current.symbol,
            decimals = 0,
            projectName = state.projectName,
            sort = CrSort(),
            locked = false,
            actions = actions,
            listState = list,
            modifier = Modifier.weight(1f),
        )
    }
}
