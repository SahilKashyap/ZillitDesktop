package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.HybridDefault
import com.zillit.desktop.feature.accounthub.domain.TimecardApprovalSummary
import com.zillit.desktop.feature.accounthub.domain.TimecardControlModel
import com.zillit.desktop.feature.accounthub.domain.TimecardDepartmentSummary
import com.zillit.desktop.feature.accounthub.domain.TimecardSetup
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * The time card configuration — the web's `timecardConfigApi` (`GET`/`PUT
 * /api/v2/payroll/timecards/config`), which Production Setup's Time Card Entry
 * Setup modal reads and writes.
 *
 * On the payroll service, scoped by the project header. A project that has
 * never saved one answers 404 or an empty document, and both read as the
 * defaults — the web shows an error only for a failure that is not a 404.
 * The save is a PUT of the whole record, and the answer is not trusted for
 * anything: the summaries it carries are the read's, so what was sent is kept.
 */
internal class HubTimecardSource(private val apiClient: ApiClient, config: AppConfig) {

    private val url = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll/timecards/config"

    suspend fun load(): ZillitResult<TimecardSetup> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = url,
        module = RequestModule.ProjectUser,
    ).let { result ->
        when {
            result is ZillitResult.Failure && (result.error as? ZillitError.Http)?.status == NOT_FOUND ->
                ZillitResult.Success(TimecardSetup())
            else -> result.flatMap { envelope ->
                if (envelope.status == REFUSED) {
                    ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
                } else {
                    ZillitResult.Success(parse(envelope.data.settingsDocument()))
                }
            }
        }
    }

    suspend fun save(setup: TimecardSetup): ZillitResult<TimecardSetup> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = url,
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("overall_control_model", JsonPrimitive(setup.model.wire))
            // Only a hybrid production has a default to fall back to; null for the rest.
            put(
                "hybrid_default",
                if (setup.model == TimecardControlModel.Hybrid) JsonPrimitive(setup.hybridDefault.wire) else JsonNull,
            )
            put("approval_cadence", JsonPrimitive(setup.cadence))
        },
    ).flatMap { envelope ->
        if (envelope.status == REFUSED) {
            ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
        } else {
            ZillitResult.Success(setup)
        }
    }

    internal companion object {
        private const val NOT_FOUND = 404
        private const val HTTP_OK = 200
        private const val REFUSED = 0

        /** The stored document as the modal edits it; every key optional, as a fresh project has none. */
        fun parse(document: JsonObject?): TimecardSetup {
            document ?: return TimecardSetup()
            val dept = document["department_summary"] as? JsonObject
            val appr = document["approval_summary"] as? JsonObject
            val split = dept?.get("split") as? JsonObject
            return TimecardSetup(
                model = TimecardControlModel.fromWire(document.text("overall_control_model")),
                hybridDefault = HybridDefault.fromWire(document.text("hybrid_default")),
                cadence = document.text("approval_cadence") ?: TimecardSetup.DEFAULT_CADENCE,
                departments = TimecardDepartmentSummary(
                    configured = dept.count("configured"),
                    completers = dept.count("completers"),
                    departmentControlled = split.count("department"),
                    crewControlled = split.count("crew"),
                    productionControlled = split.count("production"),
                    completerIds = dept.ids("completerUserIds"),
                ),
                approvals = TimecardApprovalSummary(
                    defaultLevels = appr.count("defaultLevels"),
                    customOverrides = appr.count("customOverrides"),
                    approverCount = appr.count("approverCount"),
                    approverIds = appr.ids("approverUserIds"),
                ),
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        /** A count that may arrive as a number or a quoted number; absent reads 0. */
        private fun JsonObject?.count(key: String): Int =
            ((this?.get(key)) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0

        private fun JsonObject?.ids(key: String): List<String> =
            runCatching { this?.get(key)?.jsonArray.orEmpty() }.getOrDefault(emptyList())
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
}
