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
            is AccountHubEvent.EditCompany -> editCompany(event.company, event.fromBank)
            is AccountHubEvent.UpdateCompanyDraft -> vm.update {
                copy(setup = setup.copy(companyDraft = event.company))
            }
            AccountHubEvent.DismissCompanyDraft -> vm.update {
                copy(setup = setup.copy(companyDraft = null, companyDraftFromBank = false))
            }
            AccountHubEvent.CommitCompanyDraft -> commitCompanyDraft()
            is AccountHubEvent.RemoveCompany -> removeCompany(event.id)
            is AccountHubEvent.EditBank -> editBank(event.account, event.fromCompany)
            is AccountHubEvent.UpdateBankDraft -> vm.update { copy(setup = setup.copy(bankDraft = event.account)) }
            AccountHubEvent.DismissBankDraft -> vm.update {
                copy(setup = setup.copy(bankDraft = null, bankDraftFromCompany = false))
            }
            AccountHubEvent.CommitBankDraft -> commitBankDraft()
            is AccountHubEvent.DeleteBank -> deleteBank(event.id)
            is AccountHubEvent.RevealBank -> revealBank(event.id)
            is AccountHubEvent.SetCurrencyFilter -> vm.update {
                copy(setup = setup.copy(currencyFilter = event.filter))
            }
            is AccountHubEvent.SearchCurrencies -> vm.update { copy(setup = setup.copy(currencySearch = event.term)) }
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
     * Opens the editor over a draft, never the list: dismissing it must leave
     * the section clean. An existing company's bank links are seeded from
     * both sides of the link (see [Companies.linkedBankIds]) so the next save
     * converges `bank_ids` onto the banks that already point here.
     */
    private fun editCompany(company: Company?, fromBank: Boolean) {
        val setup = vm.setupState.setup
        val draft = company?.copy(bankIds = Companies.linkedBankIds(company, setup.banks))
            ?: Company(id = vm.newLocalId("co"))
        vm.update {
            copy(
                setup = this.setup.copy(
                    companyDraft = draft,
                    companyDraftFromBank = fromBank,
                    companyDraftSession = this.setup.companyDraftSession + 1,
                ),
            )
        }
    }

    /**
     * Folds the drafted company into the list and saves it straight away —
     * the web's Done hits the API itself, so nobody has to find the section's
     * Save button afterwards. On failure the dialog stays open for a retry
     * and the section is left dirty, so Save changes reappears as a fallback.
     *
     * The bank re-assignment happens here rather than in the dialog because it
     * touches *other* companies: a bank moved without being taken from its
     * previous owner ends up owned twice. See [Companies.linking].
     */
    private fun commitCompanyDraft() {
        val setup = vm.setupState.setup
        val draft = setup.companyDraft ?: return
        val existing = setup.companies.edited
        val isNew = existing.none { it.id == draft.id }
        // A brand-new draft with no name is the same as cancel.
        if (isNew && draft.name.isBlank()) {
            vm.update { copy(setup = this.setup.copy(companyDraft = null, companyDraftFromBank = false)) }
            return
        }
        Companies.problem(draft)?.let { return vm.sendSideEffect(AccountHubEffect.Failed(it)) }
        if (!vm.mayEdit()) return
        val merged = if (isNew) existing + draft else existing.map { if (it.id == draft.id) draft else it }
        val next = Companies.linking(merged, draft.id, draft.bankIds)
        val fromBank = setup.companyDraftFromBank
        saveCompanies(next, notice = if (isNew) "Company added." else "Company saved.", shownFirst = true) {
            copy(
                setup = this.setup.copy(
                    companyDraft = null,
                    companyDraftFromBank = false,
                    // The bank editor underneath takes the new company as its holder.
                    bankDraft = if (fromBank) {
                        this.setup.bankDraft?.copy(entityId = draft.id, accountHolderName = draft.name.trim())
                    } else {
                        this.setup.bankDraft
                    },
                ),
            )
        }
    }

    /**
     * Saves the list without the company, and only then drops it from the
     * screen (ZL-20382): an optimistic removal would strand the row when the
     * server refuses because a purchase order still references the company.
     */
    private fun removeCompany(id: String) {
        val setup = vm.setupState.setup
        if (!vm.mayEdit()) return
        val next = setup.companies.edited.filterNot { it.id == id }
        saveCompanies(next, notice = "Company removed.", shownFirst = false) {
            copy(
                setup = this.setup.copy(
                    removal = null,
                    companyDraft = this.setup.companyDraft?.takeIf { it.id != id },
                ),
            )
        }
    }

    /**
     * [shownFirst] puts [next] on screen before the call — an edit that
     * fails then stays as an unsaved edit the section's own Save can retry.
     * A removal is not shown first: a row that vanished and then could not
     * be removed is a row the person believes is gone.
     */
    private fun saveCompanies(
        next: List<Company>,
        notice: String,
        shownFirst: Boolean,
        onSaved: AccountHubUiState.() -> AccountHubUiState,
    ) {
        val banks = vm.setupState.setup.banks
        vm.update {
            val edited = if (shownFirst) setup.companies.edit(next) else setup.companies
            copy(setup = setup.copy(companies = edited.copy(saving = true)))
        }
        vm.runResult(
            { vm.repo.saveCompanies(Companies.forWire(next, banks)) },
            { rows ->
                vm.update { copy(setup = setup.copy(companies = setup.companies.committed(rows))).onSaved() }
                vm.update { copy(notice = notice) }
            },
            { error ->
                vm.update { copy(setup = setup.copy(companies = setup.companies.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    // -- banks --------------------------------------------------------------

    /**
     * Opens the bank editor. Added from inside a company's own editor, the
     * holder is that company when it already exists on the server — a company
     * still being created has only a client-side id, which must never become
     * a dangling `entity_id` on the bank, so the picker stays for it.
     */
    private fun editBank(account: BankAccount?, fromCompany: Boolean) {
        val setup = vm.setupState.setup
        val host = setup.companyDraft?.takeIf { draft ->
            fromCompany && setup.companies.saved.any { it.id == draft.id }
        }
        val holder = host ?: setup.companies.edited.singleOrNull()
        val draft = account ?: BankAccount(
            id = "",
            // A production with one company banks with it; pre-filled so the
            // holder reads right before anything is typed.
            entityId = holder?.id,
            accountHolderName = holder?.name.orEmpty(),
            currencyCode = setup.currencies.edited.defaultCode.orEmpty(),
        ).let { fresh ->
            val currency = setup.currencies.edited.default
            if (currency != null) fresh.copy(currencyName = currency.name, currencySymbol = currency.symbol) else fresh
        }
        vm.update { copy(setup = this.setup.copy(bankDraft = draft, bankDraftFromCompany = fromCompany)) }
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
        val isNew = outgoing.id.isBlank()
        val fromCompany = state.setup.bankDraftFromCompany
        vm.update { copy(setup = setup.copy(bankSaving = true)) }
        vm.runResult(
            { if (isNew) vm.repo.createBankAccount(outgoing) else vm.repo.updateBankAccount(outgoing) },
            { saved ->
                vm.update {
                    copy(
                        setup = setup.copy(
                            bankDraft = null,
                            bankDraftFromCompany = false,
                            bankSaving = false,
                            companyDraft = setup.companyDraft?.linkedTo(saved, fromCompany && isNew),
                        ),
                        notice = "Bank account saved.",
                    )
                }
                vm.loadBanks()
            },
            { error ->
                vm.update { copy(setup = setup.copy(bankSaving = false)) }
                vm.report(error)
            },
        )
    }

    /**
     * A bank created from inside a company's editor was made for that
     * company: it is linked onto the draft at once. An edit, or a bank added
     * from the section, leaves the selection alone.
     */
    private fun Company.linkedTo(saved: BankAccount, created: Boolean): Company =
        if (created && saved.id.isNotBlank() && saved.id !in bankIds) copy(bankIds = bankIds + saved.id) else this

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

    /**
     * A new rule starts on its list's usual condition and is *not* an
     * enhancement: the web's rules editor starts "Add on top" unticked, and
     * the flag is what decides base × a against base × (1 + a) — a default of
     * true made a 1.5× overtime bill 2.5× on the web once.
     */
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
            isEnhancement = false,
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
