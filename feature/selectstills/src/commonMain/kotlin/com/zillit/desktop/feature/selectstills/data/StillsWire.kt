@file:Suppress("TooManyFunctions") // One reader per wire shape; see the file note.

package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.feature.selectstills.domain.ApplyResult
import com.zillit.desktop.feature.selectstills.domain.ApprovalRow
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.Face
import com.zillit.desktop.feature.selectstills.domain.FaceBox
import com.zillit.desktop.feature.selectstills.domain.FaceCandidate
import com.zillit.desktop.feature.selectstills.domain.FaceState
import com.zillit.desktop.feature.selectstills.domain.Headshot
import com.zillit.desktop.feature.selectstills.domain.HeadshotLink
import com.zillit.desktop.feature.selectstills.domain.HeadshotVerdict
import com.zillit.desktop.feature.selectstills.domain.HeadshotsChecked
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.OriginalLink
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoAnswer
import com.zillit.desktop.feature.selectstills.domain.PhotoPage
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewCounts
import com.zillit.desktop.feature.selectstills.domain.ReviewPage
import com.zillit.desktop.feature.selectstills.domain.SectionAllowance
import com.zillit.desktop.feature.selectstills.domain.SimilarFace
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsSettings
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.SummaryMember
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.UploadLimits
import com.zillit.desktop.feature.selectstills.domain.UploadLink
import com.zillit.desktop.feature.selectstills.domain.UploadsConfirmed
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The service's JSON → the module's models.
 *
 * Read by hand off [JsonElement] rather than through `@Serializable` DTOs,
 * because a photo answer is deeply optional in every field and the shapes
 * differ between the gallery, the review queue and a single photo — one reader
 * per shape says plainly what each one is allowed to leave out.
 *
 * Nothing here throws. A field the service renames comes back as its default,
 * which is the behaviour the rest of the estate's DTOs have (nullable strings
 * everywhere) — a tool that crashed on an added field would be worse than a
 * tool missing one.
 */
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()

internal fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject?.strOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject?.int(key: String, fallback: Int = 0): Int =
    (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() } ?: fallback

internal fun JsonObject?.intOrNull(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

internal fun JsonObject?.long(key: String, fallback: Long = 0): Long =
    (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: fallback

internal fun JsonObject?.float(key: String): Float =
    (this?.get(key) as? JsonPrimitive)?.let { it.doubleOrNull?.toFloat() ?: it.contentOrNull?.toFloatOrNull() } ?: 0f

/** A boolean the service may send as `true`, `"true"` or `1`. */
internal fun JsonObject?.bool(key: String, fallback: Boolean = false): Boolean {
    val raw = this?.get(key) as? JsonPrimitive ?: return fallback
    raw.booleanOrNull?.let { return it }
    return when (raw.contentOrNull?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> fallback
    }
}

internal fun JsonObject?.strings(key: String): List<String> =
    this?.get(key).arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** `{ "1": 2, "3": 0 }` — a section left out is uncapped, so it is left out here too. */
internal fun JsonObject?.limits(key: String): Map<String, Int> =
    this?.get(key).obj().orEmpty()
        .mapNotNull { (section, value) ->
            val n = (value as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
            n?.let { section to it }
        }
        .toMap()

/** `{ "1": { limit, used, remaining } }` for all six sections. */
internal fun JsonObject?.allowance(key: String = "allowance"): Map<String, SectionAllowance> =
    this?.get(key).obj().orEmpty()
        .mapNotNull { (section, value) ->
            val row = value.obj() ?: return@mapNotNull null
            section to SectionAllowance(
                limit = row.intOrNull("limit"),
                used = row.int("used"),
                remaining = row.intOrNull("remaining"),
            )
        }
        .toMap()

internal fun JsonObject?.headers(key: String = "headers"): Map<String, String> =
    this?.get(key).obj().orEmpty()
        .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
        .toMap()

// -- the reader ----------------------------------------------------------------------

internal fun JsonElement?.toMe(): StillsMe {
    val root = obj()
    return StillsMe(
        userId = root.strOrNull("user_id"),
        canPost = root.bool("can_post"),
        isAdmin = root.bool("is_admin"),
        scope = ViewerScope.of(root.str("scope")),
        agentFor = root?.get("agent_for").arr().mapNotNull { it.toClient() },
        settings = root?.get("settings").toSettings(),
        storageSupported = root.bool("storage_supported"),
        regionSupported = root.bool("region_supported"),
        upload = root?.get("upload").obj().let { upload ->
            UploadLimits(types = upload.strings("types"), maxBytes = upload.long("max_bytes"))
        },
        queueWaiting = root?.get("queue").obj().int("waiting"),
    )
}

internal fun JsonElement?.toClient(): Client? {
    val row = obj() ?: return null
    val id = row.strOrNull("member_id") ?: return null
    return Client(memberId = id, name = row.str("name"), allowance = row.allowance())
}

internal fun JsonElement?.toSettings(): StillsSettings {
    val row = obj()
    val numbers = row?.get("thresholds").obj()
    return StillsSettings(
        attested = row.bool("attested"),
        viewerScope = ViewerScope.of(row.str("viewer_scope")),
        defaultDiscardLimits = row.limits("default_discard_limits"),
        thresholds = Thresholds(
            auto = numbers.int("auto"),
            suggest = numbers.int("suggest"),
            margin = numbers.int("margin"),
        ),
    )
}

// -- the cast ------------------------------------------------------------------------

internal fun JsonElement?.toMember(): Member? {
    val row = obj() ?: return null
    val id = row.strOrNull("id") ?: row.strOrNull("member_id") ?: return null
    val shots = row["headshots"].arr().mapNotNull { shot ->
        val item = shot.obj() ?: return@mapNotNull null
        item.strOrNull("id")?.let { Headshot(it, item.str("url")) }
    }
    return Member(
        id = id,
        name = row.str("name"),
        characterName = row.str("character_name"),
        headshots = shots,
        headshotUrl = row.strOrNull("headshot_url") ?: shots.firstOrNull()?.url.orEmpty(),
        agentUserId = row.strOrNull("agent_user_id"),
        approvalRequired = row.bool("approval_required", fallback = true),
        discardLimits = row.limits("discard_limits"),
        allowance = row.allowance(),
        recognition = Recognition.of(row.str("recognition")),
        photoCount = row.int("photo_count"),
        isMyClient = row.bool("is_my_client"),
        createdMillis = row.long("created"),
        learnedFaces = row.int("learned_faces"),
    )
}

// -- photos --------------------------------------------------------------------------

internal fun JsonElement?.toTile(): PhotoTile? {
    val row = obj() ?: return null
    val id = row.strOrNull("id") ?: return null
    return PhotoTile(
        id = id,
        sortAt = row.long("sort_at"),
        rev = row.long("rev"),
        status = PhotoStatus.of(row.str("status")),
        thumbUrl = row.str("thumb_url"),
        originalName = row.str("original_name"),
        publicState = PublicState.of(row.str("public_state")),
        hasApprovals = row.bool("has_approvals"),
        people = row.int("people"),
        names = row.strings("names"),
        moreNames = row.int("more_names"),
        unnamed = row.int("unnamed"),
        errorCode = row.str("error_code"),
        section = row.strOrNull("section"),
        discardIsFree = row.bool("discard_is_free"),
        myRows = row["my_rows"].arr().mapNotNull { it.toApprovalRow() },
        others = row["others"].arr().mapNotNull { it.toApprovalRow() },
    )
}

internal fun JsonElement?.toApprovalRow(): ApprovalRow? {
    val row = obj() ?: return null
    val id = row.strOrNull("member_id") ?: return null
    return ApprovalRow(
        memberId = id,
        name = row.str("name"),
        state = Decision.of(row.str("state")),
        note = row.str("note"),
        canDecide = row.bool("can_decide"),
        agentUserId = row.strOrNull("agent_user_id"),
        hasAgent = row.bool("has_agent", fallback = true),
    )
}

internal fun JsonElement?.toFace(): Face? {
    val row = obj() ?: return null
    val id = row.strOrNull("id") ?: return null
    val box = row["box"].obj()?.let { b ->
        FaceBox(left = b.float("l"), top = b.float("t"), width = b.float("w"), height = b.float("h"))
    }
    return Face(
        id = id,
        memberId = row.strOrNull("member_id"),
        name = row.str("name"),
        state = FaceState.of(row.str("state")),
        similarity = row.float("similarity"),
        manual = row.bool("manual"),
        box = box,
        cropUrl = row.str("crop_url"),
        candidates = row["candidates"].arr().mapNotNull { candidate ->
            val item = candidate.obj() ?: return@mapNotNull null
            item.strOrNull("member_id")?.let { FaceCandidate(it, item.str("name"), item.float("similarity")) }
        },
        hasVector = row.bool("has_vector"),
    )
}

internal fun JsonElement?.toPhoto(): Photo? {
    val row = obj() ?: return null
    val id = row.strOrNull("id") ?: return null
    return Photo(
        id = id,
        status = PhotoStatus.of(row.str("status")),
        width = row.int("width"),
        height = row.int("height"),
        previewUrl = row.str("preview_url"),
        originalName = row.str("original_name"),
        shootLabel = row.str("shoot_label"),
        createdMillis = row.long("created"),
        uploadedBy = row.strOrNull("uploaded_by"),
        publicState = PublicState.of(row.str("public_state")),
        people = row.int("people"),
        unnamed = row.int("unnamed"),
        truncated = row.bool("truncated"),
        section = row.strOrNull("section"),
        errorCode = row.str("error_code"),
        faces = row["faces"].arr().mapNotNull { it.toFace() },
        approvals = row["approvals"].arr().mapNotNull { it.toApprovalRow() },
    )
}

internal fun JsonElement?.toPhotoPage(): PhotoPage {
    val root = obj()
    return PhotoPage(
        photos = root?.get("photos").arr().mapNotNull { it.toTile() },
        hasMore = root.bool("has_more"),
        next = root.strOrNull("next"),
        linksExpireAt = root.long("links_expire_at"),
    )
}

internal fun JsonElement?.toPhotoAnswer(): PhotoAnswer? {
    val root = obj() ?: return null
    val photo = root["photo"].toPhoto() ?: return null
    return PhotoAnswer(photo, root.long("links_expire_at"))
}

internal fun JsonElement?.toSummary(): StillsSummary {
    val root = obj()
    return StillsSummary(
        total = root.intOrNull("total"),
        bySection = root.limits("by_section"),
        needsNames = root.intOrNull("needs_names"),
        noPeople = root.intOrNull("no_people"),
        failed = root.intOrNull("failed"),
        processing = root.intOrNull("processing"),
        approved = root.intOrNull("approved"),
        pending = root.intOrNull("pending"),
        blocked = root.intOrNull("blocked"),
        members = (root?.get("members") as? JsonArray)?.mapNotNull { row ->
            val item = row.obj() ?: return@mapNotNull null
            item.strOrNull("member_id")?.let { SummaryMember(it, item.str("name"), item.intOrNull("photos")) }
        },
        shoots = root.strings("shoots"),
    )
}

internal fun JsonElement?.toReviewPage(): ReviewPage {
    val root = obj()
    val counts = root?.get("counts").obj()
    return ReviewPage(
        photos = root?.get("photos").arr().mapNotNull { it.toTile() },
        hasMore = root.bool("has_more"),
        next = root.strOrNull("next"),
        counts = counts?.let {
            ReviewCounts(
                pending = it.int("pending"),
                approved = it.int("approved"),
                rejected = it.int("rejected"),
                all = it.int("all"),
            )
        },
        clients = (root?.get("clients") as? JsonArray)?.mapNotNull { it.toClient() },
        linksExpireAt = root.long("links_expire_at"),
    )
}

internal fun JsonElement?.toOriginalLink(): OriginalLink? {
    val root = obj() ?: return null
    val url = root.strOrNull("url") ?: return null
    return OriginalLink(url, root.str("name"))
}

internal fun JsonElement?.toSimilarFaces(): List<SimilarFace> =
    obj()?.get("items").arr().mapNotNull { row ->
        val item = row.obj() ?: return@mapNotNull null
        val photoId = item.strOrNull("photo_id") ?: return@mapNotNull null
        val faceId = item.strOrNull("face_id") ?: return@mapNotNull null
        SimilarFace(photoId, faceId, item.float("similarity"), item.str("crop_url"), item.str("thumb_url"))
    }

internal fun JsonElement?.toApplyResults(): List<ApplyResult> =
    obj()?.get("results").arr().mapNotNull { row ->
        val item = row.obj() ?: return@mapNotNull null
        val photoId = item.strOrNull("photo_id") ?: return@mapNotNull null
        val faceId = item.strOrNull("face_id") ?: return@mapNotNull null
        ApplyResult(photoId, faceId, item.bool("ok"))
    }

// -- uploads -------------------------------------------------------------------------

internal fun JsonElement?.toUploadLinks(): List<UploadLink> =
    obj()?.get("items").arr().mapNotNull { row ->
        val item = row.obj() ?: return@mapNotNull null
        val uniqueId = item.strOrNull("unique_id") ?: return@mapNotNull null
        UploadLink(
            uniqueId = uniqueId,
            photoId = item.str("photo_id"),
            url = item.str("url"),
            headers = item.headers(),
            refused = item.str("refused"),
            duplicateOf = item.str("duplicate_of"),
            uploaded = item.bool("uploaded"),
        )
    }

internal fun JsonElement?.toUploadsConfirmed(): UploadsConfirmed {
    val root = obj()
    return UploadsConfirmed(
        accepted = root.strings("accepted").toSet(),
        missing = root.strings("missing").toSet(),
    )
}

internal fun JsonElement?.toHeadshotLinks(): List<HeadshotLink> =
    obj()?.get("items").arr().map { row ->
        val item = row.obj()
        HeadshotLink(
            name = item.str("name"),
            headshotId = item.str("headshot_id"),
            url = item.str("url"),
            headers = item.headers(),
            refused = item.str("refused"),
        )
    }

internal fun JsonElement?.toHeadshotsChecked(): HeadshotsChecked {
    val root = obj()
    return HeadshotsChecked(
        member = root?.get("member").toMember(),
        results = root?.get("results").arr().mapNotNull { row ->
            val item = row.obj() ?: return@mapNotNull null
            val id = item.strOrNull("headshot_id") ?: return@mapNotNull null
            HeadshotVerdict(
                headshotId = id,
                ok = item.bool("ok"),
                reason = item.str("reason"),
                matchedName = item["data"].obj().str("name"),
            )
        },
    )
}
