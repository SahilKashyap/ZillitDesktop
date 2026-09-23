package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PoTermsFiles
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import kotlinx.coroutines.delay

/**
 * The Settings tab's behaviour — the web's `POSettings.jsx`.
 *
 * Each card saves on its own: description format, rental split, numbering,
 * the asset register rule and the rules list are five save paths with five
 * dirty flags and a tick that fades. The terms document is the one exception,
 * persisting the moment its upload lands, as the web's does. Its own
 * collaborator so the view model stays the orders'.
 */
internal class PoSettingsActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
    private val people: PoSettingsPeople?,
    private val termsFiles: PoTermsFiles?,
) {
    private val settings: PoSettingsState get() = vm.currentState.settings
    private val symbol: String get() = Money.symbol(PoSettings.DEFAULT_CURRENCY)
    private var nextLocalId = 1

    /** True when [event] was one of this tab's. */
    fun onEvent(event: PoEvent): Boolean {
        when (event) {
            is PoEvent.EditSettings -> edit { copy(edited = event.settings) }
            is PoEvent.SaveSettings -> save(event.section)
            PoEvent.PickTermsDocument -> pickTerms()
            PoEvent.OpenTermsDocument -> settings.edited.termsDocument?.let { vm.emit(PoEffect.OpenAttachment(it)) }
            PoEvent.AddRule -> addRule()
            is PoEvent.EditRule -> edit { copy(rules = rules.map { if (it.id == event.rule.id) event.rule else it }) }
            is PoEvent.RemoveRule -> removeRule(event.id)
            else -> return false
        }
        return true
    }

    /**
     * Reads the document, the rules and everything the pickers offer. [silent]
     * keeps the loader off for a live update — the web's `reloadSettings({silent})`.
     * Either way the server's copy replaces what is on screen, as the web's does.
     */
    fun load(silent: Boolean = false) {
        edit {
            copy(
                loading = if (silent) loading else true,
                rulesLoading = if (silent) rulesLoading else true,
                loadError = null,
                canAttachTerms = termsFiles != null,
            )
        }
        vm.launchWork {
            when (val bundle = repository.settings()) {
                is ZillitResult.Success -> edit {
                    copy(
                        loading = false,
                        rulesLoading = false,
                        saved = bundle.data.settings,
                        edited = bundle.data.settings,
                        savedRules = bundle.data.rules,
                        rules = bundle.data.rules,
                    )
                }
                is ZillitResult.Failure -> edit {
                    copy(loading = false, rulesLoading = false, loadError = if (silent) loadError else bundle.error)
                }
            }
        }
        people?.let { source ->
            vm.launchWork { source.team().let { team -> edit { copy(team = team) } } }
            vm.launchWork { source.departments().let { rows -> edit { copy(departments = rows) } } }
        }
        vm.launchWork { repository.nominalCodes().getOrNull()?.let { rows -> edit { copy(nominals = rows) } } }
        vm.launchWork { repository.assetTags().getOrNull()?.let { rows -> edit { copy(tags = rows) } } }
    }

    private fun save(section: PoSettingsSection) {
        if (section in settings.saving) return
        if (section == PoSettingsSection.Rules) {
            saveRules()
            return
        }
        val edited = settings.edited
        val refused = edited.assetFilters.error
        if (section == PoSettingsSection.Asset && refused != null) {
            vm.fail(refused)
            return
        }
        edit { copy(saving = saving + section) }
        vm.launchWork {
            val result = when (section) {
                PoSettingsSection.Description -> repository.saveDescriptionFormat(edited.descriptionFormat)
                PoSettingsSection.Rental -> repository.saveRentalSplit(edited.autoSplitRentals, edited.splitType)
                PoSettingsSection.Numbering ->
                    repository.saveNumbering(edited.numberPrefix, edited.allowAmendAfterApproval)
                else -> repository.saveAssetFilters(edited.assetFilters)
            }
            when (result) {
                is ZillitResult.Success -> {
                    // What was sent is what landed — except the asset rule,
                    // which takes the server's normalised echo, so the editor
                    // shows what was stored rather than what was typed.
                    val echo = result.data.assetFilters
                    val landed = if (section == PoSettingsSection.Asset && !echo.isEmpty) {
                        edited.copy(assetFilters = echo)
                    } else {
                        edited
                    }
                    edit {
                        copy(
                            saved = saved.taking(section, landed),
                            edited = this.edited.taking(section, landed),
                            saving = saving - section,
                        )
                    }
                    flash(section)
                }
                is ZillitResult.Failure -> {
                    edit { copy(saving = saving - section) }
                    vm.fail(result.error.localised())
                }
            }
        }
    }

    /** Every row goes — a stored one is patched, a new one created — in list order, as the web saves them. */
    private fun saveRules() {
        val rules = settings.rules
        if (rules.any { it.assignTo.isBlank() }) {
            vm.fail(str(S.desktop_inv_pick_who_each_rule_assigns_to))
            return
        }
        edit { copy(saving = saving + PoSettingsSection.Rules) }
        vm.launchWork {
            val landed = mutableListOf<PoAssignmentRule>()
            rules.forEachIndexed { index, rule ->
                when (val result = if (rule.persisted) repository.updateRule(rule) else repository.createRule(rule)) {
                    is ZillitResult.Success -> landed += result.data
                    is ZillitResult.Failure -> {
                        // The rows before this one are on the server now, so
                        // they keep the ids it gave them: a retry then patches
                        // them instead of creating a second copy of each. The
                        // web's loop drops them and duplicates on retry.
                        edit {
                            copy(
                                saving = saving - PoSettingsSection.Rules,
                                rules = landed + rules.drop(index),
                                savedRules = savedRules.merging(landed),
                            )
                        }
                        vm.fail(result.error.localised())
                        return@launchWork
                    }
                }
            }
            edit { copy(saving = saving - PoSettingsSection.Rules, rules = landed, savedRules = landed) }
            flash(PoSettingsSection.Rules)
        }
    }

    private fun addRule() {
        val rule = PoAssignmentRule(
            id = PoAssignmentRule.LOCAL_ID_PREFIX + nextLocalId++,
            assignTo = settings.team.firstOrNull()?.id.orEmpty(),
        )
        edit { copy(rules = rules + rule) }
    }

    /** A stored rule asks first — the web's "Remove Assignment Rule" modal; one never saved just goes. */
    private fun removeRule(id: String) {
        val rule = settings.rules.firstOrNull { it.id == id } ?: return
        if (!rule.persisted) {
            edit { copy(rules = rules - rule) }
            return
        }
        val assignee = settings.team.firstOrNull { it.id == rule.assignTo }?.label ?: str(S.desktop_unknown)
        vm.ask(
            PoPrompt.Confirm(
                action = PoConfirmAction.RemoveRule,
                targetId = id,
                title = str(S.desktop_po_remove_assignment_rule),
                message = str(S.desktop_po_remove_rule_question) + "\n\n" +
                    str(S.desktop_po_rule_assign_to, rule.summary(symbol), assignee) +
                    "\n\n" + str(S.action_cannot_be_undone),
            ),
        )
    }

    /** After the confirm: gone from the list at once, then from the server. */
    fun deleteRule(id: String) {
        edit { copy(rules = rules.filterNot { it.id == id }, savedRules = savedRules.filterNot { it.id == id }) }
        vm.launchWork {
            val result = repository.deleteRule(id)
            if (result is ZillitResult.Failure) vm.fail(result.error.localised())
        }
    }

    /**
     * One file, uploaded and saved straight away — the web's "Saves on upload".
     * The row shows the new file the moment the upload lands and rolls back if
     * the save then fails, so the screen never claims a document the server
     * never stored.
     */
    private fun pickTerms() {
        val files = termsFiles ?: return
        if (!vm.currentState.viewer.isSeniorAccountant) return
        vm.launchWork {
            edit { copy(termsError = null) }
            val picked = files.pick { refusal -> edit { copy(termsError = refusal) } } ?: return@launchWork
            edit { copy(termsUploading = true) }
            val document = when (val stored = files.upload(picked)) {
                is ZillitResult.Success -> stored.data
                is ZillitResult.Failure -> {
                    edit { copy(termsUploading = false, termsError = UPLOAD_FAILED) }
                    return@launchWork
                }
            }
            val previous = settings.saved.termsDocument
            edit { copy(edited = edited.copy(termsDocument = document), termsUploading = false) }
            when (val patched = repository.saveTermsDocument(document)) {
                is ZillitResult.Success -> edit { copy(saved = saved.copy(termsDocument = document)) }
                is ZillitResult.Failure -> {
                    edit { copy(edited = edited.copy(termsDocument = previous)) }
                    vm.fail(patched.error.localised())
                }
            }
        }
    }

    /** "Saved", for a moment — the web's 2.5 s tick. */
    private fun flash(section: PoSettingsSection) {
        edit { copy(justSaved = justSaved + section) }
        vm.launchWork {
            delay(SAVED_FLASH_MILLIS)
            edit { copy(justSaved = justSaved - section) }
        }
    }

    private fun edit(block: PoSettingsState.() -> PoSettingsState) = vm.update { copy(settings = settings.block()) }

    /** What the server holds after a partial save: the rows it took, over the rows it already had. */
    private fun List<PoAssignmentRule>.merging(landed: List<PoAssignmentRule>): List<PoAssignmentRule> {
        val byId = landed.associateBy { it.id }
        val patched = map { byId[it.id] ?: it }
        return patched + landed.filterNot { row -> any { it.id == row.id } }
    }

    companion object {
        const val SAVED_FLASH_MILLIS = 2_500L
        val UPLOAD_FAILED: String get() = str(S.desktop_hub_upload_failed_please_try_again)
    }
}
