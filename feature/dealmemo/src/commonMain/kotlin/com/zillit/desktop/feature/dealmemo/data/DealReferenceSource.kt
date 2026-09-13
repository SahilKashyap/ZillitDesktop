package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.dealmemo.domain.CatalogueDepartment
import com.zillit.desktop.feature.dealmemo.domain.CatalogueDesignation
import com.zillit.desktop.feature.dealmemo.domain.DealCrewUser
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementDocument
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementListing
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.CoveredDepartment
import com.zillit.desktop.feature.dealmemo.domain.rates.DealReferenceData
import com.zillit.desktop.feature.dealmemo.domain.rates.DesignationRate
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateTierEntry
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.empStatusOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reference routes of `/api/v2/deal-memo`, and the departments master on
 * the core host.
 */
class DealReferenceSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) : DealReferenceData {

    private val base = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"
    private val departmentsUrl = "${config.apiV2(ZillitService.Core)}departments"
    private val projectUsersUrl = "${config.apiV2(ZillitService.Core)}project/users"

    /** Null when this environment has no AD dashboard service configured. */
    private val vendorsUrl = runCatching {
        "${config.baseUrl(ZillitService.AdDashboard)}/api/v2/ad-rate-config/vendors"
    }.getOrNull()

    override suspend fun coveredTerritories(): ZillitResult<Set<String>> =
        read("$base/branches/covered-territories").map { data ->
            DocRead.array(data).mapNotNull { (it as? JsonPrimitive)?.content?.lowercase()?.takeIf(String::isNotBlank) }
                .toSet()
        }

    override suspend fun unions(territory: String): ZillitResult<List<UnionSummary>> =
        read("$base/unions", mapOf("territory" to territory)).map { data ->
            DocRead.objects(data).mapNotNull { row ->
                val id = DocRead.text(row, "_identifier") ?: return@mapNotNull null
                UnionSummary(
                    identifier = id,
                    name = DocRead.text(row, "name") ?: id,
                    shortLabel = DocRead.text(row, "short_label"),
                    source = DocRead.text(row, "source"),
                )
            }
        }

    override suspend fun branches(territory: String): ZillitResult<List<Branch>> =
        read("$base/branches", mapOf("territory" to territory)).map { data ->
            DocRead.objects(data).mapNotNull(::branchOf)
        }

    override suspend fun agreements(territory: String?, branchIdentifier: String?): ZillitResult<AgreementListing> =
        read(
            "$base/agreements",
            buildMap {
                territory?.let { put("territory", it) }
                branchIdentifier?.let { put("branch_identifier", it) }
            },
        ).map(::listingOf)

    override suspend fun agreement(identifier: String): ZillitResult<AgreementDocument> =
        read("$base/agreements/$identifier").flatMap { data ->
            (data as? JsonObject)?.let { ZillitResult.Success(AgreementDocument(it)) }
                ?: ZillitResult.Failure(ZillitError.Serialization("agreement $identifier came back empty"))
        }

    override suspend fun designationRates(branchIdentifier: String): ZillitResult<List<DesignationRate>> =
        read(
            "$base/designation-rates",
            mapOf("branch_identifier" to branchIdentifier, "limit" to RATE_CARD_LIMIT, "offset" to 0),
        ).map { data -> DocRead.objects(data).map(::rateOf) }

    override suspend fun importDefaults(): ZillitResult<String?> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/agreements/import-defaults",
            module = RequestModule.ProjectUser,
        ).flatMap { it.refusedOrOk() }.map { it.message?.takeIf(String::isNotBlank) }

    override suspend fun departments(): ZillitResult<DepartmentCatalogue> =
        read(departmentsUrl, mapOf("designations" to true)).flatMap { data ->
            // The body may be the list itself or carry it one level down.
            val rows = (data as? JsonArray) ?: ((data as? JsonObject)?.get("data") as? JsonArray)
            rows?.let { list ->
                ZillitResult.Success(DepartmentCatalogue(DocRead.objects(list).mapNotNull(::departmentOf)))
            }
                // An unreadable master is a failure, not an empty production:
                // grouping against nothing would orphan every rate row.
                ?: ZillitResult.Failure(ZillitError.Serialization("departments came back without a list"))
        }

    override suspend fun agencies(): ZillitResult<Map<String, String>> {
        val url = vendorsUrl ?: return ZillitResult.Success(emptyMap())
        return read(url).map { data ->
            DocRead.objects(data).mapNotNull { row ->
                val id = DocRead.text(row, "id") ?: DocRead.text(row, "_id") ?: return@mapNotNull null
                DocRead.text(row, "name")?.let { id to it }
            }.toMap()
        }
    }

    override suspend fun resolveRates(
        department: String,
        designation: String,
        agreement: String,
        productionType: String,
        budget: Double?,
    ): ZillitResult<List<JsonObject>> {
        val query = buildMap<String, Any?> {
            put("department_identifier", department)
            put("designation_identifier", designation)
            put("production_type", productionType)
            if (agreement.isNotEmpty()) put("agreement_identifier", agreement)
            budget?.let { put("budget", Js.number(it)) }
        }
        return when (val result = read("$base/designation-rates/resolve", query)) {
            is ZillitResult.Success -> ZillitResult.Success(
                when (val data = result.data) {
                    is JsonArray -> data.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(data)
                    else -> emptyList()
                },
            )
            // A role the card doesn't publish answers 404 — that is "no rate".
            is ZillitResult.Failure -> if ((result.error as? ZillitError.Http)?.status == HTTP_NOT_FOUND) {
                ZillitResult.Success(emptyList())
            } else {
                result
            }
        }
    }

    override suspend fun coveredRoles(
        agreement: String,
        productionType: String?,
    ): ZillitResult<List<CoveredDepartment>> =
        read(
            "$base/designation-rates/department-designations",
            buildMap {
                put("agreement_identifier", agreement)
                productionType?.takeIf { it.isNotEmpty() }?.let { put("production_type", it) }
            },
        ).map { data ->
            DocRead.objects(data).mapNotNull { row ->
                val department = DocRead.text(row, "department_identifier") ?: return@mapNotNull null
                CoveredDepartment(
                    department,
                    DocRead.array(row["designations"]).mapNotNull {
                        (it as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)
                    },
                )
            }
        }

    override suspend fun crew(): ZillitResult<List<DealCrewUser>> =
        read(projectUsersUrl).map { data ->
            DocRead.objects(data).mapNotNull { row ->
                val userId = DocRead.text(row, "user_id") ?: return@mapNotNull null
                val name = DocRead.text(row, "full_name") ?: DocRead.text(row, "name")
                    ?: listOfNotNull(DocRead.text(row, "first_name"), DocRead.text(row, "last_name")).joinToString(" ")
                DealCrewUser(
                    userId = userId,
                    fullName = name,
                    status = DocRead.text(row, "status"),
                    departmentIdentifier = DocRead.text(row, "department_identifier"),
                    designationIdentifier = DocRead.text(row, "designation_identifier"),
                    departmentName = DocRead.text(row, "department_name"),
                    designationName = DocRead.text(row, "designation_name"),
                    departmentId = DocRead.text(row, "department_id"),
                    designationId = DocRead.text(row, "designation_id"),
                    joinUnitId = DocRead.text(row, "join_unit_id"),
                )
            }
        }

    private suspend fun read(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement?> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = query,
        ).flatMap { it.refusedOrOk() }.map(ApiEnvelope::data)

    private companion object {
        /** The web's scope for a branch card: everything, in one page. */
        const val RATE_CARD_LIMIT = 5000
        const val HTTP_NOT_FOUND = 404
    }
}

internal fun branchOf(row: JsonObject): Branch? {
    val id = DocRead.text(row, "_identifier") ?: return null
    return Branch(
        identifier = id,
        name = DocRead.text(row, "name") ?: DocRead.text(row, "label") ?: id,
        unionIdentifier = DocRead.text(row, "union_identifier") ?: id,
        shortLabel = DocRead.text(row, "short_label"),
        territory = DocRead.text(row, "territory")?.lowercase(),
        region = DocRead.text(row, "region"),
        source = DocRead.text(row, "source"),
        currency = DocRead.text(row, "currency"),
    )
}

/** `{union: [...], emp_statuses: [...]}`, or the legacy bare list of agreements. */
internal fun listingOf(data: JsonElement?): AgreementListing {
    val envelope = data as? JsonObject
    val rows = if (data is JsonArray) data else envelope?.get("union")
    return AgreementListing(
        agreements = DocRead.objects(rows).mapNotNull { row ->
            val id = DocRead.text(row, "_identifier") ?: return@mapNotNull null
            AgreementSummary(
                identifier = id,
                name = DocRead.text(row, "name") ?: DocRead.text(row, "label") ?: id,
                territory = DocRead.text(row, "territory"),
                currency = DocRead.text(row, "currency"),
                shortLabel = DocRead.text(row, "short_label"),
                unionIdentifier = DocRead.text(row, "union_identifier"),
            )
        },
        empStatuses = DocRead.objects(envelope?.get("emp_statuses")).mapNotNull(::empStatusOf),
    )
}

internal fun rateOf(row: JsonObject): DesignationRate = DesignationRate(
    designationIdentifier = DocRead.text(row, "designation_identifier"),
    departmentIdentifier = DocRead.text(row, "department_identifier"),
    branchIdentifier = DocRead.text(row, "branch_identifier"),
    unionIdentifier = DocRead.text(row, "union_identifier"),
    agreementIdentifier = DocRead.text(row, "agreement_identifier"),
    productionType = DocRead.text(row, "production_type"),
    minBudget = DocRead.number(row, "min_budget"),
    maxBudget = DocRead.number(row, "max_budget"),
    minExp = DocRead.number(row, "min_exp"),
    maxExp = DocRead.number(row, "max_exp"),
    hourly = tiersOf(row["hourly"]),
    daily = tiersOf(row["daily"]),
    weekly = tiersOf(row["weekly"]),
    flatRate = tiersOf(row["flat_rate"]),
    currency = DocRead.text(row, "currency"),
    notes = DocRead.text(row, "notes"),
)

/** A tier list — or a single tier object, which older rows carry. */
internal fun tiersOf(element: JsonElement?): List<RateTierEntry> = when (element) {
    is JsonObject -> listOf(tierOf(element))
    else -> DocRead.objects(element).map(::tierOf)
}

internal fun tierOf(json: JsonObject): RateTierEntry = RateTierEntry(
    baseRate = DocRead.number(json, "base_rate"),
    minRate = DocRead.number(json, "min_rate"),
    maxRate = DocRead.number(json, "max_rate"),
    workHours = DocRead.number(json, "work_hrs"),
    dayType = (json["day_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
)

internal fun departmentOf(row: JsonObject): CatalogueDepartment? {
    val identifier = DocRead.text(row, "identifier") ?: return null
    return CatalogueDepartment(
        id = DocRead.text(row, "_id"),
        identifier = identifier,
        nameKey = DocRead.text(row, "department_name") ?: identifier,
        designations = DocRead.objects(row["designations"]).mapNotNull { designation ->
            val id = DocRead.text(designation, "identifier") ?: return@mapNotNull null
            CatalogueDesignation(
                id = DocRead.text(designation, "_id"),
                identifier = id,
                nameKey = DocRead.text(designation, "designation_name") ?: id,
            )
        },
    )
}
