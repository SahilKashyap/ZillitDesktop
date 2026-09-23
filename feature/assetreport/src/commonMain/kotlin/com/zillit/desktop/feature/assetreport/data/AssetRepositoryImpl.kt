package com.zillit.desktop.feature.assetreport.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `purchase-orders/line-items` and `purchase-orders/asset-register` on the
 * PO host; vendors on the account hub (its own host — the path misleads).
 *
 * Wire rules that cost the clients bugs, kept here on purpose: the feed's
 * `vendor`/`department`/`currency` alias `po_*` and must coalesce; `PATCH /:id`
 * silently drops `comments`; a `list` row is SLIM — no note, no attachments —
 * so it only ever discovers a record's id; dates arrive as epoch ms OR ISO
 * strings; and a 200 answering `status: 0` is a refusal.
 */
class AssetRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : AssetRepository {

    /**
     * Stored attachment objects, as the server sent them, by key. A PATCH
     * re-sends the whole list; sending back the objects it gave keeps any
     * field this client does not model.
     */
    private val storedAttachments = mutableMapOf<String, JsonObject>()
    private val storedLock = Mutex()

    override suspend fun lines(): ZillitResult<List<AssetLine>> =
        read("${po()}line-items").map { data ->
            data.rows().mapNotNull { row -> row.decodeAs(LineDto.serializer())?.toModel() }
        }

    override suspend fun record(line: AssetLine): ZillitResult<AssetRecord?> {
        // The feed carries the record's id; without it the list discovers one —
        // a slim row, so the full record is still read by id before editing.
        val knownId = line.assetId?.takeIf { it.isNotBlank() }
            ?: when (val found = discover(line.lineItemId)) {
                is ZillitResult.Failure -> return found
                is ZillitResult.Success -> found.data ?: return ZillitResult.Success(null)
            }
        return read("${po()}asset-register/$knownId").map { data -> remember(data) }
    }

    override suspend fun create(
        line: AssetLine,
        category: AssetCategory,
        comments: String,
        attachments: List<AssetAttachment>,
    ): ZillitResult<AssetRecord> = write(
        verb = HttpVerb.Post,
        url = "${po()}asset-register",
        body = buildJsonObject {
            put("entity", "purchase_order")
            put("po_id", line.poId)
            put("line_item_id", line.lineItemId)
            put("category", category.wire)
            put("attachments", attachmentsJson(attachments))
            put("comments", comments)
        },
    )

    override suspend fun update(
        assetId: String,
        category: AssetCategory,
        attachments: List<AssetAttachment>,
    ): ZillitResult<AssetRecord> = write(
        verb = HttpVerb.Patch,
        url = "${po()}asset-register/$assetId",
        body = buildJsonObject {
            put("category", category.wire)
            put("attachments", attachmentsJson(attachments))
        },
    )

    override suspend fun updateComment(assetId: String, comments: String): ZillitResult<AssetRecord> = write(
        verb = HttpVerb.Patch,
        url = "${po()}asset-register/$assetId/comment",
        body = buildJsonObject { put("comments", comments) },
    )

    override suspend fun vendors(): ZillitResult<Map<String, String>> =
        read("${config.apiV2(ZillitService.AccountHub)}vendors").map { data ->
            data.rows().mapNotNull { row ->
                val vendor = row.decodeAs(VendorDto.serializer()) ?: return@mapNotNull null
                val id = vendor.id ?: return@mapNotNull null
                val name = vendor.name?.trim().orEmpty().ifBlank { vendor.companyName?.trim().orEmpty() }
                name.takeIf { it.isNotBlank() }?.let { id to it }
            }.toMap()
        }

    private suspend fun discover(lineItemId: String): ZillitResult<String?> =
        read("${po()}asset-register", mapOf("line_item_id" to lineItemId)).map { data ->
            val first = data.rows().firstOrNull() ?: (data as? JsonObject)
            first?.decodeAs(RecordDto.serializer())?.toModel()?.id?.takeIf { it.isNotBlank() }
        }

    private suspend fun read(url: String, query: Map<String, Any?> = emptyMap()): ZillitResult<JsonElement?> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = query,
        ).accepted()

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject): ZillitResult<AssetRecord> =
        when (
            val answered = apiClient.envelope(
                verb = verb,
                url = url,
                module = RequestModule.ProjectUser,
                body = body,
            ).accepted()
        ) {
            is ZillitResult.Failure -> answered
            is ZillitResult.Success -> remember(answered.data)
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Validation(str(S.desktop_asset_register_no_record)))
        }

    /** Decodes a record and keeps its attachment objects for the next write. */
    private suspend fun remember(data: JsonElement?): AssetRecord? {
        val row = data as? JsonObject ?: return null
        (row["attachments"] as? JsonArray)?.forEach { item ->
            val stored = item as? JsonObject ?: return@forEach
            val key = stored.toAttachment()?.media ?: return@forEach
            storedLock.withLock { storedAttachments[key] = stored }
        }
        return row.decodeAs(RecordDto.serializer())?.toModel()
    }

    private suspend fun attachmentsJson(attachments: List<AssetAttachment>): JsonArray {
        val stored = storedLock.withLock { attachments.associate { it.media to storedAttachments[it.media] } }
        return buildJsonArray { attachments.forEach { add(it.toJson(stored[it.media])) } }
    }

    private fun po() = "${config.apiV2(ZillitService.PurchaseOrder)}purchase-orders/"

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** A 200 with `status: 0` is the service saying no; its message is the reason. */
        fun ZillitResult<ApiEnvelope>.accepted(): ZillitResult<JsonElement?> = when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success -> if (data.status == 0) {
                ZillitResult.Failure(
                    ZillitError.Validation(
                        data.message?.takeIf { it.isNotBlank() }?.localisedMessage()
                            ?: str(S.desktop_asset_register_refused),
                    ),
                )
            } else {
                ZillitResult.Success(data.data)
            }
        }

        fun JsonElement?.rows(): List<JsonElement> = (this as? JsonArray).orEmpty()

        fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T? =
            runCatching { json.decodeFromJsonElement(serializer, this) }.getOrNull()
    }
}
