package com.zillit.desktop.feature.formsignature.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.HistoryEntry
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Documents & Signature surface, all on the documents service
 * (`FORMS_BASE_URL`), transcribed from the web's V2 tool.
 *
 * ## Signing sends a file, not marks
 *
 * Both sign routes take a **document descriptor** — the flattened PDF this
 * client already stamped and uploaded. No signature id, no coordinates, no
 * image travel on the sign call; the coordinates only ever travel the other
 * way, when the sender places them. Getting this wrong looks like success:
 * the server stores whatever document it is handed.
 *
 * ## One POST for the signature block, create and update alike
 *
 * The web's add and update are byte-identical POSTs to the same route. There
 * is no PATCH; `signature_id` present is what makes it an update.
 */
class FormSignatureRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
) : FormSignatureRepository {

    // `api/v2`, not `v2`: the documents service routes under the `/api`
    // prefix (bare `/v2/...` answers `route_not_found`) — verified against
    // develop, 2026-08-13.
    private val base = config.apiV2(ZillitService.Forms).trimEnd('/')

    /**
     * See [FormSignatureRepository.refreshes]. `signed`/`counter:signed`
     * fan out to both kinds, as both web lists refetch on them. Conflated:
     * a signing emits several of these back to back and one refetch per
     * list answers all.
     */
    override val refreshes: Flow<FormSignRefresh> =
        bus?.onAny(FORM_SIGN_SYNC_EVENTS)
            ?.transform { message -> refreshKindsFor(message.event).forEach { emit(it) } }
            ?.conflate()
            ?: emptyFlow()

    override suspend fun standardForms(selfAssigned: Boolean): ZillitResult<List<StandardForm>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = if (selfAssigned) "$base/sign-document/general/self-assign" else "$base/sign-document/general",
            serializer = ListSerializer(StandardFormDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun selfAssign(documentId: String): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/sign-document/general/self-assign",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("document_id", documentId) },
    ).map { }

    override suspend fun deleteStandardForm(documentId: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "$base/sign-document/general-document/$documentId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    override suspend fun addStandardForm(
        document: StoredDocument,
        type: StandardFormType,
        note: String,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/sign-document/general",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put(
                "documents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("document_type", type.wire)
                            put("stakeholder", "")
                            put("skip_counter_sign", false)
                            put("pre_signed", false)
                            put("document", document.toWire())
                            put("note", note)
                        },
                    )
                },
            )
        },
    ).map { }

    /**
     * History arrives in more than one shape across this backend family —
     * sometimes a bare array, sometimes an object holding one. Parsed
     * tolerantly: whatever list can be found is rendered, and an
     * unrecognisable payload is an empty history, not an error page.
     */
    override suspend fun history(documentId: String): ZillitResult<List<HistoryEntry>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/sign-document/sent-document/$documentId/history",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { element -> historyEntries(element) }

    private fun historyEntries(element: JsonElement): List<HistoryEntry> {
        val array: JsonArray = when {
            element is JsonArray -> element
            element is kotlinx.serialization.json.JsonObject -> {
                element.values.firstOrNull { it is JsonArray }?.jsonArray ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            runCatching {
                json.decodeFromJsonElement(HistoryEntryDto.serializer(), row.jsonObject)
            }.getOrNull()?.toDomain()
        }
    }

    override suspend fun documents(tab: SignDocumentTab): ZillitResult<List<SignDocument>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/document/${tab.route}",
            serializer = ListSerializer(SignDocumentDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun sendForSignature(
        document: StoredDocument,
        signers: List<DocumentSigner>,
        onlySignatureRequired: Boolean,
        userSignatureRequired: Boolean,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/document",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("document", document.toWire())
            // The union of every signer's boxes, as the web sends it.
            put(
                "document_coordinates",
                buildJsonArray { signers.flatMap { it.spots }.forEach { add(it.toWire()) } },
            )
            put(
                "users",
                buildJsonArray {
                    signers.forEach { signer ->
                        add(
                            buildJsonObject {
                                put("user_id", signer.userId)
                                put("user_email", signer.email)
                                put(
                                    "document_coordinates",
                                    buildJsonArray { signer.spots.forEach { add(it.toWire()) } },
                                )
                                put("order_of_signing", signer.order)
                                if (signer.isExternal) put("is_external", true)
                                if (signer.fullName.isNotBlank()) {
                                    put("user_fullname", signer.fullName)
                                }
                            },
                        )
                    }
                },
            )
            put("only_signature_required", onlySignatureRequired)
            put("user_signature_required", userSignatureRequired)
        },
    ).map { }

    /**
     * `POST /v2/sign-document/send-document` with the *whole* signer list.
     *
     * The route's name says "send", but on an existing document it is how the
     * signer list is edited — adding one means posting everyone.
     */
    override suspend fun updateSigners(
        documentId: String,
        signerIds: List<String>,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/sign-document/send-document",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("document_id", JsonPrimitive(documentId))
            put(
                "signers",
                buildJsonArray { signerIds.distinct().forEach { add(JsonPrimitive(it)) } },
            )
        },
    ).map { }

    override suspend fun deleteDocument(documentId: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "$base/document/$documentId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    override suspend fun signDocument(
        documentId: String,
        signed: StoredDocument,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Put,
        url = "$base/document/$documentId/sign",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("document", signed.toWire()) },
    ).map { }

    override suspend fun signStandardForm(
        documentId: String,
        signed: StoredDocument,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/sign-document/received-document/sign",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("document_id", documentId)
            put("document", signed.toWire())
        },
    ).map { }

    override suspend fun signatures(): ZillitResult<List<SignatureBlock>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/signature",
        serializer = ListSerializer(SignatureBlockDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun saveSignature(
        image: StoredDocument,
        name: String,
        isSignature: Boolean,
        existingId: String?,
    ): ZillitResult<Unit> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/signature",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            existingId?.let { put("signature_id", it) }
            // The typed name overwrites the file name on the descriptor —
            // the web does exactly this before posting.
            put("signature", image.copy(name = name).toSignatureWire())
            put("is_signature", isSignature)
        },
    ).map { }

    override suspend fun deleteSignature(signatureId: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "$base/signature/$signatureId",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { }

    override suspend fun signerOptions(): ZillitResult<List<SignerOption>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/sign-document/document/users",
        serializer = ListSerializer(SignerOptionDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private companion object {
        /** The list routes under `document`, per tab. */
        val SignDocumentTab.route: String
            get() = when (this) {
                SignDocumentTab.Uploaded -> "uploaded"
                SignDocumentTab.Received -> "received"
                SignDocumentTab.Finalized -> "finalized"
            }
    }
}
