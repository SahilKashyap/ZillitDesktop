@file:Suppress("TooManyFunctions", "MaxLineLength") // Wire: one method per route.

package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.selectstills.domain.ApplyResult
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.DecisionAnswer
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.HeadshotLink
import com.zillit.desktop.feature.selectstills.domain.HeadshotsChecked
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.MemberDraft
import com.zillit.desktop.feature.selectstills.domain.OriginalLink
import com.zillit.desktop.feature.selectstills.domain.PhotoAnswer
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoPage
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewPage
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.SimilarFace
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsRepository
import com.zillit.desktop.feature.selectstills.domain.StillsSettings
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.UploadDeclaration
import com.zillit.desktop.feature.selectstills.domain.UploadLink
import com.zillit.desktop.feature.selectstills.domain.UploadsConfirmed
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.domain.Wrote
import com.zillit.desktop.feature.selectstills.domain.toQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `zillit_selectstills`. Every call is project-scoped, so every call rides
 * [RequestModule.ProjectUser].
 *
 * `config.apiV2(StillKills)` ends at `/api/v2/`; this adds `still-kills`.
 * There is no project in the URL: the project and user come from the session
 * the client attaches, so nothing here sets a header.
 *
 * Rule refusals from the service are **404 / 409 / 422, never 403** (a discard
 * with no allowance left, a member still named in photos, a headshot with two
 * faces in it): a 403 anywhere in this app is read as "no longer a member".
 * The only 403 is `still_kills_tool_not_enabled`, which is why nothing calls
 * until the tool's rights row has confirmed it — see
 * [com.zillit.desktop.feature.selectstills.domain.StillsViewer.canCall].
 */
class StillsRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : StillsRepository {

    /**
     * Null when this environment has no Select Stills host configured.
     * [AppConfig.apiV2] throws for a missing service, and a throw inside a
     * view model's coroutine would end the app — so the absence is carried
     * here and answered as a failure by [call], which every request goes
     * through.
     */
    private val root: String?
        get() = runCatching { config.apiV2(ZillitService.StillKills).trimEnd('/') + "/still-kills" }.getOrNull()

    // -- the reader and the production's settings ------------------------------------

    override suspend fun me(): ZillitResult<StillsMe> =
        call(HttpVerb.Get, "/me").map { it.toMe() }

    override suspend fun settings(): ZillitResult<StillsSettings> =
        call(HttpVerb.Get, "/settings").map { it.toSettings() }

    override suspend fun updateSettings(
        viewerScope: ViewerScope?,
        defaultDiscardLimits: Map<String, Int>?,
        thresholds: Thresholds?,
    ): ZillitResult<Wrote<StillsSettings>> {
        val body = buildJsonObject {
            viewerScope?.let { put("viewer_scope", it.wire) }
            defaultDiscardLimits?.let { put("default_discard_limits", it.asLimits()) }
            thresholds?.let {
                put(
                    "thresholds",
                    buildJsonObject {
                        put("auto", it.auto)
                        put("suggest", it.suggest)
                        put("margin", it.margin)
                    },
                )
            }
        }
        return call(HttpVerb.Put, "/settings", body = body).wrote { it.toSettings() }
    }

    override suspend fun acceptNotice(): ZillitResult<Wrote<Unit>> =
        call(HttpVerb.Post, "/settings/attestation", body = JsonObject(emptyMap())).wrote { }

    override suspend fun deleteAllFaceData(): ZillitResult<Wrote<Unit>> =
        call(HttpVerb.Delete, "/settings/face-data").wrote { }

    // -- the cast list ---------------------------------------------------------------

    override suspend fun members(): ZillitResult<List<Member>> =
        call(HttpVerb.Get, "/members").map { data ->
            data.obj()?.get("members").arr().mapNotNull { it.toMember() }
        }

    override suspend fun agentCandidates(): ZillitResult<Set<String>> =
        call(HttpVerb.Get, "/members/agent-candidates").map { it.obj().strings("user_ids").toSet() }

    override suspend fun member(memberId: String): ZillitResult<Member> =
        call(HttpVerb.Get, "/members/$memberId").mapMember()

    override suspend fun createMember(draft: MemberDraft): ZillitResult<Wrote<Member>> {
        val body = buildJsonObject {
            put("name", draft.name.trim())
            put("character_name", draft.characterName.trim())
            put("approval_required", draft.approvalRequired)
            // A null rather than a blank: the service reads an empty string as a malformed id.
            if (draft.approvalRequired && !draft.agentUserId.isNullOrBlank()) {
                put("agent_user_id", draft.agentUserId)
            } else {
                put("agent_user_id", JsonNull)
            }
            put("recognition", draft.recognition.wire)
            put("consent", draft.consent)
            // Only sent when somebody has to approve: there is no allowance to spend otherwise.
            if (draft.approvalRequired) put("discard_limits", draft.discardLimits.asLimits())
        }
        return call(HttpVerb.Post, "/members", body = body).wroteMember()
    }

    override suspend fun updateMember(
        memberId: String,
        name: String?,
        characterName: String?,
        recognition: Recognition?,
        approvalRequired: Boolean?,
    ): ZillitResult<Wrote<Member>> {
        val body = buildJsonObject {
            name?.let { put("name", it) }
            characterName?.let { put("character_name", it) }
            recognition?.let { put("recognition", it.wire) }
            approvalRequired?.let { put("approval_required", it) }
        }
        return call(HttpVerb.Put, "/members/$memberId", body = body).wroteMember()
    }

    override suspend fun removeMember(memberId: String, force: Boolean): ZillitResult<Wrote<Unit>> =
        call(
            HttpVerb.Delete,
            "/members/$memberId",
            query = if (force) mapOf("force" to true) else emptyMap(),
        ).wrote { }

    override suspend fun setMemberAgent(memberId: String, agentUserId: String?): ZillitResult<Wrote<Member>> {
        val body = buildJsonObject {
            if (agentUserId.isNullOrBlank()) put("agent_user_id", JsonNull) else put("agent_user_id", agentUserId)
        }
        return call(HttpVerb.Put, "/members/$memberId/agent", body = body).wroteMember()
    }

    override suspend fun setMemberLimits(memberId: String, limits: Map<String, Int>): ZillitResult<Wrote<Member>> {
        val body = buildJsonObject { put("discard_limits", limits.asLimits()) }
        return call(HttpVerb.Put, "/members/$memberId/discard-limits", body = body).wroteMember()
    }

    override suspend fun presignHeadshots(memberId: String, files: List<UploadDeclaration>): ZillitResult<List<HeadshotLink>> {
        val body = buildJsonObject {
            put(
                "files",
                buildJsonArray {
                    files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.name)
                                put("type", file.type)
                                put("size", file.size)
                            },
                        )
                    }
                },
            )
        }
        return call(HttpVerb.Post, "/members/$memberId/headshots/presign", body = body).map { it.toHeadshotLinks() }
    }

    override suspend fun completeHeadshots(memberId: String, headshotIds: List<String>, force: Boolean): ZillitResult<Wrote<HeadshotsChecked>> {
        val body = buildJsonObject {
            put("headshot_ids", JsonArray(headshotIds.map(::JsonPrimitive)))
            put("force", force)
        }
        return call(HttpVerb.Post, "/members/$memberId/headshots/complete", body = body).wrote { it.toHeadshotsChecked() }
    }

    override suspend fun removeHeadshot(memberId: String, headshotId: String): ZillitResult<Wrote<Member>> =
        call(HttpVerb.Delete, "/members/$memberId/headshots/$headshotId").wroteMember()

    override suspend fun eraseMemberFaceData(memberId: String): ZillitResult<Wrote<Member>> =
        call(HttpVerb.Post, "/members/$memberId/erase-face-data", body = JsonObject(emptyMap())).wroteMember()

    // -- uploads ---------------------------------------------------------------------

    override suspend fun presignUploads(
        files: List<UploadDeclaration>,
        shootLabel: String,
        batchId: String,
        batchOffset: Int,
        allowDuplicates: Boolean,
        projectId: String?,
    ): ZillitResult<List<UploadLink>> {
        val body = buildJsonObject {
            put(
                "files",
                buildJsonArray {
                    files.forEach { file ->
                        add(
                            buildJsonObject {
                                put("unique_id", file.uniqueId)
                                put("name", file.name)
                                put("type", file.type)
                                put("size", file.size)
                                if (file.fingerprint.isNotBlank()) put("fingerprint", file.fingerprint)
                            },
                        )
                    }
                },
            )
            put("shoot_label", shootLabel)
            put("batch_id", batchId)
            put("batch_offset", batchOffset)
            put("allow_duplicates", allowDuplicates)
        }
        return call(HttpVerb.Post, "/uploads/presign", body = body, projectId = projectId).map { it.toUploadLinks() }
    }

    override suspend fun completeUploads(photoIds: List<String>, projectId: String?): ZillitResult<UploadsConfirmed> {
        val body = buildJsonObject { put("photo_ids", JsonArray(photoIds.map(::JsonPrimitive))) }
        return call(HttpVerb.Post, "/uploads/complete", body = body, projectId = projectId).map { it.toUploadsConfirmed() }
    }

    // -- photos ----------------------------------------------------------------------

    override suspend fun photos(
        filters: PhotoFilters,
        limit: Int,
        before: String?,
        ids: List<String>,
    ): ZillitResult<PhotoPage> {
        val query = filters.toQuery().toMutableMap()
        query["limit"] = limit
        before?.let { query["before"] = it }
        if (ids.isNotEmpty()) query["ids"] = ids.joinToString(",")
        return call(HttpVerb.Get, "/photos", query = query).map { it.toPhotoPage() }
    }

    override suspend fun summary(shoot: String): ZillitResult<StillsSummary> =
        call(
            HttpVerb.Get,
            "/summary",
            query = shoot.trim().takeIf { it.isNotEmpty() }?.let { mapOf("shoot" to it) } ?: emptyMap(),
        ).map { it.toSummary() }

    override suspend fun photo(photoId: String): ZillitResult<PhotoAnswer> =
        call(HttpVerb.Get, "/photos/$photoId").mapPhoto()

    override suspend fun originalLink(photoId: String): ZillitResult<OriginalLink> =
        call(HttpVerb.Get, "/photos/$photoId/original").mapNotNull("the original answer carried no link") { it.toOriginalLink() }

    override suspend fun deletePhoto(photoId: String): ZillitResult<Wrote<Unit>> =
        call(HttpVerb.Delete, "/photos/$photoId").wrote { }

    override suspend fun restorePhoto(photoId: String): ZillitResult<Wrote<Unit>> =
        call(HttpVerb.Post, "/photos/$photoId/restore", body = JsonObject(emptyMap())).wrote { }

    override suspend fun retryPhoto(photoId: String): ZillitResult<Wrote<Unit>> =
        call(HttpVerb.Post, "/photos/$photoId/retry", body = JsonObject(emptyMap())).wrote { }

    // -- faces -----------------------------------------------------------------------

    override suspend fun setFace(photoId: String, faceId: String, edit: FaceEdit): ZillitResult<Wrote<PhotoAnswer>> {
        // Exactly one of the three, as the service requires.
        val body = buildJsonObject {
            when (edit) {
                is FaceEdit.Name -> put("member_id", edit.memberId)
                FaceEdit.NotCast -> put("dismiss", true)
                FaceEdit.Clear -> put("clear", true)
            }
        }
        return call(HttpVerb.Put, "/photos/$photoId/faces/$faceId", body = body).wrotePhoto()
    }

    override suspend fun addPerson(photoId: String, memberId: String): ZillitResult<Wrote<PhotoAnswer>> {
        val body = buildJsonObject { put("member_id", memberId) }
        return call(HttpVerb.Post, "/photos/$photoId/faces", body = body).wrotePhoto()
    }

    override suspend fun dismissUnknown(photoId: String): ZillitResult<Wrote<PhotoAnswer>> =
        call(HttpVerb.Post, "/photos/$photoId/faces/dismiss-unknown", body = JsonObject(emptyMap())).wrotePhoto()

    override suspend fun findSimilar(photoId: String, faceId: String): ZillitResult<List<SimilarFace>> =
        call(HttpVerb.Post, "/photos/$photoId/faces/$faceId/find-similar", body = JsonObject(emptyMap()))
            .map { it.toSimilarFaces() }

    override suspend fun applyToFaces(items: List<Pair<String, String>>, memberId: String?): ZillitResult<Wrote<List<ApplyResult>>> {
        val body = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    items.forEach { (photoId, faceId) ->
                        add(
                            buildJsonObject {
                                put("photo_id", photoId)
                                put("face_id", faceId)
                            },
                        )
                    }
                },
            )
            if (memberId.isNullOrBlank()) put("dismiss", true) else put("member_id", memberId)
        }
        return call(HttpVerb.Post, "/faces/apply", body = body).wrote { it.toApplyResults() }
    }

    override suspend fun disputeFace(photoId: String, faceId: String): ZillitResult<Wrote<PhotoAnswer>> =
        call(HttpVerb.Post, "/photos/$photoId/faces/$faceId/dispute", body = JsonObject(emptyMap())).wrotePhoto()

    // -- approvals -------------------------------------------------------------------

    override suspend fun review(tab: ReviewTab, member: String?, limit: Int, before: String?): ZillitResult<ReviewPage> {
        val query = mutableMapOf<String, Any?>("tab" to tab.wire, "limit" to limit)
        member?.takeIf { it.isNotBlank() }?.let { query["member"] = it }
        before?.let { query["before"] = it }
        return call(HttpVerb.Get, "/review", query = query).map { it.toReviewPage() }
    }

    override suspend fun decide(photoId: String, memberId: String, state: Decision, note: String): ZillitResult<Wrote<DecisionAnswer>> {
        val body = buildJsonObject {
            put(
                "decisions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("member_id", memberId)
                            put("state", state.wire)
                            if (note.isNotBlank()) put("note", note)
                        },
                    )
                },
            )
        }
        return call(HttpVerb.Put, "/photos/$photoId/decisions", body = body).wrote { data ->
            val root = data.obj()
            DecisionAnswer(
                // `photo` present but null means "no longer yours to see" — the
                // lightbox leaves it. Absent means the answer said nothing.
                photo = if (root != null && root.containsKey("photo")) root["photo"].toPhoto() else null,
                allowances = (root?.get("allowances") as? JsonArray)?.mapNotNull { it.toClient() },
            )
        }
    }

    // -- plumbing --------------------------------------------------------------------

    private fun Map<String, Int>.asLimits(): JsonObject =
        JsonObject(mapValues { (_, value) -> JsonPrimitive(value) })

    /** What a call answered: its `data`, and the service's own words about it. */
    private class Answered(val data: JsonElement?, val message: String?)

    private suspend fun call(
        verb: HttpVerb,
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
        projectId: String? = null,
    ): ZillitResult<Answered> {
        val base = root ?: return ZillitResult.Failure(ZillitError.Validation(NOT_CONFIGURED))
        val answer = apiClient.envelope(
            verb = verb,
            url = base + path,
            module = RequestModule.ProjectUser,
            body = body,
            queryParameters = query,
            // The upload queue outlives the screen, and the client reads the
            // CURRENT production at call time otherwise: a still must never
            // land in the production next door.
            options = if (projectId.isNullOrBlank()) CallOptions() else CallOptions(projectId = projectId),
        )
        return when (answer) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.asData()
        }
    }

    /**
     * A 200 whose envelope says `status: 0` is a refusal, not a success — see
     * the estate's envelope rule. Its message key is what the toast shows.
     */
    private fun ApiEnvelope.asData(): ZillitResult<Answered> = if (status == 1) {
        ZillitResult.Success(Answered(data, message))
    } else {
        ZillitResult.Failure(ZillitError.Http(status = OK, serverMessage = message, messageElements = messageElements.orEmpty()))
    }

    private inline fun <T> ZillitResult<Answered>.map(transform: (JsonElement?) -> T): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> ZillitResult.Success(transform(data.data))
    }

    /** As [map], keeping the service's words for the toast. */
    private inline fun <T> ZillitResult<Answered>.wrote(transform: (JsonElement?) -> T): ZillitResult<Wrote<T>> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> ZillitResult.Success(Wrote(transform(data.data), data.message))
    }

    private inline fun <T : Any> ZillitResult<Answered>.mapNotNull(
        complaint: String,
        transform: (JsonElement?) -> T?,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> transform(data.data)?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization(complaint))
    }

    private inline fun <T : Any> ZillitResult<Answered>.wroteNotNull(
        complaint: String,
        transform: (JsonElement?) -> T?,
    ): ZillitResult<Wrote<T>> = when (this) {
        is ZillitResult.Failure -> ZillitResult.Failure(error)
        is ZillitResult.Success -> transform(data.data)?.let { ZillitResult.Success(Wrote(it, data.message)) }
            ?: ZillitResult.Failure(ZillitError.Serialization(complaint))
    }

    private fun ZillitResult<Answered>.mapMember(): ZillitResult<Member> =
        mapNotNull("the answer carried no member") { it.toMember() }

    private fun ZillitResult<Answered>.wroteMember(): ZillitResult<Wrote<Member>> =
        wroteNotNull("the answer carried no member") { it.toMember() }

    private fun ZillitResult<Answered>.mapPhoto(): ZillitResult<PhotoAnswer> =
        mapNotNull("the answer carried no photo") { it.toPhotoAnswer() }

    private fun ZillitResult<Answered>.wrotePhoto(): ZillitResult<Wrote<PhotoAnswer>> =
        wroteNotNull("the answer carried no photo") { it.toPhotoAnswer() }

    private companion object {
        const val OK = 200
        const val NOT_CONFIGURED = "The Select Stills service is not set up for this environment."
    }
}

/** The HTTP status a failure carried, or null for a transport failure. */
val ZillitError.stillsHttpStatus: Int?
    get() = when (this) {
        is ZillitError.Http -> status
        is ZillitError.Unauthorized -> 401
        is ZillitError.Forbidden -> 403
        else -> null
    }

/** The server's own message key on a refusal, or null when the client diagnosed it. */
val ZillitError.stillsMessageKey: String?
    get() = (this as? ZillitError.Http)?.serverMessage?.takeIf { it.isNotBlank() }
