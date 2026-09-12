package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.CurrencyFilter
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.SetupTab
import com.zillit.desktop.feature.accounthub.ui.SpendSetup
import com.zillit.desktop.feature.accounthub.ui.asAmountText
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HubModuleCard
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoChip
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell

/**
 * Production Setup — the web's `ProductionSetupModule`.
 *
 * Two tabs, because the sections belong to two different owners: the accounting
 * side lives in the finance schema, and the deal-memo side in the production
 * one. Users think of them that way too — an accountant sets currencies and a
 * production coordinator sets the shoot dates. The sections follow the web's
 * order exactly, and the six module setups are tiles that open the web's
 * drill-down modals — or, for the three tools that already have a settings
 * page on this client, hand off to it.
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
    canOpenDocuments: Boolean = false,
) {
    val setup = state.setup

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Production Setup",
            description = "Project-wide defaults inherited by new deal memos, purchase orders, payroll runs, " +
                "and onboarding flows.",
        )

        if (!state.viewer.canEdit) {
            ZillitNotice(
                text = "You can see this configuration but not change it — edits are the " +
                    "accounts department's.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SetupTabBar(active = setup.tab, onSelect = { onEvent(AccountHubEvent.SwitchSetupTab(it)) })
        }

        if (setup.loading && !setup.loaded) {
            SetupSkeleton()
            return@HubPage
        }

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            when (setup.tab) {
                SetupTab.Accounting -> AccountingSections(state, onEvent)
                SetupTab.DealMemo -> DealMemoSections(state, onEvent, canAttachAgreements, canOpenDocuments)
            }
        }
    }

    CompanyDialog(state, onEvent)
    BankAccountDialog(state, onEvent)
    SetupRemovalDialog(state, onEvent)
    SetupModals(state, onEvent, canAttachAgreements, canOpenDocuments)
}

/**
 * The pill tab strip — an accent dot, the label, a mono count chip; the active
 * tab raised onto the surface (the web's `TabBar`).
 */
@Composable
private fun SetupTabBar(active: SetupTab, onSelect: (SetupTab) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        SetupTab.entries.forEach { tab ->
            val isActive = tab == active
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.large)
                    .background(if (isActive) colors.surface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) colors.accent else colors.borderStrong),
                )
                ZillitText(
                    text = tab.label,
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActive) colors.textPrimary else colors.textSecondary,
                )
                MonoChip(tab.count.toString(), active = isActive)
            }
        }
    }
}

/** The shimmer the web shows while the slices land — a header, a tab bar and three card stubs. */
@Composable
private fun ColumnScope.SetupSkeleton() {
    repeat(SKELETON_CARDS) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSkeletonBar(modifier = Modifier.width(SKELETON_TITLE))
            ZillitSkeletonBar(modifier = Modifier.fillMaxWidth(SKELETON_FILL))
            ZillitSkeletonBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ColumnScope.AccountingSections(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    // The web's order within this tab: the two relational sections first,
    // then currencies, tags and taxes, then the six module tiles.
    CompaniesSection(state, onEvent)
    BankAccountsSection(state, onEvent)
    CurrenciesSection(state, onEvent)
    AccountTagsSection(state, onEvent)
    TaxTypesSection(state, onEvent)
    HubModuleCard(
        title = "Payroll Entry Setup",
        description = "Approvers authorised to sign off payroll runs, and the project's pay-cycle window " +
            "(e.g. Mon → Sun or Wed → Tue).",
        icon = ZillitIcons.Wallet,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Payroll)) },
    )
    HubModuleCard(
        title = "Time Card Entry Setup",
        description = "Control model, department-level setup, approval chain, cadence, allowance rules, and " +
            "data-source priority for crew time cards.",
        icon = ZillitIcons.Clock,
        onConfigure = { onEvent(AccountHubEvent.OpenTimecardSetup) },
        actionText = "Open Time Card ›",
    )
    HubModuleCard(
        title = "Purchase Order Entry Setup",
        description = "Defaults for the PO module — description formatting, rental-split handling, and " +
            "auto-assignment rules.",
        icon = ZillitIcons.Receipt,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.PurchaseOrders)) },
    )
    HubModuleCard(
        title = "Invoices Entry Setup",
        description = "AP controls — posting limits, alert preferences, and the sign-off chain that gates " +
            "payment runs.",
        icon = ZillitIcons.File,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Invoices)) },
    )
    // Card and Petty Cash edit each module's own `/settings` document. The
    // desktop already renders that document in the tool, so a modal here
    // would be a second editor over one record; the tile deep-links instead.
    HubModuleCard(
        title = "Production Expense Cards Entry Setup",
        description = "Card-spend configuration — custodian, posting rights, approval shortcuts, deduction " +
            "rules, and auto-coding.",
        icon = ZillitIcons.CreditCard,
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.Cards)) },
        actionText = "Open card settings ›",
    )
    HubModuleCard(
        title = "Petty Cash Entry Setup",
        description = "Petty-cash configuration — float custodian, posting rights, approval shortcuts, " +
            "deduction rules, and auto-coding.",
        icon = ZillitIcons.Wallet,
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.PettyCash)) },
        actionText = "Open petty cash settings ›",
    )
}

@Composable
private fun ColumnScope.DealMemoSections(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttachAgreements: Boolean,
    canOpenDocuments: Boolean,
) {
    // The web's order: schedule, rate cards, allowances, then the document /
    // clause / bureau cluster, then payroll defaults.
    ScheduleSection(state, onEvent)
    NonUnionPaySection(state, onEvent)
    AllowancesSection(state, onEvent)
    AgreementsSection(state, onEvent, canAttach = canAttachAgreements, canOpen = canOpenDocuments)
    DealConditionsSection(state, onEvent)
    PayrollBureausSection(state, onEvent)
    PayrollDefaultsSection(state, onEvent)
}

// -- companies --------------------------------------------------------------

@Composable
private fun CompaniesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val companies = setup.companies.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Companies / Entities",
        description = "Legal entities that own this production's bank accounts. A bank can sit under at most one " +
            "company; the picker only lists banks not already claimed by another company.",
        dirty = setup.companies.dirty,
        saving = setup.companies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Companies)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Companies)) },
        editable = editable,
        extraActions = {
            if (editable) {
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
            EmptyLine("No companies added yet. Click Add company to create the first one.")
        }
        companies.forEach { company ->
            CompanyCard(state, company, editable, onEvent)
        }
    }
}

/**
 * One company — the web's card: a peach monogram, "Production Co.", the name
 * with a country chip and one pill per currency its banks span, and the
 * linked banks as mono chips.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CompanyCard(
    state: AccountHubUiState,
    company: com.zillit.desktop.feature.accounthub.domain.Company,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val banks = state.setup.banks
    val linked = banks.filter { it.id in company.bankIds }
    val currencies = Companies.currencyCodes(company, banks)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Column(
            modifier = Modifier.width(MONOGRAM_PANE).background(colors.surfaceSunken).padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier.size(MONOGRAM).clip(ZillitTheme.shapes.large).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = company.monogram,
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.accentText,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.success))
                FieldHint("Active · ${linked.size} ${if (linked.size == 1) "account" else "accounts"}")
            }
        }
        Row(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                MonoLabel("Production Co.")
                CompanyTitleRow(company.name, company.country, currencies)
                if (company.legalName.isNotBlank()) FieldHint("Legal name · ${company.legalName}")
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                MonoLabel("Linked banks")
                if (linked.isEmpty()) {
                    FieldHint("None")
                } else {
                    LinkedBankChips(linked.map { it.name.ifBlank { "Unnamed account" } })
                }
            }
            if (editable) {
                Column {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit ${company.name}",
                        onClick = { onEvent(AccountHubEvent.EditCompany(company)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove ${company.name}",
                        onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.CompanyRow(company))) },
                        tint = colors.danger,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanyTitleRow(name: String, country: String, currencies: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = name.ifBlank { "Unnamed company" },
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (country.isNotBlank()) Pill(country)
        currencies.forEach { Pill(it, tone = StatusTone.Pending) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedBankChips(names: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        names.forEach { MonoChip(it) }
    }
}

// -- banks ------------------------------------------------------------------

@Composable
private fun BankAccountsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Bank Accounts",
        description = "Project-level bank accounts that payroll and vendor disbursements default to. Stored in the " +
            "central bank-accounts table (the same one Bank Reconciliation uses).",
        editable = editable,
        extraActions = {
            if (editable) {
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
        // Said explicitly: this is the one section on the page with no Save
        // button, and its absence otherwise reads as a missing control.
        FieldHint("Each account saves on its own — there is no section-level save here.")
        if (setup.banks.isEmpty() && !setup.banksLoading) EmptyLine("No bank accounts on this project yet.")
        setup.banks.forEach { bank -> BankCard(state, bank, editable, onEvent) }
    }
}

/**
 * One bank — the web's card: the bank's name and currency, the holder as the
 * primary beat, and a three-column grid of the account details with the
 * number masked until revealed (and re-masked after five seconds).
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun BankCard(
    state: AccountHubUiState,
    bank: com.zillit.desktop.feature.accounthub.domain.BankAccount,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val setup = state.setup
    val revealed = setup.revealedBankId == bank.id
    // Re-derived from the live companies rather than read off the stored
    // snapshot, which goes stale the moment the owning company is deleted.
    val holder = bank.holderName(setup.companies.edited, setup.banksLoading)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Column(
            modifier = Modifier.width(BANK_LEFT).background(colors.surfaceSunken).padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = bank.name.ifBlank { "Unnamed account" },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (bank.currencyCode.isNotBlank()) Pill(bank.currencyCode, tone = StatusTone.Pending)
            }
            FieldLabel("Holder")
            ZillitText(
                text = holder.ifBlank { "—" },
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            // The render test keys on this line; it is also the one-glance summary.
            FieldHint(
                listOfNotNull(
                    holder.takeIf { it.isNotBlank() },
                    SortCode.formatted(bank.sortCode).takeIf { it.isNotBlank() },
                    bank.accountNumber.takeIf { it.isNotBlank() },
                    bank.currencyCode.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "—" },
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Account details", modifier = Modifier.weight(1f))
                if (bank.accountNumber.isNotBlank()) {
                    ZillitIconButton(
                        icon = ZillitIcons.Eye,
                        contentDescription = if (revealed) "Hide" else "Show (auto-hides in 5s)",
                        onClick = { onEvent(AccountHubEvent.RevealBank(if (revealed) null else bank.id)) },
                    )
                }
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit account",
                        onClick = { onEvent(AccountHubEvent.EditBank(bank)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove account",
                        onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.BankRow(bank))) },
                        tint = colors.danger,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                CardField(
                    "Account number",
                    if (revealed) bank.accountNumber else BankAccounts.masked(bank.accountNumber),
                    Modifier.weight(1.1f),
                )
                CardField("Sort code", SortCode.formatted(bank.sortCode), Modifier.weight(0.9f))
                CardField("SWIFT / BIC", bank.swiftCode, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                CardField("IBAN", bank.ibanNumber, Modifier.weight(1.1f))
                CardField("Nominal", bank.nominalCode, Modifier.weight(0.9f))
                CardField("AP clearance", bank.apClearanceNominalCode, Modifier.weight(1f))
            }
            if (bank.chequeNumber.isNotBlank() || bank.wireNumber.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    CardField("Cheque number", bank.chequeNumber, Modifier.weight(1.1f))
                    CardField("Wire number", bank.wireNumber, Modifier.weight(0.9f))
                    Box(Modifier.weight(1f))
                }
            }
            if (bank.additionalDetails.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    bank.additionalDetails.take(MAX_EXTRA_COLUMNS).forEach {
                        CardField(it.title, it.value, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        MonoLabel(label)
        ZillitText(
            text = value.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
    }
}

// -- currencies -------------------------------------------------------------

/**
 * The web's currency section: selected cards with a rate input on every
 * non-default one, a default-currency picker, and a searchable catalogue with
 * All / Major chips. A non-default currency without a positive rate blocks
 * the save.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrenciesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val settings = setup.currencies.edited
    val editable = state.viewer.canEdit
    val missing = settings.missingRates

    SectionShell(
        title = "Project Currencies",
        description = "Every currency this production transacts in. Drives FX warnings and the currency dropdown on " +
            "POs and invoices.",
        dirty = setup.currencies.dirty,
        saving = setup.currencies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Currencies)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Currencies)) },
        editable = editable,
        extraActions = {
            if (editable && settings.currencies.isNotEmpty()) {
                ZillitButton(
                    text = "Clear all",
                    onClick = { onEvent(AccountHubEvent.EditCurrencies(CurrencySettings())) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
    ) {
        if (settings.currencies.isEmpty()) {
            EmptyLine("No currencies selected yet. Pick them from the catalogue below.")
        } else {
            FieldLabel("Default currency")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                settings.currencies.forEach { currency ->
                    val isDefault = currency.code == settings.defaultCode
                    ZillitButton(
                        text = currency.code,
                        onClick = {
                            if (editable) onEvent(
                                AccountHubEvent.EditCurrencies(settings.copy(defaultCode = currency.code)),
                            )
                        },
                        variant = if (isDefault) ButtonVariant.Primary else ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = editable,
                    )
                }
            }
            FieldLabel("Selected")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                settings.currencies.forEach { currency ->
                    SelectedCurrencyCard(
                        currency = currency,
                        isDefault = currency.code == settings.defaultCode,
                        invalid = currency.code in missing,
                        editable = editable,
                        onRate = { text ->
                            onEvent(
                                AccountHubEvent.EditCurrencies(
                                    settings.copy(
                                        currencies = settings.currencies.map {
                                            if (it.code == currency.code) it.copy(rate = text.toDoubleOrNull()) else it
                                        },
                                    ),
                                ),
                            )
                        },
                        onRemove = { onEvent(AccountHubEvent.EditCurrencies(settings.without(currency.code))) },
                    )
                }
            }
            if (missing.isNotEmpty()) {
                ZillitNotice(
                    text = "Add an exchange rate for ${missing.joinToString(", ")} before saving.",
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Warning,
                )
            }
        }
        if (editable) CurrencyCatalogue(state, settings, onEvent)
    }
}

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
            .width(CURRENCY_CARD)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(
                if (isDefault) 2.dp else 1.dp,
                if (isDefault) colors.accent else colors.border,
                ZillitTheme.shapes.large,
            )
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = "${currency.symbol} ${currency.code}".trim(),
                    style = ZillitTheme.typography.titleSmall,
                )
                if (isDefault) Pill("Default", tone = StatusTone.Pending)
            }
            FieldHint(currency.name)
        }
        if (isDefault) {
            MonoChip("1.00")
        } else {
            ZillitTextField(
                value = currency.rate.asAmountText(),
                onValueChange = onRate,
                placeholder = "1.00",
                enabled = editable,
                errorText = if (invalid) "Rate" else null,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyCatalogue(
    state: AccountHubUiState,
    settings: CurrencySettings,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val setup = state.setup
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldLabel("Add currencies", modifier = Modifier.weight(1f))
            CurrencyFilter.entries.forEach { filter ->
                ZillitButton(
                    text = filter.label,
                    onClick = { onEvent(AccountHubEvent.SetCurrencyFilter(filter)) },
                    variant = if (setup.currencyFilter == filter) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        ZillitSearchField(
            value = setup.currencySearch,
            onValueChange = { onEvent(AccountHubEvent.SearchCurrencies(it)) },
            placeholder = "Search currencies… (code or name)",
            modifier = Modifier.fillMaxWidth(),
        )
        val choices = setup.currencyChoices.take(CATALOGUE_LIMIT)
        if (setup.currencyCatalogue.isEmpty()) {
            FieldHint("Loading the currency catalogue…")
        } else if (choices.isEmpty()) {
            FieldHint("No currencies match.")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            choices.forEach { currency ->
                ZillitButton(
                    text = "${currency.code} — ${currency.name}",
                    onClick = {
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
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
        if (setup.currencyChoices.size > CATALOGUE_LIMIT) FieldHint("Showing $CATALOGUE_LIMIT of " +
            "${setup.currencyChoices.size} — search to narrow.")
    }
}

// -- account tags -----------------------------------------------------------

/**
 * Free-form upper-cased tags — the web's `AccountTagsSection`: chips, a
 * comma-or-Enter input, and dashed "Suggested" chips for the common ones.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountTagsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val tags = setup.assetTags.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Account Tags",
        description = "Free-form labels attached to purchases and POs for ad-hoc grouping (camera, lighting, set " +
            "dressing, marketing…). Drives the auto-complete on the PO form.",
        dirty = setup.assetTags.dirty,
        saving = setup.assetTags.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.AssetTags)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.AssetTags)) },
        editable = editable,
    ) {
        if (tags.isEmpty()) EmptyLine("No tags yet.")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            tags.forEach { tag ->
                Chip(
                    text = tag,
                    onRemove = if (editable) ({ onEvent(AccountHubEvent.EditAssetTags(tags - tag)) }) else null,
                )
            }
        }
        if (editable) {
            ZillitTextField(
                value = setup.tagDraft,
                onValueChange = { text ->
                    // A comma commits what came before it, as the web's input does.
                    if (text.endsWith(",")) {
                        onEvent(AccountHubEvent.EditTagDraft(text))
                        onEvent(AccountHubEvent.CommitTagDraft)
                    } else {
                        onEvent(AccountHubEvent.EditTagDraft(text))
                    }
                },
                placeholder = "Type a tag and press Enter (or comma)…",
                imeAction = ImeAction.Done,
                onImeAction = { onEvent(AccountHubEvent.CommitTagDraft) },
                modifier = Modifier.fillMaxWidth(),
            )
            val suggested = SUGGESTED_TAGS.filter { it !in tags }
            if (suggested.isNotEmpty()) {
                FieldLabel("Suggested")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    suggested.forEach { tag ->
                        ZillitButton(
                            text = tag,
                            onClick = { onEvent(AccountHubEvent.EditAssetTags(tags + tag)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Add,
                        )
                    }
                }
            }
        }
    }
}

// -- tax types --------------------------------------------------------------

/**
 * Tax rates — the web's `TaxTypesSection`: pick a country, tick its
 * catalogue rates (each with a reclaimable toggle and a nominal), and add
 * custom rates with a 0–100 guard.
 */
@Composable
private fun TaxTypesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val rows = setup.taxTypes.edited
    val editable = state.viewer.canEdit
    val countries = setup.countryTaxes
    val chosenCountries = rows.mapNotNull { it.countryCode }.distinct()
    val shownCountry = setup.taxCountry ?: chosenCountries.firstOrNull()
    val country = countries.firstOrNull { it.countryCode == shownCountry }

    SectionShell(
        title = "Tax Types",
        description = "Add the countries and their tax types for the project. Used directly in the line items of " +
            "purchase orders, invoices, and card and cash expense receipts.",
        dirty = setup.taxTypes.dirty,
        saving = setup.taxTypes.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.TaxTypes)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.TaxTypes)) },
        editable = editable,
        leftPanel = { TaxCountrySummary(rows, countries) },
    ) {
        HubSelect(
            value = country,
            options = countries,
            label = { "${it.country} (${it.countryCode})" },
            onSelect = { onEvent(AccountHubEvent.PickTaxCountry(it?.countryCode)) },
            placeholder = if (countries.isEmpty()) "Loading countries…" else "Choose a country…",
            fieldLabel = "Country",
            enabled = editable && countries.isNotEmpty(),
            secondary = { "${it.taxes.size} rate${if (it.taxes.size == 1) "" else "s"}" },
        )
        if (country != null) {
            CountryRates(country, rows, editable, state, onEvent)
        }
        CustomRates(rows, editable, state, onEvent)
        TaxType.problem(rows)?.let { ZillitNotice(text = it, tone = StatusTone.Rejected, icon = ZillitIcons.Warning) }
    }
}

@Composable
private fun TaxCountrySummary(rows: List<TaxType>, countries: List<CountryTaxes>) {
    val byCountry = rows.filterNot { it.isCustom }.groupBy { it.countryCode.orEmpty() }
    val custom = rows.count { it.isCustom }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        MonoLabel("Selected")
        if (byCountry.isEmpty() && custom == 0) FieldHint("Nothing selected yet.")
        byCountry.forEach { (code, taxes) ->
            val name = countries.firstOrNull { it.countryCode == code }?.country ?: taxes.firstOrNull()?.country ?: code
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoChip(code)
                ZillitText(
                    text = name.ifBlank { code },
                    style = ZillitTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                FieldHint("${taxes.size}")
            }
        }
        if (custom > 0) FieldHint("$custom custom rate${if (custom == 1) "" else "s"}")
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CountryRates(
    country: CountryTaxes,
    rows: List<TaxType>,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val allChecked = country.taxes.all { tax -> rows.any { it.identifier == tax.identifier } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("${country.country} rates", modifier = Modifier.weight(1f))
        if (editable) {
            ZillitButton(
                text = if (allChecked) "Clear all" else "Select all",
                onClick = {
                    val next = if (allChecked) {
                        rows.filterNot { row -> country.taxes.any { it.identifier == row.identifier } }
                    } else {
                        rows + country.taxes.filter { tax -> rows.none { it.identifier == tax.identifier } }
                    }
                    onEvent(AccountHubEvent.EditTaxTypes(next))
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
    country.taxes.forEach { tax ->
        val existing = rows.firstOrNull { it.identifier == tax.identifier }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitCheckbox(
                checked = existing != null,
                onCheckedChange = { on ->
                    onEvent(
                        AccountHubEvent.EditTaxTypes(
                            if (on) rows + tax else rows.filterNot { it.identifier == tax.identifier },
                        ),
                    )
                },
                label = "${tax.label} · ${tax.value}%",
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            if (existing != null) {
                ZillitCheckbox(
                    checked = existing.isRecoverable,
                    onCheckedChange = { on ->
                        onEvent(
                            AccountHubEvent.EditTaxTypes(
                                rows.map {
                                    if (it.identifier == existing.identifier) it.copy(isRecoverable = on) else it
                                },
                            ),
                        )
                    },
                    label = "Reclaimable",
                    enabled = editable,
                )
                CoaCodeField(
                    value = existing.nominal,
                    onValueChange = { code ->
                        onEvent(
                            AccountHubEvent.EditTaxTypes(
                                rows.map { if (it.identifier == existing.identifier) it.copy(nominal = code) else it },
                            ),
                        )
                    },
                    accounts = state.chart.accounts,
                    placeholder = "Nominal",
                    enabled = editable,
                    modifier = Modifier.width(NOMINAL_WIDTH),
                    onCreate = quickCreateHandler(state, onEvent),
                )
            }
        }
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CustomRates(
    rows: List<TaxType>,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val custom = rows.filter { it.isCustom }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("Custom rates", modifier = Modifier.weight(1f))
        if (editable) {
            GhostAddButton(
                text = "Add custom rate",
                onClick = {
                    onEvent(
                        AccountHubEvent.EditTaxTypes(
                            // Minted from what is already there rather than from
                            // a counter, so two sessions cannot both mint custom_3.
                            rows + TaxType(identifier = TaxType.nextCustomIdentifier(rows), type = "custom"),
                        ),
                    )
                },
            )
        }
    }
    if (custom.isEmpty()) FieldHint("No custom rates.")
    custom.forEach { tax ->
        fun update(next: TaxType) =
            onEvent(AccountHubEvent.EditTaxTypes(rows.map { if (it.identifier == tax.identifier) next else it }))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = tax.type,
                onValueChange = { update(tax.copy(type = it)) },
                placeholder = "Type (e.g. VAT)",
                enabled = editable,
                modifier = Modifier.width(TYPE_WIDTH),
            )
            ZillitTextField(
                value = tax.label,
                onValueChange = { update(tax.copy(label = it)) },
                placeholder = "Label",
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = tax.value,
                onValueChange = { update(tax.copy(value = it)) },
                placeholder = "Rate %",
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
                placeholder = "Nominal",
                enabled = editable,
                modifier = Modifier.width(NOMINAL_WIDTH),
                onCreate = quickCreateHandler(state, onEvent),
            )
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove ${tax.label}",
                    onClick = { onEvent(AccountHubEvent.EditTaxTypes(rows - tax)) },
                )
            }
        }
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

/** The web's `SUGGESTED_TAGS`. */
private val SUGGESTED_TAGS = listOf("COSTUME", "VFX", "CATERING", "TRANSPORT", "INSURANCE")

private const val SKELETON_CARDS = 3
private const val SKELETON_FILL = 0.6f
private const val CATALOGUE_LIMIT = 24
private const val MAX_EXTRA_COLUMNS = 3
private val SKELETON_TITLE = 180.dp
private val MONOGRAM_PANE = 160.dp
private val MONOGRAM = 44.dp
private val BANK_LEFT = 260.dp
private val CURRENCY_CARD = 300.dp
private val RATE_WIDTH = 96.dp
private val NOMINAL_WIDTH = 150.dp
private val TYPE_WIDTH = 120.dp
