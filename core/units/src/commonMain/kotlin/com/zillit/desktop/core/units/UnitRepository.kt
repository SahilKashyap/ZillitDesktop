package com.zillit.desktop.core.units

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A production unit a crew member can be attached to.
 *
 * Main unit, second unit, splinter — a shoot runs several at once, and which
 * one someone is on decides whose call sheets and notices they see.
 */
data class ProductionUnit(val id: String, val name: String)

/** The units this user may join, and which one they are on. */
interface UnitRepository {

    /**
     * Units on a production that this user can be attached to.
     *
     * [projectId] names the production when it is not the open one — the join
     * form asks about a production the user is not in yet.
     */
    suspend fun joinUnits(projectId: String? = null): ZillitResult<List<ProductionUnit>>

    /** Attaches this user to [unitId]. */
    suspend fun setJoinUnit(unitId: String): ZillitResult<Unit>
}

/**
 * `GET join/unit` to list, `PUT user/profile` to choose.
 *
 * Two hosts, because the unit list belongs to the units service and the choice
 * is a field on the user's profile in core. That split is the server's, not
 * this client's — the web does exactly the same pair of calls.
 *
 * The list is read leniently rather than through a typed DTO. A strict reader
 * has failed against an HTTP 200 on this API four separate times, and a unit
 * picker that comes up empty because one row grew a field is worse than one
 * that skips the row.
 */
class UnitRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : UnitRepository {

    override suspend fun joinUnits(projectId: String?): ZillitResult<List<ProductionUnit>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Units)}join/unit",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            options = CallOptions(projectId = projectId),
        ).map { it.toUnits() }

    override suspend fun setJoinUnit(unitId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${config.apiV2(ZillitService.Core)}user/profile",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("join_unit_id", unitId) },
        ).map { }
}

/**
 * Pulls units out of whatever shape the list arrived in.
 *
 * Accepts a bare array or an object wrapping one, because both are live on this
 * API — `storage_folders` returning an object where a list was expected once
 * broke an entire screen's decode, and that was a typed reader failing on a 200.
 */
internal fun JsonElement.toUnits(): List<ProductionUnit> {
    val rows = when (this) {
        is JsonArray -> this
        is JsonObject -> LIST_KEYS.firstNotNullOfOrNull { this[it] as? JsonArray }.orEmpty()
        else -> emptyList()
    }

    return rows.mapNotNull { (it as? JsonObject)?.toUnit() }
}

private fun JsonObject.toUnit(): ProductionUnit? {
    val id = ID_KEYS.firstNotNullOfOrNull { text(it) } ?: return null
    // A unit with no name is still a unit the server knows about; showing its
    // id beats dropping it and leaving the user unable to pick their own unit.
    val name = NAME_KEYS.firstNotNullOfOrNull { text(it) } ?: id
    return ProductionUnit(id, name)
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private val LIST_KEYS = listOf("units", "data", "entries", "list")
private val ID_KEYS = listOf("_id", "id", "unit_id")
private val NAME_KEYS = listOf("unit_name", "name", "title")
