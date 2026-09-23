package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceSetup
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import kotlinx.coroutines.delay
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Settings page's behaviour — the web's `SettingsPage.jsx`.
 *
 * Three of the four cards save on their own, each with its own dirty flag and
 * a tick that fades. The team table is the exception: an added, edited or
 * removed member persists straight away, which is why that card carries no
 * Save on the web either. Its own collaborator so the view model stays the
 * invoices'.
 */
internal class InvoiceSetupActions(private val vm: InvoicesViewModel) {

    private val setup: InvoiceSetupState get() = vm.currentState.setup
    private var nextLocalId = 1

    /** True when [event] was one of this page's. */
    @Suppress("CyclomaticComplexMethod") // One branch per control on the page.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InvoicesEvent.ToggleAlert -> edit { copy(edited = edited.toggle(event.alert)) }
            is InvoicesEvent.SaveSetupSection -> save(event.section)

            InvoicesEvent.AddTeamMember -> edit {
                copy(memberDraft = TeamMemberDraft.of(InvoiceTeamRow(userId = ""), isNew = true))
            }
            is InvoicesEvent.EditTeamMember -> edit {
                copy(memberDraft = TeamMemberDraft.of(event.row, isNew = false))
            }
            is InvoicesEvent.ChangeTeamMemberDraft -> edit { copy(memberDraft = event.draft) }
            InvoicesEvent.CommitTeamMember -> commitMember()
            InvoicesEvent.CancelTeamMember -> edit { copy(memberDraft = null) }
            is InvoicesEvent.RequestRemoveTeamMember -> edit { copy(removingMember = event.userId) }
            InvoicesEvent.ConfirmRemoveTeamMember -> removeMember()
            InvoicesEvent.CancelRemoveTeamMember -> edit { copy(removingMember = null) }

            is InvoicesEvent.AddRunAuthLevel -> edit { copy(edited = edited.insertingLevel(event.index)) }
            is InvoicesEvent.RemoveRunAuthLevel -> edit { copy(edited = edited.removingLevel(event.tier)) }
            is InvoicesEvent.OpenRunAuthPicker -> edit { copy(pickingForTier = event.tier, pickerSearch = "") }
            is InvoicesEvent.SearchRunAuthPicker -> edit { copy(pickerSearch = event.query) }
            is InvoicesEvent.PickRunAuthUser -> edit {
                val tier = pickingForTier ?: return@edit this
                copy(edited = edited.addingToLevel(tier, listOf(event.userId)))
            }
            InvoicesEvent.CloseRunAuthPicker -> edit { copy(pickingForTier = null) }
            is InvoicesEvent.RemoveRunAuthUser -> edit {
                copy(edited = edited.removingFromLevel(event.tier, event.userId))
            }

            InvoicesEvent.AddAssignmentRule -> addRule()
            is InvoicesEvent.EditAssignmentRule -> edit {
                copy(rules = rules.map { if (it.id == event.rule.id) event.rule else it })
            }
            is InvoicesEvent.RequestRemoveRule -> requestRemoveRule(event.id)
            InvoicesEvent.ConfirmRemoveRule -> confirmRemoveRule()
            InvoicesEvent.CancelRemoveRule -> edit { copy(removingRule = null) }
            else -> return false
        }
        return true
    }

    /**
     * Reads the document, the rules, and the chart lines the rules pick from.
     *
     * [silent] keeps the loader off for a live update; either way the
     * server's copy replaces what is on screen, as the web's reload does.
     */
    fun load(silent: Boolean = false) {
        edit {
            copy(
                loading = if (silent) loading else true,
                rulesLoading = if (silent) rulesLoading else true,
                loadError = null,
            )
        }
        vm.run {
            when (val bundle = vm.repo.setup()) {
                is ZillitResult.Success -> edit {
                    copy(
                        loading = false,
                        rulesLoading = false,
                        saved = bundle.data.setup,
                        edited = bundle.data.setup,
                        savedRules = bundle.data.rules,
                        rules = bundle.data.rules,
                    )
                }
                is ZillitResult.Failure -> edit {
                    copy(loading = false, rulesLoading = false, loadError = if (silent) loadError else bundle.error)
                }
            }
        }
        vm.run {
            (vm.repo.nominalCodes() as? ZillitResult.Success)?.let { r -> edit { copy(nominals = r.data) } }
        }
        // The pickers need the directory whether or not a list was loaded —
        // this page fetches no invoices, so nothing else fills it in.
        vm.fillDirectories()
        edit { copy(people = vm.people()) }
    }

    private fun save(section: InvoiceSetupSection) {
        if (section in setup.saving) return
        if (section == InvoiceSetupSection.Rules) {
            saveRules()
            return
        }
        val edited = setup.edited
        edit { copy(saving = saving + section) }
        vm.run {
            val result = when (section) {
                InvoiceSetupSection.Alerts -> vm.repo.saveAlerts(edited.alerts)
                else -> vm.repo.saveRunAuthorisation(edited.runAuthorisation)
            }
            when (result) {
                is ZillitResult.Success -> {
                    edit { copy(saved = saved.taking(section, edited), saving = saving - section) }
                    flash(section)
                }
                is ZillitResult.Failure -> {
                    edit { copy(saving = saving - section) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    // -- the team table ------------------------------------------------------

    /**
     * An added or edited member goes to the server at once, and the table
     * only changes when it lands — the web's `persistTeam`, which keeps the
     * sheet open on a refusal so the edit is not lost.
     */
    private fun commitMember() {
        val draft = setup.memberDraft ?: return
        if (!draft.isReady) return
        val row = draft.toRow()
        val next = if (draft.isNew) {
            setup.edited.teamMembers + row
        } else {
            setup.edited.teamMembers.map { if (it.userId == row.userId) row else it }
        }
        edit { copy(memberDraft = memberDraft?.copy(busy = true)) }
        persistTeam(next) { edit { copy(memberDraft = null) } }
    }

    private fun removeMember() {
        val userId = setup.removingMember ?: return
        persistTeam(setup.edited.teamMembers.filterNot { it.userId == userId }) {
            edit { copy(removingMember = null) }
        }
    }

    private fun persistTeam(next: List<InvoiceTeamRow>, onDone: () -> Unit) {
        edit { copy(saving = saving + InvoiceSetupSection.Team) }
        vm.run {
            when (val result = vm.repo.saveTeam(next)) {
                is ZillitResult.Success -> {
                    edit {
                        copy(
                            saved = saved.copy(teamMembers = next),
                            edited = edited.copy(teamMembers = next),
                            saving = saving - InvoiceSetupSection.Team,
                        )
                    }
                    onDone()
                    // The reader's own rights come from this document, so the
                    // buttons the page gates change with it.
                    vm.reloadViewerRights()
                }
                is ZillitResult.Failure -> {
                    edit {
                        copy(
                            saving = saving - InvoiceSetupSection.Team,
                            memberDraft = memberDraft?.copy(busy = false),
                        )
                    }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    // -- the rules -----------------------------------------------------------

    /** Every row goes — a stored one patched, a new one created — in list order, as the web saves them. */
    private fun saveRules() {
        val rules = setup.rules
        if (rules.any { it.assignTo.isBlank() }) {
            vm.fail(str(S.desktop_inv_pick_who_each_rule_assigns_to))
            return
        }
        edit { copy(saving = saving + InvoiceSetupSection.Rules) }
        vm.run {
            val landed = mutableListOf<InvoiceAssignmentRule>()
            rules.forEachIndexed { index, rule ->
                val result = if (rule.persisted) vm.repo.updateRule(rule) else vm.repo.createRule(rule)
                when (result) {
                    is ZillitResult.Success -> landed += result.data
                    is ZillitResult.Failure -> {
                        // The rows already on the server keep the ids it gave
                        // them, so a retry patches them instead of creating a
                        // second copy of each.
                        edit {
                            copy(
                                saving = saving - InvoiceSetupSection.Rules,
                                rules = landed + rules.drop(index),
                                savedRules = savedRules.merging(landed),
                            )
                        }
                        vm.fail(result.error.localised())
                        return@run
                    }
                }
            }
            edit { copy(saving = saving - InvoiceSetupSection.Rules, rules = landed, savedRules = landed) }
            flash(InvoiceSetupSection.Rules)
        }
    }

    private fun addRule() {
        val rule = InvoiceAssignmentRule(
            id = InvoiceAssignmentRule.LOCAL_ID_PREFIX + nextLocalId++,
            assignTo = vm.team().firstOrNull()?.id.orEmpty(),
        )
        edit { copy(rules = rules + rule) }
    }

    /** A stored rule asks first — the web's "Delete Assignment Rule" modal; one never saved just goes. */
    private fun requestRemoveRule(id: String) {
        val rule = setup.rules.firstOrNull { it.id == id } ?: return
        if (!rule.persisted) {
            edit { copy(rules = rules - rule) }
            return
        }
        edit { copy(removingRule = rule) }
    }

    /** After the confirm: gone from the list at once, then from the server. */
    private fun confirmRemoveRule() {
        val rule = setup.removingRule ?: return
        edit {
            copy(
                removingRule = null,
                rules = rules.filterNot { it.id == rule.id },
                savedRules = savedRules.filterNot { it.id == rule.id },
            )
        }
        vm.run {
            val result = vm.repo.deleteRule(rule.id)
            if (result is ZillitResult.Failure) vm.fail(result.error.localised())
        }
    }

    /** "Saved", for a moment — the web's 2.5 s tick. */
    private fun flash(section: InvoiceSetupSection) {
        edit { copy(justSaved = justSaved + section) }
        vm.run {
            delay(SAVED_FLASH_MILLIS)
            edit { copy(justSaved = justSaved - section) }
        }
    }

    private fun edit(block: InvoiceSetupState.() -> InvoiceSetupState) =
        vm.update { copy(setup = setup.block()) }

    /** What the server holds after a partial save: the rows it took, over the rows it already had. */
    private fun List<InvoiceAssignmentRule>.merging(
        landed: List<InvoiceAssignmentRule>,
    ): List<InvoiceAssignmentRule> {
        val byId = landed.associateBy { it.id }
        val patched = map { byId[it.id] ?: it }
        return patched + landed.filterNot { row -> any { it.id == row.id } }
    }

    companion object {
        const val SAVED_FLASH_MILLIS = 2_500L
    }
}

/** [from]'s values for [section], everything else as it is. */
internal fun InvoiceSetup.taking(section: InvoiceSetupSection, from: InvoiceSetup): InvoiceSetup = when (section) {
    InvoiceSetupSection.Team -> copy(teamMembers = from.teamMembers)
    InvoiceSetupSection.Alerts -> copy(alerts = from.alerts)
    InvoiceSetupSection.RunAuthorisation -> copy(runAuthorisation = from.runAuthorisation)
    InvoiceSetupSection.Rules -> this
}
