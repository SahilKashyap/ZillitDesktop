package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.AccountPatch
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaUpdate
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The chart of accounts' own calls — the web's `api/account-hub/chart-of-accounts.js`.
 *
 * Split out of [AccountHubRepositoryImpl] the way [HubSetupSource] was: the
 * repository keeps the interface and one-line delegations, and the chart's
 * wire rules live together here.
 */
internal class HubChartSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {
    private val base = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub/chart-of-accounts"

    suspend fun accounts(activeOnly: Boolean): ZillitResult<List<CoaAccount>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = base,
            serializer = ListSerializer(CoaAccountDto.serializer()),
            module = RequestModule.ProjectUser,
            // Always stated, as every web caller states it. Left out, the
            // server's default decides — and a default of active-only would hide
            // every retired code behind a Show inactive toggle with nothing to show.
            queryParameters = mapOf("active_only" to activeOnly.toString()),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun createAccount(account: NewAccount): ZillitResult<CoaAccount> =
        write(
            verb = HttpVerb.Post,
            url = base,
            body = buildJsonObject {
                // Upper-cased as the web sends it: the code is the natural key.
                put("code", JsonPrimitive(account.code.trim().uppercase()))
                put("name", JsonPrimitive(account.name.trim()))
                put("line_type", JsonPrimitive(account.lineType.wire))
                put("cost_type", JsonPrimitive(account.costType.wire))
                // The immediate parent only. The server walks up from it to fill
                // the rest of the breadcrumb, and sending a partial breadcrumb
                // here would have it disagree with the ancestors.
                put("parent_id", account.parentId?.let(::JsonPrimitive) ?: JsonNull)
                // Sent on create too: a grid row added with Active unticked was
                // otherwise created live.
                put("is_active", JsonPrimitive(account.isActive))
                put("posting_box", JsonPrimitive(account.isPosting))
            },
        ).map { it.account }

    suspend fun updateAccount(id: String, patch: AccountPatch): ZillitResult<CoaUpdate> = write(
        verb = HttpVerb.Patch,
        url = "$base/$id",
        // Only what the patch sets. The line type and parent go only when the
        // structure changed: the server re-walks the breadcrumb then, which is
        // the expensive half of the write, and the web gates it the same way.
        body = buildJsonObject {
            patch.name?.let { put("name", JsonPrimitive(it.trim())) }
            patch.costType?.let { put("cost_type", JsonPrimitive(it.wire)) }
            patch.isActive?.let { put("is_active", JsonPrimitive(it)) }
            patch.isPosting?.let { put("posting_box", JsonPrimitive(it)) }
            patch.code?.takeIf { it.isNotBlank() }?.let { put("code", JsonPrimitive(it.trim())) }
            if (patch.structureChanged) {
                patch.lineType?.let { put("line_type", JsonPrimitive(it.wire)) }
                put("parent_id", patch.parentId?.let(::JsonPrimitive) ?: JsonNull)
            }
        },
    )

    /** Soft delete, refused on a `status: 0` the way a bank record's delete is. */
    suspend fun deactivateAccount(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$base/$id",
        module = RequestModule.ProjectUser,
    ).flatMap { envelope ->
        if (envelope.status == 0) {
            ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
        } else {
            ZillitResult.Success(Unit)
        }
    }

    /**
     * A chart write, judged by its envelope rather than its HTTP status.
     *
     * A `status: 0` over a 200 is the server refusing, and reading it as success
     * closed the form on a code that was never saved. The row's
     * `_cascaded_descendants` says how far a change of class reached.
     */
    private suspend fun write(verb: HttpVerb, url: String, body: JsonElement): ZillitResult<CoaUpdate> =
        apiClient.envelope(
            verb = verb,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { envelope ->
            if (envelope.status == 0) {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
            } else {
                val row = (envelope.data as? JsonObject)?.let { data ->
                    runCatching { accountHubJson.decodeFromJsonElement(CoaAccountDto.serializer(), data) }.getOrNull()
                }
                ZillitResult.Success(
                    CoaUpdate(
                        account = row?.toDomain() ?: CoaAccount(id = ""),
                        cascadedDescendants = row?.cascadedCount() ?: 0,
                    ),
                )
            }
        }
}
