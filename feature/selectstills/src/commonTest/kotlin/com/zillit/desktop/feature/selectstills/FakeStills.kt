package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.selectstills.data.StillsFileReader
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
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoAnswer
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoPage
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewPage
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.SimilarFace
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsRepository
import com.zillit.desktop.feature.selectstills.domain.StillsSettings
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.UploadDeclaration
import com.zillit.desktop.feature.selectstills.domain.UploadLink
import com.zillit.desktop.feature.selectstills.domain.UploadsConfirmed
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.feature.selectstills.domain.Wrote

/**
 * A stand-in for the service, driven by the test rather than by a server.
 *
 * Every call is recorded in [calls], so a test can say what the screen asked
 * for rather than only what it ended up showing.
 */
@Suppress("TooManyFunctions", "MaxLineLength") // One override per route; the recorded call lines read best whole.
class FakeStillsRepository : StillsRepository {

    val calls = mutableListOf<String>()

    var me: StillsMe = StillsMe(
        userId = "u1",
        canPost = true,
        scope = ViewerScope.All,
        settings = StillsSettings(attested = true, thresholds = Thresholds(80, 60, 5)),
        storageSupported = true,
        regionSupported = true,
    )
    var meFails = false
    var members: List<Member> = emptyList()
    var gallery: PhotoPage = PhotoPage()
    var galleryFails = false
    var galleryById: Map<String, PhotoPage> = emptyMap()
    var summary: StillsSummary = StillsSummary(total = 0)
    var review: ReviewPage = ReviewPage()
    var photo: Photo? = null
    var decision: DecisionAnswer = DecisionAnswer(null, null)
    var decisionError: ZillitError? = null
    var similar: List<SimilarFace> = emptyList()
    var agentIds: Set<String> = emptySet()
    var presign: List<UploadLink> = emptyList()
    var presignError: ZillitError? = null
    var confirmed: UploadsConfirmed = UploadsConfirmed(emptySet(), emptySet())
    var removeError: ZillitError? = null

    override suspend fun me(): ZillitResult<StillsMe> {
        calls += "me"
        return if (meFails) ZillitResult.Failure(ZillitError.NoConnection("test")) else ZillitResult.Success(me)
    }

    override suspend fun settings() = ZillitResult.Success(me.settings)

    override suspend fun updateSettings(
        viewerScope: ViewerScope?,
        defaultDiscardLimits: Map<String, Int>?,
        thresholds: Thresholds?,
    ): ZillitResult<Wrote<StillsSettings>> {
        calls += "updateSettings(scope=$viewerScope, limits=$defaultDiscardLimits, numbers=$thresholds)"
        val next = me.settings.copy(
            viewerScope = viewerScope ?: me.settings.viewerScope,
            defaultDiscardLimits = defaultDiscardLimits ?: me.settings.defaultDiscardLimits,
            thresholds = thresholds ?: me.settings.thresholds,
        )
        me = me.copy(settings = next)
        return ZillitResult.Success(Wrote(next, "still_kills_settings_saved"))
    }

    override suspend fun acceptNotice(): ZillitResult<Wrote<Unit>> {
        calls += "acceptNotice"
        me = me.copy(settings = me.settings.copy(attested = true))
        return ZillitResult.Success(Wrote(Unit))
    }

    override suspend fun deleteAllFaceData(): ZillitResult<Wrote<Unit>> {
        calls += "deleteAllFaceData"
        return ZillitResult.Success(Wrote(Unit))
    }

    override suspend fun members(): ZillitResult<List<Member>> {
        calls += "members"
        return ZillitResult.Success(members)
    }

    override suspend fun agentCandidates(): ZillitResult<Set<String>> {
        calls += "agentCandidates"
        return ZillitResult.Success(agentIds)
    }

    override suspend fun member(memberId: String): ZillitResult<Member> {
        calls += "member($memberId)"
        return members.firstOrNull { it.id == memberId }
            ?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Http(404, "still_kills_member_not_found"))
    }

    override suspend fun createMember(draft: MemberDraft): ZillitResult<Wrote<Member>> {
        calls += "createMember(${draft.name})"
        val made = Member(id = "new", name = draft.name, approvalRequired = draft.approvalRequired)
        members = members + made
        return ZillitResult.Success(Wrote(made, "still_kills_member_created"))
    }

    override suspend fun updateMember(
        memberId: String,
        name: String?,
        characterName: String?,
        recognition: Recognition?,
        approvalRequired: Boolean?,
    ): ZillitResult<Wrote<Member>> {
        calls += "updateMember($memberId, name=$name, character=$characterName, recognition=$recognition, approval=$approvalRequired)"
        val row = members.first { it.id == memberId }
        return ZillitResult.Success(Wrote(row.copy(name = name ?: row.name)))
    }

    override suspend fun removeMember(memberId: String, force: Boolean): ZillitResult<Wrote<Unit>> {
        calls += "removeMember($memberId, force=$force)"
        removeError?.takeIf { !force }?.let { return ZillitResult.Failure(it) }
        members = members.filterNot { it.id == memberId }
        return ZillitResult.Success(Wrote(Unit))
    }

    override suspend fun setMemberAgent(memberId: String, agentUserId: String?): ZillitResult<Wrote<Member>> {
        calls += "setMemberAgent($memberId, $agentUserId)"
        val row = members.first { it.id == memberId }
        return ZillitResult.Success(Wrote(row.copy(agentUserId = agentUserId)))
    }

    override suspend fun setMemberLimits(memberId: String, limits: Map<String, Int>): ZillitResult<Wrote<Member>> {
        calls += "setMemberLimits($memberId, $limits)"
        val row = members.first { it.id == memberId }
        return ZillitResult.Success(Wrote(row.copy(discardLimits = limits)))
    }

    override suspend fun presignHeadshots(memberId: String, files: List<UploadDeclaration>): ZillitResult<List<HeadshotLink>> =
        ZillitResult.Success(files.mapIndexed { index, file -> HeadshotLink(file.name, "h$index", "https://store/h$index") })

    override suspend fun completeHeadshots(
        memberId: String,
        headshotIds: List<String>,
        force: Boolean,
    ): ZillitResult<Wrote<HeadshotsChecked>> =
        ZillitResult.Success(Wrote(HeadshotsChecked(members.firstOrNull { it.id == memberId }, emptyList())))

    override suspend fun removeHeadshot(memberId: String, headshotId: String): ZillitResult<Wrote<Member>> =
        ZillitResult.Success(Wrote(members.first { it.id == memberId }))

    override suspend fun eraseMemberFaceData(memberId: String): ZillitResult<Wrote<Member>> {
        calls += "eraseMemberFaceData($memberId)"
        return ZillitResult.Success(Wrote(members.first { it.id == memberId }))
    }

    override suspend fun presignUploads(
        files: List<UploadDeclaration>,
        shootLabel: String,
        batchId: String,
        batchOffset: Int,
        allowDuplicates: Boolean,
        projectId: String?,
    ): ZillitResult<List<UploadLink>> {
        calls += "presign(${files.size} files, shoot=$shootLabel, offset=$batchOffset, dupes=$allowDuplicates, project=$projectId)"
        presignError?.let { return ZillitResult.Failure(it) }
        // Each declared file is answered in order; the test sets the answers.
        return ZillitResult.Success(
            files.mapIndexed { index, file ->
                presign.getOrNull(index)?.copy(uniqueId = file.uniqueId)
                    ?: UploadLink(uniqueId = file.uniqueId, photoId = "p$index", url = "https://store/$index")
            },
        )
    }

    override suspend fun completeUploads(photoIds: List<String>, projectId: String?): ZillitResult<UploadsConfirmed> {
        calls += "complete(${photoIds.joinToString(",")}, project=$projectId)"
        return ZillitResult.Success(
            if (confirmed.accepted.isEmpty() && confirmed.missing.isEmpty()) {
                UploadsConfirmed(photoIds.toSet(), emptySet())
            } else {
                confirmed
            },
        )
    }

    override suspend fun photos(
        filters: PhotoFilters,
        limit: Int,
        before: String?,
        ids: List<String>,
    ): ZillitResult<PhotoPage> {
        calls += "photos(kind=${filters.kind}, member=${filters.member}, before=$before, ids=${ids.joinToString(",")}, limit=$limit)"
        if (galleryFails) return ZillitResult.Failure(ZillitError.NoConnection("test"))
        if (ids.isNotEmpty()) return ZillitResult.Success(galleryById[ids.joinToString(",")] ?: PhotoPage())
        return ZillitResult.Success(gallery)
    }

    override suspend fun summary(shoot: String): ZillitResult<StillsSummary> {
        calls += "summary($shoot)"
        return ZillitResult.Success(summary)
    }

    override suspend fun photo(photoId: String): ZillitResult<PhotoAnswer> {
        calls += "photo($photoId)"
        return photo?.takeIf { it.id == photoId }
            ?.let { ZillitResult.Success(PhotoAnswer(it)) }
            ?: ZillitResult.Failure(ZillitError.Http(404, "still_kills_photo_not_found"))
    }

    override suspend fun originalLink(photoId: String): ZillitResult<OriginalLink> {
        calls += "originalLink($photoId)"
        return ZillitResult.Success(OriginalLink("https://store/original", "A001.jpg"))
    }

    override suspend fun deletePhoto(photoId: String): ZillitResult<Wrote<Unit>> {
        calls += "deletePhoto($photoId)"
        return ZillitResult.Success(Wrote(Unit))
    }

    override suspend fun restorePhoto(photoId: String) = ZillitResult.Success(Wrote(Unit))

    override suspend fun retryPhoto(photoId: String): ZillitResult<Wrote<Unit>> {
        calls += "retryPhoto($photoId)"
        return ZillitResult.Success(Wrote(Unit))
    }

    override suspend fun setFace(photoId: String, faceId: String, edit: FaceEdit): ZillitResult<Wrote<PhotoAnswer>> {
        calls += "setFace($photoId, $faceId, $edit)"
        return photo?.let { ZillitResult.Success(Wrote(PhotoAnswer(it))) }
            ?: ZillitResult.Failure(ZillitError.Http(404, "still_kills_photo_not_found"))
    }

    override suspend fun addPerson(photoId: String, memberId: String): ZillitResult<Wrote<PhotoAnswer>> {
        calls += "addPerson($photoId, $memberId)"
        return ZillitResult.Success(Wrote(PhotoAnswer(photo!!)))
    }

    override suspend fun dismissUnknown(photoId: String): ZillitResult<Wrote<PhotoAnswer>> {
        calls += "dismissUnknown($photoId)"
        return ZillitResult.Success(Wrote(PhotoAnswer(photo!!)))
    }

    override suspend fun findSimilar(photoId: String, faceId: String): ZillitResult<List<SimilarFace>> {
        calls += "findSimilar($photoId, $faceId)"
        return ZillitResult.Success(similar)
    }

    override suspend fun applyToFaces(
        items: List<Pair<String, String>>,
        memberId: String?,
    ): ZillitResult<Wrote<List<ApplyResult>>> {
        calls += "applyToFaces(${items.size}, member=$memberId)"
        return ZillitResult.Success(Wrote(items.map { (photoId, faceId) -> ApplyResult(photoId, faceId, ok = true) }))
    }

    override suspend fun disputeFace(photoId: String, faceId: String): ZillitResult<Wrote<PhotoAnswer>> {
        calls += "disputeFace($photoId, $faceId)"
        return ZillitResult.Success(Wrote(PhotoAnswer(photo!!)))
    }

    override suspend fun review(
        tab: ReviewTab,
        member: String?,
        limit: Int,
        before: String?,
    ): ZillitResult<ReviewPage> {
        calls += "review(tab=${tab.wire}, member=$member, limit=$limit, before=$before)"
        return ZillitResult.Success(review)
    }

    override suspend fun decide(
        photoId: String,
        memberId: String,
        state: Decision,
        note: String,
    ): ZillitResult<Wrote<DecisionAnswer>> {
        calls += "decide($photoId, $memberId, ${state.wire}, note=$note)"
        decisionError?.let { return ZillitResult.Failure(it) }
        return ZillitResult.Success(Wrote(decision, "still_kills_decision_saved"))
    }
}

/** A disk that is whatever the test says it is. */
class FakeStillsFiles(
    private val tree: Map<String, List<StillsPick>> = emptyMap(),
    private val bytes: ByteArray = ByteArray(10),
) : StillsFileReader {
    val readPaths = mutableListOf<String>()
    var missing: Set<String> = emptySet()

    override fun exists(path: String): Boolean = path !in missing

    override fun readAll(path: String): ByteArray = bytes

    override suspend fun readInChunks(path: String, chunk: Int, onChunk: suspend (ByteArray, Int) -> Unit) {
        readPaths += path
        onChunk(bytes, bytes.size)
    }

    override fun fingerprint(path: String, size: Long): String = "fp-$path.$size"

    override fun walkPhotos(path: String): List<StillsPick> = tree[path].orEmpty()

    override fun describe(path: String): StillsPick =
        StillsPick(path = path, name = path.substringAfterLast('/'), size = bytes.size.toLong(), type = "image/jpeg")
}
