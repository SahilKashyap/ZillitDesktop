package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Production Setup's own dialogs and pickers.
 *
 * The company and bank editors, the currency and tax pickers, the tag input,
 * the pay-rule editor and every "are you sure" the page asks. Its own
 * collaborator because none of it saves a section — it prepares what a
 * section then holds — and the view model was carrying a third of its length
 * in this one shape.
 */
@Suppress("TooManyFunctions") // One handler per dialog action.
internal class SetupUiActions(private val vm: AccountHubViewModel) {

    private var revealJob: Job? = null

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action; every one delegates.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.EditCompany -> vm.update {
                copy(setup = setup.copy(companyDraft = event.company ?: Company(id = vm.newLocalId("co"))))
            }
            is AccountHubEvent.UpdateCompanyDraft -> vm.update {
                copy(setup = setup.copy(companyDraft = event.company))
            }
            AccountHubEvent.DismissCompanyDraft -> vm.update { copy(setup = setup.copy(companyDraft = null)) }
            AccountHubEvent.CommitCompanyDraft -> commitCompanyDraft()
            is AccountHubEvent.RemoveCompany -> removeCompany(event.id)
            is AccountHubEvent.EditBank -> editBank(event.account)
            is AccountHubEvent.UpdateBankDraft -> vm.update { copy(setup = setup.copy(bankDraft = event.account)) }
            AccountHubEvent.DismissBankDraft -> vm.update { copy(setup = setup.copy(bankDraft = null)) }
            AccountHubEvent.CommitBankDraft -> commitBankDraft()
            is AccountHubEvent.DeleteBank -> deleteBank(event.id)
            is AccountHubEvent.RevealBank -> revealBank(event.id)
            is AccountHubEvent.SetCurrencyFilter -> vm.update {
                copy(setup = setup.copy(currencyFilter = event.filter))
            }
            is AccountHubEvent.SearchCurrencies -> vm.update { copy(setup = setup.copy(currencySearch = event.term)) }
            is AccountHubEvent.ToggleCurrencyPicker ->
                vm.update { copy(setup = setup.copy(currencyPickerOpen = event.open, currencySearch = "")) }
            is AccountHubEvent.PickTaxCountry -> vm.update { copy(setup = setup.copy(taxCountry = event.countryCode)) }
            is AccountHubEvent.EditTagDraft -> vm.update { copy(setup = setup.copy(tagDraft = event.text)) }
            AccountHubEvent.CommitTagDraft -> commitTags()
            is AccountHubEvent.ComposePayRule -> composePayRule(event.kind, event.index)
            is AccountHubEvent.EditPayRule -> vm.update {
                copy(setup = setup.copy(ruleEditor = setup.ruleEditor?.copy(rule = event.rule)))
            }
            AccountHubEvent.CommitPayRule -> commitPayRule()
            AccountHubEvent.DismissPayRule -> vm.update { copy(setup = setup.copy(ruleEditor = null)) }
            is AccountHubEvent.RemovePayRule -> removePayRule(event.kind, event.index)
            is AccountHubEvent.ToggleDepartmentPicker -> vm.update {
                copy(setup = setup.copy(departmentPickerOpen = event.open, departmentPickerSearch = ""))
            }
            is AccountHubEvent.SearchDepartmentPicker ->
                vm.update { copy(setup = setup.copy(departmentPickerSearch = event.term)) }
            is AccountHubEvent.AskRemove -> vm.update { copy(setup = setup.copy(removal = event.removal)) }
            AccountHubEvent.DismissRemove -> vm.update { copy(setup = setup.copy(removal = null)) }
            AccountHubEvent.ConfirmRemove -> confirmRemove()
            else -> return false
        }
        return true
    }

    // -- companies ----------------------------------------------------------

    /**
     * Folds the drafted company back into the list.
     *
     * The bank re-assignment happens here rather than in the dialog because it
     * touches *other* companies: a bank moved without being taken from its
     * previous owner ends up owned twice. See [Companies.linking].
     */
    private fun commitCompanyDraft() {
        val draft = vm.setupState.setup.companyDraft ?: return
        if (draft.name.isBlank()) {
            vm.sendSideEffect(AccountHubEffect.Failed("Give the company a name."))
            return
        }
        vm.update {
            val existing = setup.companies.edited
            val merged = if (existing.any { it.id == draft.id }) {
                existing.map { if (it.id == draft.id) draft else it }
            } else {
                existing + draft
            }
            copy(
                setup = setup.copy(
                    companies = setup.companies.edit(Companies.linking(merged, draft.id, draft.bankIds)),
                    companyDraft = null,
                ),
            )
        }
    }

    private fun removeCompany(id: String) = vm.update {
        val remaining = setup.companies.edited.filterNot { it.id == id }
        copy(setup = setup.copy(companies = setup.companies.edit(remaining), removal = null))
    }

    // -- banks --------------------------------------------------------------

    private fun editBank(account: BankAccount?) {
        val setup = vm.setupState.setup
        val draft = account ?: BankAccount(
            id = "",
            // A production with one company banks with it; pre-filled so the
            // holder reads right before anything is typed.
            entityId = setup.companies.edited.singleOrNull()?.id,
            currencyCode = setup.currencies.edited.defaultCode.orEmpty(),
        ).let { fresh ->
            val currency = setup.currencies.edited.default
            if (currency != null) fresh.copy(currencyName = currency.name, currencySymbol = currency.symbol) else fresh
        }
        vm.update { copy(setup = this.setup.copy(bankDraft = draft)) }
    }

    /**
     * Saves the bank, after the web's own checks.
     *
     * The holder is the linked company's *current* name, and the two nominal
     * codes are wrapped as `[[code]]` when the chart does not know them — a
     * typed code the ledger can tell from a resolved one.
     */
    private fun commitBankDraft() {
        val state = vm.setupState
        val draft = state.setup.bankDraft ?: return
        if (!vm.mayEdit()) return
        val companies = state.setup.companies.edited
        val problem = BankAccounts.validationError(
            draft = draft,
            banks = state.setup.banks,
            companies = companies,
            accountant = state.viewer.isAccountant,
        )
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        val holder = companies.firstOrNull { it.id == draft.entityId }?.name ?: draft.accountHolderName
        val known = if (state.chartKnown) state.chart.accounts.map { it.code } else null
        val outgoing = draft.copy(
            accountHolderName = holder,
            nominalCode = known?.let { BankAccounts.wrapNominal(draft.nominalCode, it) } ?: draft.nominalCode,
            apClearanceNominalCode = known?.let { BankAccounts.wrapNominal(draft.apClearanceNominalCode, it) }
                ?: draft.apClearanceNominalCode,
        )
        vm.update { copy(setup = setup.copy(bankSaving = true)) }
        vm.runResult(
            { if (outgoing.id.isBlank()) vm.repo.createBankAccount(outgoing) else vm.repo.updateBankAccount(outgoing) },
            {
                vm.update {
                    copy(setup = setup.copy(bankDraft = null, bankSaving = false), notice = "Bank account saved.")
                }
                vm.loadBanks()
            },
            { error ->
                vm.update { copy(setup = setup.copy(bankSaving = false)) }
                vm.report(error)
            },
        )
    }

    private fun deleteBank(id: String) {
        if (!vm.mayEdit()) return
        vm.runResult({ vm.repo.deleteBankAccount(id) }, {
            vm.update { copy(setup = setup.copy(removal = null), notice = "Bank account removed.") }
            vm.loadBanks()
        }, vm::report)
    }

    /** Unmasks one card; re-masks after five seconds, as the web's card does. */
    private fun revealBank(id: String?) {
        revealJob?.cancel()
        vm.update { copy(setup = setup.copy(revealedBankId = id)) }
        if (id == null) return
        revealJob = vm.launchWork {
            delay(REVEAL_MS)
            vm.update { copy(setup = setup.copy(revealedBankId = null)) }
        }
    }

    // -- tags ---------------------------------------------------------------

    /**
     * Commits the draft: split on commas, trimmed, upper-cased, deduplicated —
     * the web's own normalisation, so "camera, Camera ,CAMERA" is one tag.
     */
    private fun commitTags() = vm.update {
        val parts = setup.tagDraft.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val next = (setup.assetTags.edited + parts).distinct()
        copy(setup = setup.copy(assetTags = setup.assetTags.edit(next), tagDraft = ""))
    }

    // -- pay rules ------------------------------------------------------------

    private fun composePayRule(kind: PayRuleKind, index: Int?) {
        val rules = vm.setupState.setup.nonUnionPay.edited.rulesFor(kind)
        val rule = index?.let { rules.getOrNull(it) } ?: newRule(kind, rules.size)
        vm.update { copy(setup = setup.copy(ruleEditor = PayRuleEditor(kind, index, rule))) }
    }

    /** A new rule starts on its list's usual condition, "Basic + OT on top" as the web does. */
    private fun newRule(kind: PayRuleKind, at: Int): PayRule {
        val template = PayRuleTemplate.defaultFor(kind)
        return PayRule(
            id = "${kind.wire}-new-$at",
            label = template.label,
            rateType = template.defaultRateType,
            rateAmount = template.defaultRateAmount,
            basis = template.defaultBasis,
            triggers = listOf(template.trigger(
                hours = "",
                clock = "",
                dayKinds = emptyList(),
                carrying = PayTrigger(),
            )),
            isEnhancement = true,
        )
    }

    private fun commitPayRule() {
        val editor = vm.setupState.setup.ruleEditor ?: return
        val rule = editor.rule
        val problem = when {
            rule.label.isBlank() -> "Give the rule a name."
            rule.rateAmount.trim().replace(",", "").toDoubleOrNull() == null -> "Give the rule an amount."
            rule.capped && rule.capAmount.trim().replace(",", "").toDoubleOrNull() == null ->
                "Give the cap an amount, or turn the cap off."
            else -> null
        }
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        vm.update {
            val pay = setup.nonUnionPay
            val rules = pay.edited.rulesFor(editor.kind)
            val next = if (editor.index != null && editor.index in rules.indices) {
                rules.mapIndexed { i, existing -> if (i == editor.index) rule else existing }
            } else {
                rules + rule
            }
            copy(setup = setup.copy(nonUnionPay = pay.edit(pay.edited.withRules(editor.kind, next)), ruleEditor = null))
        }
    }

    private fun removePayRule(kind: PayRuleKind, index: Int) = vm.update {
        val pay = setup.nonUnionPay
        val rules = pay.edited.rulesFor(kind).filterIndexed { i, _ -> i != index }
        copy(setup = setup.copy(nonUnionPay = pay.edit(pay.edited.withRules(kind, rules))))
    }

    // -- removals -------------------------------------------------------------

    private fun confirmRemove() {
        val removal = vm.setupState.setup.removal ?: return
        when (removal) {
            is SetupRemoval.CompanyRow -> removeCompany(removal.company.id)
            is SetupRemoval.BankRow -> deleteBank(removal.bank.id)
            is SetupRemoval.AgreementRow -> {
                vm.update { copy(setup = setup.copy(removal = null)) }
                vm.onEvent(AccountHubEvent.DeleteAgreementDocument(removal.document.id))
            }
            is SetupRemoval.PayrollGroupRow -> deletePayrollGroup(removal.group.id)
            is SetupRemoval.PayrollAccountCode -> removePayrollAccount(removal)
        }
    }

    private fun deletePayrollGroup(id: String) {
        vm.runResult({ vm.repo.deletePayrollGroup(id) }, {
            vm.update {
                copy(
                    setup = setup.copy(payrollGroups = setup.payrollGroups.filterNot { it.id == id }, removal = null),
                    notice = "Payroll group removed.",
                )
            }
        }, vm::report)
    }

    /**
     * Drops a payroll code: deactivated in the chart and taken off the list in
     * one call. Refused by the server when the code is in use or has active
     * children — its message is shown as it comes.
     */
    private fun removePayrollAccount(removal: SetupRemoval.PayrollAccountCode) {
        val id = removal.accountId
        if (id == null) {
            vm.sendSideEffect(AccountHubEffect.Failed("${removal.code} is not in the chart of accounts."))
            return
        }
        vm.runResult({ vm.repo.updatePayrollAccounts(listOf(PayrollAccountRow(id = id, delete = true))) }, { settings ->
            vm.update {
                copy(
                    setup = setup.copy(payrollSettings = setup.payrollSettings.committed(settings), removal = null),
                    notice = "Payroll account removed.",
                )
            }
            vm.chart.load()
        }, { error ->
            vm.update { copy(setup = setup.copy(removal = null)) }
            vm.report(error)
        })
    }

    private companion object {
        const val REVEAL_MS = 5_000L
    }
}
