package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One unit a tool's own service lists — a tab of that tool's board. */
data class ReportUnit(
    val id: String,
    /** The unit's name — a label key (`breakfast_label`) or a typed name. */
    val name: String,
    val identifier: String?,
    /** `system_defined` on the wire; a production's own units are not. */
    val systemDefined: Boolean = false,
    /** True for a unit the production deleted — the wire's non-zero `deleted` stamp. */
    val deleted: Boolean = false,
)

/**
 * A board tool's tabs, from that tool's OWN service.
 *
 * Several tools are the Home board engine with tabs the production's tool
 * list does not know: Camera & Sound Report asks the script-notes host
 * (`reports/unit/`), Catering and Accounts ask the unit host
 * (`catering/unit`, `account/unit`), Location asks its own host
 * (`location/units`). Rights are not on these rows; the tool's one right
 * covers them all.
 */
class ReportUnitsSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val service: ZillitService = ZillitService.ScriptNotes,
    private val route: String = "reports/unit/",
    /**
     * Asks for the units as the File Cabinet lists them (`isFileCabinet=true`,
     * which the web adds on its settings page) — the server answers the units
     * whose records were moved into the cabinet.
     */
    private val fileCabinet: Boolean = false,
) {
    suspend fun units(): ZillitResult<List<ReportUnit>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(service)}$route${if (fileCabinet) "?isFileCabinet=true" else ""}",
            serializer = ListSerializer(ReportUnitDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows ->
            rows.mapNotNull { row ->
                val id = row.id?.takeIf { it.isNotBlank() } ?: row.unitId?.takeIf { it.isNotBlank() }
                val name = row.unitName?.takeIf { it.isNotBlank() }
                if (id == null || name == null) {
                    null
                } else {
                    ReportUnit(id, name, row.identifier, row.systemDefined == true, row.isDeleted)
                }
            }
        }
}

/** One row of a tool's unit list — a home unit's shape, without the rights. */
@Serializable
internal data class ReportUnitDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    /** 0 while the unit stands; a timestamp once removed (the web keeps `deleted === 0`). */
    @SerialName("deleted") val deleted: JsonElement? = null,
) {
    val isDeleted: Boolean
        get() = (deleted as? JsonPrimitive)
            ?.let { it !is JsonNull && it.content != "0" && it.content != "false" } == true
}
