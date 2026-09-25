package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.RequestCapProblem
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.problem

/**
 * Saving the production's card configuration, one section at a time.
 *
 * Its own collaborator because it carries three things nothing else needs: what
 * each section refuses to be saved without, which key it writes, and — the one
 * that matters — whether the server actually kept what it was sent.
 */
internal class CardSettingsActions(private val vm: CardExpensesViewModel) {

    /**
     * Saves one section, and takes the whole document back from the answer.
     *
     * Judging the echo rather than the status is what catches a write the
     * server did not make: a card service that accepts a PATCH and stores
     * nothing answers `status: 1` with the old document, which is
     * indistinguishable from success until somebody reloads the page an hour
     * later and finds their work gone.
     */
    fun save(section: SettingsSection) {
        // A senior accountant's page; the handler says so too, not only the sidebar.
        if (!vm.current.viewer.canOpenSettings) {
            vm.fail(str(S.desktop_po_no_rights_on_project))
            return
        }
        val draft = vm.current.settingsDraft ?: return
        // The coordinators flag their rows inline, as the web does, rather
        // than raising one toast for the whole list (`SettingsPage.jsx:356-369`).
        if (section == SettingsSection.Coordinators) {
            val errors = coordinatorErrors(draft)
            vm.update { copy(insights = insights.copy(coordinatorErrors = errors)) }
            if (errors.isNotEmpty()) return
        }
        val invalid = section.validate(draft)
        if (invalid != null) {
            vm.fail(invalid)
            return
        }
        vm.run {
            vm.update { copy(busy = true) }
            when (val saved = vm.repo.updateSettings(section, draft)) {
                is ZillitResult.Success -> settle(section, stored = saved.data, sent = draft)
                is ZillitResult.Failure -> {
                    vm.update { copy(busy = false) }
                    vm.fail(saved.error.localised())
                }
            }
        }
    }

    /**
     * Takes the stored document back, but only this section into the draft:
     * the other sections' unsaved edits stay where they were. The assignment
     * rules are the hub's, written through its own routes, and a settings
     * PATCH echo is not trusted to carry them.
     */
    private fun settle(section: SettingsSection, stored: CardSettings, sent: CardSettings) {
        val kept = section.matches(stored, sent)
        vm.update {
            val merged = stored.copy(assignmentRules = settings?.assignmentRules ?: sent.assignmentRules)
            copy(
                busy = false,
                settings = merged,
                settingsDraft = (settingsDraft ?: merged).withSection(section, merged),
                notice = if (kept) str(S.desktop_card_section_saved, section.label) else null,
            )
        }
        if (!kept) {
            vm.fail(
                str(S.desktop_card_section_not_stored, section.label),
            )
        }
    }

    /**
     * What each section refuses to be saved without.
     *
     * Providers are not refused for a missing name: the web keeps such a row
     * (`sanitizeCardProviders` drops only empty ones) and marks the field red,
     * and so does the page. The request cap's two blocks are the web's
     * (`RequestCapSection.jsx:76-78`) and are shown inline beside the fields.
     */
    private fun SettingsSection.validate(draft: CardSettings): String? = when (this) {
        SettingsSection.Team ->
            str(S.desktop_card_team_row_needs_person)
                .takeIf { draft.teamMembers.any { member -> member.userId.isBlank() } }

        SettingsSection.RequestCap -> when (draft.requestCap.problem()) {
            RequestCapProblem.Multiplier -> str(S.desktop_pc_cap_needs_multiplier)
            RequestCapProblem.Amount -> str(S.desktop_ce_cap_needs_amount)
            null -> null
        }

        SettingsSection.Coordinators, SettingsSection.Overrides, SettingsSection.Providers -> null
    }

    /** The web's per-row keys: `<index>_dept` and `<index>_users`. */
    private fun coordinatorErrors(draft: CardSettings): Map<String, String> = buildMap {
        draft.coordinators.forEachIndexed { index, row ->
            if (row.departmentId.isBlank()) put("${index}_dept", str(S.desktop_select_a_department))
            if (row.userIds.isEmpty()) put("${index}_users", str(S.desktop_bs_select_at_least_one_user))
        }
    }

    private fun CardSettings.withSection(section: SettingsSection, from: CardSettings): CardSettings = when (section) {
        SettingsSection.Team -> copy(teamMembers = from.teamMembers)
        SettingsSection.Coordinators -> copy(coordinators = from.coordinators)
        SettingsSection.Overrides -> copy(overrides = from.overrides)
        SettingsSection.Providers -> copy(providers = from.providers)
        SettingsSection.RequestCap -> copy(requestCap = from.requestCap)
    }

    /**
     * Whether the section that was sent came back the way it went.
     *
     * Providers are compared by name rather than whole: the server mints its
     * own ids for rows the client invented, so an id that differs is the
     * server doing its job, and a name that differs is the write being lost.
     */
    private fun SettingsSection.matches(stored: CardSettings, sent: CardSettings): Boolean = when (this) {
        SettingsSection.Team -> stored.teamMembers == sent.teamMembers.map { it.normalised() }
        SettingsSection.Coordinators -> stored.coordinators == sent.coordinators
        SettingsSection.Overrides -> stored.overrides == sent.overrides
        SettingsSection.Providers -> {
            val kept = sent.providers.filterNot { it.blank }
            stored.providers.map { it.name } == kept.map { it.name.trim() } &&
                stored.providers.map { it.custodianAccount } == kept.map { it.custodianAccount.trim() }
        }

        SettingsSection.RequestCap -> stored.requestCap == sent.requestCap
    }
}
