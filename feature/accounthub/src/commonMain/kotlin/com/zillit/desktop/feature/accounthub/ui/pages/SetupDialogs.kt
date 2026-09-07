package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState

/**
 * The company editor.
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

    ZillitDialogShell(
        title = if (draft?.name.isNullOrBlank()) "New company" else "Edit company",
        subtitle = "Bank accounts hang off a company, so link them here.",
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissCompanyDraft) },
        icon = ZillitIcons.Bank,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissCompanyDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Done",
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
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.country,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(country = it)))
                },
                label = "Country",
                modifier = Modifier.weight(WEIGHT_WIDE),
            )
            ZillitTextField(
                value = draft.countryCode,
                onValueChange = {
                    onEvent(
                        AccountHubEvent.UpdateCompanyDraft(
                            draft.copy(countryCode = it.uppercase().take(COUNTRY_CODE_LENGTH)),
                        ),
                    )
                },
                label = "ISO code",
                modifier = Modifier.weight(1f),
            )
        }

        ZillitSectionLabel("Bank accounts")
        if (state.setup.banks.isEmpty()) {
            ZillitText(
                text = "No bank accounts on this project yet. Add one from the Bank " +
                    "Accounts section, then link it here.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
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
                    modifier = Modifier.weight(1f),
                )
                // Named, because ticking it will silently take the bank off
                // that company — better to say so before it happens.
                ownedElsewhere?.let {
                    ZillitText(
                        text = "Currently ${it.name}'s",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * The bank account editor.
 *
 * Saves on its own, unlike every other section on the page: a bank is a
 * first-class record in the table Bank Reconciliation, Vendors and Payroll all
 * read, not a slice of the settings document.
 */
@Suppress("LongMethod") // One account's fields, in the order a bank prints them.
@Composable
internal fun BankAccountDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.bankDraft

    ZillitDialogShell(
        title = if (draft?.id.isNullOrBlank()) "New bank account" else "Edit bank account",
        subtitle = "Shared with Bank Reconciliation, Vendors and Payroll.",
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissBankDraft) },
        icon = ZillitIcons.Bank,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissBankDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save account",
                onClick = { onEvent(AccountHubEvent.CommitBankDraft) },
                enabled = !draft?.name.isNullOrBlank(),
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell

        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(name = it))) },
            label = "Bank name",
        )
        ZillitTextField(
            value = draft.accountHolderName,
            onValueChange = {
                onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(accountHolderName = it)))
            },
            label = "Account holder",
            helperText = "Overridden by the linked company's current name when there is one.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.accountNumber,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(accountNumber = it)))
                },
                label = "Account number",
                // The server's validator caps it here. Not a UK 8-digit rule:
                // truncating to 8 was tried and reverted (ZL-20361).
                maxLength = ACCOUNT_NUMBER_MAX,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                // Masked as it is typed; stored digit-only. The hyphens are a
                // display convention, and persisting them would make two
                // accounts with the same sort code compare unequal.
                value = SortCode.formatted(draft.sortCode),
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(sortCode = SortCode.digits(it))))
                },
                label = "Sort code",
                placeholder = "20-48-91",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.swiftCode,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(swiftCode = it)))
                },
                label = "SWIFT / BIC",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.ibanNumber,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(ibanNumber = it)))
                },
                label = "IBAN",
                modifier = Modifier.weight(WEIGHT_WIDE),
            )
        }
        ZillitTextField(
            value = draft.nominalCode,
            onValueChange = {
                onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(nominalCode = it)))
            },
            label = "Nominal code",
        )

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
    }
}

private const val WEIGHT_WIDE = 2f
private const val ACCOUNT_NUMBER_MAX = 50
private const val COUNTRY_CODE_LENGTH = 2
private const val SORT_CODE_DIGITS = 6
