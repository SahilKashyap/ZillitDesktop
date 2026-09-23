package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.FraudRule

/**
 * Which automatic checks run, and at what thresholds.
 *
 * The two halves save separately, as on the web, and that is a correctness
 * property rather than a layout choice: one PUT carrying both would take
 * whatever the other section has unsaved along with it.
 */
internal class RulesActions(private val vm: BankRecViewModel) {

    private val state: RulesState get() = vm.ui.rules

    private fun edit(reducer: RulesState.() -> RulesState) =
        vm.update { copy(rules = rules.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.ToggleMatchRule -> edit {
                copy(settings = settings.copy(autoMatch = settings.autoMatch + (event.key to event.enabled)))
            }

            is BankRecEvent.ToggleFraudRule -> editRule(event.key) { it.copy(enabled = event.enabled) }
            is BankRecEvent.SetFraudThreshold -> editRule(event.key) { it.copy(amount = event.amount) }
            BankRecEvent.SaveMatchRules -> saveMatch()
            BankRecEvent.SaveFraudRules -> saveFraud()
            else -> return false
        }
        return true
    }

    /**
     * Reads the rules — silently once they are on screen.
     *
     * An unsaved edit is never overwritten by a background reload: showing
     * somebody a rule they did not just turn off is worse than a stale page.
     */
    fun load(force: Boolean) {
        if (state.matchDirty || state.fraudDirty) return
        if (state.loading || (!force && state.loaded)) return
        edit { copy(loading = !loaded) }
        vm.runResult(vm.repo::rulesSettings, { settings ->
            edit { copy(settings = settings, saved = settings, loading = false, loaded = true) }
        }, { error ->
            // The defaults stand rather than an empty page: every check is on
            // by default, and a rules page showing nothing reads as a
            // production with no fraud detection at all.
            edit { copy(loading = false, loaded = true) }
            vm.report(error)
        })
    }

    private fun editRule(key: String, change: (FraudRule) -> FraudRule) = edit {
        val current = settings.fraud[key] ?: FraudRule()
        copy(settings = settings.copy(fraud = settings.fraud + (key to change(current))))
    }

    private fun saveMatch() {
        val rules = state.settings.autoMatch
        if (state.savingMatch) return
        edit { copy(savingMatch = true) }
        vm.runResult({ vm.repo.saveAutoMatchRules(rules) }, {
            edit { copy(savingMatch = false, saved = saved.copy(autoMatch = rules)) }
            vm.notify(str(S.desktop_br_match_rules_saved))
        }, { error ->
            edit { copy(savingMatch = false) }
            vm.report(error)
        })
    }

    private fun saveFraud() {
        val rules = state.settings.fraud
        if (state.savingFraud) return
        edit { copy(savingFraud = true) }
        vm.runResult({ vm.repo.saveFraudRules(rules) }, {
            edit { copy(savingFraud = false, saved = saved.copy(fraud = rules)) }
            vm.notify(str(S.desktop_br_fraud_rules_saved))
        }, { error ->
            edit { copy(savingFraud = false) }
            vm.report(error)
        })
    }
}
