package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.TypedDetailsEditor

/**
 * The company editor — the web's `CompanyFormModal`.
 *
 * ## It edits a draft, not the list
 *
 * Opening the dialog and dismissing it must leave the section clean. Editing
 * the list directly makes the section dirty the moment the dialog opens, so a
 * user who changed their mind is left with a Save button over an edit they
 * cancelled.
 *
 * ## Linking a bank takes it from whoever had it
 *
 * A bank belongs to at most one company. The subtraction happens when the draft
 * is committed rather than as each checkbox is ticked, so cancelling really
 * does undo it.
 */
@Suppress("LongMethod") // A form plus its bank list, read as one dialog.
@Composable
internal fun CompanyDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.companyDraft
    val isNew = draft != null && state.setup.companies.edited.none { it.id == draft.id }

    ZillitDialogShell(
        title = if (isNew) "New company" else "Edit company",
        subtitle = "Bank accounts hang off a company, so link them here.",
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissCompanyDraft) },
        icon = ZillitIcons.Bank,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissCompanyDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (isNew) "Add company" else "Done",
                onClick = { onEvent(AccountHubEvent.CommitCompanyDraft) },
                enabled = !draft?.name.isNullOrBlank(),
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell

        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(name = it))) },
            label = "Company name",
            placeholder = "e.g. Acme Productions Ltd",
        )
        ZillitTextField(
            value = draft.legalName,
            onValueChange = { onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(legalName = it))) },
            label = "Legal name",
            placeholder = "As registered at Companies House",
        )
        CountryPicker(
            countries = state.setup.countryTaxes,
            country = draft.country,
            countryCode = draft.countryCode,
            onPick = { name, code -> onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(
                country = name,
                countryCode = code,
            ))) },
        )
        TaxCreditsField(
            credits = draft.taxCredits,
            onChange = { onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(taxCredits = it))) },
        )
        ZillitSectionLabel("UK payroll references")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.ukPayeRef,
                onValueChange = { onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(ukPayeRef = it))) },
                label = "PAYE reference",
                placeholder = "123/AB456",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.ukAccountsOfficeRef,
                onValueChange = { onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(ukAccountsOfficeRef = it))) },
                label = "Accounts Office reference",
                placeholder = "123PA00012345",
                modifier = Modifier.weight(1f),
            )
        }

        ZillitSectionLabel("Bank accounts")
        if (state.setup.banks.isEmpty()) {
            FieldHint("No bank accounts on this project yet. Add one from the Bank Accounts section, then link it " +
                "here.")
        }
        state.setup.banks.forEach { bank ->
            val ownedElsewhere = state.setup.companies.edited
                .firstOrNull { it.id != draft.id && bank.id in it.bankIds }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitCheckbox(
                    checked = bank.id in draft.bankIds,
                    onCheckedChange = { checked ->
                        val next = if (checked) draft.bankIds + bank.id else draft.bankIds - bank.id
                        onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(bankIds = next)))
                    },
                    label = bank.name.ifBlank { "Unnamed account" },
                    // The web only lists banks no other company has claimed.
                    enabled = ownedElsewhere == null,
                    modifier = Modifier.weight(1f),
                )
                ownedElsewhere?.let { FieldHint("Linked to ${it.name}") }
            }
        }
    }
}

/** A searchable country picker over the tax catalogue's countries — the web's `SearchableSelect`. */
@Composable
private fun CountryPicker(
    countries: List<CountryTaxes>,
    country: String,
    countryCode: String,
    onPick: (name: String, code: String) -> Unit,
) {
    val current = countries.firstOrNull { it.countryCode == countryCode }
        ?: country.takeIf { it.isNotBlank() }?.let { CountryTaxes(country = it, countryCode = countryCode) }
    HubSelect(
        value = current,
        options = countries,
        label = { "${it.country} (${it.countryCode})" },
        onSelect = { picked -> onPick(picked?.country.orEmpty(), picked?.countryCode.orEmpty()) },
        placeholder = if (countries.isEmpty()) "Loading countries…" else "Type country name or ISO code…",
        fieldLabel = "Country",
        clearable = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Free-typed tax-credit chips — the web's `TaxCreditsField` combobox. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaxCreditsField(credits: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel("Tax credits")
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
            onValueChange = { draft = it },
            placeholder = "e.g. uk-avec — press Enter to add",
            imeAction = ImeAction.Done,
            onImeAction = {
                val credit = draft.trim()
                if (credit.isNotEmpty() && credit !in credits) onChange(credits + credit)
                draft = ""
            },
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
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One account's fields, in the order a bank prints them.
@Composable
internal fun BankAccountDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.bankDraft
    val companies = state.setup.companies.edited
    val problem = draft?.let {
        BankAccounts.validationError(it, state.setup.banks, companies, state.viewer.isAccountant)
    }

    ZillitDialogShell(
        title = if (draft?.id.isNullOrBlank()) "New bank account" else "Edit bank account",
        subtitle = "Shared with Bank Reconciliation, Vendors and Payroll.",
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissBankDraft) },
        icon = ZillitIcons.Bank,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissBankDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save account",
                onClick = { onEvent(AccountHubEvent.CommitBankDraft) },
                enabled = problem == null && !state.setup.bankSaving,
                loading = state.setup.bankSaving,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(next: com.zillit.desktop.feature.accounthub.domain.BankAccount) =
            onEvent(AccountHubEvent.UpdateBankDraft(next))

        ZillitTextField(
            value = draft.name,
            onValueChange = { update(draft.copy(name = it)) },
            label = "Bank name",
            placeholder = "e.g. Barclays",
        )
        HubSelect(
            value = companies.firstOrNull { it.id == draft.entityId },
            options = companies,
            label = { it.name.ifBlank { "Unnamed company" } },
            onSelect = { picked -> update(draft.copy(
                entityId = picked?.id,
                accountHolderName = picked?.name ?: draft.accountHolderName,
            )) },
            placeholder = if (companies.isEmpty()) "Add a company first" else "Select the account holder…",
            fieldLabel = "Account holder company",
            enabled = companies.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (companies.isEmpty()) {
            ZillitTextField(
                value = draft.accountHolderName,
                onValueChange = { update(draft.copy(accountHolderName = it)) },
                label = "Account holder",
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.accountNumber,
                onValueChange = { update(draft.copy(accountNumber = it)) },
                label = "Account number",
                placeholder = "e.g. 40183762",
                // The server's validator caps it here. Not a UK 8-digit rule:
                // truncating to 8 was tried and reverted (ZL-20361).
                maxLength = ACCOUNT_NUMBER_MAX,
                errorText = if (BankAccounts.duplicateNumber(
                    draft,
                    state.setup.banks,
                )) "An account with this number already exists." else null,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                // Masked as it is typed; stored digit-only. The hyphens are a
                // display convention, and persisting them would make two
                // accounts with the same sort code compare unequal.
                value = SortCode.formatted(draft.sortCode),
                onValueChange = { update(draft.copy(sortCode = SortCode.digits(it))) },
                label = "Sort code",
                placeholder = "e.g. 20-48-91",
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.ibanNumber,
                onValueChange = { update(draft.copy(ibanNumber = it)) },
                label = "IBAN",
                placeholder = "e.g. GB29NWBK60161331926819",
                modifier = Modifier.weight(WEIGHT_WIDE),
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CoaCodeField(
                value = draft.nominalCode,
                onValueChange = { update(draft.copy(nominalCode = it)) },
                accounts = state.chart.accounts,
                label = "Bank account nominal code",
                placeholder = "e.g. 1200",
                costType = com.zillit.desktop.feature.accounthub.domain.CoaCostType.Asset,
                modifier = Modifier.weight(1f),
                onCreate = quickCreateHandler(state, onEvent),
            )
            CoaCodeField(
                value = draft.apClearanceNominalCode,
                onValueChange = { update(draft.copy(apClearanceNominalCode = it)) },
                accounts = state.chart.accounts,
                label = "AP clearance nominal code",
                placeholder = "e.g. 2150",
                costType = com.zillit.desktop.feature.accounthub.domain.CoaCostType.Liability,
                modifier = Modifier.weight(1f),
                onCreate = quickCreateHandler(state, onEvent),
            )
        }
        CurrencyPicker(
            options = state.setup.currencies.edited.currencies.ifEmpty { state.setup.currencyCatalogue },
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

@Composable
private fun CurrencyPicker(options: List<ProjectCurrency>, code: String, onPick: (ProjectCurrency?) -> Unit) {
    HubSelect(
        value = options.firstOrNull { it.code == code },
        options = options,
        label = { "${it.code} — ${it.name}".trimEnd(' ', '—') },
        onSelect = onPick,
        placeholder = "Select currency…",
        fieldLabel = "Currency",
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
                "Nothing is written until the section is saved.",
            "Remove",
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
    )
}

private val DIALOG_WIDTH = 640.dp
private const val WEIGHT_WIDE = 2f
private const val ACCOUNT_NUMBER_MAX = 50
private const val SORT_CODE_DIGITS = 6
