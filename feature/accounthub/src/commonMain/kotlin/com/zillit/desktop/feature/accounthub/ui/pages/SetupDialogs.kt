package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.IsdCountry
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.domain.UkPayrollRefs
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoChip
import com.zillit.desktop.feature.accounthub.ui.components.TypedDetailsEditor
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

/**
 * The company editor — the web's `CompanyFormModal`.
 *
 * ## It edits a draft, not the list
 *
 * Opening the dialog and dismissing it must leave the section clean. Editing
 * the list directly makes the section dirty the moment the dialog opens, so a
 * user who changed their mind is left with a Save button over an edit they
 * cancelled. Done persists straight away, as the web's does.
 *
 * ## Linking a bank takes it from whoever had it
 *
 * A bank belongs to at most one company. The subtraction happens when the draft
 * is committed rather than as each checkbox is ticked, so cancelling really
 * does undo it.
 *
 * ## Opened from the bank editor
 *
 * The bank editor's own "+ Add company" opens this without its bank block —
 * you are already creating a bank, so there is no bank → company → bank
 * nesting — and the saved company becomes that bank's holder.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A form plus its bank list, read as one dialog.
@Composable
internal fun CompanyDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val draft = setup.companyDraft
    val isNew = draft != null && setup.companies.edited.none { it.id == draft.id }
    val fromBank = setup.companyDraftFromBank
    val saving = setup.companies.saving
    val problem = draft?.let(Companies::problem)

    ZillitDialogShell(
        title = if (isNew) "New company" else "Edit ${draft?.name?.ifBlank { "company" }}",
        subtitle = if (fromBank) {
            "The bank you are adding will be held by this company."
        } else {
            "Bank accounts hang off a company, so link them here."
        },
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissCompanyDraft) },
        icon = ZillitIcons.Bank,
        width = DIALOG_WIDTH,
        actions = {
            if (!isNew && draft != null && !fromBank) {
                ZillitButton(
                    text = "Remove company",
                    onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.CompanyRow(draft))) },
                    variant = ButtonVariant.Danger,
                    enabled = !saving,
                )
                Box(Modifier.weight(1f))
            }
            // Edit mode has no Cancel (web 03f047d47): × / Esc still discard,
            // and Done is the one commit action; create keeps it.
            if (isNew) {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(AccountHubEvent.DismissCompanyDraft) },
                    variant = ButtonVariant.Tertiary,
                    enabled = !saving,
                )
            }
            ZillitButton(
                text = if (isNew) "Add company" else "Done",
                onClick = { onEvent(AccountHubEvent.CommitCompanyDraft) },
                enabled = problem == null && !saving,
                loading = saving,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(next: Company) = onEvent(AccountHubEvent.UpdateCompanyDraft(next))

        // -- names ----------------------------------------------------------
        // The legal name hides behind a tick and mirrors the trading name until
        // somebody says the two differ. Two values on purpose: what was typed
        // survives unticking (a misclick in a dialog with no undo costs
        // nothing), and what is persisted is always resolved, never blank.
        val seededDiffers = draft.legalName.trim().let { it.isNotEmpty() && it != draft.name.trim() }
        val session = setup.companyDraftSession
        var legalNameDiffers by remember(session) { mutableStateOf(seededDiffers) }
        var legalNameInput by remember(session) { mutableStateOf(if (seededDiffers) draft.legalName else "") }
        fun resolvedLegalName(name: String, differs: Boolean, input: String) =
            if (differs && input.isNotBlank()) input else name

        ZillitTextField(
            value = draft.name,
            onValueChange = { name ->
                update(draft.copy(name = name, legalName = resolvedLegalName(name, legalNameDiffers, legalNameInput)))
            },
            label = "Company / Entity name *",
            placeholder = "e.g. Acme Productions Ltd",
        )
        ZillitCheckbox(
            checked = legalNameDiffers,
            onCheckedChange = { differs ->
                legalNameDiffers = differs
                update(draft.copy(legalName = resolvedLegalName(draft.name, differs, legalNameInput)))
            },
            label = "Tick if Company’s legal name is different from above",
        )
        if (legalNameDiffers) {
            ZillitTextField(
                value = legalNameInput,
                onValueChange = { input ->
                    legalNameInput = input
                    update(draft.copy(legalName = resolvedLegalName(draft.name, true, input)))
                },
                label = "Legal name",
                placeholder = "As registered at Companies House",
            )
        }

        // -- country --------------------------------------------------------
        CountryPicker(
            countries = state.vendors.countries,
            country = draft.country,
            countryCode = draft.countryCode,
            onPick = { name, code -> update(draft.copy(country = name, countryCode = code)) },
        )

        // -- UK employer references, below the picker that gates them ---------
        if (draft.isUk) {
            ZillitSectionLabel("UK payroll")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                // Trimmed as typed: neither reference contains a space, and a
                // pasted " 123/AB456" must not reach a bureau hand-off.
                ZillitTextField(
                    value = draft.ukPayeRef,
                    onValueChange = { update(draft.copy(ukPayeRef = it.trim())) },
                    label = "PAYE reference",
                    placeholder = "123/AB456",
                    maxLength = UkPayrollRefs.REF_MAX,
                    errorText = UkPayrollRefs.PAYE_ERROR.takeIf { UkPayrollRefs.isPayeInvalid(draft.ukPayeRef) },
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.ukAccountsOfficeRef,
                    onValueChange = { update(draft.copy(ukAccountsOfficeRef = it.trim())) },
                    label = "Accounts Office reference",
                    placeholder = "123PA00012345",
                    maxLength = UkPayrollRefs.REF_MAX,
                    errorText = UkPayrollRefs.ACCOUNTS_OFFICE_ERROR
                        .takeIf { UkPayrollRefs.isAccountsOfficeInvalid(draft.ukAccountsOfficeRef) },
                    modifier = Modifier.weight(1f),
                )
            }
            // Free text, so not trimmed per keystroke — a provider name carries
            // spaces, and trimming as you type makes the space unreachable.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = draft.ukPensionProvider,
                    onValueChange = { update(draft.copy(ukPensionProvider = it)) },
                    label = "Pension provider",
                    placeholder = "e.g. NEST",
                    maxLength = UkPayrollRefs.PENSION_PROVIDER_MAX,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.ukPensionSchemeRef,
                    onValueChange = { update(draft.copy(ukPensionSchemeRef = it)) },
                    label = "Pension scheme reference",
                    placeholder = "e.g. SCH-000123",
                    maxLength = UkPayrollRefs.PENSION_SCHEME_MAX,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        TaxCreditsField(
            credits = draft.taxCredits,
            onChange = { update(draft.copy(taxCredits = it)) },
        )

        if (!fromBank) CompanyBanksField(state, draft, onEvent)
    }
}

/**
 * The bank block inside the company editor — the web's `CompanyBanksField`.
 *
 * Only banks this draft can take are listed: its own, plus any no other
 * company claims. A bank owned elsewhere is hidden outright rather than shown
 * greyed and locked. The inline "Add bank account" stacks the bank editor on
 * top so a bank can be created without leaving the company flow; a bank added
 * that way is linked onto the draft when its save lands.
 */
@Suppress("LongMethod") // One list, read top to bottom.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanyBanksField(state: AccountHubUiState, draft: Company, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val banks = setup.banks
    // Ownership is judged against what is committed, not the draft.
    val ownedElsewhere = buildSet {
        setup.companies.saved.filter { it.id != draft.id }.forEach { addAll(Companies.linkedBankIds(it, banks)) }
    }
    val selectable = banks.filter { it.id in draft.bankIds || it.id !in ownedElsewhere }
    val currencies = Companies.currencyCodes(draft.bankIds, banks)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Bank accounts", modifier = Modifier.weight(1f))
            ZillitButton(
                text = "Add bank account",
                onClick = { onEvent(AccountHubEvent.EditBank(null, fromCompany = true)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        when {
            setup.banksLoading && banks.isEmpty() -> FieldHint("Loading banks…")
            banks.isEmpty() -> FieldHint("No production banks yet — use Add bank account to create one.")
            selectable.isEmpty() -> FieldHint(
                "All production banks are already assigned to other companies — use Add bank account to create " +
                    "another.",
            )
            else -> Column(
                modifier = Modifier.heightIn(max = BANK_LIST_MAX).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                selectable.forEach { bank ->
                    SelectableBankRow(
                        bank = bank,
                        checked = bank.id in draft.bankIds,
                        onToggle = {
                            val next = if (bank.id in draft.bankIds) {
                                draft.bankIds - bank.id
                            } else {
                                draft.bankIds + bank.id
                            }
                            onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(bankIds = next)))
                        },
                        onEdit = { onEvent(AccountHubEvent.EditBank(bank, fromCompany = true)) },
                    )
                }
            }
        }
        // Derived, never picked: the backend owns the canonical company currency.
        if (selectable.isNotEmpty()) {
            if (currencies.isEmpty()) {
                FieldHint("Currency is set by the selected bank accounts.")
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    FieldLabel("Currency", modifier = Modifier.align(Alignment.CenterVertically))
                    currencies.forEach { MonoChip(it, active = true) }
                }
            }
        }
    }
}

/** One bank the company can take: the whole row toggles, the pencil edits it in place. */
@Composable
private fun SelectableBankRow(bank: BankAccount, checked: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (checked) colors.accentSoft else colors.surface)
            .border(1.dp, if (checked) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(BANK_ICON)
                .clip(ZillitTheme.shapes.medium)
                .background(if (checked) colors.accent.copy(alpha = ICON_WASH) else colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Bank,
                tint = if (checked) colors.accentText else colors.textMuted,
                size = ICON_GLYPH,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = bank.name.ifBlank { "Unnamed account" },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (bank.currencyCode.isNotBlank()) MonoChip(bank.currencyCode, active = true)
            }
            FieldHint(
                listOfNotNull(
                    bank.accountHolderName.ifBlank { "—" },
                    SortCode.formatted(bank.sortCode).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }
        Box(
            modifier = Modifier
                .size(CHECK_CIRCLE)
                .clip(CircleShape)
                .background(if (checked) colors.accent else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = CHECK_GLYPH)
        }
        ZillitIconButton(icon = ZillitIcons.Edit, contentDescription = "Edit ${bank.name}", onClick = onEdit)
    }
}

/**
 * A searchable country picker over the ISD list — one row per country, the
 * web's `SearchableSelect` over `buildCountryOptions` (ZL-20594; the currency
 * catalogue collapsed the whole Eurozone into one row).
 */
@Composable
private fun CountryPicker(
    countries: List<IsdCountry>,
    country: String,
    countryCode: String,
    onPick: (name: String, code: String) -> Unit,
) {
    val sorted = remember(countries) {
        countries.filter { it.name.isNotBlank() && it.code.isNotBlank() }.sortedBy { it.name }
    }
    val current = sorted.firstOrNull { it.code.equals(countryCode, ignoreCase = true) && countryCode.isNotBlank() }
        ?: sorted.firstOrNull { it.name.equals(country, ignoreCase = true) }
        ?: country.takeIf { it.isNotBlank() }?.let { IsdCountry(name = it, dialCode = "", code = countryCode) }
    HubSelect(
        value = current,
        options = sorted,
        label = { it.name },
        secondary = { it.code },
        onSelect = { picked -> onPick(picked?.name.orEmpty(), picked?.code.orEmpty()) },
        placeholder = if (sorted.isEmpty()) "Loading countries…" else "Type country name or ISO code…",
        fieldLabel = "Country *",
        clearable = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Free-typed tax-credit chips — the web's `TaxCreditsField` combobox: Enter or comma commits, case-blind. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxCreditsField(credits: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    fun commit() {
        val parts = draft.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val next = credits.toMutableList()
        parts.forEach { part -> if (next.none { it.equals(part, ignoreCase = true) }) next += part }
        if (next.size != credits.size) onChange(next)
        draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel("Tax credit tagging")
        if (credits.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                credits.forEach { credit -> Chip(text = credit, onRemove = { onChange(credits - credit) }) }
            }
        }
        ZillitTextField(
            value = draft,
            onValueChange = { text ->
                // A comma commits what came before it, as the web's input does.
                if (text.endsWith(",")) {
                    draft = text.dropLast(1)
                    commit()
                } else {
                    draft = text
                }
            },
            placeholder = if (credits.isEmpty()) "Type a regime, press Enter (e.g. UK HETV)" else "Add another…",
            imeAction = ImeAction.Done,
            onImeAction = ::commit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The bank account editor — the web's `BankAccountFormModal`.
 *
 * Saves on its own, unlike every other section on the page: a bank is a
 * first-class record in the table Bank Reconciliation, Vendors and Payroll all
 * read, not a slice of the settings document. The holder is the linked
 * company; the two nominal codes are typeaheads over the chart; the currency
 * is required; typed extra details ride along.
 *
 * Opened from inside a company's own editor, the holder is that company and
 * is shown read-only rather than hidden — it is a required field, and the
 * person should see what will be saved as the owning entity.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One account's fields, in the order a bank prints them.
@Composable
internal fun BankAccountDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val draft = setup.bankDraft
    val companies = setup.companies.edited
    val problem = draft?.let {
        BankAccounts.validationError(it, setup.banks, companies, state.viewer.isAccountant)
    }
    val isNew = draft?.id.isNullOrBlank()
    // The host company, resolved against the persisted list: a company still
    // being created must never become a dangling entity_id on the bank.
    val hostCompany = setup.companyDraft?.takeIf { host ->
        setup.bankDraftFromCompany && setup.companies.saved.any { it.id == host.id }
    }
    // Add mode only: an existing bank has a holder of its own, and repointing
    // it because of which editor it was opened from would move it silently.
    val lockedHolder = hostCompany?.takeIf { isNew }
    val holderMatched = draft?.entityId?.let { id -> companies.any { it.id == id } } == true

    ZillitDialogShell(
        title = if (isNew) "Add Bank Account" else "Edit Bank Account",
        subtitle = "Shared with Bank Reconciliation, Vendors and Payroll.",
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissBankDraft) },
        icon = ZillitIcons.Bank,
        width = DIALOG_WIDTH,
        actions = {
            // Edit mode drops Cancel, the same rule as the company dialog
            // (web 03f047d47); × / Esc still discard.
            if (isNew) {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(AccountHubEvent.DismissBankDraft) },
                    variant = ButtonVariant.Tertiary,
                    enabled = !setup.bankSaving,
                )
            }
            ZillitButton(
                text = if (isNew) "Add account" else "Save changes",
                onClick = { onEvent(AccountHubEvent.CommitBankDraft) },
                enabled = problem == null && !setup.bankSaving,
                loading = setup.bankSaving,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(next: BankAccount) = onEvent(AccountHubEvent.UpdateBankDraft(next))

        ZillitTextField(
            value = draft.name,
            onValueChange = { update(draft.copy(name = it)) },
            label = "Bank name *",
            placeholder = "e.g. Barclays",
        )
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Account holder *", modifier = Modifier.weight(1f))
                // From inside a company's editor this only leads back to where
                // the person already is, so the link is not offered there.
                if (!setup.bankDraftFromCompany) {
                    ZillitButton(
                        text = "Add company",
                        onClick = { onEvent(AccountHubEvent.EditCompany(null, fromBank = true)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            }
            if (lockedHolder != null) {
                ReadOnlyField(lockedHolder.name.ifBlank { "—" })
            } else {
                HubSelect(
                    value = companies.firstOrNull { it.id == draft.entityId },
                    options = companies,
                    label = { it.name.ifBlank { "Unnamed company" } },
                    secondary = { it.country },
                    onSelect = { picked -> update(draft.copy(
                        entityId = picked?.id,
                        accountHolderName = picked?.name.orEmpty(),
                    )) },
                    placeholder = if (companies.isEmpty()) {
                        "No companies yet — add one in the Companies section above"
                    } else {
                        "Select holder company…"
                    },
                    enabled = companies.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // A legacy bank with a free-typed holder, or a dangling id whose
                // company was deleted: the stored name is shown to re-pick from.
                if (!holderMatched && draft.accountHolderName.isNotBlank()) {
                    FieldHint("Previously: ${draft.accountHolderName} — select the matching company.")
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                // Masked as it is typed; stored digit-only. The hyphens are a
                // display convention, and persisting them would make two
                // accounts with the same sort code compare unequal.
                value = SortCode.formatted(draft.sortCode),
                onValueChange = { update(draft.copy(sortCode = SortCode.digits(it))) },
                label = "Sort code",
                placeholder = "e.g. 20-48-91",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.accountNumber,
                // Digits only on what is typed; a stored non-digit value is
                // displayed and round-tripped untouched.
                onValueChange = { update(draft.copy(accountNumber = BankAccounts.typedAccountNumber(it))) },
                label = "Account number *",
                placeholder = "e.g. 40183762",
                keyboardType = KeyboardType.Number,
                // The server's validator caps it here. Not a UK 8-digit rule:
                // truncating to 8 was tried and reverted (ZL-20361).
                maxLength = ACCOUNT_NUMBER_MAX,
                errorText = if (BankAccounts.duplicateNumber(draft, setup.banks)) {
                    "An account with this number already exists."
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.ibanNumber,
                onValueChange = { update(draft.copy(ibanNumber = it)) },
                label = "IBAN",
                placeholder = "e.g. GB29NWBK60161331926819",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.swiftCode,
                onValueChange = { update(draft.copy(swiftCode = it)) },
                label = "SWIFT / BIC",
                placeholder = "e.g. NWBKGB2L",
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.chequeNumber,
                onValueChange = { update(draft.copy(chequeNumber = it)) },
                label = "Cheque number",
                placeholder = "e.g. 100123",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.wireNumber,
                onValueChange = { update(draft.copy(wireNumber = it)) },
                label = "Wire number",
                placeholder = "e.g. 500456",
                modifier = Modifier.weight(1f),
            )
        }
        // Required, and asterisked, for accountants only: a coordinator reaching
        // this from a company's editor has no business knowing a suspense code.
        val required = if (state.viewer.isAccountant) " *" else ""
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CoaCodeField(
                value = draft.nominalCode,
                onValueChange = { update(draft.copy(nominalCode = it)) },
                accounts = state.chart.accounts,
                label = "Bank account nominal code$required",
                placeholder = "e.g. 1200",
                costType = com.zillit.desktop.feature.accounthub.domain.CoaCostType.Asset,
                modifier = Modifier.weight(1f),
                onCreate = quickCreateHandler(state, onEvent),
            )
            CoaCodeField(
                value = draft.apClearanceNominalCode,
                onValueChange = { update(draft.copy(apClearanceNominalCode = it)) },
                accounts = state.chart.accounts,
                label = "AP clearance nominal code$required",
                placeholder = "e.g. 2150",
                costType = com.zillit.desktop.feature.accounthub.domain.CoaCostType.Liability,
                modifier = Modifier.weight(1f),
                onCreate = quickCreateHandler(state, onEvent),
            )
        }
        CurrencyPicker(
            projectCurrencies = setup.currencies.edited.currencies,
            catalogue = setup.currencyCatalogue,
            code = draft.currencyCode,
            onPick = { picked ->
                update(draft.copy(
                    currencyCode = picked?.code.orEmpty(),
                    currencyName = picked?.name.orEmpty(),
                    currencySymbol = picked?.symbol.orEmpty(),
                ))
            },
        )
        ZillitSectionLabel("Additional details")
        TypedDetailsEditor(rows = draft.additionalDetails, onChange = { update(draft.copy(additionalDetails = it)) })

        if (draft.sortCode.isNotBlank() && SortCode.digits(draft.sortCode).length < SORT_CODE_DIGITS) {
            // A warning, not a block: non-UK accounts leave this empty and
            // carry their routing in the IBAN, so a short value is worth
            // flagging but never worth refusing.
            ZillitNotice(
                text = "A UK sort code is six digits. Leave it blank for a non-UK account.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }
        problem?.let { ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info) }
    }
}

/** A value that is set and cannot be changed here, drawn as a field so the form reads whole. */
@Composable
private fun ReadOnlyField(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1)
    }
}

/**
 * The full catalogue with the project's own currencies listed first and
 * tagged, as on the web — any currency can be chosen, the likely ones are on
 * top.
 */
@Composable
private fun CurrencyPicker(
    projectCurrencies: List<ProjectCurrency>,
    catalogue: List<ProjectCurrency>,
    code: String,
    onPick: (ProjectCurrency?) -> Unit,
) {
    val project = projectCurrencies.map { it.code.uppercase() }.toSet()
    val options = remember(projectCurrencies, catalogue) {
        val first = catalogue.filter { it.code.uppercase() in project }.ifEmpty { projectCurrencies }
        val rest = catalogue.filter { it.code.uppercase() !in project }
        first + rest
    }
    HubSelect(
        value = options.firstOrNull { it.code == code } ?: code.takeIf { it.isNotBlank() }?.let { ProjectCurrency(it) },
        options = options,
        label = { "${it.code} — ${it.name}".trimEnd(' ', '—') },
        secondary = { if (it.code.uppercase() in project) "Project" else it.country },
        onSelect = onPick,
        placeholder = "Select currency…",
        fieldLabel = "Currency *",
        clearable = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The removals Production Setup confirms — a company, a bank, a document, a group, a payroll code. */
@Composable
internal fun SetupRemovalDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val removal = state.setup.removal
    val (title, message, confirm) = when (removal) {
        is SetupRemoval.CompanyRow -> Triple(
            "Remove company",
            "Remove \"${removal.company.name.ifBlank { "this company" }}\"? Its banks are unlinked, not deleted. " +
                "This can't be undone.",
            "Delete",
        )
        is SetupRemoval.BankRow -> Triple(
            "Remove bank account",
            "Remove " +
                "\"${removal.bank.name.ifBlank { "this account" }}\"? This deletes the record Bank Reconciliation, " +
                "Vendors and Payroll read. It cannot be undone.",
            "Remove",
        )
        is SetupRemoval.AgreementRow -> Triple(
            "Remove document",
            "Remove \"${removal.document.title.ifBlank { removal.document.name }}\"? Deal memos will stop offering it.",
            "Remove",
        )
        is SetupRemoval.PayrollGroupRow -> Triple("Delete payroll group", "Delete this payroll group?", "Delete")
        is SetupRemoval.PayrollAccountCode -> Triple(
            "Remove payroll account",
            "Remove ${removal.code} from payroll accounts? This deactivates the code in the Chart of Accounts. It " +
                "will " +
                "fail if the code is in use or has active child accounts.",
            "Remove",
        )
        null -> Triple("", "", "")
    }
    HubConfirmDialog(
        visible = removal != null,
        title = title,
        message = message,
        confirmLabel = confirm,
        onConfirm = { onEvent(AccountHubEvent.ConfirmRemove) },
        onDismiss = { onEvent(AccountHubEvent.DismissRemove) },
        // A company removal is the list being saved; the dialog stays up until it lands.
        loading = removal is SetupRemoval.CompanyRow && state.setup.companies.saving,
    )
}

private val DIALOG_WIDTH = 640.dp
private val BANK_LIST_MAX = 260.dp
private val BANK_ICON = 32.dp
private val ICON_GLYPH = 15.dp
private val CHECK_CIRCLE = 22.dp
private val CHECK_GLYPH = 11.dp
private const val ICON_WASH = 0.15f
private const val ACCOUNT_NUMBER_MAX = 50
private const val SORT_CODE_DIGITS = 6
