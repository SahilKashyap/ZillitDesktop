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
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings as DomainPayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings.Companion as DomainPayroll
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.ui.SpendSetup
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
fun ProductionSetupPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    /** Whether the host wired file storage; false leaves Agreements read-only. */
    canAttachAgreements: Boolean = false,
) {
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

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            when (setup.tab) {
                SetupTab.Accounting -> AccountingSections(state, onEvent, canAttachAgreements)
                SetupTab.DealMemo -> DealMemoSections(state, onEvent, canAttachAgreements)
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
    canAttachAgreements: Boolean,
) {
    CompaniesSection(state, onEvent)
    BankAccountsSection(state, onEvent)
    // The web's order within this tab: currencies, tags, then taxes.
    CurrenciesSection(state, onEvent)
    AssetTagsSection(state, onEvent)
    TaxTypesSection(state, onEvent)
    PoSetupSection(state, onEvent, canAttach = canAttachAgreements)
    InvoicesSetupSection(state, onEvent)
    PayrollSettingsSection(state, onEvent)
    TimecardSetupTile(onEvent)
    SpendSetupTiles(onEvent)
    // Project Budget is deliberately absent. The web still has the component
    // (`sections/ProjectBudgetSection.jsx`) but imports it nowhere, so the
    // section no longer renders there — the project's budget lives in the
    // hub's own Budget module now. Found 2026-09-09 reviewing against the web.
}

@Composable
private fun ColumnScope.DealMemoSections(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttachAgreements: Boolean,
) {
    ScheduleSection(state, onEvent)
    // The web's order: rate cards first, allowances next, then the document /
    // clause / bureau cluster.
    NonUnionPaySection(state, onEvent)
    // Beside the pay breakdown, as on the web, and saved separately — see
    // DayTypesSection.
    DayTypesSection(state, onEvent)
    AllowancesSection(state, onEvent)
    AgreementsSection(state, onEvent, canAttach = canAttachAgreements)
    DealConditionsSection(state, onEvent)
    PayrollBureausSection(state, onEvent)
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
        description = "The legal entities that own this project's bank accounts.",
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
            EmptyLine("No bank accounts on this project yet.")
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
        description = "Everything this project transacts in, and the one that pre-fills " +
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

/**
 * Payroll approvers, and the week a pay period runs over.
 *
 * A section rather than the web's drill-down modal: this page already renders
 * everything the hub itself owns as a card, and the two settings here are one
 * list and one pair of days. The tile-and-modal shape the web uses is reserved
 * for settings that belong to *another* tool — the Card, Petty Cash and Time
 * Card tiles below.
 *
 * The row reaches further than this screen: payroll-server reads it directly,
 * so an approver added here is an approver on a payroll run.
 */
@Composable
private fun PayrollSettingsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.payrollSettings
    val value = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Payroll Settings",
        description = "Who may sign off a payroll run, and the seven days a pay period covers.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollSettings)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.PayrollSettings)) },
        editable = editable,
    ) {
        ZillitTextField(
            value = value.approverIds.joinToString(", "),
            onValueChange = { text ->
                val ids = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                onEvent(AccountHubEvent.EditPayrollSettings(value.copy(approverIds = ids)))
            },
            label = "Approver user ids",
            placeholder = "Comma-separated",
            enabled = editable,
        )

        ZillitSectionLabel("Pay period")
        PayPeriodPicker(value, editable, onEvent)
    }
}

/**
 * The seven-day window, either end of which moves the other.
 *
 * A pay period can never be four days or nine — payroll downstream assumes a
 * week, and the web enforces the same invariant by pairing the two selects.
 */
@Composable
private fun PayPeriodPicker(
    value: DomainPayrollSettings,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val days = (DomainPayroll.MONDAY..DomainPayroll.SUNDAY).toList()
    val enabled = editable && !value.payPeriodLocked

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = value.payPeriodStartDay,
            options = days,
            onSelect = { day ->
                onEvent(
                    AccountHubEvent.EditPayrollSettings(
                        value.copy(payPeriodStartDay = day, payPeriodEndDay = DomainPayroll.endFor(day)),
                    ),
                )
            },
            label = { DomainPayroll.dayName(it) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = "to", style = ZillitTheme.typography.bodySmall)
        ZillitSelect(
            value = value.payPeriodEndDay,
            options = days,
            onSelect = { day ->
                onEvent(
                    AccountHubEvent.EditPayrollSettings(
                        value.copy(payPeriodEndDay = day, payPeriodStartDay = DomainPayroll.startFor(day)),
                    ),
                )
            },
            label = { DomainPayroll.dayName(it) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
    if (value.payPeriodLocked) {
        ZillitNotice(
            text = "The pay period is fixed — this production already has timecards against " +
                "it, and the service refuses a change once that is true.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
}

/**
 * Card and Petty Cash setup, which live in those tools.
 *
 * The web opens a modal here that edits each module's own `/settings`
 * document. The desktop already renders that document — both tools have a
 * Settings page of their own — so a modal over it would be a second editor for
 * one record, and the two would disagree the first time either changed. These
 * tiles hand off instead, deep-linking to the page that owns the setting, the
 * way the Time Card tile above does.
 *
 * Purchase Orders and Invoices have no tile: neither tool has a settings
 * surface on this client yet, so a Configure button would open onto nothing.
 * Their web details are the remaining Production Setup work.
 */
@Composable
private fun SpendSetupTiles(onEvent: (AccountHubEvent) -> Unit) {
    SetupModuleTile(
        title = "Production Expense Cards Setup",
        description = "Coding requirements, senior sign-off and statement matching for every " +
            "card on this production.",
        icon = ZillitIcons.CreditCard,
        actionText = "Open card settings",
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.Cards)) },
    )
    SetupModuleTile(
        title = "Petty Cash Entry Setup",
        description = "Float custodian, coordinator coding and the sign-off a batch passes " +
            "through before it posts.",
        icon = ZillitIcons.Wallet,
        actionText = "Open petty cash settings",
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.PettyCash)) },
    )
}

/**
 * Time Card Configuration, which is not configured here.
 *
 * The web renders this as a tile among the setup sections and clicking it
 * *navigates* to the Timecard tool rather than opening a modal, because in
 * zillit the configuration belongs to that tool
 * (`TimecardSetupSection.jsx`). The desktop does the same: the tile is a
 * signpost, and the hand-off is the hub's existing one.
 *
 * No configured pill: nothing on this wire says whether it is set up. The web
 * hardcodes "Setup required" here, which is a claim it cannot support — an
 * absent pill is the honest version of the same tile.
 */
@Composable
private fun TimecardSetupTile(onEvent: (AccountHubEvent) -> Unit) {
    SetupModuleTile(
        title = "Time Card Entry Setup",
        description = "Control model, department setup, approval chain, cadence, allowance " +
            "rules and data-source priority for crew time cards.",
        icon = ZillitIcons.Clock,
        actionText = "Open Time Card",
        onConfigure = { onEvent(AccountHubEvent.OpenTimecardSetup) },
    )
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
internal fun EmptyLine(text: String) {
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
