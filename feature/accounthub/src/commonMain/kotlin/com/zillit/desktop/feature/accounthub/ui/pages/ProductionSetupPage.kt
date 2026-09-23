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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.bankLoad
import com.zillit.desktop.feature.accounthub.ui.sectionLoad
import com.zillit.desktop.feature.accounthub.ui.SetupTab
import com.zillit.desktop.feature.accounthub.ui.SpendSetup
import com.zillit.desktop.feature.accounthub.ui.components.HubPageHeader
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubModuleCard
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
    val scroll = rememberScrollState()
    // A tab is a different page: it starts at its top, not wherever the other
    // one was left.
    LaunchedEffect(setup.tab) { scroll.scrollTo(0) }

    HubPage {
        HubPageHeader(
            eyebrow = str(S.desktop_setup),
            title = str(S.ps_production_setup),
            description = str(S.desktop_hub_project_wide_defaults_inherited_by_new_deal_memos_purchase_orders),
        )

        if (!state.viewer.canEdit) {
            ZillitNotice(
                text = str(S.desktop_hub_you_can_see_this_configuration_but_not_change_it_edits),
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
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            when (setup.tab) {
                SetupTab.Accounting -> AccountingSections(state, onEvent)
                SetupTab.DealMemo -> DealMemoSections(state, onEvent, canAttachAgreements, canOpenDocuments)
            }
        }
    }

    // Composed in stacking order: the bank editor opens over the company
    // editor (its inline "Add bank account"), and a removal confirms over both.
    CompanyDialog(state, onEvent)
    BankAccountDialog(state, onEvent)
    NonUnionPayDialogs(state, onEvent)
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
        title = str(S.desktop_payroll_entry_setup),
        description = str(S.desktop_hub_approvers_authorised_to_sign_off_payroll_runs_and_the_projects),
        icon = ZillitIcons.Wallet,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Payroll)) },
    )
    HubModuleCard(
        title = str(S.desktop_hub_time_card_entry_setup),
        description = str(S.desktop_hub_control_model_department_level_setup_approval_chain_cadence_allowance_rules),
        icon = ZillitIcons.Clock,
        onConfigure = { onEvent(AccountHubEvent.OpenTimecardSetup) },
        actionText = str(S.desktop_open_time_card_chevron),
    )
    HubModuleCard(
        title = str(S.desktop_hub_purchase_order_entry_setup),
        description = str(S.desktop_hub_defaults_for_the_po_module_description_formatting_rental_split_handling),
        icon = ZillitIcons.Receipt,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.PurchaseOrders)) },
    )
    HubModuleCard(
        title = str(S.desktop_invoices_entry_setup),
        description = str(S.desktop_hub_ap_controls_posting_limits_alert_preferences_and_the_sign_off),
        icon = ZillitIcons.File,
        onConfigure = { onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Invoices)) },
    )
    // Card and Petty Cash edit each module's own `/settings` document. The
    // desktop already renders that document in the tool, so a modal here
    // would be a second editor over one record; the tile deep-links instead.
    HubModuleCard(
        title = str(S.desktop_hub_production_expense_cards_entry_setup),
        description = str(
            S.desktop_hub_card_spend_configuration_custodian_posting_rights_approval_shortcuts_deduction_rules,
        ),
        icon = ZillitIcons.CreditCard,
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.Cards)) },
        actionText = str(S.desktop_open_card_settings_chevron),
    )
    HubModuleCard(
        title = str(S.desktop_hub_petty_cash_entry_setup),
        description = str(
            S.desktop_hub_petty_cash_configuration_float_custodian_posting_rights_approval_shortcuts_deduction,
        ),
        icon = ZillitIcons.Wallet,
        onConfigure = { onEvent(AccountHubEvent.OpenSpendSetup(SpendSetup.PettyCash)) },
        actionText = str(S.desktop_hub_open_petty_cash_settings_chevron),
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

/**
 * The production's legal entities — the web's `CompaniesSection`.
 *
 * Every edit persists from its dialog, so the section's own Save is only a
 * fallback for an edit whose save failed; the button appears when that
 * happens and not otherwise.
 */
@Composable
private fun CompaniesSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val companies = setup.companies.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = str(S.ps_companies_title),
        description = str(S.desktop_hub_legal_entities_that_own_this_productions_bank_accounts_a_bank),
        dirty = setup.companies.dirty,
        saving = setup.companies.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Companies)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Companies)) },
        load = state.sectionLoad(SetupSection.Companies, onEvent),
        editable = editable,
        extraActions = {
            if (editable) {
                ZillitButton(
                    text = str(S.desktop_add_company),
                    onClick = { onEvent(AccountHubEvent.EditCompany(null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        if (companies.isEmpty()) {
            EmptyLine(str(S.desktop_hub_no_companies_added_yet_click_add_company_to_register_the))
        }
        companies.forEach { company ->
            CompanyCard(state, company, editable, onEvent)
        }
    }
}

/**
 * One company — the web's card: a gradient identity panel with a peach
 * monogram and the account count, the name with a country chip and one pill
 * per currency its banks span, the linked banks as chips, and an action rail
 * that brightens on hover. The whole card opens the editor.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun CompanyCard(
    state: AccountHubUiState,
    company: Company,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val banks = state.setup.banks
    // Both sides of the link (ZL-20605): a bank created from the Bank Accounts
    // section points here by entity_id and must count.
    val linkedIds = Companies.linkedBankIds(company, banks)
    val linked = linkedIds.mapNotNull { id -> banks.firstOrNull { it.id == id } }
    val currencies = Companies.currencyCodes(linkedIds, banks)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val open = { if (editable) onEvent(AccountHubEvent.EditCompany(company)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, if (hovered) colors.borderStrong else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(enabled = editable, onClick = open)
            .padding(CARD_INSET)
            .height(IntrinsicSize.Min),
    ) {
        IdentityPanel(modifier = Modifier.width(MONOGRAM_PANE).fillMaxHeight()) {
            Monogram(company.monogram)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.success))
                FieldHint("Active · ${linked.size} ${if (linked.size == 1) "account" else "accounts"}")
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                MonoLabel(str(S.desktop_production_co))
                CompanyTitleRow(company, currencies)
                if (company.legalName.isNotBlank() && company.legalName.trim() != company.name.trim()) {
                    FieldHint("${str(S.ps_company_legal_name)} · ${company.legalName}")
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                modifier = Modifier.widthIn(max = LINKED_MAX),
            ) {
                MonoLabel(str(S.desktop_linked_banks))
                if (linked.isEmpty()) {
                    FieldHint(str(S.none))
                } else {
                    LinkedBankChips(linked)
                }
            }
            if (editable) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit ${company.name}",
                        onClick = open,
                        tint = if (hovered) colors.textPrimary else colors.textMuted,
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove ${company.name}",
                        onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.CompanyRow(company))) },
                        tint = if (hovered) colors.danger else colors.textMuted,
                    )
                    Box(
                        modifier = Modifier
                            .size(CHEVRON_PILL)
                            .clip(ZillitTheme.shapes.medium)
                            .background(if (hovered) colors.textPrimary else colors.surfaceSunken),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(
                            icon = ZillitIcons.ChevronRight,
                            tint = if (hovered) colors.surface else colors.textSecondary,
                            size = CHEVRON_GLYPH,
                        )
                    }
                }
            }
        }
    }
}

/** The web's gradient identity panel — a soft wash from grey to peach, the card's left third. */
@Composable
internal fun IdentityPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.surfaceSunken,
                        colors.accentSoft.copy(alpha = if (colors.isDark) DARK_WASH else LIGHT_WASH),
                    ),
                ),
            )
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/** The peach-wash monogram square the identity panels lead with. */
@Composable
internal fun Monogram(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.size(MONOGRAM).clip(ZillitTheme.shapes.large).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanyTitleRow(company: Company, currencies: List<String>) {
    // Wraps: a company linked to banks in several currencies used to run its
    // pills under the action rail (ZL-20980).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = company.name.ifBlank { str(S.desktop_unnamed_company) },
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (company.country.isNotBlank()) {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if (company.countryCode.isNotBlank()) MonoChip(company.countryCode.uppercase())
                Pill(company.country)
            }
        }
        currencies.forEach {
            Pill(it, tone = StatusTone.Pending, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedBankChips(banks: List<BankAccount>) {
    val colors = ZillitTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        banks.forEach { bank ->
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .padding(start = ZillitTheme.spacing.xs, end = ZillitTheme.spacing.sm, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(MINI_MONOGRAM)
                        .clip(ZillitTheme.shapes.small)
                        .background(colors.textPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = bank.name.filter { it.isLetter() }.take(2).uppercase().ifBlank { "?" },
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.surface,
                    )
                }
                ZillitText(
                    text = bank.name.ifBlank { "—" },
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}

// -- banks ------------------------------------------------------------------

@Composable
private fun BankAccountsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val editable = state.viewer.canEdit

    SectionShell(
        title = str(S.desktop_bank_accounts),
        description = str(S.desktop_hub_project_level_bank_accounts_that_payroll_and_vendor_disbursements_default),
        editable = editable,
        load = state.bankLoad(onEvent),
        extraActions = {
            if (editable) {
                ZillitButton(
                    text = str(S.desktop_add_account),
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
        FieldHint(str(S.desktop_hub_each_account_saves_on_its_own_there_is_no_section))
        if (setup.banks.isEmpty() && !setup.banksLoading) {
            EmptyBankState(editable, onAdd = { onEvent(AccountHubEvent.EditBank(null)) })
        }
        setup.banks.forEach { bank -> BankCard(state, bank, editable, onEvent) }
    }
}

/** The web's empty state: one large soft card that is itself the "add" affordance. */
@Composable
private fun EmptyBankState(editable: Boolean, onAdd: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .clickable(enabled = editable, onClick = onAdd)
            .padding(vertical = ZillitTheme.spacing.xxl, horizontal = ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(MONOGRAM).clip(CircleShape).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Bank, tint = colors.accentText)
        }
        ZillitText(
            text = str(S.desktop_hub_no_bank_accounts_on_this_project_yet),
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        FieldHint(
            if (editable) {
                str(S.desktop_hub_click_to_add_the_first_one_payroll_and_vendor_payments)
            } else {
                str(S.desktop_none_added)
            },
        )
    }
}

/**
 * One bank — the web's card: a gradient identity panel with the bank's
 * monogram, name and nominal subline, the holder as the primary beat, and a
 * three-column grid of the account details with the number masked until
 * revealed (re-masked after five seconds) and a copy button on every value.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun BankCard(
    state: AccountHubUiState,
    bank: BankAccount,
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
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(CARD_INSET)
            .height(IntrinsicSize.Min),
    ) {
        IdentityPanel(modifier = Modifier.width(BANK_LEFT).fillMaxHeight()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Monogram(bank.name.filter { it.isLetter() }.take(2).uppercase().ifBlank { "?" })
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = bank.name.ifBlank { str(S.desktop_unnamed_bank) },
                            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        val codes = listOfNotNull(
                            bank.nominalCode.takeIf { it.isNotBlank() }?.let { "NOM $it" },
                            bank.apClearanceNominalCode.takeIf { it.isNotBlank() }?.let { "AP $it" },
                        )
                        if (codes.isNotEmpty()) {
                            ZillitText(
                                text = codes.joinToString("  "),
                                style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                    if (bank.currencyCode.isNotBlank()) Pill(bank.currencyCode, tone = StatusTone.Pending)
                }
            }
            Column(modifier = Modifier.padding(top = ZillitTheme.spacing.lg)) {
                MonoLabel(str(S.ah_holder))
                ZillitText(
                    text = holder.ifBlank { "—" },
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                // The render test keys on this line; it is also the one-glance
                // summary. The number stays masked here — the eye on the grid
                // is the one place it is shown whole.
                FieldHint(
                    listOfNotNull(
                        holder.takeIf { it.isNotBlank() },
                        SortCode.formatted(bank.sortCode).takeIf { it.isNotBlank() },
                        BankAccounts.masked(bank.accountNumber).takeIf { it.isNotBlank() },
                        bank.currencyCode.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "—" },
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(str(S.desktop_account_details), modifier = Modifier.weight(1f))
                if (bank.accountNumber.isNotBlank()) {
                    ZillitIconButton(
                        icon = ZillitIcons.Eye,
                        contentDescription = if (revealed) {
                            str(S.hide)
                        } else {
                            str(S.desktop_hub_show_auto_hides_in_5s_paren)
                        },
                        onClick = { onEvent(AccountHubEvent.RevealBank(if (revealed) null else bank.id)) },
                    )
                }
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = str(S.desktop_edit_account),
                        onClick = { onEvent(AccountHubEvent.EditBank(bank)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.desktop_remove_account),
                        onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.BankRow(bank))) },
                        tint = colors.danger,
                    )
                }
            }
            DetailGrid(
                listOf(
                    DetailCell(
                        str(S.account_number),
                        if (revealed) bank.accountNumber else BankAccounts.masked(bank.accountNumber),
                        copyValue = bank.accountNumber,
                    ),
                    DetailCell(str(S.ah_lbl_sort_code), SortCode.formatted(bank.sortCode)),
                    DetailCell(str(S.ah_lbl_swift_row), bank.swiftCode),
                ),
            )
            DetailGrid(
                listOf(
                    DetailCell("IBAN", bank.ibanNumber),
                    DetailCell(str(S.dm_rule_nominal), bank.nominalCode),
                    DetailCell(str(S.desktop_ap_clearance), bank.apClearanceNominalCode),
                ),
            )
            if (bank.chequeNumber.isNotBlank() || bank.wireNumber.isNotBlank()) {
                DetailGrid(
                    listOf(
                        DetailCell(str(S.desktop_cheque_number), bank.chequeNumber),
                        DetailCell(str(S.desktop_wire_number), bank.wireNumber),
                        null,
                    ),
                )
            }
            if (bank.additionalDetails.isNotEmpty()) {
                ExtraDetailChips(bank)
            }
        }
    }
}

private data class DetailCell(val label: String, val value: String, val copyValue: String = value)

/** Three columns with hairline dividers between them, as the web's grid draws them. */
@Composable
private fun DetailGrid(cells: List<DetailCell?>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.divider))
            }
            if (cell == null) {
                Spacer(Modifier.weight(1f))
            } else {
                CardField(cell.label, cell.value, cell.copyValue, Modifier.weight(if (index == 1) 0.9f else 1.1f))
            }
        }
    }
}

/** A label over a mono value, with a copy button that turns into a tick for a moment. */
@Composable
private fun CardField(label: String, value: String, copyValue: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(COPIED_MS)
            copied = false
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        MonoLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                text = value.ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (copyValue.isNotBlank()) {
                ZillitIconButton(
                    icon = if (copied) ZillitIcons.Check else ZillitIcons.Copy,
                    contentDescription = if (copied) str(S.dd_copied) else "Copy $label",
                    onClick = {
                        clipboard.setText(AnnotatedString(copyValue))
                        copied = true
                    },
                    tint = if (copied) ZillitTheme.colors.success else ZillitTheme.colors.textMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraDetailChips(bank: BankAccount) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        bank.additionalDetails.filter { it.title.isNotBlank() || it.value.isNotBlank() }.forEach {
            Chip(text = "${it.title.ifBlank { "—" }}: ${it.value}")
        }
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
        title = str(S.desktop_account_tags),
        description = str(S.desktop_hub_free_form_labels_attached_to_purchases_and_pos_for_ad),
        dirty = setup.assetTags.dirty,
        saving = setup.assetTags.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.AssetTags)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.AssetTags)) },
        load = state.sectionLoad(SetupSection.AssetTags, onEvent),
        editable = editable,
    ) {
        FieldLabel("Tags · ${tags.size}")
        if (tags.isEmpty() && !editable) EmptyLine(str(S.desktop_no_tags_yet))
        // One control: the chips live inside the same well as the input, so the
        // whole thing reads as a single field, as the web's combobox does.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surfaceSunken)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (tags.isNotEmpty()) {
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
            }
            if (editable) TagInput(setup.tagDraft, tags, onEvent)
        }
        if (editable) {
            val suggested = SUGGESTED_TAGS.filter { it !in tags }
            if (suggested.isNotEmpty()) {
                FieldLabel(str(S.desktop_suggested))
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

/**
 * The tag input — the web's: a comma or Enter commits, leaving the field
 * commits what was typed, and Backspace in an empty field takes the last tag.
 */
@Composable
private fun TagInput(draft: String, tags: List<String>, onEvent: (AccountHubEvent) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    ZillitTextField(
        value = draft,
        onValueChange = { text ->
            onEvent(AccountHubEvent.EditTagDraft(text))
            // A comma commits what came before it, as the web's input does.
            if (text.endsWith(",")) onEvent(AccountHubEvent.CommitTagDraft)
        },
        placeholder = if (tags.isEmpty()) {
            str(S.desktop_hub_type_a_tag_and_press_enter_or_comma)
        } else {
            str(S.desktop_dm_add_another_ellipsis)
        },
        imeAction = ImeAction.Done,
        onImeAction = { onEvent(AccountHubEvent.CommitTagDraft) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (focused && !focus.isFocused && draft.isNotBlank()) onEvent(AccountHubEvent.CommitTagDraft)
                focused = focus.isFocused
            }
            .onPreviewKeyEvent { event ->
                val eraseLast = event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                    draft.isEmpty() && tags.isNotEmpty()
                if (eraseLast) onEvent(AccountHubEvent.EditAssetTags(tags.dropLast(1)))
                eraseLast
            },
    )
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
private const val COPIED_MS = 1_400L
private const val LIGHT_WASH = 0.7f
private const val DARK_WASH = 0.22f
private val SKELETON_TITLE = 180.dp
private val CARD_INSET = 4.dp
private val MONOGRAM_PANE = 168.dp
private val MONOGRAM = 44.dp
private val MINI_MONOGRAM = 20.dp
private val LINKED_MAX = 280.dp
private val CHEVRON_PILL = 36.dp
private val CHEVRON_GLYPH = 12.dp
private val BANK_LEFT = 280.dp
