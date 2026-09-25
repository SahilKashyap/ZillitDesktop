package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CatalogueCurrency
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The project-currency select the credit-note and sales forms share — the
 * web's `RichSelect` over `useCurrencyOptions` (`CreditsPage.jsx:602-615`,
 * `SalesPage.jsx:756-769`): the production's currencies, each labelled
 * `currencyLabel(code, symbol, country)` and searchable by code, name and symbol.
 */
@Composable
internal fun CurrencyPicker(
    state: InvoicesUiState,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val catalogue = state.currencyCatalogue.associateBy { it.code.uppercase() }
    val options = (state.currencyOptions + value).filter { it.isNotBlank() }.map { it.uppercase() }.distinct()
    SearchPicker(
        value = value.uppercase().takeIf { it.isNotBlank() },
        options = options,
        label = { code -> currencyLabel(code, catalogue[code]) },
        searchText = { code -> catalogue[code]?.let { "${it.code} ${it.name} ${it.symbol}" } ?: code },
        subline = { code -> catalogue[code]?.name.orEmpty() },
        onSelect = onSelect,
        placeholder = str(S.desktop_dm_select_project_currency),
        enabled = enabled,
        modifier = modifier,
    )
}

/** `GBP £ — United Kingdom` — the web's `currencyLabel(code, symbol, country)`. */
internal fun currencyLabel(code: String, row: CatalogueCurrency?): String {
    if (code.isBlank()) return ""
    val head = row?.symbol?.takeIf { it.isNotBlank() }?.let { "$code $it" } ?: code
    return row?.country?.takeIf { it.isNotBlank() }?.let { "$head — $it" } ?: head
}
