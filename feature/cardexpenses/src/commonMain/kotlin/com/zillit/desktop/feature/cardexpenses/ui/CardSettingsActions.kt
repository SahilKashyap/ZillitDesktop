package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection

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

    private fun settle(section: SettingsSection, stored: CardSettings, sent: CardSettings) {
        val kept = section.matches(stored, sent)
        vm.update {
            copy(
                busy = false,
                settings = stored,
                settingsDraft = stored,
                notice = if (kept) str(S.desktop_card_section_saved, section.label) else null,
            )
        }
        if (!kept) {
            vm.fail(
                str(S.desktop_card_section_not_stored, section.label),
            )
        }
    }

    /** What each section refuses to be saved without. */
    private fun SettingsSection.validate(draft: CardSettings): String? = when (this) {
        SettingsSection.Team ->
            str(S.desktop_card_team_row_needs_person)
                .takeIf { draft.teamMembers.any { member -> member.userId.isBlank() } }

        SettingsSection.Coordinators ->
            str(S.desktop_card_coordinator_row_incomplete)
                .takeIf { draft.coordinators.any { row -> !row.complete } }

        // A row with nothing in it is dropped, as the web drops it; one with
        // codes but no name cannot be picked from any card form.
        SettingsSection.Providers ->
            str(S.desktop_card_provider_needs_name)
                .takeIf { draft.providers.any { provider -> !provider.blank && provider.name.isBlank() } }

        SettingsSection.Overrides, SettingsSection.RequestCap -> null
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
            val kept = sent.providers.filter { it.name.isNotBlank() }
            stored.providers.map { it.name } == kept.map { it.name.trim() } &&
                stored.providers.map { it.custodianAccount } == kept.map { it.custodianAccount.trim() }
        }

        SettingsSection.RequestCap -> stored.requestCap == sent.requestCap
    }
}
