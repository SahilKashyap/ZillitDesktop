package com.zillit.desktop.feature.formsignature.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.formsignature.domain.ChatUnit
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Documents & Signature surface, all on the documents service
 * (`FORMS_BASE_URL`) except the discussion room, transcribed from the web's
 * V2 tool (`api/formSignatureApi/formSignatureApi.js`).
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

    /** The discussion room lives on the unit host, like every other tool's board. */
    private val units = config.apiV2(ZillitService.Units).trimEnd('/')

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

    /** The answer's `message` is what the web pops in its success modal. */
    override suspend fun selfAssign(documentId: String): ZillitResult<String> = written(
        verb = HttpVerb.Post,
        url = "$base/sign-document/general/self-assign",
        body = buildJsonObject { put("document_id", documentId) },
    ).map { envelope -> envelope.message.orEmpty() }

    override suspend fun deleteStandardForm(documentId: String): ZillitResult<Unit> =
        write(
            verb = HttpVerb.Delete,
            url = "$base/sign-document/general-document/$documentId",
            body = null,
        )

    /**
     * `generatePostPayload(isArray = true, …)` — one document in a
     * `documents[]`, `stakeholder: 'internal'` (the web's state default, never
     * changed on this screen), `skip_counter_sign: false`, `pre_signed: false`,
     * an empty `note` (the web's textarea is commented out).
     */
    override suspend fun addStandardForm(
        document: StoredDocument,
        type: StandardFormType,
    ): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = "$base/sign-document/general",
        body = buildJsonObject {
            put(
                "documents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("document_type", type.wire)
                            put("stakeholder", STAKEHOLDER_INTERNAL)
                            put("skip_counter_sign", false)
                            put("pre_signed", false)
                            put("document", document.toWire())
                            put("note", "")
                        },
                    )
                },
            )
        },
    )

    override suspend fun documents(tab: SignDocumentTab): ZillitResult<List<SignDocument>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/document/${tab.route}",
            serializer = ListSerializer(SignDocumentDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun sendForSignature(
        document: StoredDocument,
        signingDocument: StoredDocument?,
        signers: List<DocumentSigner>,
        onlySignatureRequired: Boolean,
        userSignatureRequired: Boolean,
    ): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = "$base/document",
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
            // The sender signed first: their stamped copy rides beside the original.
            signingDocument?.let { put("signing_document", it.toWire()) }
        },
    )

    override suspend fun deleteDocument(documentId: String): ZillitResult<Unit> =
        write(
            verb = HttpVerb.Delete,
            url = "$base/document/$documentId",
            body = null,
        )

    override suspend fun signDocument(
        documentId: String,
        signed: StoredDocument,
    ): ZillitResult<Unit> = write(
        verb = HttpVerb.Put,
        url = "$base/document/$documentId/sign",
        body = buildJsonObject { put("document", signed.toWire()) },
    )

    override suspend fun signStandardForm(
        documentId: String,
        signed: StoredDocument,
    ): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = "$base/sign-document/received-document/sign",
        body = buildJsonObject {
            put("document_id", documentId)
            put("document", signed.toWire())
        },
    )

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
    ): ZillitResult<Unit> = write(
        verb = HttpVerb.Post,
        url = "$base/signature",
        body = buildJsonObject {
            existingId?.let { put("signature_id", it) }
            // The typed name overwrites the file name on the descriptor —
            // the web does exactly this before posting.
            put("signature", image.copy(name = name).toSignatureWire())
            put("is_signature", isSignature)
        },
    )

    override suspend fun deleteSignature(signatureId: String): ZillitResult<Unit> =
        write(
            verb = HttpVerb.Delete,
            url = "$base/signature/$signatureId",
            body = null,
        )

    override suspend fun signerOptions(): ZillitResult<List<SignerOption>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/sign-document/document/users",
        serializer = ListSerializer(SignerOptionDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * `GET form-signature/unit` on the unit host answers a one-element array
     * (the web reads `data[0]`). Tolerant of a bare object too.
     */
    override suspend fun chatUnit(): ZillitResult<ChatUnit?> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$units/form-signature/unit",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { element ->
        val row = when (element) {
            is kotlinx.serialization.json.JsonArray -> element.firstOrNull()
            is kotlinx.serialization.json.JsonObject -> element
            else -> null
        } ?: return@map null
        runCatching { json.decodeFromJsonElement(ChatUnitDto.serializer(), row.jsonObject) }
            .getOrNull()?.toDomain()
    }

    /**
     * A write, judged by the envelope's `status`: this service answers `200`
     * with `status: 0` and a `message` for every refusal, and `request()`
     * would read that as success (the whole family does this; see the
     * account-hub notes). The message is the user-facing one the web pops.
     */
    private suspend fun written(
        verb: HttpVerb,
        url: String,
        body: JsonElement?,
    ): ZillitResult<ApiEnvelope> = when (
        val answer = apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body)
    ) {
        is ZillitResult.Failure -> answer
        is ZillitResult.Success -> if (answer.data.status == STATUS_OK) {
            answer
        } else {
            ZillitResult.Failure(
                ZillitError.Validation(answer.data.message?.ifBlank { null } ?: "The request was refused."),
            )
        }
    }

    private suspend fun write(verb: HttpVerb, url: String, body: JsonElement?): ZillitResult<Unit> =
        written(verb, url, body).map { }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private companion object {
        const val STAKEHOLDER_INTERNAL = "internal"
        const val STATUS_OK = 1

        /** The list routes under `document`, per tab. */
        val SignDocumentTab.route: String
            get() = when (this) {
                SignDocumentTab.Uploaded -> "uploaded"
                SignDocumentTab.Received -> "received"
                SignDocumentTab.Finalized -> "finalized"
            }
    }
}
