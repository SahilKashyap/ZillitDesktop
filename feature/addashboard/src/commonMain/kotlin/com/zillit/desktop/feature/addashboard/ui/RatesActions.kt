package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AgencyDraft
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.DayTypeOption
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PayRule
import com.zillit.desktop.feature.addashboard.domain.PayRuleGroup
import com.zillit.desktop.feature.addashboard.domain.PayRuleTemplates
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef
import com.zillit.desktop.feature.addashboard.domain.UnionPayBreakdown

/**
 * Rates & Config: one document, one Save.
 *
 * The non-union document and one document per union agreement all go in a
 * single `PATCH`, which is why the whole page is held here rather than per
 * section — a distribution-list edit needs that same Save.
 *
 * A remote change is applied only while nothing is unsaved ([RatesState.dirty]
 * gates the socket reload in the view model), so the last Save wins rather
 * than a concurrent edit silently replacing this one.
 */
@Suppress("TooManyFunctions") // One handler per control on the page.
internal class RatesActions(private val vm: AdViewModel) {

    fun load() {
        vm.update { copy(rates = rates.copy(loading = true)) }
        vm.run {
            when (val config = vm.repo.rateConfig()) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        rates = rates.copy(
                            loading = false,
                            config = config.data,
                            nonUnion = config.data.nonUnion,
                            dirty = false,
                        ),
                    )
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(rates = rates.copy(loading = false)) }
                    vm.failWith(config.error)
                }
            }
            // The nominal-code pickers read the active chart of accounts; a
            // failure leaves them free text rather than blocking the page.
            (vm.repo.chartOfAccounts() as? ZillitResult.Success)?.let { codes ->
                vm.update { copy(rates = rates.copy(nominalCodes = codes.data)) }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table; one line per control.
    fun on(event: RatesEvent) {
        when (event) {
            is RatesEvent.DayRate -> patch { copy(dayRates = dayRates + (event.categoryLabel to event.amount)) }
            is RatesEvent.AddDayType -> patch { copy(dayTypes = dayTypes + DayTypeOption(event.name)) }
            is RatesEvent.DayTypeEdit -> patchDayType(event)
            is RatesEvent.RemoveDayType -> patch {
                copy(dayTypes = dayTypes.filterIndexed { index, _ -> index != event.index })
            }

            is RatesEvent.AddRule -> addRule(event.group)
            is RatesEvent.EditRule -> vm.update { copy(rates = rates.copy(editingRule = event.id)) }
            is RatesEvent.PatchRule -> patch {
                withRules(
                    event.group,
                    rules(event.group).map { if (it.id == event.rule.id) event.rule else it },
                )
            }

            is RatesEvent.RemoveRule -> patch {
                withRules(event.group, rules(event.group).filterNot { it.id == event.id })
            }

            RatesEvent.AddAllowance -> patch { copy(allowances = allowances + newLine(allowances.size)) }
            is RatesEvent.AllowanceField -> patch {
                copy(allowances = allowances.patched(event.index, event.field, event.text))
            }

            is RatesEvent.RemoveAllowance -> patch {
                copy(allowances = allowances.filterIndexed { index, _ -> index != event.index })
            }

            RatesEvent.AddFee -> patch { copy(additionalFees = additionalFees + newLine(additionalFees.size)) }
            is RatesEvent.FeeField -> patch {
                copy(additionalFees = additionalFees.patched(event.index, event.field, event.text))
            }

            is RatesEvent.RemoveFee -> patch {
                copy(additionalFees = additionalFees.filterIndexed { index, _ -> index != event.index })
            }

            RatesEvent.OpenDistributionPicker ->
                vm.update { copy(rates = rates.copy(distributionPickerOpen = true)) }

            RatesEvent.CloseDistributionPicker ->
                vm.update { copy(rates = rates.copy(distributionPickerOpen = false)) }

            is RatesEvent.ToggleDistribution -> patch {
                val already = distribution.any { it.ref == event.entry.ref && it.email == event.entry.email }
                copy(
                    distribution = if (already) {
                        distribution.filterNot { it.ref == event.entry.ref && it.email == event.entry.email }
                    } else {
                        distribution + event.entry
                    },
                )
            }

            RatesEvent.Save -> save()
            is RatesEvent.StartVendorForm -> vm.posting {
                vm.update {
                    copy(
                        rates = rates.copy(
                            vendorForm = AgencyFormState(draft = event.agency?.asDraft() ?: AgencyDraft()),
                        ),
                    )
                }
            }

            RatesEvent.CancelVendorForm -> vm.update { copy(rates = rates.copy(vendorForm = null)) }
            is RatesEvent.VendorName -> patchVendor { copy(name = event.text) }
            is RatesEvent.VendorEmail -> patchVendor { copy(email = event.text) }
            is RatesEvent.VendorContact -> patchVendor { copy(contactPerson = event.text) }
            is RatesEvent.VendorPhone -> patchVendor { copy(phone = event.text) }
            is RatesEvent.VendorAddress -> patchVendor { copy(address = event.text) }
            is RatesEvent.VendorBank -> patchVendor { copy(bank = bank.with(event.field, event.text)) }
            RatesEvent.SaveVendor -> saveVendor()

            is RatesEvent.AskDeleteVendor -> vm.update {
                copy(rates = rates.copy(confirmDeleteVendor = event.agency))
            }

            RatesEvent.ConfirmDeleteVendor -> confirmDeleteVendor()
            RatesEvent.CancelDeleteVendor -> vm.update { copy(rates = rates.copy(confirmDeleteVendor = null)) }
            is RatesEvent.OpenUnionEditor -> openUnionEditor(event.agreementId)
            RatesEvent.CloseUnionEditor -> vm.update { copy(rates = rates.copy(unionEditor = null)) }
            is RatesEvent.PickTerritory -> pickTerritory(event.territory)
            is RatesEvent.PickAgreement -> pickAgreement(event.agreement)
            is RatesEvent.UnionDayRate -> patchUnion(event.agreementId) {
                copy(dayRates = dayRates + (event.categoryLabel to event.amount))
            }

            is RatesEvent.UnionAllowance -> patchUnion(event.agreementId) {
                copy(allowanceAmounts = allowanceAmounts + (event.rowId to event.amount))
            }

            is RatesEvent.UnionFee -> patchUnion(event.agreementId) {
                copy(feeAmounts = feeAmounts + (event.rowId to event.amount))
            }

            is RatesEvent.RefreshAgreement -> refreshAgreement(event.agreementId)
            is RatesEvent.AskDeleteUnion -> vm.update {
                copy(rates = rates.copy(confirmDeleteUnion = event.agreement))
            }

            RatesEvent.ConfirmDeleteUnion -> confirmDeleteUnion()
            RatesEvent.CancelDeleteUnion -> vm.update { copy(rates = rates.copy(confirmDeleteUnion = null)) }
        }
    }

    // -- the non-union document --------------------------------------------

    private fun addRule(group: PayRuleGroup) {
        val template = PayRuleTemplates.byId(group.defaultTemplate)
        val rule = PayRule(
            id = "r${vm.clock()}",
            templateId = template.id,
            formValue = template.defaultValue,
            dayKinds = template.defaultValue
                .takeIf { template.field == com.zillit.desktop.feature.addashboard.domain.PayRuleField.DayKinds }
                ?.let { wire ->
                    listOfNotNull(com.zillit.desktop.feature.addashboard.domain.DayKind.from(wire))
                }
                .orEmpty(),
            amount = if (group == PayRuleGroup.Penalties) "0" else "1.5",
            basis = when (group) {
                PayRuleGroup.Overtimes -> com.zillit.desktop.feature.addashboard.domain.RateBasis.Hour
                PayRuleGroup.Premiums -> com.zillit.desktop.feature.addashboard.domain.RateBasis.Day
                PayRuleGroup.Penalties -> com.zillit.desktop.feature.addashboard.domain.RateBasis.Event
            },
            rateKind = if (group == PayRuleGroup.Penalties) {
                com.zillit.desktop.feature.addashboard.domain.RateKind.Flat
            } else {
                com.zillit.desktop.feature.addashboard.domain.RateKind.Multiplier
            },
        )
        vm.update {
            copy(
                rates = rates.copy(
                    nonUnion = rates.nonUnion.withRules(group, rates.nonUnion.rules(group) + rule),
                    dirty = true,
                    editingRule = rule.id,
                ),
            )
        }
    }

    private fun patchDayType(event: RatesEvent.DayTypeEdit) = patch {
        copy(
            dayTypes = dayTypes.mapIndexed { index, option ->
                if (index != event.index) {
                    option
                } else {
                    when (event.field) {
                        DayTypeField.Name -> option.copy(dayType = event.text)
                        DayTypeField.WorkMinutes -> option.copy(workMinutes = event.text.toIntOrNull() ?: 0)
                        DayTypeField.MealMinutes -> option.copy(mealBreakMinutes = event.text.toIntOrNull() ?: 0)
                    }
                }
            },
        )
    }

    /** One Save writes the non-union document **and** every union document. */
    private fun save() = vm.posting {
        val state = vm.current.rates
        if (state.saving) return@posting
        vm.update { copy(rates = rates.copy(saving = true)) }
        vm.run {
            val result = vm.repo.saveRateConfig(state.nonUnion, state.union.values.toList())
            vm.update { copy(rates = rates.copy(saving = false)) }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update { copy(rates = rates.copy(dirty = false)) }
                    vm.notice(str(S.desktop_ad_rate_config_saved))
                    load()
                }

                is ZillitResult.Failure -> vm.report(result.error)
            }
        }
    }

    /**
     * Registers an agency.
     *
     * The only path that creates one, as on the web: the Agency tab lists,
     * edits and verifies but never creates, and this endpoint is the one the
     * server forces `type: agency` on. Its artistes' `agency_id` is what a
     * purchase order is later raised against.
     */
    private fun saveVendor() {
        val form = vm.current.rates.vendorForm ?: return
        if (!form.draft.ready) {
            vm.update {
                val said = str(S.desktop_ad_agency_name_required)
                copy(rates = rates.copy(vendorForm = rates.vendorForm?.copy(error = said)))
            }
            return
        }
        vm.posting {
            vm.update { copy(rates = rates.copy(vendorForm = rates.vendorForm?.copy(saving = true, error = null))) }
            vm.run {
                when (val result = vm.repo.createAgencyVendor(form.draft)) {
                    is ZillitResult.Success -> {
                        vm.update { copy(rates = rates.copy(vendorForm = null)) }
                        vm.notice(str(S.desktop_ad_agency_saved))
                        load()
                    }

                    is ZillitResult.Failure -> {
                        vm.update {
                            copy(rates = rates.copy(vendorForm = rates.vendorForm?.copy(saving = false)))
                        }
                        vm.report(result.error)
                    }
                }
            }
        }
    }

    private fun patchVendor(change: AgencyDraft.() -> AgencyDraft) = vm.update {
        copy(
            rates = rates.copy(
                vendorForm = rates.vendorForm?.let { it.copy(draft = it.draft.change(), error = null) },
            ),
        )
    }

    private fun confirmDeleteVendor() {
        val target = vm.current.rates.confirmDeleteVendor ?: return
        vm.posting {
            vm.run {
                val result = vm.repo.deleteAgencyVendor(target.id)
                vm.update { copy(rates = rates.copy(confirmDeleteVendor = null)) }
                when (result) {
                    is ZillitResult.Success -> {
                        vm.notice(str(S.desktop_ad_agency_removed))
                        load()
                    }

                    is ZillitResult.Failure -> vm.report(result.error)
                }
            }
        }
    }

    // -- the union documents -------------------------------------------------

    /**
     * Opens the union editor on one agreement.
     *
     * An agreement the project has **saved** renders from its own snapshot,
     * with no deal-memo call; one not saved yet reads the live agreement. Only
     * **Refresh** re-pulls a saved one.
     */
    private fun openUnionEditor(agreementId: String?) = vm.posting {
        vm.update { copy(rates = rates.copy(unionEditor = UnionEditorState(agreementId = agreementId))) }
        if (agreementId == null) return@posting
        vm.update { copy(rates = rates.copy(unionEditor = rates.unionEditor?.copy(loading = true))) }
        vm.run {
            when (val saved = vm.repo.savedUnion(agreementId)) {
                is ZillitResult.Success -> {
                    val breakdown = saved.data
                    if (breakdown != null) {
                        vm.update {
                            copy(
                                rates = rates.copy(
                                    union = rates.union + (agreementId to breakdown),
                                    unionEditor = rates.unionEditor?.copy(loading = false),
                                ),
                            )
                        }
                    } else {
                        loadLiveAgreement(agreementId)
                    }
                }

                is ZillitResult.Failure -> loadLiveAgreement(agreementId)
            }
        }
    }

    private suspend fun loadLiveAgreement(agreementId: String) {
        val live = vm.repo.catalogueAgreement(agreementId)
        val label = vm.current.rates.config.unionAgreements
            .firstOrNull { it.id == agreementId }?.label
            ?: agreementId
        vm.update {
            val document = (live as? ZillitResult.Success)?.data
            copy(
                rates = rates.copy(
                    union = rates.union + (
                        agreementId to (
                            rates.union[agreementId]
                                ?: UnionPayBreakdown(agreementId = agreementId, label = label)
                            ).copy(document = document, currency = document?.currency.orEmpty())
                        ),
                    unionEditor = rates.unionEditor?.copy(loading = false),
                ),
            )
        }
    }

    private fun pickTerritory(territory: String) {
        vm.update {
            copy(
                rates = rates.copy(
                    unionEditor = rates.unionEditor?.copy(territory = territory, loading = true),
                ),
            )
        }
        vm.run {
            when (val rows = vm.repo.agreementCatalogue(territory)) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        rates = rates.copy(
                            unionEditor = rates.unionEditor?.copy(catalogue = rows.data, loading = false),
                        ),
                    )
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(rates = rates.copy(unionEditor = rates.unionEditor?.copy(loading = false))) }
                    vm.report(rows.error)
                }
            }
        }
    }

    /** Picking an agreement adds it to the page, and marks it unsaved. */
    private fun pickAgreement(agreement: UnionAgreementRef) {
        vm.update {
            copy(
                rates = rates.copy(
                    union = rates.union + (
                        agreement.id to UnionPayBreakdown(
                            agreementId = agreement.id,
                            label = agreement.label,
                        )
                        ),
                    unionEditor = rates.unionEditor?.copy(agreementId = agreement.id),
                    dirty = true,
                ),
            )
        }
        vm.run { loadLiveAgreement(agreement.id) }
    }

    /**
     * Re-pulls the live agreement over a saved snapshot.
     *
     * Marks the page dirty on purpose: the next Save writes a fresh snapshot,
     * which is the only way an upstream agreement change reaches the pay
     * engine.
     */
    private fun refreshAgreement(agreementId: String) {
        vm.update { copy(rates = rates.copy(unionEditor = rates.unionEditor?.copy(refreshing = true))) }
        vm.run {
            when (val live = vm.repo.catalogueAgreement(agreementId)) {
                is ZillitResult.Success -> {
                    val document = live.data
                    if (document == null) {
                        vm.fail(str(S.desktop_ad_agreement_not_loaded))
                    } else {
                        vm.update {
                            copy(
                                rates = rates.copy(
                                    union = rates.union.mapValues { (id, breakdown) ->
                                        if (id == agreementId) breakdown.copy(document = document) else breakdown
                                    },
                                    dirty = true,
                                ),
                            )
                        }
                        vm.notice(str(S.desktop_ad_agreement_refreshed))
                    }
                }

                is ZillitResult.Failure -> vm.report(live.error)
            }
            vm.update { copy(rates = rates.copy(unionEditor = rates.unionEditor?.copy(refreshing = false))) }
        }
    }

    private fun confirmDeleteUnion() {
        val target = vm.current.rates.confirmDeleteUnion ?: return
        vm.posting {
            vm.run {
                val result = vm.repo.deleteUnion(target.id)
                vm.update { copy(rates = rates.copy(confirmDeleteUnion = null)) }
                when (result) {
                    is ZillitResult.Success -> {
                        vm.update { copy(rates = rates.copy(union = rates.union - target.id)) }
                        vm.notice(str(S.desktop_ad_union_deleted))
                        load()
                    }

                    is ZillitResult.Failure -> vm.report(result.error)
                }
            }
        }
    }

    private fun patchUnion(agreementId: String, change: UnionPayBreakdown.() -> UnionPayBreakdown) = vm.update {
        val existing = rates.union[agreementId] ?: UnionPayBreakdown(agreementId = agreementId)
        copy(rates = rates.copy(union = rates.union + (agreementId to existing.change()), dirty = true))
    }

    private fun patch(change: NonUnionRates.() -> NonUnionRates) = vm.update {
        copy(rates = rates.copy(nonUnion = rates.nonUnion.change(), dirty = true))
    }

    private fun newLine(index: Int) = RateLine(identifier = "line-${vm.clock()}-$index")

    private fun List<RateLine>.patched(index: Int, field: MoneyField, text: String): List<RateLine> =
        mapIndexed { position, line ->
            if (position != index) {
                line
            } else {
                when (field) {
                    MoneyField.Name -> line.copy(label = text)
                    MoneyField.Amount -> line.copy(rateAmount = text.toDoubleOrNull() ?: 0.0)
                    MoneyField.NominalCode -> line.copy(nominalCode = text)
                }
            }
        }
}

/**
 * The eight categories a day rate is set for, as the wire stores them.
 *
 * Keyed by the category's **label**, not its enum: `day_rates` is written over
 * the label list, which is the one place in this module where the label is the
 * key rather than the display.
 */
internal val RATE_CATEGORY_LABELS: List<String>
    get() = ArtisteCategory.entries.map { it.label }
