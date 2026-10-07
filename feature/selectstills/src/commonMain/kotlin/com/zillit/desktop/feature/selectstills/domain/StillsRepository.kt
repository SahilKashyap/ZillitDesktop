package com.zillit.desktop.feature.selectstills.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The Select Stills service (`zillit_selectstills`), `/api/v2/still-kills`.
 *
 * There is no project in the URL: the project and the user come from the
 * session the HTTP client already attaches, so nothing here names either.
 *
 * Rule refusals from the service are **404 / 409 / 422, never 403** — a 403
 * only ever means "tool off" or "no rights", and the app reads a 403 as "no
 * longer a member". So nothing here may be called before the rights gate has
 * confirmed the tool ([StillsViewer.canCall]).
 *
 * Files never go through here: each one is PUT straight to storage with a link
 * from [presignUploads] (see `StillsUploader`).
 */
@Suppress("TooManyFunctions") // One method per service route; grouping them would hide the surface.
interface StillsRepository {

    // -- the reader and the production's settings ------------------------------------

    suspend fun me(): ZillitResult<StillsMe>
    suspend fun settings(): ZillitResult<StillsSettings>

    /** A partial update: scope, the default allowance, the matching numbers. */
    suspend fun updateSettings(
        viewerScope: ViewerScope? = null,
        defaultDiscardLimits: Map<String, Int>? = null,
        thresholds: Thresholds? = null,
    ): ZillitResult<Wrote<StillsSettings>>

    /** The first posting user acknowledges that the tool stores face data. */
    suspend fun acceptNotice(): ZillitResult<Wrote<Unit>>

    /** Project admins only (422 `still_kills_admin_only` otherwise). */
    suspend fun deleteAllFaceData(): ZillitResult<Wrote<Unit>>

    // -- the cast list ---------------------------------------------------------------

    suspend fun members(): ZillitResult<List<Member>>

    /** Project members who can open the tool — only they can be an approver. */
    suspend fun agentCandidates(): ZillitResult<Set<String>>

    suspend fun member(memberId: String): ZillitResult<Member>
    suspend fun createMember(draft: MemberDraft): ZillitResult<Wrote<Member>>

    /** Only the fields that changed; a null is "not touched". */
    suspend fun updateMember(
        memberId: String,
        name: String? = null,
        characterName: String? = null,
        recognition: Recognition? = null,
        approvalRequired: Boolean? = null,
    ): ZillitResult<Wrote<Member>>

    /**
     * 409 `still_kills_member_in_use` while they are named in photos, unless
     * [force]. The count comes from the member's own `photo_count` — the
     * client's error type does not carry a refusal's body.
     */
    suspend fun removeMember(memberId: String, force: Boolean = false): ZillitResult<Wrote<Unit>>

    suspend fun setMemberAgent(memberId: String, agentUserId: String?): ZillitResult<Wrote<Member>>
    suspend fun setMemberLimits(memberId: String, limits: Map<String, Int>): ZillitResult<Wrote<Member>>

    suspend fun presignHeadshots(memberId: String, files: List<UploadDeclaration>): ZillitResult<List<HeadshotLink>>
    suspend fun completeHeadshots(
        memberId: String,
        headshotIds: List<String>,
        force: Boolean = false,
    ): ZillitResult<Wrote<HeadshotsChecked>>
    suspend fun removeHeadshot(memberId: String, headshotId: String): ZillitResult<Wrote<Member>>
    suspend fun eraseMemberFaceData(memberId: String): ZillitResult<Wrote<Member>>

    // -- uploads ---------------------------------------------------------------------

    /** At most 50 files a request; each is answered on its own. */
    suspend fun presignUploads(
        files: List<UploadDeclaration>,
        shootLabel: String,
        batchId: String,
        batchOffset: Int,
        allowDuplicates: Boolean,
        projectId: String?,
    ): ZillitResult<List<UploadLink>>

    /** Which files arrived. Safe to repeat. */
    suspend fun completeUploads(photoIds: List<String>, projectId: String?): ZillitResult<UploadsConfirmed>

    // -- photos ----------------------------------------------------------------------

    suspend fun photos(
        filters: PhotoFilters = PhotoFilters(),
        limit: Int,
        before: String? = null,
        ids: List<String> = emptyList(),
    ): ZillitResult<PhotoPage>

    suspend fun summary(shoot: String = ""): ZillitResult<StillsSummary>
    suspend fun photo(photoId: String): ZillitResult<PhotoAnswer>
    suspend fun originalLink(photoId: String): ZillitResult<OriginalLink>
    suspend fun deletePhoto(photoId: String): ZillitResult<Wrote<Unit>>
    suspend fun restorePhoto(photoId: String): ZillitResult<Wrote<Unit>>
    suspend fun retryPhoto(photoId: String): ZillitResult<Wrote<Unit>>

    // -- faces -----------------------------------------------------------------------

    suspend fun setFace(photoId: String, faceId: String, edit: FaceEdit): ZillitResult<Wrote<PhotoAnswer>>

    /** Add a cast member the camera missed. */
    suspend fun addPerson(photoId: String, memberId: String): ZillitResult<Wrote<PhotoAnswer>>
    suspend fun dismissUnknown(photoId: String): ZillitResult<Wrote<PhotoAnswer>>
    suspend fun findSimilar(photoId: String, faceId: String): ZillitResult<List<SimilarFace>>

    /** Name, or set aside, a set of faces at once. [memberId] null means "not cast". */
    suspend fun applyToFaces(
        items: List<Pair<String, String>>,
        memberId: String?,
    ): ZillitResult<Wrote<List<ApplyResult>>>

    /** An approver's "this is not my client". */
    suspend fun disputeFace(photoId: String, faceId: String): ZillitResult<Wrote<PhotoAnswer>>

    // -- approvals -------------------------------------------------------------------

    suspend fun review(
        tab: ReviewTab,
        member: String? = null,
        limit: Int,
        before: String? = null,
    ): ZillitResult<ReviewPage>

    suspend fun decide(
        photoId: String,
        memberId: String,
        state: Decision,
        note: String = "",
    ): ZillitResult<Wrote<DecisionAnswer>>
}
