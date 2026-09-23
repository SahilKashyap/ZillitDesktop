package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.CurrencyFilter
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.sectionLoad
import com.zillit.desktop.feature.accounthub.ui.asAmountText
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoChip
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SectionLoad
import com.zillit.desktop.feature.accounthub.ui.components.SectionLoadState
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

// The two catalogue-driven sections of Production Setup's accounting tab:
// currencies picked from the preset list, and tax rates picked per country.

// -- currencies -------------------------------------------------------------

/**
 * The web's `ProjectCurrenciesSection`: the selection on the left — a default
 * picker and one card per currency with its rate against the default — and
 * the catalogue on the right as a searchable grid of toggling tiles. A
 * non-default currency without a positive rate blocks the save.
 */
@Composable
internal fun CurrenciesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val settings = setup.currencies.edited
    val editable = state.viewer.canEdit
    val missing = settings.missingRates
    fun edit(next: CurrencySettings) = onEvent(AccountHubEvent.EditCurrencies(next))

    SectionShell(
        title = str(S.desktop_project_currencies),
        description = str(S.desktop_hub_every_currency_this_production_transacts_in_drives_fx_warnings_and),
        dirty = setup.currencies.dirty,
        saving = setup.currencies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Currencies)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Currencies)) },
        load = state.sectionLoad(SetupSection.Currencies, onEvent),
        editable = editable,
        leftPanel = { SelectedCurrenciesPanel(state, settings, editable, ::edit) },
    ) {
        if (missing.isNotEmpty()) {
            ZillitNotice(
                text = "Add an exchange rate for ${missing.joinToString(", ")} before saving.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }
        CurrencyCatalogue(state, settings, editable, ::edit, onEvent)
    }
}

/** The left column: "Selected · n", Clear all, the default picker and the selected cards. */
@Composable
private fun SelectedCurrenciesPanel(
    state: AccountHubUiState,
    settings: CurrencySettings,
    editable: Boolean,
    edit: (CurrencySettings) -> Unit,
) {
    val selected = settings.currencies.map(state.setup::currencyMeta)
    val missing = settings.missingRates
    Column(
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            MonoLabel(str(S.selected))
            Spacer(Modifier.width(ZillitTheme.spacing.xs))
            if (selected.isNotEmpty()) MonoChip(selected.size.toString(), active = true)
            Spacer(Modifier.weight(1f))
            if (editable && selected.isNotEmpty()) {
                ZillitButton(
                    text = str(S.docusign_initials_clear_all),
                    onClick = { edit(CurrencySettings()) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        if (selected.isEmpty()) {
            EmptyCurrencies()
        } else {
            DefaultCurrencyPicker(
                options = selected,
                value = settings.defaultCode,
                editable = editable,
                onChange = { edit(settings.copy(defaultCode = it)) },
            )
            selected.forEach { currency ->
                SelectedCurrencyCard(
                    currency = currency,
                    isDefault = currency.code == settings.defaultCode,
                    invalid = currency.code in missing,
                    editable = editable,
                    onRate = { text ->
                        edit(
                            settings.copy(
                                currencies = settings.currencies.map {
                                    if (it.code == currency.code) it.copy(rate = text.toDoubleOrNull()) else it
                                },
                            ),
                        )
                    },
                    onRemove = { edit(settings.without(currency.code)) },
                )
            }
        }
    }
}

@Composable
private fun EmptyCurrencies() {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = str(S.desktop_hub_no_currencies_selected_yet),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        FieldHint(str(S.desktop_hub_pick_at_least_one_from_the_list_arrow))
    }
}

/**
 * The default-currency picker — the web's card: a header, the current default
 * as a symbol badge, and one chip per selected currency. Only a picked
 * currency can be the default.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultCurrencyPicker(
    options: List<ProjectCurrency>,
    value: String?,
    editable: Boolean,
    onChange: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    val current = options.firstOrNull { it.code == value }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.desktop_default_currency),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                )
                FieldHint(str(S.desktop_hub_pre_fills_new_transactions_across_every_module))
            }
            if (current != null) {
                Row(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .background(colors.surface)
                        .border(1.dp, colors.accent, ZillitTheme.shapes.pill)
                        .padding(
                            start = ZillitTheme.spacing.xs,
                            end = ZillitTheme.spacing.sm,
                            top = 2.dp,
                            bottom = 2.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    SymbolPill(current.symbol.ifBlank { current.code.take(1) }, active = true, size = SYMBOL_SMALL)
                    ZillitText(
                        text = current.code,
                        style = ZillitTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.accentText,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            options.forEach { currency ->
                val active = currency.code == value
                Row(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.pill)
                        .background(if (active) colors.surface else Color.Transparent)
                        .border(1.dp, if (active) colors.accent else Color.Transparent, ZillitTheme.shapes.pill)
                        .clickable(enabled = editable) { onChange(currency.code) }
                        .padding(
                            start = ZillitTheme.spacing.xs,
                            end = ZillitTheme.spacing.sm,
                            top = 3.dp,
                            bottom = 3.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    SymbolPill(currency.symbol.ifBlank { currency.code.take(1) }, active = active, size = SYMBOL_SMALL)
                    ZillitText(
                        text = currency.code,
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (active) colors.accentText else colors.textSecondary,
                    )
                    if (active) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accentText, size = CHECK_SMALL)
                }
            }
        }
    }
}

/** A chosen currency — the web's gradient card with the rate against the default, or "BASE · 1" on the default. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun SelectedCurrencyCard(
    currency: ProjectCurrency,
    isDefault: Boolean,
    invalid: Boolean,
    editable: Boolean,
    onRate: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.surfaceSunken,
                        colors.accentSoft.copy(alpha = if (colors.isDark) DARK_WASH else LIGHT_WASH),
                    ),
                ),
            )
            .border(
                if (isDefault) 2.dp else 1.dp,
                if (isDefault) colors.accent else colors.border,
                ZillitTheme.shapes.large,
            )
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        SymbolPill(currency.symbol.ifBlank { currency.code.take(1) }, active = true, size = SYMBOL_LARGE)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = currency.code,
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                if (isDefault) Pill(str(S.desktop_email_format_default), tone = StatusTone.Pending)
            }
            ZillitText(
                text = currency.country.ifBlank { currency.name },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        if (isDefault) {
            MonoChip(str(S.desktop_base_1))
        } else {
            // The text is held beside the parsed rate, not derived from it: a
            // field bound to a Double turns "1." back into "1" under the
            // cursor and a decimal can never be typed. Only a change from
            // outside — a revert, a reload — resets what is shown.
            var text by remember(currency.code) { mutableStateOf(currency.rate.asAmountText()) }
            if (text.toDoubleOrNull() != currency.rate && !(text.isBlank() && currency.rate == null)) {
                text = currency.rate.asAmountText()
            }
            ZillitTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    onRate(typed)
                },
                placeholder = "1.00",
                enabled = editable,
                errorText = if (invalid) str(S.docusign_prop_required) else null,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(RATE_WIDTH),
            )
        }
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Remove ${currency.code}",
                onClick = onRemove,
            )
        }
    }
}

/** The right column: search, All / Major chips, and the grid of tiles. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CurrencyCatalogue(
    state: AccountHubUiState,
    settings: CurrencySettings,
    editable: Boolean,
    edit: (CurrencySettings) -> Unit,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val setup = state.setup
    val chosen = settings.currencies.map { it.code }.toSet()
    val shown = setup.currencyChoices
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FieldLabel(str(S.desktop_add_currencies), modifier = Modifier.weight(1f))
            MonoChip("${shown.size} / ${setup.currencyCatalogue.size}")
        }
        ZillitSearchField(
            value = setup.currencySearch,
            onValueChange = { onEvent(AccountHubEvent.SearchCurrencies(it)) },
            placeholder = str(S.desktop_hub_search_by_code_name_country_or_symbol),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            CurrencyFilter.entries.forEach { filter ->
                ZillitButton(
                    text = filter.label,
                    onClick = { onEvent(AccountHubEvent.SetCurrencyFilter(filter)) },
                    variant = if (setup.currencyFilter == filter) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        when {
            setup.currencyCatalogue.isEmpty() && setup.currencyCatalogueError != null -> SectionLoadState(
                SectionLoad(
                    error = setup.currencyCatalogueError,
                    onRetry = { onEvent(AccountHubEvent.RetryCatalogues) },
                ),
            )
            setup.currencyCatalogue.isEmpty() -> FieldHint(str(S.desktop_loading_currencies))
            shown.isEmpty() -> FieldHint(
                if (setup.currencySearch.isBlank()) {
                    str(S.desktop_hub_no_currencies_match_the_current_filter)
                } else {
                    "No currencies match “${setup.currencySearch}”."
                },
            )
            else -> Column(
                // Bounded and scrolling on its own, as the web's grid is: the
                // catalogue is ~150 tiles and must not stretch the page.
                modifier = Modifier.heightIn(max = GRID_MAX).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                shown.chunked(TILE_COLUMNS).forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        line.forEach { currency ->
                            CurrencyTile(
                                currency = currency,
                                selected = currency.code in chosen,
                                enabled = editable,
                                onClick = { edit(settings.toggled(currency)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(TILE_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** One catalogue tile: symbol pill, code, name, and a check (chosen) or plus (available) at the edge. */
@Composable
private fun CurrencyTile(
    currency: ProjectCurrency,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else if (hovered) colors.surfaceHover else colors.surfaceSunken)
            .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        SymbolPill(currency.symbol.ifBlank { currency.code.take(1) }, active = selected, size = SYMBOL_LARGE)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = currency.code,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (selected) colors.accentText else colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = currency.name,
                style = ZillitTheme.typography.labelSmall,
                color = if (selected) colors.accentText else colors.textMuted,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .size(CHECK_CIRCLE)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = if (selected) ZillitIcons.Check else ZillitIcons.Add,
                tint = if (selected) colors.textOnAccent else colors.textMuted,
                size = CHECK_SMALL,
            )
        }
    }
}

/** The peach-wash symbol square every currency surface leads with. */
@Composable
private fun SymbolPill(symbol: String, active: Boolean, size: androidx.compose.ui.unit.Dp) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(1.dp, if (active) Color.Transparent else colors.border, ZillitTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = symbol,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) colors.accentText else colors.textSecondary,
            maxLines = 1,
        )
    }
}

// -- tax types --------------------------------------------------------------

/**
 * Tax rates — the web's `TaxTypesSection`: add a country and every one of its
 * catalogue rates is ticked; each country is a group with a tri-state header,
 * and a ticked rate carries a reclaimable flag and a nominal. Custom rates
 * sit below with a 0–100 guard.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
internal fun TaxTypesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val rows = setup.taxTypes.edited
    val editable = state.viewer.canEdit
    val countries = setup.countryTaxes
    val chosenCodes = (rows.mapNotNull { it.countryCode } + setup.taxCountries).distinct()
    val available = countries.filter { it.countryCode !in chosenCodes }
    // A country stays chosen when its last rate is unticked; only its chip removes it.
    fun edit(next: List<TaxType>) {
        val emptied = chosenCodes.filter { code -> next.none { it.countryCode == code } }
        emptied.forEach { onEvent(AccountHubEvent.SetTaxCountry(it, chosen = true)) }
        onEvent(AccountHubEvent.EditTaxTypes(next))
    }

    SectionShell(
        title = str(S.desktop_tax_types),
        description = str(S.desktop_hub_choose_which_tax_rates_are_available_across_card_expenses_and),
        dirty = setup.taxTypes.dirty,
        saving = setup.taxTypes.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.TaxTypes)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.TaxTypes)) },
        load = state.sectionLoad(SetupSection.TaxTypes, onEvent),
        editable = editable,
        leftPanel = { TaxSummary(rows, countries) },
    ) {
        FieldLabel(str(S.desktop_countries))
        if (countries.isEmpty() && setup.countryTaxesError != null) {
            SectionLoadState(
                SectionLoad(
                    error = setup.countryTaxesError,
                    onRetry = { onEvent(AccountHubEvent.RetryCatalogues) },
                ),
            )
        }
        SelectedCountryChips(chosenCodes, countries, editable) { code ->
            onEvent(AccountHubEvent.EditTaxTypes(rows.filterNot { it.countryCode == code }))
            onEvent(AccountHubEvent.SetTaxCountry(code, chosen = false))
        }
        if (editable) {
            HubSelect(
                value = null,
                options = available,
                label = { it.country },
                secondary = { "${it.countryCode} · ${it.taxes.size} rate${if (it.taxes.size == 1) "" else "s"}" },
                // Adding a country ticks all of it — the usual case is wanting
                // the lot, and unticking one is a click.
                onSelect = { picked ->
                    if (picked != null) {
                        onEvent(AccountHubEvent.SetTaxCountry(picked.countryCode, chosen = true))
                        edit(rows + picked.taxes.filter { tax -> rows.none { it.identifier == tax.identifier } })
                    }
                },
                placeholder = when {
                    countries.isEmpty() -> str(S.desktop_loading_countries)
                    chosenCodes.isEmpty() -> str(S.desktop_add_a_country)
                    else -> str(S.desktop_add_another_country)
                },
                enabled = countries.isNotEmpty() && available.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        chosenCodes.forEach { code ->
            val country = countries.firstOrNull { it.countryCode == code }
            if (country != null) {
                CountryRateGroup(country, rows, editable, state, onEvent, ::edit)
            } else {
                StoredCountryGroup(code, rows, editable, ::edit)
            }
        }
        CustomRates(rows, editable, state, onEvent, ::edit)
        TaxType.problem(rows)?.let { ZillitNotice(text = it, tone = StatusTone.Rejected, icon = ZillitIcons.Warning) }
    }
}

/** The left column: the active count, then one line per country with a dot per rate — filled when ticked. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun TaxSummary(rows: List<TaxType>, countries: List<CountryTaxes>) {
    val colors = ZillitTheme.colors
    val byCountry = rows.filterNot { it.isCustom }.groupBy { it.countryCode.orEmpty() }
    val custom = rows.count { it.isCustom && it.label.isNotBlank() }
    val total = rows.count { !it.isCustom } + custom
    Column(
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = total.toString(),
                style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            )
            Column(modifier = Modifier.padding(bottom = 3.dp)) {
                MonoLabel(str(S.desktop_active_rates))
                FieldHint("across ${byCountry.size} ${if (byCountry.size == 1) "country" else "countries"}")
            }
        }
        byCountry.forEach { (code, taxes) ->
            val catalogue = countries.firstOrNull { it.countryCode == code }
            val name = catalogue?.country ?: taxes.firstOrNull()?.country.orEmpty()
            val tint = TINTS[byCountry.keys.indexOf(code) % TINTS.size]
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .background(tint(colors).copy(alpha = TINT_WASH))
                        .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp),
                ) {
                    ZillitText(
                        text = code,
                        style = ZillitTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = tint(colors),
                    )
                }
                ZillitText(
                    text = name.ifBlank { code },
                    style = ZillitTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val all = catalogue?.taxes ?: taxes
                    all.forEach { tax ->
                        val on = taxes.any { it.identifier == tax.identifier }
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (on) tint(colors) else colors.borderStrong),
                        )
                    }
                }
            }
        }
        if (custom > 0) FieldHint("$custom custom rate${if (custom == 1) "" else "s"}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCountryChips(
    codes: List<String>,
    countries: List<CountryTaxes>,
    editable: Boolean,
    onRemove: (String) -> Unit,
) {
    if (codes.isEmpty()) {
        FieldHint(str(S.desktop_hub_no_countries_yet_add_one_below))
        return
    }
    val colors = ZillitTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        codes.forEach { code ->
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.pill)
                    .background(colors.accentSoft)
                    .padding(
                        start = ZillitTheme.spacing.sm,
                        end = if (editable) 2.dp else ZillitTheme.spacing.sm,
                        top = 2.dp,
                        bottom = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = countries.firstOrNull { it.countryCode == code }?.country ?: code,
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.accentText,
                )
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = "Remove $code",
                        onClick = { onRemove(code) },
                        tint = colors.accentText,
                        size = CHIP_CLOSE,
                    )
                }
            }
        }
    }
}

/** One country's rates as a group: a tri-state header and one row per catalogue rate. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CountryRateGroup(
    country: CountryTaxes,
    rows: List<TaxType>,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    edit: (List<TaxType>) -> Unit,
) {
    val colors = ZillitTheme.colors
    val checked = country.taxes.count { tax -> rows.any { it.identifier == tax.identifier } }
    val allOn = checked == country.taxes.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            TriStateBox(
                state = when {
                    allOn -> TriState.On
                    checked > 0 -> TriState.Partial
                    else -> TriState.Off
                },
                enabled = editable,
                onClick = {
                    edit(
                        if (allOn) {
                            rows.filterNot { row -> country.taxes.any { it.identifier == row.identifier } }
                        } else {
                            rows + country.taxes.filter { tax -> rows.none { it.identifier == tax.identifier } }
                        },
                    )
                },
            )
            MonoChip(country.countryCode)
            ZillitText(
                text = country.country,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            FieldHint("$checked of ${country.taxes.size}")
        }
        country.taxes.forEach { tax ->
            val existing = rows.firstOrNull { it.identifier == tax.identifier }
            val on = existing != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (on) {
                            colors.accent.copy(alpha = if (colors.isDark) DARK_ROW_WASH else LIGHT_ROW_WASH)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitCheckbox(
                    checked = on,
                    onCheckedChange = { next ->
                        edit(if (next) rows + tax else rows.filterNot { it.identifier == tax.identifier })
                    },
                    label = "${tax.label} · ${tax.value}%",
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
                if (existing != null) {
                    ZillitCheckbox(
                        checked = existing.isRecoverable,
                        onCheckedChange = { next ->
                            edit(
                                rows.map {
                                    if (it.identifier == existing.identifier) it.copy(isRecoverable = next) else it
                                },
                            )
                        },
                        label = "Reclaimable",
                        enabled = editable,
                    )
                    CoaCodeField(
                        value = existing.nominal,
                        onValueChange = { code ->
                            edit(rows.map { if (it.identifier == existing.identifier) it.copy(nominal = code) else it })
                        },
                        accounts = state.chart.accounts,
                        placeholder = str(S.dm_rule_nominal),
                        enabled = editable,
                        modifier = Modifier.width(NOMINAL_WIDTH),
                        onCreate = quickCreateHandler(state, onEvent),
                    )
                }
            }
        }
    }
}

/**
 * Rates stored for a country the catalogue no longer lists. Kept and shown
 * rather than dropped: they are somebody's agreed rates, and a save that
 * silently lost them would be worse than a group with no picker.
 */
@Composable
private fun StoredCountryGroup(code: String, rows: List<TaxType>, editable: Boolean, edit: (List<TaxType>) -> Unit) {
    val colors = ZillitTheme.colors
    val mine = rows.filter { it.countryCode == code }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            MonoChip(code)
            ZillitText(
                text = mine.firstOrNull()?.country?.ifBlank { code } ?: code,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            FieldHint(str(S.desktop_hub_not_in_the_catalogue))
        }
        mine.forEach { tax ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitCheckbox(
                    checked = true,
                    onCheckedChange = { edit(rows - tax) },
                    label = "${tax.label} · ${tax.value}%",
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class TriState { On, Partial, Off }

/** The group header's box: filled for all, half-tinted for some, hollow for none. */
@Composable
private fun TriStateBox(state: TriState, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(TRI_BOX)
            .clip(ZillitTheme.shapes.small)
            .background(
                when (state) {
                    TriState.On -> colors.accent
                    TriState.Partial -> colors.accent.copy(alpha = PARTIAL_ALPHA)
                    TriState.Off -> colors.surface
                },
            )
            .border(
                1.dp,
                if (state == TriState.Off) colors.borderStrong else Color.Transparent,
                ZillitTheme.shapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state != TriState.Off) ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = CHECK_SMALL)
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CustomRates(
    rows: List<TaxType>,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    edit: (List<TaxType>) -> Unit,
) {
    val custom = rows.filter { it.isCustom }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = ZillitTheme.spacing.xs)) {
        FieldLabel(str(S.desktop_custom_rates), modifier = Modifier.weight(1f))
        if (editable) {
            GhostAddButton(
                text = str(S.desktop_add_custom_rate),
                onClick = {
                    // Minted from what is already there rather than from a
                    // counter, so two sessions cannot both mint custom_3.
                    edit(rows + TaxType(identifier = TaxType.nextCustomIdentifier(rows), type = "custom"))
                },
            )
        }
    }
    if (custom.isEmpty()) FieldHint(str(S.desktop_no_custom_rates))
    custom.forEach { tax ->
        fun update(next: TaxType) = edit(rows.map { if (it.identifier == tax.identifier) next else it })
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = tax.type,
                onValueChange = { update(tax.copy(type = it)) },
                placeholder = str(S.desktop_hub_type_e_g_vat_paren),
                enabled = editable,
                modifier = Modifier.width(TYPE_WIDTH),
            )
            ZillitTextField(
                value = tax.label,
                onValueChange = { update(tax.copy(label = it)) },
                placeholder = str(S.desktop_rate_description),
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = tax.value,
                onValueChange = { update(tax.copy(value = it)) },
                placeholder = str(S.desktop_rate_pct),
                enabled = editable,
                keyboardType = KeyboardType.Decimal,
                errorText = if (TaxType.isRateOutOfRange(tax.value)) "0–100" else null,
                modifier = Modifier.width(RATE_WIDTH),
            )
            ZillitCheckbox(
                checked = tax.isRecoverable,
                onCheckedChange = { update(tax.copy(isRecoverable = it)) },
                label = "Reclaimable",
                enabled = editable,
            )
            CoaCodeField(
                value = tax.nominal,
                onValueChange = { update(tax.copy(nominal = it)) },
                accounts = state.chart.accounts,
                placeholder = str(S.dm_rule_nominal),
                enabled = editable,
                modifier = Modifier.width(NOMINAL_WIDTH),
                onCreate = quickCreateHandler(state, onEvent),
            )
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove ${tax.label}",
                    onClick = { edit(rows - tax) },
                )
            }
        }
    }
}

/** The web's five country tints, cycled in order of appearance. */
private val TINTS: List<(com.zillit.desktop.core.designsystem.ZillitColors) -> Color> = listOf(
    { it.info },
    { it.success },
    { it.accent },
    { it.violet },
    { it.teal },
)

private const val TINT_WASH = 0.14f
private const val LIGHT_WASH = 0.7f
private const val DARK_WASH = 0.22f
private const val LIGHT_ROW_WASH = 0.08f
private const val DARK_ROW_WASH = 0.08f
private const val PARTIAL_ALPHA = 0.4f
private const val TILE_COLUMNS = 3
private val GRID_MAX = 520.dp
private val SYMBOL_SMALL = 20.dp
private val SYMBOL_LARGE = 34.dp
private val CHECK_CIRCLE = 22.dp
private val CHECK_SMALL = 11.dp
private val CHIP_CLOSE = 18.dp
private val TRI_BOX = 18.dp
private val RATE_WIDTH = 96.dp
private val TYPE_WIDTH = 120.dp
private val NOMINAL_WIDTH = 150.dp
