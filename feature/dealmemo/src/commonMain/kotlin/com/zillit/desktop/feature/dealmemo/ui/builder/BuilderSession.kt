package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import com.zillit.desktop.feature.dealmemo.domain.SavedRecord
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.BureauRow
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealHydration
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.authoring.EffectiveRates
import com.zillit.desktop.feature.dealmemo.domain.authoring.PayloadParts
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateResolve
import com.zillit.desktop.feature.dealmemo.domain.authoring.RateTables
import com.zillit.desktop.feature.dealmemo.domain.authoring.RuleAuthoring
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.BuilderFileEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.SetupPageEvent
import com.zillit.desktop.feature.dealmemo.ui.preview.RulesEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * One visit to a builder page: its state, its seeds, its autosave and its
 * saves. The web runs its seeds as effects on dependency changes; here
 * [reconcile] runs them after anything they read changes, each latched the
 * way its effect is.
 */
@Suppress("TooManyFunctions") // The page's effects, one function each.
internal class BuilderSession(
    private val vm: DealMemoViewModel,
    val actions: BuilderActions,
    val id: Long,
    val mode: BuilderMode,
) : AutosaveHost {

    /** Minted once per visit — a deal's and a setup's never cross. */
    val clientDealId: String = PayloadParts.newClientId()
    val clientTemplateId: String = PayloadParts.newClientId()

    val autosave = BuilderAutosave(this)
    val saves = BuilderSaves(vm, this)
    private val files = BuilderFiles(vm, this)
    private val setup = BuilderSetup(vm, this)

    /** Custom documents picked and not yet uploaded — a save uploads them — by row id. */
    val pendingFiles = mutableMapOf<String, PickedDealFile>()

    /** An explicit save or issue happened, so Leave Without Saving must not delete the record. */
    var userPersisted = false

    /** Leave Without Saving was chosen — autosave never writes again. */
    var discarded = false

    /** The last state seen, for work still finishing after the page was left. */
    private var frozen: BuilderState? = null
    private val loads = mutableListOf<Job>()

    private var reconciling = false
    private var again = false
    private var lastForm: DealForm? = null
    private var lastTick = 0
    private var lastEnabled = false
    private var scrollNonce = 0L

    private var hydrated = false
    private var presetsMerged = false
    private var conditionsSeeded = false
    private var currencySeeded = false
    private var scheduleSeeded = false
    private var bureausSeeded = false
    private var dealDefaultsSeeded = false
    private var soleSetupApplied = false
    private var soleEntityChecked = false
    private var engagementFor: JsonObject? = null
    private var engagementChecked = false
    private var entityBaseline: String? = null

    /** What Step5Rates' effects last ran on; a null visibility means the step has not mounted yet. */
    private var ratesVisible: Boolean? = null
    private var ratesChosen: JsonObject? = null
    private var ratesDeps: List<String?>? = null
    private var dgaFeeDeps: String? = null

    fun start() {
        val initial = if (mode.lockedGroup == SetupGroup.NonUnion && mode.templateId == null) {
            DealForm.INITIAL.with("union", SetupGroup.NonUnion.wire)
        } else {
            DealForm.INITIAL
        }
        vm.update {
            copy(
                builder = BuilderState(
                    visit = id,
                    mode = mode,
                    form = initial,
                    dealId = mode.dealId,
                    pickedTemplateId = mode.useTemplateId,
                    autosavedTemplateId = mode.templateId,
                    editLoading = mode.dealId != null || mode.templateId != null,
                    savedRules = DealHydration.savedRules(DealForm.INITIAL),
                ),
            )
        }
        vm.ensureProjectSettings()
        vm.ensureProduction()
        vm.ensureCoa()
        vm.ensureCrewDirectory()
        if (mode.deal) {
            vm.ensureAgencies()
            loadTakenCrew()
        } else {
            actions.references.loadCoveredTerritories()
        }
        when {
            mode.dealId != null -> loadDeal(mode.dealId)
            mode.templateId != null -> loadTemplate(mode.templateId)
            mode.useTemplateId != null -> useTemplate(mode.useTemplateId)
            mode.dealGroup == null -> hydrated = true
        }
        reconcile()
    }

    /** Leaving: loads stop; [flush] sends the page's last autosave unless it was discarded. */
    fun stop(flush: Boolean) {
        frozen = vm.ui.builder?.takeIf { it.visit == id }
        loads.forEach { it.cancel() }
        loads.clear()
        files.reset()
        setup.reset()
        if (flush) autosave.flushOnExit() else autosave.disarm()
    }

    // -- state ---------------------------------------------------------------------------------

    fun state(): BuilderState? = vm.ui.builder?.takeIf { it.visit == id } ?: frozen

    val live: Boolean get() = vm.ui.builder?.visit == id

    fun update(reducer: BuilderState.() -> BuilderState) =
        vm.update { builder?.takeIf { it.visit == id }?.let { copy(builder = it.reducer()) } ?: this }

    /** A user edit — `set` / `setForm`: the page is dirty and autosave arms. */
    fun edit(transform: (DealForm) -> DealForm) {
        if (!live) return
        update { copy(form = transform(form), dirty = true, dirtyTick = dirtyTick + 1) }
        autosave.arm()
        reconcile()
    }

    /** A write the page makes itself — hydration, seeds, `setAuto`: never dirty. */
    fun raw(transform: (DealForm) -> DealForm) {
        if (!live) return
        update { copy(form = transform(form)) }
        reconcile()
    }

    fun requestScroll(sectionId: Int) {
        scrollNonce += 1
        update { copy(scroll = ScrollRequest(sectionId, scrollNonce)) }
    }

    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: BuilderEvent) {
        if (!live) return
        when (event) {
            is BuilderEvent.SetField -> edit { it.with(event.key, event.value) }
            is BuilderEvent.Patch -> edit { it.with(event.values) }
            is BuilderEvent.Edit -> edit(event.transform)
            is BuilderEvent.Auto -> raw { it.with(event.values) }
            is BuilderEvent.AutoEdit -> raw(event.transform)
            BuilderEvent.ResetRules -> resetRules()
            is BuilderEvent.ConfirmResetRules -> update { copy(confirmResetRules = event.open) }
            BuilderEvent.RefetchRate -> actions.references.refetchRate()
            is BuilderEvent.DayRate -> state()?.let(::rates)?.let { (_, effective) ->
                edit { RateResolve.withDayRate(it, event.value, effective.daysPerWeek) }
            }
            is BuilderEvent.WeeklyRate -> state()?.let(::rates)?.let { (_, effective) ->
                edit { RateResolve.withWeeklyRate(it, event.value, effective.daysPerWeek) }
            }
            BuilderEvent.ResetToScale -> state()?.let(::rates)?.let { (_, effective) ->
                edit { RateResolve.resetToScale(it, effective) }
                actions.references.refetchRate()
            }
            BuilderEvent.OpenRules -> openRules()
            is BuilderEvent.ToggleEdit -> toggleEdit(event.sectionId)
            is BuilderEvent.ToggleCollapse -> update {
                copy(
                    expanded = if (event.sectionId in expanded) {
                        expanded - event.sectionId
                    } else {
                        expanded + event.sectionId
                    },
                )
            }
            BuilderEvent.ScrollDone -> update { copy(scroll = null) }
            is BuilderEvent.PickSetup -> pickSetup(event.templateId)
            BuilderEvent.ResetToSetup -> state()?.pickedTemplateId?.let(::pickSetup)
            BuilderEvent.AddBureau -> editBureaus { it + BureauRow("bureau-${vm.clock()}") }
            is BuilderEvent.EditBureau -> editBureaus { rows ->
                rows.map { row ->
                    if (row.id == event.id) {
                        row.copy(title = event.title ?: row.title, description = event.description ?: row.description)
                    } else {
                        row
                    }
                }
            }
            is BuilderEvent.RemoveBureau -> editBureaus { rows -> rows.filterNot { it.id == event.id } }
            is BuilderFileEvent -> files.onEvent(event)
            is SetupPageEvent -> setup.onEvent(event)
            else -> saves.onEvent(event)
        }
    }

    // -- loads ---------------------------------------------------------------------------------

    private fun track(job: Job) {
        loads += job
    }

    /** `applyPayloadToForm`: the saved payload over the initial form, rates stamped as authored, rules banked. */
    private fun apply(payload: JsonObject) {
        val form = DealHydration.form(payload, vm.ui.catalogue)
        update {
            copy(
                form = form,
                savedRules = DealHydration.savedRules(form),
                dealDefaultCustomDays = (payload["default_custom_days"] as? JsonArray)?.toList()
                    ?: dealDefaultCustomDays,
            )
        }
    }

    /** The saved deal, once the departments master has answered; its bank from the linked account when empty. */
    private fun loadDeal(dealId: String) = track(
        vm.work {
            vm.awaitDirectory()
            when (val result = vm.repository.deal(dealId)) {
                is ZillitResult.Success -> {
                    val deal = result.data
                    apply(deal.json)
                    update { copy(dealReference = deal.reference ?: dealReference, dealStatus = deal.rawStatus) }
                    state()?.form?.let(::fillBankFromAccount)
                }
                is ZillitResult.Failure -> Unit
            }
            update { copy(editLoading = false) }
            hydrated = true
            reconcile()
        },
    )

    private fun fillBankFromAccount(form: DealForm) {
        val accountId = form.text("bankAccId")
        if (accountId.isEmpty() || !PayloadParts.isBankEmpty(form.obj("bank"))) return
        track(
            vm.work {
                vm.repository.bankAccount(accountId).getOrNull()?.let { row ->
                    raw { it.with("bank", PayloadParts.bankFromWire(row)) }
                }
            },
        )
    }

    /** A setup opened for editing hydrates raw — a strip here would delete its fields on the next autosave. */
    private fun loadTemplate(templateId: String) = track(
        vm.work {
            vm.awaitDirectory()
            when (val result = vm.repository.template(templateId)) {
                is ZillitResult.Success -> {
                    result.data.form?.let(::apply)
                    if (result.data.name.isNotEmpty()) update { copy(templateName = result.data.name) }
                }
                is ZillitResult.Failure -> vm.toastError(result.error, "template_load_failed")
            }
            update { copy(editLoading = false) }
            hydrated = true
            reconcile()
        },
    )

    /** A setup's "Create Deal Memo": its payload without anyone's person or bank. */
    private fun useTemplate(templateId: String) = track(
        vm.work {
            vm.awaitTemplates()
            vm.ui.templates.rows?.firstOrNull { it.id == templateId }?.form
                ?.takeIf { it.isNotEmpty() }
                ?.let { apply(DealHydration.stripForUse(it)) }
            hydrated = true
            reconcile()
        },
    )

    /** Crew who already hold a deal that isn't completed, cancelled or deactivated — this deal's own excepted. */
    private fun loadTakenCrew() = track(
        vm.work {
            val taken = when (val result = vm.repository.deals()) {
                is ZillitResult.Success -> result.data
                    .filter { it.id != mode.dealId && it.rawStatus?.lowercase() !in RELEASED_STATUSES }
                    .mapNotNull { it.userId }
                    .toSet()
                is ZillitResult.Failure -> emptySet()
            }
            update { copy(takenUserIds = taken) }
        },
    )

    /** The Create menu group's setups; null while they load. */
    fun groupSetups(): List<DealTemplate>? {
        val group = mode.dealGroup ?: return null
        val templates = vm.ui.templates
        val rows = templates.rows ?: return if (templates.failed) emptyList() else null
        return rows.filter { it.nonUnion == (group == SetupGroup.NonUnion) }
    }

    /** `applyPickedSetup`: the deal reloads from the picked setup; clearing only forgets the pick. */
    private fun pickSetup(templateId: String?) {
        update { copy(pickedTemplateId = templateId) }
        if (templateId == null) return
        val form = groupSetups()?.firstOrNull { it.id == templateId }?.form
        if (form == null || form.isEmpty()) {
            vm.toast("This setup has nothing saved to use.", DealToastTone.Error)
            return
        }
        apply(DealHydration.stripForUse(form))
        reconcile()
    }

    private fun resetRules() {
        val snapshot = state()?.savedRules ?: return
        update { copy(confirmResetRules = false) }
        edit { it.with(snapshot) }
    }

    // -- rates -----------------------------------------------------------------------------------

    /** The schedule the rates follow, and the tiers it and the agreement publish. */
    private fun rates(state: BuilderState): Pair<JsonObject?, EffectiveRates> {
        val chosen = RateResolve.chosenRate(state.reference.resolvedRates, state.form)
        return chosen to RateResolve.effective(chosen, state.reference.selectedUnionForSteps)
    }

    /** The grid opens on the deal's rules as they stand; an empty book opens on one blank row. */
    private fun openRules() {
        val state = state() ?: return
        val lists = RuleAuthoring.editorLists(
            state.form,
            state.reference.selectedUnionForSteps,
            vm.ui.projectSettings.view.nonUnionPaybreakdown,
        )
        val rows = BulkRules.rows(lists, vm.newId).ifEmpty { listOf(BulkRules.blank(vm.newId())) }
        vm.ensureCoa()
        update { copy(rules = RulesEditorState(rows = rows), rulesTarget = RulesTarget.Deal) }
    }

    /** The grid's edits stay in the grid until Save folds them into the deal — a user edit — and closes it. */
    @Suppress("CyclomaticComplexMethod")
    fun onRules(event: RulesEvent) {
        if (!live) return
        val editor = state()?.rules ?: return
        fun rows(transform: (List<BulkRuleRow>) -> List<BulkRuleRow>) = update {
            copy(rules = rules?.copy(rows = transform(rules.rows)))
        }
        when (event) {
            is RulesEvent.Patch -> rows { list -> list.map { if (it.uid == event.uid) event.row else it } }
            is RulesEvent.Remove -> rows { list -> list.filterNot { it.uid == event.uid } }
            is RulesEvent.Add -> rows { list -> list + List(event.count) { BulkRules.blank(vm.newId()) } }
            RulesEvent.Save -> {
                update { copy(rules = rules?.copy(tried = true)) }
                if (!editor.allReady || editor.saving) return
                val committed = BulkRules.lists(editor.rows)
                if (state()?.rulesTarget == RulesTarget.Project) {
                    setup.commitProjectRules(committed)
                    return
                }
                val agreement = state()?.reference?.selectedUnionForSteps
                val paybreakdown = vm.ui.projectSettings.view.nonUnionPaybreakdown
                update { copy(rules = null) }
                edit { RuleAuthoring.committed(it, agreement, paybreakdown, committed) }
            }
            RulesEvent.Close -> if (!editor.saving) update { copy(rules = null, rulesTarget = RulesTarget.Deal) }
            RulesEvent.ImportAgreement -> if (state()?.rulesTarget == RulesTarget.Project) setup.openRuleImport()
            RulesEvent.Import -> Unit
        }
    }

    /**
     * Step5Rates' effects. The web keeps that step mounted on a deal page —
     * hidden with the raw setter while its editor is closed, visible with the
     * dirty one while open — so they run either way; opening the editor is a
     * mount, and a mount asks the rate card again.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun ratesEffects() {
        val state = state() ?: return
        val form = state.form
        val agreement = state.reference.selectedUnionForSteps
        val visible = state.editingSection == DealValidators.RATES
        val (chosen, effective) = rates(state)
        val mounted = ratesVisible != visible
        ratesVisible = visible
        val resolvable = form.flag("union") && form.flag("designation") && !RateResolve.missingBand(form, agreement)
        if (mounted && visible && resolvable) actions.references.refetchRate()
        val basic = agreement?.get("basic_rate_details") as? JsonObject
        val rateDeps = listOf("daily", "weekly", "hourly").map { tier ->
            (basic?.get(tier) as? JsonObject)?.let { "${it["base_rate"]}/${it["work_hrs"]}" }
        } + DealHydration.rateContextKey(form)
        if (mounted || chosen !== ratesChosen || rateDeps != ratesDeps) {
            ratesChosen = chosen
            ratesDeps = rateDeps
            if (RateResolve.autoApplied(form, effective, chosen) != null) {
                val apply = { current: DealForm -> RateResolve.autoApplied(current, effective, chosen) ?: current }
                if (visible) edit(apply) else raw(apply)
            }
        }
        val feeDeps = "${form.text("union")}|${form.text("jobTitle")}"
        if (mounted || feeDeps != dgaFeeDeps) {
            dgaFeeDeps = feeDeps
            RateTables.dgaSeedFee(form, agreement)?.takeIf { it != form.text("dgaWeeklyFee") }?.let { fee ->
                if (visible) edit { it.with("dgaWeeklyFee", fee) } else raw { it.with("dgaWeeklyFee", fee) }
            }
        }
        val paybreakdown = vm.ui.projectSettings.view.nonUnionPaybreakdown
        state()?.form?.let { current ->
            RuleAuthoring.materialised(current, agreement, paybreakdown)?.let { customised ->
                raw { it.with("rulesCustomized", customised) }
            }
        }
        state()?.form?.let { current ->
            RuleAuthoring.pruned(current, agreement, paybreakdown)?.let { kept ->
                raw { it.with("ruleRowEdits", kept) }
            }
        }
    }

    /** One editor at a time; closing a flagged section re-checks it. */
    private fun toggleEdit(sectionId: Int) {
        val state = state() ?: return
        val editing = state.editingSection == sectionId
        if (editing && state.issueErrors.containsKey(sectionId)) {
            val fields = sectionFields(sectionId, state)
            update {
                copy(issueErrors = if (fields == null) issueErrors - sectionId else issueErrors + (sectionId to fields))
            }
        }
        update { copy(editingSection = if (editing) null else sectionId) }
    }

    /** The section's missing fields, or null when it passes. */
    fun sectionFields(sectionId: Int, state: BuilderState): List<String>? =
        DealValidators.section(sectionId, state.form, state.reference.selectedUnion)?.fields

    /** Bureau rows live beside the form; the first titled one is the setup's `bureau`. */
    private fun editBureaus(transform: (List<BureauRow>) -> List<BureauRow>) {
        update { copy(setupBureaus = transform(setupBureaus)) }
        val first = state()?.setupBureaus?.firstOrNull { it.title.isNotBlank() }?.title?.trim().orEmpty()
        edit { it.with("bureau", first) }
    }

    // -- effects -------------------------------------------------------------------------------

    /** Runs the page's effects until the form stops changing under them. */
    fun reconcile() {
        if (reconciling) {
            again = true
            return
        }
        reconciling = true
        try {
            repeat(MAX_PASSES) {
                again = false
                val state = state() ?: return
                if (live) actions.references.sync(state)
                runSeeds()
                trackBaseline()
                if (!again) return
            }
        } finally {
            reconciling = false
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun runSeeds() {
        if (!live) return
        val settingsState = vm.ui.projectSettings
        val settings = settingsState.view
        applySoleSetup()
        val state = state() ?: return
        if (settingsState.loaded && hydrated && !state.editLoading) {
            if (!presetsMerged) mergePresets(settings)
            if (mode.dealId == null) seedCreateDefaults(settings)
            if (mode.setup && !bureausSeeded) seedBureaus(settings)
            if (mode.deal && !dealDefaultsSeeded) seedDealDefaults(settings)
            lockContractCurrency(settings)
            if (mode.dealId == null) convertPresetAmounts(settings)
            if (mode.setup) territoryEffects(settings)
        }
        if (mode.deal && settingsState.loaded) seedEngagementDates(settings)
        if (mode.deal && hydrated && state()?.editLoading == false) ratesEffects()
        if (mode.setup) setup.reconcile()
        if (mode.deal && state()?.form?.text("dealMemoDate").isNullOrEmpty()) raw { it.with("dealMemoDate", today()) }
        revalidate()
    }

    /** A Create menu group with exactly one setup starts from it — once. */
    private fun applySoleSetup() {
        if (hydrated || mode.dealGroup == null) return
        val group = groupSetups() ?: return
        hydrated = true
        val state = state() ?: return
        if (!soleSetupApplied && state.pickedTemplateId == null && group.size == 1) {
            soleSetupApplied = true
            pickSetup(group.single().id)
        }
    }

    /** Production Setup's allowances and rentals in its order, the form's own rows after — once. */
    private fun mergePresets(settings: ProjectSettingsView) {
        presetsMerged = true
        raw { form ->
            form.with(
                mapOf(
                    "allowances" to BuilderSeeds.mergeOverPresets(
                        settings.allowances.map { BuilderSeeds.psRow(it, rental = false) { generatedId("allow") } },
                        form.list("allowances"),
                    ),
                    "rentals" to BuilderSeeds.mergeOverPresets(
                        settings.rentals.map { BuilderSeeds.psRow(it, rental = true) { generatedId("rental") } },
                        form.list("rentals"),
                    ),
                ),
            )
        }
    }

    /** Standard conditions, the payment currency and the schedule — for anything but a saved deal. */
    private fun seedCreateDefaults(settings: ProjectSettingsView) {
        if (!conditionsSeeded && BuilderSeeds.conditionTexts(settings.standardConditions).isNotEmpty()) {
            conditionsSeeded = true
            raw { BuilderSeeds.withStandardConditions(it, settings.standardConditions) ?: it }
        }
        if (!currencySeeded) {
            settings.defaultCurrency?.let { currency ->
                currencySeeded = true
                raw { BuilderSeeds.withPaymentCurrency(it, currency) }
            }
        }
        if (!scheduleSeeded) {
            settings.productionSchedule?.let { schedule ->
                scheduleSeeded = true
                raw { BuilderSeeds.withProjectSchedule(it, schedule) }
            }
        }
    }

    /** The project's bureaus replace the single blank row, unless the user already typed in it. */
    private fun seedBureaus(settings: ProjectSettingsView) {
        bureausSeeded = true
        val rows = BuilderSeeds.bureauRows(settings)
        if (rows.isEmpty()) return
        update {
            val pristine =
                setupBureaus.size == 1 && setupBureaus[0].title.isBlank() && setupBureaus[0].description.isBlank()
            if (pristine) copy(setupBureaus = rows) else this
        }
    }

    /** A deal's production type from the project, and its entity when the production has only one. */
    private fun seedDealDefaults(settings: ProjectSettingsView) {
        val subType = vm.productionData.project().productionType
        val companies = settings.companies
        val sole = if (companies.size == 1) companies[0]["id"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty() else ""
        if (subType.isEmpty() && sole.isEmpty()) return
        dealDefaultsSeeded = true
        raw { form ->
            var next = form
            if (subType.isNotEmpty() && form.text("productionType") != subType) {
                next = next.with("productionType", subType)
            }
            if (sole.isNotEmpty() && form.text("productionEntity").isEmpty()) next = next.with("productionEntity", sole)
            next
        }
    }

    /** The contract currency is the rule's, on every page: the project's for non-union, the territory's for union. */
    private fun lockContractCurrency(settings: ProjectSettingsView) {
        val form = state()?.form ?: return
        val locked = BuilderSeeds.contractCurrency(form.text("union"), form.text("territory"), settings.defaultCurrency)
        if (locked.isNotEmpty() && form.text("currency") != locked) raw { it.with("currency", locked) }
    }

    /** Seeded amounts follow the contract currency; the banner says what happened. */
    private fun convertPresetAmounts(settings: ProjectSettingsView) {
        val form = state()?.form ?: return
        val from = settings.defaultCurrency.orEmpty()
        val to = BuilderSeeds.contractCurrency(form.text("union"), form.text("territory"), settings.defaultCurrency)
        val rate = if (from.isNotEmpty() && to.isNotEmpty() && from != to) settings.exchangeRate(to) else null
        val (next, info) = BuilderSeeds.convertSeededRows(form, from, to, rate)
        if (next != form) raw { BuilderSeeds.convertSeededRows(it, from, to, rate).first }
        if (state()?.conversion != info) update { copy(conversion = info) }
    }

    /**
     * Step 1 on a setup page: the project's production type; with one company,
     * that company — and its territory; and whenever the entity changes, the
     * employment status cleared and the territory re-resolved.
     */
    private fun territoryEffects(settings: ProjectSettingsView) {
        val productionType = vm.productionData.project().productionType
        state()?.form?.let { form ->
            if (productionType.isNotEmpty() && form.text("productionType") != productionType) {
                raw { it.with("productionType", productionType) }
            }
        }
        if (!vm.ui.production.loaded) return
        val companies = settings.companies
        if (!soleEntityChecked && companies.isNotEmpty()) {
            soleEntityChecked = true
            val form = state()?.form
            if (form != null && form.text("productionEntity").isEmpty() && companies.size == 1) {
                val entity = companies[0]["id"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty()
                if (entity.isNotEmpty()) {
                    entityBaseline = entity
                    raw { resolveEntity(it.with("productionEntity", entity), settings) }
                }
            }
        }
        val entity = state()?.form?.text("productionEntity") ?: return
        val baseline = entityBaseline
        if (baseline == null) {
            entityBaseline = entity
            return
        }
        if (entity == baseline) return
        entityBaseline = entity
        edit { resolveEntity(it, settings) }
    }

    /** The employing company owns PAYE and loan-out, so a new entity clears the status and re-reads the territory. */
    private fun resolveEntity(form: DealForm, settings: ProjectSettingsView): DealForm {
        val cleared = form.with("employmentStatus", "")
        val territory = BuilderSeeds.territoryForEntity(
            form.text("productionEntity"),
            settings.companies,
            vm.ui.production.countries,
        )
        return if (territory != null && territory != form.text("territory")) {
            cleared.with("territory", territory)
        } else {
            cleared
        }
    }

    /** A deal with neither date takes them from the project schedule, each time the settings arrive. */
    @Suppress("ReturnCount") // Each guard is one of the web's bail-outs.
    private fun seedEngagementDates(settings: ProjectSettingsView) {
        if (engagementChecked && engagementFor === settings.json) return
        engagementChecked = true
        engagementFor = settings.json
        val state = state() ?: return
        if (state.editLoading) return
        val form = state.form
        if (form.text("dealStart").isNotEmpty() || form.text("dealEnd").isNotEmpty()) return
        val (from, to) = BuilderSeeds.engagementDates(settings.productionSchedule)
        if (from.isEmpty() && to.isEmpty()) return
        raw { current -> current.with("dealStart", from).let { if (to.isNotEmpty()) it.with("dealEnd", to) else it } }
    }

    /** Flagged sections re-check as the form changes; a section that now passes drops its flag. */
    private fun revalidate() {
        val state = state() ?: return
        if (!mode.deal || state.issueErrors.isEmpty()) return
        val next = LinkedHashMap<Int, List<String>>()
        state.issueErrors.keys.forEach { sectionId -> sectionFields(sectionId, state)?.let { next[sectionId] = it } }
        if (next != state.issueErrors) update { copy(issueErrors = next) }
    }

    /**
     * The autosave baseline follows every form change that isn't a user edit,
     * while nothing is outstanding — hydration and defaults never save by themselves.
     */
    private fun trackBaseline() {
        val state = state() ?: return
        val enabled = autosaveEnabled
        if (state.form != lastForm || enabled != lastEnabled) {
            if (state.dirtyTick != lastTick) {
                lastTick = state.dirtyTick
            } else if (enabled && !state.dirty) {
                autosave.markSaved(saves.payload(state, state.form, autosave = true))
            }
        }
        lastForm = state.form
        lastEnabled = enabled
    }

    private fun today(): String = PayloadParts.fromEpoch(JsonPrimitive(vm.clock()))

    private fun generatedId(prefix: String): String {
        val random = (1..RANDOM_ID_CHARS).map { ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)] }.joinToString("")
        return "$prefix-${vm.clock()}-$random"
    }

    // -- autosave host ----------------------------------------------------------------------------

    override val autosaveEnabled: Boolean
        get() {
            val state = state() ?: return false
            return !state.editLoading && (mode.setup || state.dealStatus != CANCELLED)
        }

    override fun autosavePayload(): JsonObject? = state()?.let { saves.payload(it, it.form, autosave = true) }

    override fun autosaveServerId(): String? = state()?.let { if (mode.setup) it.autosavedTemplateId else it.dealId }

    override suspend fun autosaveCreate(payload: JsonObject): ZillitResult<SavedRecord?> = saves.autosaveCreate()

    override suspend fun autosaveUpdate(payload: JsonObject): ZillitResult<Unit> = saves.autosaveUpdate()

    override fun onAutosave(ui: AutosaveUi) = update { copy(autosave = ui) }

    override fun launch(block: suspend CoroutineScope.() -> Unit): Job = vm.work(block)

    override fun now(): Long = vm.clock()

    private companion object {
        const val MAX_PASSES = 6
        const val CANCELLED = "cancelled"
        const val RANDOM_ID_CHARS = 6
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** `RELEASED_DEAL_STATUSES` — every other status still holds the crew member. */
        val RELEASED_STATUSES = setOf("completed", "cancelled", "deactivated")
    }
}
