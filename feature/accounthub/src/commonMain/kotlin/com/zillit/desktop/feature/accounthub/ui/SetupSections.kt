package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.DayType
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.domain.ScheduleRules
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.DealCondition

/**
 * Saving and reverting the Production Setup sections.
 *
 * Its own collaborator because there is one branch per section and there are
 * now nine of them — the view model was carrying a third of its length in
 * this one shape.
 *
 * Sections are independent: a save in one must not disturb unsaved edits in
 * another, which is why each has its own endpoint and its own
 * [SectionEdit] rather than one form over the lot.
 */
internal class SetupSections(private val vm: AccountHubViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action.
    fun save(section: SetupSection) {
        if (!vm.mayEdit()) return
        val setup = vm.setupState.setup
        // A section that never loaded holds the empty default; saving it would
        // write that over what the server has. See SliceLoads.
        if (section in SliceLoads.TRACKED && !setup.slices.isLoaded(section)) {
            return vm.fail(str(S.desktop_hub_setup_section_not_loaded))
        }
        // The web's own gates, applied before the call rather than after the
        // server's refusal — each names the row that is wrong.
        val refusal = when (section) {
            SetupSection.Currencies -> setup.currencies.edited.validationError()
            SetupSection.TaxTypes -> TaxType.problem(setup.taxTypes.edited)
            // A half-typed date reads as unset, so saving it would clear the
            // stored date somebody was in the middle of changing.
            SetupSection.Schedule -> str(S.desktop_hub_fix_the_schedule_dates_before_saving).takeIf {
                setup.schedule.edited.hasHalfTypedDate || ScheduleRules.hasErrors(setup.schedule.edited.toDomain())
            }
            else -> null
        }
        if (refusal != null) return vm.sendSideEffect(AccountHubEffect.Failed(refusal))
        when (section) {
            SetupSection.Companies -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(companies = this.setup.companies.copy(saving = true))) },
                call = { vm.repo.saveCompanies(Companies.forWire(setup.companies.edited, setup.banks)) },
                done = { rows -> copy(setup = this.setup.copy(companies = this.setup.companies.committed(rows))) },
                failed = { copy(setup = this.setup.copy(companies = this.setup.companies.copy(saving = false))) },
                notice = str(S.desktop_companies_saved),
            )
            SetupSection.Currencies -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(currencies = this.setup.currencies.copy(saving = true))) },
                call = { vm.repo.saveCurrencies(setup.currencies.edited) },
                done = { value -> copy(setup = this.setup.copy(currencies = this.setup.currencies.committed(value))) },
                failed = { copy(setup = this.setup.copy(currencies = this.setup.currencies.copy(saving = false))) },
                notice = str(S.desktop_currencies_saved),
            )
            SetupSection.TaxTypes -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.copy(saving = true))) },
                call = { vm.repo.saveTaxTypes(setup.taxTypes.edited) },
                done = { rows -> copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.committed(rows))) },
                failed = { copy(setup = this.setup.copy(taxTypes = this.setup.taxTypes.copy(saving = false))) },
                notice = str(S.desktop_tax_types_saved),
            )
            SetupSection.AssetTags -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(assetTags = this.setup.assetTags.copy(saving = true))) },
                call = { vm.repo.saveAssetTags(setup.assetTags.edited) },
                done = { tags -> copy(setup = this.setup.copy(assetTags = this.setup.assetTags.committed(tags))) },
                failed = { copy(setup = this.setup.copy(assetTags = this.setup.assetTags.copy(saving = false))) },
                notice = str(S.desktop_hub_account_tags_saved),
            )
            SetupSection.Budget -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(budget = this.setup.budget.copy(saving = true))) },
                call = { vm.repo.saveProjectBudget(setup.budget.edited.toDomain()) },
                done = { value ->
                    copy(setup = this.setup.copy(budget = this.setup.budget.committed(BudgetForm.from(value))))
                },
                failed = { copy(setup = this.setup.copy(budget = this.setup.budget.copy(saving = false))) },
                notice = str(S.desktop_budget_saved),
            )
            SetupSection.Schedule -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(schedule = this.setup.schedule.copy(saving = true))) },
                call = { vm.repo.saveProductionSchedule(setup.schedule.edited.toDomain()) },
                done = { value ->
                    copy(setup = this.setup.copy(schedule = this.setup.schedule.committed(ScheduleForm.from(value))))
                },
                failed = { copy(setup = this.setup.copy(schedule = this.setup.schedule.copy(saving = false))) },
                notice = str(S.desktop_schedule_saved),
            )
            SetupSection.PayrollDefaults -> vm.commitSection(
                marking = {
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.copy(saving = true)))
                },
                call = { vm.repo.savePayrollDefaults(setup.payrollDefaults.edited) },
                done = { value ->
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.committed(value)))
                },
                failed = {
                    copy(setup = this.setup.copy(payrollDefaults = this.setup.payrollDefaults.copy(saving = false)))
                },
                notice = str(S.desktop_payroll_defaults_saved),
            )
            SetupSection.DealConditions -> vm.commitSection(
                marking = {
                    copy(setup = this.setup.copy(dealConditions = this.setup.dealConditions.copy(saving = true)))
                },
                // The clauses are renumbered on the way out: the server stores
                // the order it is given, and a list edited by drag or delete
                // arrives with gaps that would persist as the real order.
                call = { vm.repo.saveDealConditions(setup.dealConditions.edited.renumbered()) },
                done = { rows ->
                    copy(setup = this.setup.copy(dealConditions = this.setup.dealConditions.committed(rows)))
                },
                failed = {
                    copy(setup = this.setup.copy(dealConditions = this.setup.dealConditions.copy(saving = false)))
                },
                notice = str(S.desktop_deal_conditions_saved),
            )
            SetupSection.PayrollBureaus -> vm.commitSection(
                marking = {
                    copy(setup = this.setup.copy(payrollBureaus = this.setup.payrollBureaus.copy(saving = true)))
                },
                call = { vm.repo.savePayrollBureaus(setup.payrollBureaus.edited) },
                done = { rows ->
                    copy(setup = this.setup.copy(payrollBureaus = this.setup.payrollBureaus.committed(rows)))
                },
                failed = {
                    copy(setup = this.setup.copy(payrollBureaus = this.setup.payrollBureaus.copy(saving = false)))
                },
                notice = str(S.desktop_payroll_bureaus_saved),
            )
            SetupSection.Allowances -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(allowances = this.setup.allowances.copy(saving = true))) },
                call = { vm.repo.saveAllowancesRentals(setup.allowances.edited) },
                done = { value -> copy(setup = this.setup.copy(allowances = this.setup.allowances.committed(value))) },
                failed = { copy(setup = this.setup.copy(allowances = this.setup.allowances.copy(saving = false))) },
                notice = str(S.desktop_hub_allowances_and_rentals_saved),
            )
            SetupSection.PayrollSettings -> vm.commitSection(
                marking = {
                    copy(setup = this.setup.copy(payrollSettings = this.setup.payrollSettings.copy(saving = true)))
                },
                call = { vm.repo.savePayrollSettings(setup.payrollSettings.edited) },
                done = { value ->
                    copy(setup = this.setup.copy(payrollSettings = this.setup.payrollSettings.committed(value)))
                },
                failed = {
                    copy(setup = this.setup.copy(payrollSettings = this.setup.payrollSettings.copy(saving = false)))
                },
                notice = str(S.desktop_payroll_settings_saved),
            )
            SetupSection.PoSetup -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(poSetup = this.setup.poSetup.copy(saving = true))) },
                call = { vm.repo.savePurchaseOrderSetup(setup.poSetup.edited) },
                done = { value -> copy(setup = this.setup.copy(poSetup = this.setup.poSetup.committed(value))) },
                failed = { copy(setup = this.setup.copy(poSetup = this.setup.poSetup.copy(saving = false))) },
                notice = str(S.desktop_hub_purchase_order_settings_saved),
            )
            SetupSection.InvoicesSetup -> vm.commitSection(
                marking = {
                    copy(setup = this.setup.copy(invoicesSetup = this.setup.invoicesSetup.copy(saving = true)))
                },
                call = { vm.repo.saveInvoicesSetup(setup.invoicesSetup.edited.withRenumberedLevels()) },
                done = { value ->
                    copy(setup = this.setup.copy(invoicesSetup = this.setup.invoicesSetup.committed(value)))
                },
                failed = {
                    copy(setup = this.setup.copy(invoicesSetup = this.setup.invoicesSetup.copy(saving = false)))
                },
                notice = str(S.desktop_invoice_settings_saved),
            )
            SetupSection.NonUnionPay -> vm.commitSection(
                marking = { copy(setup = this.setup.copy(nonUnionPay = this.setup.nonUnionPay.copy(saving = true))) },
                call = { vm.repo.saveNonUnionPay(setup.nonUnionPay.edited) },
                done = { value ->
                    copy(setup = this.setup.copy(nonUnionPay = this.setup.nonUnionPay.committed(value)))
                },
                failed = { copy(setup = this.setup.copy(nonUnionPay = this.setup.nonUnionPay.copy(saving = false))) },
                notice = str(S.desktop_pay_rules_saved),
            )
            // Its own endpoint, and its own save: editing a day type must not
            // re-save the pay rules it is rendered inside.
            SetupSection.DayTypes -> saveDayTypes(setup.dayTypes.edited)
        }
    }

    /**
     * The day-type catalogue, refused rather than sent when it cannot work.
     *
     * A duplicate code is the one that matters: the pay engine looks a day
     * type up by code, so a repeat means one of them is never found — and
     * nothing on the screen would say which.
     */
    private fun saveDayTypes(rows: List<DayType>) {
        DayTypes.problem(rows)?.let { return vm.sendSideEffect(AccountHubEffect.Failed(it)) }
        vm.commitSection(
            marking = { copy(setup = this.setup.copy(dayTypes = this.setup.dayTypes.copy(saving = true))) },
            call = { vm.repo.saveDayTypes(rows) },
            done = { saved ->
                copy(setup = this.setup.copy(dayTypes = this.setup.dayTypes.committed(DayTypes.seeded(saved))))
            },
            failed = { copy(setup = this.setup.copy(dayTypes = this.setup.dayTypes.copy(saving = false))) },
            notice = str(S.desktop_day_types_saved),
        )
    }

    /** Clause order is positional, so it is rewritten from the list itself. */
    private fun List<DealCondition>.renumbered(): List<DealCondition> =
        mapIndexed { index, condition -> condition.copy(order = index + 1) }

    @Suppress("CyclomaticComplexMethod") // One line per section; a map would hide which.
    fun revert(section: SetupSection) = vm.update {
        val next = when (section) {
            SetupSection.Companies -> setup.copy(companies = setup.companies.reverted())
            SetupSection.Currencies -> setup.copy(currencies = setup.currencies.reverted())
            SetupSection.TaxTypes -> setup.copy(taxTypes = setup.taxTypes.reverted(), taxCountries = emptySet())
            SetupSection.AssetTags -> setup.copy(assetTags = setup.assetTags.reverted())
            SetupSection.Budget -> setup.copy(budget = setup.budget.reverted())
            SetupSection.Schedule -> setup.copy(schedule = setup.schedule.reverted())
            SetupSection.PayrollDefaults -> setup.copy(payrollDefaults = setup.payrollDefaults.reverted())
            SetupSection.DealConditions -> setup.copy(dealConditions = setup.dealConditions.reverted())
            SetupSection.PayrollBureaus -> setup.copy(payrollBureaus = setup.payrollBureaus.reverted())
            SetupSection.Allowances -> setup.copy(allowances = setup.allowances.reverted())
            SetupSection.PayrollSettings -> setup.copy(payrollSettings = setup.payrollSettings.reverted())
            SetupSection.PoSetup -> setup.copy(poSetup = setup.poSetup.reverted())
            SetupSection.InvoicesSetup -> setup.copy(invoicesSetup = setup.invoicesSetup.reverted())
            SetupSection.NonUnionPay -> setup.copy(nonUnionPay = setup.nonUnionPay.reverted())
            SetupSection.DayTypes -> setup.copy(dayTypes = setup.dayTypes.reverted())
        }
        copy(setup = next)
    }
}
