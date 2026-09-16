package com.zillit.desktop.feature.accounthub.ui

/**
 * Importing a union agreement's rules into the non-union breakdown — the
 * flow behind the web's `ImportAgreementRulesModal` and the editor's
 * `commitRules`.
 *
 * Import is a save, not an edit: the web's editor commit persists at once,
 * so nobody has to find the section's Save button after the import lands.
 * On failure the appended rules stay as an unsaved edit that Save can retry.
 */
internal class RuleImportActions(private val vm: AccountHubViewModel) {

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            AccountHubEvent.OpenRuleImport -> open()
            AccountHubEvent.DismissRuleImport -> vm.update { copy(setup = setup.copy(ruleImport = null)) }
            is AccountHubEvent.PickImportTerritory -> pickTerritory(event.territory)
            is AccountHubEvent.PickImportAgreement -> pickAgreement(event.identifier)
            AccountHubEvent.ConfirmRuleImport -> confirm()
            else -> return false
        }
        return true
    }

    private fun open() {
        if (!vm.mayEdit()) return
        vm.update { copy(setup = setup.copy(ruleImport = RuleImportState())) }
        // Coverage narrows the picker; a failure leaves the whole catalogue,
        // which is the safe direction — a blip must never empty the list.
        vm.runResult({ vm.repo.coveredTerritories() }, { covered ->
            vm.update { copy(setup = setup.copy(ruleImport = setup.ruleImport?.copy(covered = covered))) }
        }, { })
    }

    private fun pickTerritory(territory: String) {
        vm.update {
            copy(
                setup = setup.copy(
                    ruleImport = setup.ruleImport?.copy(
                        territory = territory,
                        agreements = emptyList(),
                        agreementsLoading = true,
                        agreementId = null,
                        rules = null,
                    ),
                ),
            )
        }
        vm.runResult({ vm.repo.unionAgreements(territory) }, { rows ->
            vm.update {
                // A slower answer for a territory no longer picked is dropped.
                val dialog = setup.ruleImport?.takeIf { it.territory == territory } ?: return@update this
                copy(setup = setup.copy(ruleImport = dialog.copy(agreements = rows, agreementsLoading = false)))
            }
        }, { error ->
            vm.update {
                copy(setup = setup.copy(ruleImport = setup.ruleImport?.copy(agreementsLoading = false)))
            }
            vm.report(error)
        })
    }

    private fun pickAgreement(identifier: String) {
        vm.update {
            copy(
                setup = setup.copy(
                    ruleImport = setup.ruleImport?.copy(agreementId = identifier, rules = null, rulesLoading = true),
                ),
            )
        }
        vm.runResult({ vm.repo.unionAgreementRules(identifier) }, { rules ->
            vm.update {
                val dialog = setup.ruleImport?.takeIf { it.agreementId == identifier } ?: return@update this
                copy(setup = setup.copy(ruleImport = dialog.copy(rules = rules, rulesLoading = false)))
            }
        }, { error ->
            vm.update { copy(setup = setup.copy(ruleImport = setup.ruleImport?.copy(rulesLoading = false))) }
            vm.report(error)
        })
    }

    private fun confirm() {
        val setup = vm.setupState.setup
        val dialog = setup.ruleImport ?: return
        val rules = dialog.rules?.takeIf { it.total > 0 } ?: return
        if (!vm.mayEdit()) return
        val next = rules.appendedTo(setup.nonUnionPay.edited)
        vm.update {
            copy(
                setup = this.setup.copy(
                    nonUnionPay = this.setup.nonUnionPay.edit(next).copy(saving = true),
                    ruleImport = dialog.copy(importing = true),
                ),
            )
        }
        vm.runResult({ vm.repo.saveNonUnionPay(next) }, { saved ->
            vm.update {
                copy(
                    setup = this.setup.copy(nonUnionPay = this.setup.nonUnionPay.committed(saved), ruleImport = null),
                    notice = "${rules.total} rule${if (rules.total == 1) "" else "s"} imported.",
                )
            }
        }, { error ->
            vm.update {
                copy(
                    setup = this.setup.copy(
                        nonUnionPay = this.setup.nonUnionPay.copy(saving = false),
                        ruleImport = this.setup.ruleImport?.copy(importing = false),
                    ),
                )
            }
            vm.report(error)
        })
    }
}
