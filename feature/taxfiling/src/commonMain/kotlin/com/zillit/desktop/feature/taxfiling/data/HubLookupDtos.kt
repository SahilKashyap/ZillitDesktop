package com.zillit.desktop.feature.taxfiling.data

import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.CoaCodes
import com.zillit.desktop.feature.taxfiling.domain.LayerCode
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The account hub's three lists a box mapping is picked from, read the way the
 * web's pickers read them.
 */

/** One chart row from `GET account-hub/chart-of-accounts?active_only=true`. */
@Serializable
data class CoaRowDto(
    @SerialName("code") val code: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("line_type") val lineType: String? = null,
    @SerialName("posting_box") val postingBox: JsonElement? = null,
    @SerialName("is_active") val isActive: JsonElement? = null,
)

/**
 * The accounts the ledger-codes picker offers — the web's `CoaCodeInput` with
 * `pickableLevels="all"`.
 *
 * Only Nominals (`category`) and Codes (`sub_category`) are postable; headers
 * and sections are group titles. A row with no `line_type` predates the field
 * and passes, as does one with no `posting_box`. Sorted by code, numerically.
 */
internal fun List<CoaRowDto>.postableCodes(): List<CoaCode> =
    asSequence()
        .filter { it.isActive.asBoolean() != false }
        .filter { it.lineType.isNullOrBlank() || it.lineType == "category" || it.lineType == "sub_category" }
        .filter { it.postingBox.asBoolean() != false }
        .mapNotNull { row ->
            row.code.asText()?.trim()?.takeIf { it.isNotEmpty() }?.let { CoaCode(it, row.name.orEmpty()) }
        }
        .distinctBy { it.code }
        .sortedWith { a, b -> CoaCodes.compare(a.code, b.code) }
        .toList()

/** A tracking set with its nodes, from `GET account-hub/tracking-sets?include_nodes=true`. */
@Serializable
data class TrackingSetDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("prefix") val prefix: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("active") val active: JsonElement? = null,
    @SerialName("nodes") val nodes: List<TrackingNodeDto>? = null,
) {
    /**
     * The set as the layers dialog lists it, or null for an inactive one.
     *
     * Its codes are the active leaves in the chart's own order — `sort_order`,
     * then code — never headers, which group codes rather than being one.
     */
    fun toDomain(): LayerSet? {
        val setId = id?.takeIf { it.isNotBlank() } ?: altId?.takeIf { it.isNotBlank() } ?: return null
        if (active.asBoolean() == false) return null
        val leaves = nodes.orEmpty()
            .filter { it.active.asBoolean() != false && it.isHeader.asBoolean() != true }
            .sortedWith(compareBy<TrackingNodeDto> { it.sortOrder.asDouble() ?: 0.0 }.thenBy { it.code.orEmpty() })
            .mapNotNull { it.toDomain() }
        return LayerSet(
            id = setId,
            name = name.orEmpty(),
            prefix = prefix.orEmpty(),
            color = color.orEmpty(),
            codes = leaves,
        )
    }
}

@Serializable
data class TrackingNodeDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("is_header") val isHeader: JsonElement? = null,
    @SerialName("active") val active: JsonElement? = null,
    @SerialName("sort_order") val sortOrder: JsonElement? = null,
) {
    fun toDomain(): LayerCode? {
        val value = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return LayerCode(
            id = id?.takeIf { it.isNotBlank() } ?: altId.orEmpty(),
            code = value,
            label = (label ?: name).orEmpty(),
            description = description.orEmpty().trim(),
        )
    }
}

/** `{ value: string[] }` — Production Setup's asset tags slice. */
@Serializable
data class AssetTagsDto(@SerialName("value") val value: JsonElement? = null)
