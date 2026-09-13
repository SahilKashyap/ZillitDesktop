package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementListing
import com.zillit.desktop.feature.dealmemo.domain.rates.CoveredDepartment
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `useWizardData` and its neighbours: the agreements a territory offers, the
 * agreement picked, the rate card's answer for the role, the roles the card
 * covers, and the territories with a branch — each read when the form's
 * inputs to it change, and cached for the session.
 */
internal class BuilderReferences(private val vm: DealMemoViewModel, private val page: BuilderActions) {

    private val listings = mutableMapOf<String, AgreementListing>()
    private val agreementDocs = mutableMapOf<String, JsonObject>()
    private val rateCache = mutableMapOf<String, List<JsonObject>>()
    private val roleCache = mutableMapOf<String, List<CoveredDepartment>>()

    private var listingJob: Job? = null
    private var agreementJob: Job? = null
    private var rateJob: Job? = null
    private var fallbackJob: Job? = null
    private var rolesJob: Job? = null
    private var coveredJob: Job? = null

    /** The territory and agreement last asked for — a failed read isn't retried until they change. */
    private var listingFor: String? = null
    private var agreementFor: String? = null

    /** Starts whatever the form's current territory, agreement and role need. */
    fun sync(state: BuilderState) {
        val form = state.form
        syncListing(form.text("territory"))
        syncAgreement(form.text("union"))
        syncRate(state)
        syncFallback(state)
        syncRoles(form)
    }

    /** Setup pages narrow the territory picker to the covered territories. */
    fun loadCoveredTerritories() {
        if (coveredJob?.isActive == true) return
        coveredJob = vm.work {
            vm.reference.coveredTerritories().getOrNull()?.let { covered ->
                reference { copy(coveredTerritories = covered) }
            }
        }
    }

    /** A territory's agreements, from the session's cache when read before; a failed read is empty. */
    suspend fun listing(territory: String): AgreementListing {
        listings[territory]?.let { return it }
        return vm.reference.agreements(territory = territory).getOrNull()?.also { listings[territory] = it }
            ?: AgreementListing()
    }

    /** An agreement's whole document, normalised and cached the way the page's own agreement is. */
    suspend fun agreementDocument(identifier: String): JsonObject? {
        agreementDocs[identifier]?.let { return it }
        return vm.reference.agreement(identifier).getOrNull()?.json?.let(::normalized)?.also {
            agreementDocs[identifier] = it
        }
    }

    /** `normalizeAgreementForUi`: `id` and `label` aliases of `_identifier` and `name`. */
    private fun normalized(json: JsonObject): JsonObject = JsonObject(
        json + mapOf(
            "id" to (json["_identifier"]?.takeUnless { it is JsonNull } ?: json["id"] ?: JsonNull),
            "label" to (json["name"]?.takeUnless { it is JsonNull } ?: json["label"] ?: JsonNull),
        ),
    )

    /** `refetchRate`: the same tuple, bypassing the cache. */
    fun refetchRate() {
        val state = vm.ui.builder ?: return
        val tuple = rateTuple(state.form, state.reference) ?: return
        rateJob?.cancel()
        rateJob = vm.work { fetchRate(tuple, force = true) }
    }

    /** A new page starts with nothing asked for; the caches stay for the session. */
    fun reset() {
        listOf(listingJob, agreementJob, rateJob, fallbackJob, rolesJob, coveredJob).forEach { it?.cancel() }
        listingFor = null
        agreementFor = null
    }

    /** A production switch: nothing cached belongs to the new one. */
    fun clear() {
        reset()
        listings.clear()
        agreementDocs.clear()
        rateCache.clear()
        roleCache.clear()
    }

    private fun syncListing(territory: String) {
        if (territory == listingFor) return
        listingFor = territory
        listingJob?.cancel()
        if (territory.isEmpty()) {
            reference {
                copy(
                    territory = "",
                    agreements = emptyList(),
                    territoryEmpStatuses = JsonArray(emptyList()),
                    agreementsLoading = false,
                )
            }
            return
        }
        listings[territory]?.let { cached ->
            reference { withListing(territory, cached) }
            return
        }
        reference {
            copy(
                territory = territory,
                agreements = emptyList(),
                territoryEmpStatuses = JsonArray(emptyList()),
                agreementsLoading = true,
            )
        }
        listingJob = vm.work {
            val listing = when (val result = vm.reference.agreements(territory = territory)) {
                is ZillitResult.Success -> result.data.also { listings[territory] = it }
                is ZillitResult.Failure -> AgreementListing()
            }
            reference { if (this.territory == territory) withListing(territory, listing) else this }
            page.reconcile()
        }
    }

    private fun BuilderReference.withListing(territory: String, listing: AgreementListing) = copy(
        territory = territory,
        agreements = listing.agreements.map { agreement ->
            buildJsonObject {
                put("_identifier", agreement.identifier)
                put("id", agreement.identifier)
                put("name", agreement.name)
                put("label", agreement.name)
                agreement.shortLabel?.let { put("short_label", it) }
                agreement.territory?.let { put("territory", it) }
                agreement.currency?.let { put("currency", it) }
                agreement.unionIdentifier?.let { put("union_identifier", it) }
            }
        },
        territoryEmpStatuses = JsonArray(listing.empStatuses.map(::statusJson)),
        agreementsLoading = false,
    )

    private fun syncAgreement(union: String) {
        if (union == agreementFor) return
        agreementFor = union
        agreementJob?.cancel()
        when {
            union.isEmpty() -> reference { copy(agreementId = "", agreement = null, agreementLoading = false) }
            isNonUnionId(union) -> reference {
                copy(agreementId = union, agreement = NON_UNION, agreementLoading = false)
            }
            agreementDocs.containsKey(union) -> reference {
                copy(agreementId = union, agreement = agreementDocs[union], agreementLoading = false)
            }
            else -> {
                reference { copy(agreementId = union, agreement = null, agreementLoading = true) }
                agreementJob = vm.work {
                    val doc = agreementDocument(union)
                    reference { if (agreementId == union) copy(agreement = doc, agreementLoading = false) else this }
                    page.reconcile()
                }
            }
        }
    }

    /** The (department × designation × agreement × production type × budget) the rate card is asked for. */
    private data class RateTuple(
        val department: String,
        val designation: String,
        val agreement: String,
        val productionType: String,
        val band: String,
        val budget: Double?,
    ) {
        val key: String get() =
            listOf(department, designation, agreement, productionType, band, budget?.let(Js::number).orEmpty(), "0")
            .joinToString("|")
    }

    private fun rateTuple(form: DealForm, reference: BuilderReference): RateTuple? {
        val union = form.text("union")
        val designation = form.text("designation")
        if (union.isEmpty() || designation.isEmpty()) return null
        val band = form.text("pactBand")
        return RateTuple(
            form.text("department"),
            designation,
            union,
            form.text("productionType"),
            band,
            bandBudget(reference.agreement, band),
        )
    }

    /** `bandBudget`: the band's minimum; an open-bottom band reads 0; no band, no budget. */
    private fun bandBudget(agreement: JsonObject?, band: String): Double? {
        if (band.isEmpty()) return null
        val bands = ((agreement?.get("pact") as? JsonObject)?.get("bands") as? JsonArray).orEmpty().mapNotNull {
            it as? JsonObject
        }
        val match =
            bands.firstOrNull { row -> row["band"]?.let { if (it is JsonNull) "null" else Js.text(it) } == band }
                ?: return null
        return when {
            match["min_budget"] != null && match["min_budget"] !is JsonNull -> Js.toNumber(match["min_budget"])
            match["max_budget"] != null && match["max_budget"] !is JsonNull -> 0.0
            else -> null
        }
    }

    private fun syncRate(state: BuilderState) {
        val tuple = rateTuple(state.form, state.reference)
        val key = tuple?.key
        if (key == state.reference.rateKey) return
        rateJob?.cancel()
        if (tuple == null) {
            reference { copy(rateKey = null, resolvedRates = emptyList(), rateLoading = false) }
            return
        }
        reference { copy(rateKey = key) }
        rateJob = vm.work {
            delay(RATE_DEBOUNCE_MILLIS)
            fetchRate(tuple, force = false)
        }
    }

    private suspend fun fetchRate(tuple: RateTuple, force: Boolean) {
        if (tuple.department.isEmpty() || tuple.productionType.isEmpty()) {
            reference { if (rateKey == tuple.key) copy(resolvedRates = emptyList(), rateLoading = false) else this }
            page.reconcile()
            return
        }
        if (!force) {
            rateCache[tuple.key]?.let { cached ->
                reference { if (rateKey == tuple.key) copy(resolvedRates = cached, rateLoading = false) else this }
                page.reconcile()
                return
            }
        }
        reference { if (rateKey == tuple.key) copy(resolvedRates = emptyList(), rateLoading = true) else this }
        val result = vm.reference.resolveRates(
            tuple.department,
            tuple.designation,
            tuple.agreement,
            tuple.productionType,
            tuple.budget,
        )
        val found = when (result) {
            is ZillitResult.Success -> result.data.also { rateCache[tuple.key] = it }
            is ZillitResult.Failure -> emptyList()
        }
        reference { if (rateKey == tuple.key) copy(resolvedRates = found, rateLoading = false) else this }
        page.reconcile()
    }

    /** A territory-less deal's production entity names the territory whose employment statuses it shows. */
    private fun syncFallback(state: BuilderState) {
        val form = state.form
        val territory = when {
            form.text("territory").isNotEmpty() || form.text("productionEntity").isEmpty() -> ""
            else -> BuilderSeeds.territoryForEntity(
                form.text("productionEntity"),
                vm.ui.projectSettings.view.companies,
                vm.ui.production.countries,
            ).orEmpty()
        }
        if (territory == state.reference.fallbackTerritory) return
        fallbackJob?.cancel()
        if (territory.isEmpty()) {
            reference { copy(fallbackTerritory = "", fallbackEmpStatuses = JsonArray(emptyList())) }
            return
        }
        listings[territory]?.let { cached ->
            reference {
                copy(
                    fallbackTerritory = territory,
                    fallbackEmpStatuses = JsonArray(cached.empStatuses.map(::statusJson)),
                )
            }
            return
        }
        reference { copy(fallbackTerritory = territory, fallbackEmpStatuses = JsonArray(emptyList())) }
        fallbackJob = vm.work {
            val listing = vm.reference.agreements(territory = territory).getOrNull()?.also { listings[territory] = it }
            reference {
                if (fallbackTerritory == territory) {
                    copy(fallbackEmpStatuses = JsonArray(listing?.empStatuses.orEmpty().map(::statusJson)))
                } else {
                    this
                }
            }
            page.reconcile()
        }
    }

    /** The roles the agreement's card covers, refetched per (agreement, production type). */
    private fun syncRoles(form: DealForm) {
        val union = form.text("union")
        val productionType = form.text("productionType")
        val key = if (union.isEmpty()) null else "$union|$productionType"
        val current = vm.ui.builder?.reference ?: return
        if (key == current.coveredRolesKey) return
        rolesJob?.cancel()
        if (key == null) {
            reference { copy(coveredRolesKey = null, coveredRoles = emptyList()) }
            return
        }
        roleCache[key]?.let { cached ->
            reference { copy(coveredRolesKey = key, coveredRoles = cached) }
            return
        }
        reference { copy(coveredRolesKey = key, coveredRoles = emptyList()) }
        rolesJob = vm.work {
            val found = vm.reference.coveredRoles(union, productionType).getOrNull().orEmpty().also {
                roleCache[key] = it
            }
            reference { if (coveredRolesKey == key) copy(coveredRoles = found) else this }
        }
    }

    private fun reference(reducer: BuilderReference.() -> BuilderReference) =
        vm.update { copy(builder = builder?.let { it.copy(reference = it.reference.reducer()) }) }

    private fun statusJson(status: EmpStatus): JsonObject = buildJsonObject {
        put("id", status.id)
        put("label", status.label)
        status.sub?.let { put("sub", it) }
        status.badge?.let { put("badge", it) }
        status.alertClass?.let { put("alert_cls", it) }
        put("hp_show", status.hpShown)
    }

    private companion object {
        const val RATE_DEBOUNCE_MILLIS = 150L

        /** The synthetic agreement a non-union deal reads — no request, no rate card. */
        val NON_UNION: JsonObject = buildJsonObject {
            put("id", "non_union")
            put("_identifier", "non_union")
            put("label", "Non-Union")
        }
    }
}
