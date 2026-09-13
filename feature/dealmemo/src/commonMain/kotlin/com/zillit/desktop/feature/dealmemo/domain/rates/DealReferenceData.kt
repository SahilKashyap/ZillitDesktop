package com.zillit.desktop.feature.dealmemo.domain.rates

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealCrewUser
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import kotlinx.serialization.json.JsonObject

/**
 * The deal-memo service's global union catalogue — territories, unions,
 * branches, agreements and designation rates — plus the production's
 * departments master every rate is grouped by.
 *
 * Read by Global Production Rates and by the deal wizard. Everything here is
 * keyed by `_identifier` slugs.
 */
interface DealReferenceData {

    /** Territory slugs with at least one branch (`/branches/covered-territories`). */
    suspend fun coveredTerritories(): ZillitResult<Set<String>>

    suspend fun unions(territory: String): ZillitResult<List<UnionSummary>>

    suspend fun branches(territory: String): ZillitResult<List<Branch>>

    /**
     * `GET /agreements`. With [branchIdentifier] the server returns the union's
     * frameworks plus the agreements pinned to that branch.
     */
    suspend fun agreements(territory: String? = null, branchIdentifier: String? = null): ZillitResult<AgreementListing>

    suspend fun agreement(identifier: String): ZillitResult<AgreementDocument>

    /** A branch's whole card — always scoped: the server refuses an unscoped rate query. */
    suspend fun designationRates(branchIdentifier: String): ZillitResult<List<DesignationRate>>

    /**
     * Wipes and re-seeds the agreements, branch registry and designation rates
     * from source data. Answers the server's message.
     */
    suspend fun importDefaults(): ZillitResult<String?>

    suspend fun departments(): ZillitResult<DepartmentCatalogue>

    /**
     * The production's agency vendors, id → name (`GET /ad-rate-config/vendors`
     * on the AD dashboard service) — so an agency is named, never shown as its id.
     */
    suspend fun agencies(): ZillitResult<Map<String, String>> = ZillitResult.Success(emptyMap())

    /**
     * `GET /designation-rates/resolve` — every rate variant the card publishes
     * for the role under the agreement (one per schedule), the first the
     * default; [budget] picks the budget tier. A 404 is no rate, not an error.
     */
    suspend fun resolveRates(
        department: String,
        designation: String,
        agreement: String,
        productionType: String,
        budget: Double?,
    ): ZillitResult<List<JsonObject>> = ZillitResult.Success(emptyList())

    /** `GET /designation-rates/department-designations` — the roles the agreement's card covers. */
    suspend fun coveredRoles(agreement: String, productionType: String?): ZillitResult<List<CoveredDepartment>> =
        ZillitResult.Success(emptyList())

    /** `GET /v2/project/users` — every member, any status, with the ids a deal is scoped by. */
    suspend fun crew(): ZillitResult<List<DealCrewUser>> = ZillitResult.Success(emptyList())
}

/** A department the rate card covers, with the designations it publishes rates for. */
data class CoveredDepartment(val departmentIdentifier: String, val designations: List<String>)
