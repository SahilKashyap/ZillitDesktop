package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.DateRangeText
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.SetupTab

/**
 * Production Setup — the configuration every other hub area reads from.
 *
 * Two tabs, because the sections belong to two different owners: the accounting
 * side lives in the finance schema, and the deal-memo side in the production
 * one. Users think of them that way too — an accountant sets currencies and a
 * production coordinator sets the shoot dates.
 *
 * The page scrolls and holds no data table. That is deliberate: a virtualised
 * table inside a scrolling column is measured against an unbounded height and
 * Compose refuses outright, so every list here is composed in full.
 */
@Composable
fun ProductionSetupPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Production Setup",
            description = "Companies, banks, currencies and the defaults every other " +
                "finance tool reads from.",
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(AccountHubEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = setup.loading,
                )
            },
        )

        if (!state.viewer.canEdit) {
            ZillitNotice(
                text = "You can see this configuration but not change it — edits are the " +
                    "accounts department's.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (setup.dirtySections.isNotEmpty()) {
            // Named rather than counted: "2 unsaved sections" on a page this
            // long leaves the user hunting for which two.
            ZillitNotice(
                text = "Unsaved changes in ${setup.dirtySections.joinToString(", ")}.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitTabStrip(
            tabs = SetupTab.entries.map { ZillitTab(it.slug, it.label) },
            activeId = setup.tab.slug,
            onSelect = { slug ->
                SetupTab.entries.firstOrNull { it.slug == slug }
                    ?.let { onEvent(AccountHubEvent.SwitchSetupTab(it)) }
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).zillitVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            when (setup.tab) {
                SetupTab.Accounting -> AccountingSections(state, onEvent)
                SetupTab.DealMemo -> DealMemoSections(state, onEvent)
            }
        }
    }

    CompanyDialog(state, onEvent)
    BankAccountDialog(state, onEvent)
}

@Composable
private fun ColumnScope.AccountingSections(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    CompaniesSection(state, onEvent)
    BankAccountsSection(state, onEvent)
    CurrenciesSection(state, onEvent)
    TaxTypesSection(state, onEvent)
    AssetTagsSection(state, onEvent)
    BudgetSection(state, onEvent)
}

@Composable
private fun ColumnScope.DealMemoSections(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ScheduleSection(state, onEvent)
    PayrollDefaultsSection(state, onEvent)
}

// -- companies --------------------------------------------------------------

@Suppress("LongMethod") // A section header and its rows, read together.
@Composable
private fun CompaniesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val companies = setup.companies.edited

    SetupSectionCard(
        title = "Companies",
        description = "The legal entities that own this production's bank accounts.",
        dirty = setup.companies.dirty,
        saving = setup.companies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Companies)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.Companies)) },
        editable = state.viewer.canEdit,
        action = {
            if (state.viewer.canEdit) {
                ZillitButton(
                    text = "Add company",
                    onClick = { onEvent(AccountHubEvent.EditCompany(null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        if (companies.isEmpty()) {
            EmptyLine("No companies yet. Bank accounts hang off a company, so add one first.")
            return@SetupSectionCard
        }
        companies.forEach { company ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = company.name.ifBlank { "Unnamed company" }, maxLines = 1)
                    val currencies = Companies.currencyCodes(company, setup.banks)
                    val subtitle = listOfNotNull(
                        company.country.takeIf { it.isNotBlank() },
                        "${company.bankIds.size} bank account" +
                            if (company.bankIds.size == 1) "" else "s",
                        // Derived from the linked banks, never stored — a
                        // co-production can straddle currencies legitimately.
                        currencies.joinToString(", ").takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    ZillitText(
                        text = subtitle,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
                if (state.viewer.canEdit) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit ${company.name}",
                        onClick = { onEvent(AccountHubEvent.EditCompany(company)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove ${company.name}",
                        onClick = { onEvent(AccountHubEvent.RemoveCompany(company.id)) },
                    )
                }
            }
        }
    }
}

// -- banks ------------------------------------------------------------------

@Composable
private fun BankAccountsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup

    SetupInfoCard(
        title = "Bank Accounts",
        // Said explicitly: this is the one section on the page with no Save
        // button, and its absence otherwise reads as a missing control.
        description = "Each account saves on its own — there is no section-level save here.",
        action = {
            if (state.viewer.canEdit) {
                ZillitButton(
                    text = "Add account",
                    onClick = { onEvent(AccountHubEvent.EditBank(null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        if (setup.banks.isEmpty()) {
            EmptyLine("No bank accounts on this production yet.")
            return@SetupInfoCard
        }
        setup.banks.forEach { bank ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = bank.name.ifBlank { "Unnamed account" }, maxLines = 1)
                    // Re-derived from the live companies rather than read off
                    // the stored snapshot, which goes stale the moment the
                    // owning company is deleted.
                    val holder = bank.holderName(setup.companies.edited, setup.banksLoading)
                    val subtitle = listOfNotNull(
                        holder.takeIf { it.isNotBlank() },
                        SortCode.formatted(bank.sortCode).takeIf { it.isNotBlank() },
                        bank.accountNumber.takeIf { it.isNotBlank() },
                        bank.currencyCode.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "—" }
                    ZillitText(
                        text = subtitle,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
                if (state.viewer.canEdit) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit ${bank.name}",
                        onClick = { onEvent(AccountHubEvent.EditBank(bank)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete ${bank.name}",
                        onClick = { onEvent(AccountHubEvent.DeleteBank(bank.id)) },
                    )
                }
            }
        }
    }
}

// -- currencies -------------------------------------------------------------

@Suppress("LongMethod") // Chips, the default line and the picker: one section.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrenciesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val settings = setup.currencies.edited

    SetupSectionCard(
        title = "Project Currencies",
        description = "Everything this production transacts in, and the one that pre-fills " +
            "new transactions.",
        dirty = setup.currencies.dirty,
        saving = setup.currencies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Currencies)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.Currencies)) },
        editable = state.viewer.canEdit,
    ) {
        if (settings.currencies.isEmpty()) {
            EmptyLine("No currencies selected yet.")
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                settings.currencies.forEach { currency ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitChoiceChip(
                            label = "${currency.symbol} ${currency.code}".trim(),
                            selected = currency.code == settings.defaultCode,
                            onClick = {
                                if (state.viewer.canEdit) {
                                    onEvent(
                                        AccountHubEvent.EditCurrencies(
                                            settings.copy(defaultCode = currency.code),
                                        ),
                                    )
                                }
                            },
                        )
                        if (state.viewer.canEdit) {
                            ZillitIconButton(
                                icon = ZillitIcons.Close,
                                contentDescription = "Remove ${currency.code}",
                                onClick = {
                                    onEvent(
                                        AccountHubEvent.EditCurrencies(settings.without(currency.code)),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            ZillitText(
                text = settings.default?.let { "Default: ${it.code} — ${it.name}".trimEnd(' ', '—') }
                    ?: "No default chosen. New transactions will not pre-fill a currency.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        if (state.viewer.canEdit && setup.currencyCatalogue.isNotEmpty()) {
            val chosen = settings.currencies.map { it.code }.toSet()
            ZillitSelect(
                value = ADD_CURRENCY,
                options = listOf(ADD_CURRENCY) + setup.currencyCatalogue.filter { it.code !in chosen },
                onSelect = { currency ->
                    if (currency.code.isNotBlank()) {
                        onEvent(
                            AccountHubEvent.EditCurrencies(
                                settings.copy(
                                    currencies = settings.currencies + currency,
                                    // The first currency added becomes the
                                    // default: a production with exactly one
                                    // currency and no default pre-fills nothing.
                                    defaultCode = settings.defaultCode ?: currency.code,
                                ),
                            ),
                        )
                    }
                },
                label = { if (it.code.isBlank()) it.name else "${it.code} — ${it.name}" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// -- tax types --------------------------------------------------------------

@Composable
private fun TaxTypesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val rows = setup.taxTypes.edited

    SetupSectionCard(
        title = "Tax Types",
        description = "The rates available on card expenses and every other posting surface.",
        dirty = setup.taxTypes.dirty,
        saving = setup.taxTypes.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.TaxTypes)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.TaxTypes)) },
        editable = state.viewer.canEdit,
        action = {
            if (state.viewer.canEdit) {
                ZillitButton(
                    text = "Add rate",
                    onClick = {
                        onEvent(
                            AccountHubEvent.EditTaxTypes(
                                rows + TaxType(
                                    // Minted from what is already there rather
                                    // than from a counter, so two sessions
                                    // editing the same project cannot collide.
                                    identifier = TaxType.nextCustomIdentifier(rows),
                                    label = "New rate",
                                    type = "custom",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        if (rows.isEmpty()) {
            EmptyLine("No tax rates configured.")
            return@SetupSectionCard
        }
        rows.forEachIndexed { index, tax ->
            TaxTypeRow(
                tax = tax,
                editable = state.viewer.canEdit,
                onChange = { next ->
                    onEvent(
                        AccountHubEvent.EditTaxTypes(
                            rows.toMutableList().also { it[index] = next },
                        ),
                    )
                },
                onRemove = { onEvent(AccountHubEvent.EditTaxTypes(rows - tax)) },
            )
        }
    }
}

@Composable
private fun TaxTypeRow(
    tax: TaxType,
    editable: Boolean,
    onChange: (TaxType) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = tax.label,
            onValueChange = { onChange(tax.copy(label = it)) },
            label = "Label",
            enabled = editable,
            modifier = Modifier.weight(WEIGHT_WIDE),
        )
        ZillitTextField(
            value = tax.value,
            onValueChange = { onChange(tax.copy(value = it)) },
            label = "Rate %",
            enabled = editable,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        ZillitCheckbox(
            checked = tax.isRecoverable,
            onCheckedChange = { onChange(tax.copy(isRecoverable = it)) },
            label = "Reclaimable",
            enabled = editable,
        )
        // The country a catalogue rate came from. The identifier itself is
        // never shown — it is plumbing for posting, not something to read.
        tax.countryCode?.let { ZillitStatusPill(label = it, tone = StatusTone.Neutral) }
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove ${tax.label}",
                onClick = onRemove,
            )
        }
    }
}

// -- asset tags -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetTagsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val tags = setup.assetTags.edited

    SetupSectionCard(
        title = "Asset Tags",
        description = "Labels the asset register offers when equipment is booked in.",
        dirty = setup.assetTags.dirty,
        saving = setup.assetTags.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.AssetTags)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.AssetTags)) },
        editable = state.viewer.canEdit,
    ) {
        if (tags.isEmpty()) EmptyLine("No asset tags yet.")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            tags.forEach { tag ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitStatusPill(label = tag, tone = StatusTone.Neutral)
                    if (state.viewer.canEdit) {
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = "Remove $tag",
                            onClick = { onEvent(AccountHubEvent.EditAssetTags(tags - tag)) },
                        )
                    }
                }
            }
        }
        if (state.viewer.canEdit) {
            TagEntry(onAdd = { tag -> onEvent(AccountHubEvent.EditAssetTags(tags + tag)) })
        }
    }
}

@Composable
private fun TagEntry(onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    ZillitTextField(
        value = draft,
        onValueChange = { draft = it },
        label = "Add a tag",
        placeholder = "Type a label and press Enter",
        imeAction = ImeAction.Done,
        onImeAction = {
            val tag = draft.trim()
            if (tag.isNotEmpty()) {
                onAdd(tag)
                draft = ""
            }
        },
    )
}

// -- budget -----------------------------------------------------------------

@Composable
private fun BudgetSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val budget = setup.budget.edited

    SetupSectionCard(
        title = "Project Budget",
        description = "The production's overall figure, against which the cost report runs.",
        dirty = setup.budget.dirty,
        saving = setup.budget.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Budget)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.Budget)) },
        editable = state.viewer.canEdit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                // The typed text, not a re-rendered parse of it. Bound to the
                // Double this turned `2500000` into `25.0` — every keystroke
                // reformatted the field under the caret.
                value = budget.amountText,
                onValueChange = { text ->
                    onEvent(AccountHubEvent.EditBudget(budget.copy(amountText = text)))
                },
                label = "Amount",
                enabled = state.viewer.canEdit,
                keyboardType = KeyboardType.Decimal,
                helperText = budget.amountText
                    .takeIf { it.isNotBlank() && it.trim().toDoubleOrNull() == null }
                    ?.let { "Not a number — this will save as no amount." },
                modifier = Modifier.weight(WEIGHT_WIDE),
            )
            ZillitTextField(
                value = budget.currency,
                onValueChange = { onEvent(AccountHubEvent.EditBudget(budget.copy(currency = it))) },
                label = "Currency",
                enabled = state.viewer.canEdit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// -- schedule ---------------------------------------------------------------

@Composable
private fun ScheduleSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val schedule = setup.schedule.edited

    SetupSectionCard(
        title = "Production Schedule",
        description = "Prep, shoot and wrap. These pre-fill a new deal memo's dates.",
        dirty = setup.schedule.dirty,
        saving = setup.schedule.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Schedule)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.Schedule)) },
        editable = state.viewer.canEdit,
    ) {
        DateRangeRow("Overall", schedule.overall, state.viewer.canEdit) {
            onEvent(AccountHubEvent.EditSchedule(schedule.copy(overall = it)))
        }
        DateRangeRow("Prep", schedule.prep, state.viewer.canEdit) {
            onEvent(AccountHubEvent.EditSchedule(schedule.copy(prep = it)))
        }
        DateRangeRow("Shoot", schedule.shoot, state.viewer.canEdit) {
            onEvent(AccountHubEvent.EditSchedule(schedule.copy(shoot = it)))
        }
        DateRangeRow("Wrap", schedule.wrap, state.viewer.canEdit) {
            onEvent(AccountHubEvent.EditSchedule(schedule.copy(wrap = it)))
        }
    }
}

/**
 * One phase's two dates.
 *
 * Holds the typed text. Bound to the parsed epoch instead, a date could not be
 * entered at all: `"2026-09-0"` parses to null, so the field emptied itself on
 * every keystroke.
 */
@Composable
private fun DateRangeRow(
    label: String,
    dates: DateRangeText,
    editable: Boolean,
    onChange: (DateRangeText) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = label, modifier = Modifier.weight(WEIGHT_LABEL))
        DateField(
            value = dates.from,
            onValueChange = { onChange(dates.copy(from = it)) },
            label = "From",
            editable = editable,
            modifier = Modifier.weight(1f),
        )
        DateField(
            value = dates.to,
            onValueChange = { onChange(dates.copy(to = it)) },
            label = "To",
            editable = editable,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    ZillitTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = "YYYY-MM-DD",
        enabled = editable,
        // Only once something has been typed that will not save — a date
        // half-entered is not yet an error.
        helperText = value.takeIf { it.isNotBlank() && IsoDate.toEpochMillis(it) == null }
            ?.let { "Not a date yet — use YYYY-MM-DD." },
        modifier = modifier,
    )
}

// -- payroll defaults -------------------------------------------------------

@Composable
private fun PayrollDefaultsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val defaults = setup.payrollDefaults.edited

    SetupSectionCard(
        title = "Payroll Defaults",
        description = "What happens to a deal once it is signed.",
        dirty = setup.payrollDefaults.dirty,
        saving = setup.payrollDefaults.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollDefaults)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.PayrollDefaults)) },
        editable = state.viewer.canEdit,
    ) {
        ZillitCheckbox(
            checked = defaults.autoSync,
            onCheckedChange = {
                onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(autoSync = it)))
            },
            label = "Sync signed deals to payroll automatically",
            enabled = state.viewer.canEdit,
        )
        ZillitCheckbox(
            checked = defaults.notifyPayroll,
            onCheckedChange = {
                onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(notifyPayroll = it)))
            },
            label = "Notify payroll when a deal is signed",
            enabled = state.viewer.canEdit,
        )
        ZillitCheckbox(
            checked = defaults.includePdf,
            onCheckedChange = {
                onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(includePdf = it)))
            },
            label = "Attach the signed PDF to that notification",
            enabled = state.viewer.canEdit,
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
    )
}

/**
 * The "pick one" row in a select that has no null.
 *
 * Adding a currency is an action, not a state, and the select takes a non-null
 * value — a sentinel with a blank code turns the picker into a one-shot action
 * without giving the component a nullable value to render.
 */
private val ADD_CURRENCY = ProjectCurrency(code = "", name = "Add a currency…")

private const val WEIGHT_WIDE = 2f
private const val WEIGHT_LABEL = 0.6f
