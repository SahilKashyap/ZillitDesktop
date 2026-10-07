package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.SpendDeductionRule
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.domain.SpendTeamMember
import kotlinx.coroutines.async

/** A dialog open over the Card or Petty Cash modal, holding the row being added or edited. */
sealed interface SpendDraft {

    /** A team member; [limitText] is the posting limit as typed, read only when the member is not unlimited. */
    data class Member(val index: Int?, val member: SpendTeamMember, val limitText: String = "") : SpendDraft

    /** A deduction rule; [valueText] is the threshold as typed and [triggerText] the trigger code being entered. */
    data class Rule(
        val index: Int?,
        val rule: SpendDeductionRule,
        val valueText: String = "",
        val triggerText: String = "",
    ) : SpendDraft
}

/**
 * Card and Petty Cash Entry Setup — the web's `SharedSpendDetail`.
 *
 * The module's settings document and its auto-assignment rules are read
 * together when the modal opens, and nothing is editable until both have
 * landed: a modal on defaults is one Save away from overwriting the stored
 * settings. A save writes what changed in the document and then diffs the
 * rules, as the purchase-order and invoices modals do.
 */
internal class SpendSetupActions(
    private val vm: AccountHubViewModel,
    /** The rules diff the other modals share: the module key, how to read the section, how to put it back. */
    private val saveRules: (
        String,
        SetupState.() -> SectionEdit<List<AssignmentRule>>,
        AccountHubUiState.(SectionEdit<List<AssignmentRule>>) -> AccountHubUiState,
    ) -> Unit,
) {

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.EditSpendSetup -> vm.update {
                copy(setup = setup.copy(spendSetup = setup.spendSetup.edit(event.value)))
            }
            is AccountHubEvent.EditSpendRules -> vm.update {
                copy(setup = setup.copy(spendRules = setup.spendRules.edit(event.rules)))
            }
            is AccountHubEvent.ComposeSpendMember -> composeMember(event.index)
            is AccountHubEvent.ComposeSpendRule -> composeRule(event.index)
            is AccountHubEvent.EditSpendDraft -> vm.update { copy(setup = setup.copy(spendDraft = event.draft)) }
            AccountHubEvent.CommitSpendDraft -> commitDraft()
            AccountHubEvent.DismissSpendDraft -> vm.update { copy(setup = setup.copy(spendDraft = null)) }
            else -> return false
        }
        return true
    }

    /** Reads the document and the rules together; the shell shows its loading and error states around it. */
    fun read(kind: SpendKind) {
        vm.launchWork {
            val document = async { vm.repo.spendSetup(kind) }
            val rules = async { vm.repo.assignmentRules(kind.moduleKey) }
            val doc = document.await()
            val rows = rules.await()
            val failure = (doc as? ZillitResult.Failure)?.error ?: (rows as? ZillitResult.Failure)?.error
            vm.update {
                // Closed or switched to the other module meanwhile: this answer is for nobody.
                val open = setup.modal?.takeIf { it.modal.spendKind == kind } ?: return@update this
                if (failure != null) {
                    copy(setup = setup.copy(modal = open.copy(loading = false, loadError = failure.localised())))
                } else {
                    copy(
                        setup = setup.copy(
                            spendSetup = SectionEdit((doc as ZillitResult.Success).data),
                            spendRules = SectionEdit((rows as ZillitResult.Success).data),
                            modal = open.copy(loading = false, loadError = null),
                        ),
                    )
                }
            }
        }
    }

    fun save(kind: SpendKind) {
        val state = vm.setupState.setup
        val section = state.spendSetup
        // The document held is the other module's: a read for this one has not landed.
        if (section.saved.kind != kind) return
        if (section.dirty && !section.saving) saveDocument(section)
        saveRules(kind.moduleKey, { spendRules }, { copy(setup = setup.copy(spendRules = it)) })
    }

    private fun saveDocument(section: SectionEdit<SpendSettings>) {
        val unnamed = section.edited.providers.any { !it.isBlank && it.name.isBlank() }
        if (unnamed) {
            vm.fail(str(S.desktop_card_provider_needs_name))
            return
        }
        val cards = section.edited.kind == SpendKind.Cards
        val done = if (cards) S.desktop_hub_sp_saved_cards else S.desktop_hub_sp_saved_cash
        vm.update { copy(setup = setup.copy(spendSetup = section.copy(saving = true))) }
        vm.runResult({ vm.repo.saveSpendSetup(section.saved, section.edited) }, { saved ->
            vm.update { copy(setup = setup.copy(spendSetup = SectionEdit(saved)), notice = str(done)) }
        }, { error: ZillitError ->
            vm.update { copy(setup = setup.copy(spendSetup = setup.spendSetup.copy(saving = false))) }
            vm.report(error)
        })
    }

    // -- the dialogs --------------------------------------------------------------

    private fun composeMember(index: Int?) = vm.update {
        val member = index?.let { setup.spendSetup.edited.team.getOrNull(it) } ?: SpendTeamMember()
        val limit = member.postingLimit?.takeIf { it != 0.0 }.asAmountText()
        copy(setup = setup.copy(spendDraft = SpendDraft.Member(index, member, limit)))
    }

    private fun composeRule(index: Int?) = vm.update {
        val rule = index?.let { setup.spendSetup.edited.deductionRules.getOrNull(it) }
            ?: SpendDeductionRule(id = "custom_${vm.nowMillis()}")
        copy(setup = setup.copy(spendDraft = SpendDraft.Rule(index, rule, rule.thresholdValue.asAmountText())))
    }

    private fun commitDraft() {
        when (val draft = vm.setupState.setup.spendDraft) {
            is SpendDraft.Member -> commitMember(draft)
            is SpendDraft.Rule -> commitRule(draft)
            null -> Unit
        }
    }

    /** One row per person, and a senior goes as the unlimited override row it is. */
    private fun commitMember(draft: SpendDraft.Member) {
        val team = vm.setupState.setup.spendSetup.edited.team
        if (draft.member.userId.isBlank()) {
            vm.fail(str(S.desktop_hub_pick_a_team_member))
            return
        }
        if (team.withIndex().any { (i, m) -> i != draft.index && m.userId == draft.member.userId }) {
            vm.fail(str(S.desktop_hub_already_on_the_team))
            return
        }
        // Blank is zero — submit only; the web's `parseFloat(...) || 0`.
        val typed = draft.limitText.trim().replace(",", "").toDoubleOrNull() ?: 0.0
        val member = draft.member.copy(postingLimit = if (draft.member.isUnlimited) null else typed).normalised()
        vm.update {
            val current = setup.spendSetup.edited
            val rows = current.team.replacedAt(draft.index, member)
            copy(setup = setup.copy(spendSetup = setup.spendSetup.edit(current.copy(team = rows)), spendDraft = null))
        }
    }

    private fun commitRule(draft: SpendDraft.Rule) {
        if (draft.rule.title.isBlank()) return
        val value = draft.valueText.trim().replace(",", "").toDoubleOrNull() ?: 0.0
        vm.update {
            val current = setup.spendSetup.edited
            val rows = current.deductionRules.replacedAt(draft.index, draft.rule.copy(thresholdValue = value))
            val next = current.copy(deductionRules = rows)
            copy(setup = setup.copy(spendSetup = setup.spendSetup.edit(next), spendDraft = null))
        }
    }

    /** [row] in place of the one at [index], or at the end for a new row. */
    private fun <T> List<T>.replacedAt(index: Int?, row: T): List<T> =
        if (index != null && index in indices) mapIndexed { i, r -> if (i == index) row else r } else this + row
}
