package com.zillit.desktop.feature.selectstills.domain

/**
 * Select Stills — what the `zillit_selectstills` service speaks, as the
 * screens need it.
 *
 * Every enum carries an `Unknown` fallback: the service decides what states
 * exist, and a build that crashed on a state added after it shipped would take
 * the whole tool down for a word.
 */

/** Where a photo stands for everybody: cleared, waiting, or refused. */
enum class PublicState(val wire: String) {
    Approved("approved"),
    Pending("pending"),
    Blocked("blocked"),
    ;

    companion object {
        fun of(raw: String?): PublicState = entries.firstOrNull { it.wire == raw } ?: Pending
    }
}

/** How far the service has got with a photo. */
enum class PhotoStatus(val wire: String) {
    Queued("queued"),
    Processing("processing"),
    Done("done"),
    Failed("failed"),
    Unknown(""),
    ;

    val isDone: Boolean get() = this == Done
    val isFailed: Boolean get() = this == Failed

    companion object {
        fun of(raw: String?): PhotoStatus = entries.firstOrNull { it.wire == raw } ?: Unknown
    }
}

/** One agent's own answer on one of their actors in one photo. */
enum class Decision(val wire: String) {
    Pending("pending"),
    Approved("approved"),
    Rejected("rejected"),
    ;

    companion object {
        fun of(raw: String?): Decision = entries.firstOrNull { it.wire == raw } ?: Pending
    }
}

/** How a face came by its name. */
enum class FaceState(val wire: String) {
    /** The system matched it to a headshot and named it. */
    Matched("matched"),

    /** The system proposes a name; a person has to confirm it. */
    Suggested("suggested"),

    /** A person named it by hand. */
    Tagged("tagged"),

    /** An extra, crew or a passer-by: its face data is deleted at once. */
    Dismissed("dismissed"),

    /** Found, but nobody knows who. */
    Unknown(""),
    ;

    companion object {
        fun of(raw: String?): FaceState = entries.firstOrNull { it.wire == raw } ?: Unknown
    }
}

/** Whether a member is recognised automatically, only proposed, or never. */
enum class Recognition(val wire: String) {
    Auto("auto"),
    SuggestOnly("suggest_only"),
    Off("off"),
    ;

    companion object {
        fun of(raw: String?): Recognition = entries.firstOrNull { it.wire == raw } ?: Auto
    }
}

/** How much of the gallery a reader is shown — the production's own setting. */
enum class ViewerScope(val wire: String) {
    /** Everything, including awaiting and blocked photos. */
    All("all"),

    /** An agent's own actors' photos. */
    Clients("clients"),

    /** Only photos cleared to go public. */
    Cleared("cleared"),
    ;

    companion object {
        fun of(raw: String?): ViewerScope = entries.firstOrNull { it.wire == raw } ?: Cleared
    }
}

/** A face's box over the preview, as ratios of it. */
data class FaceBox(val left: Float, val top: Float, val width: Float, val height: Float)

/** Another member the system thought this face might be. */
data class FaceCandidate(val memberId: String, val name: String, val similarity: Float)

/** One face found in a photo. */
data class Face(
    val id: String,
    val memberId: String? = null,
    val name: String = "",
    val state: FaceState = FaceState.Unknown,
    val similarity: Float = 0f,
    /** Added by a person rather than found by the camera: it has no box. */
    val manual: Boolean = false,
    val box: FaceBox? = null,
    val cropUrl: String = "",
    val candidates: List<FaceCandidate> = emptyList(),
    /** Whether a stored pattern exists, so "find in other photos" can run. */
    val hasVector: Boolean = false,
) {
    /** Named for good: not a proposal and not set aside. */
    val isNamed: Boolean
        get() = !memberId.isNullOrBlank() &&
            state != FaceState.Suggested &&
            state != FaceState.Dismissed
}

/** One member whose agent has to sign a photo off, and where that stands. */
data class ApprovalRow(
    val memberId: String,
    val name: String = "",
    val state: Decision = Decision.Pending,
    val note: String = "",
    /** Whether the reader is the one who answers for this row. */
    val canDecide: Boolean = false,
    val agentUserId: String? = null,
    /** False when nobody has been named their agent yet. */
    val hasAgent: Boolean = true,
)

/** A photo as a grid tile: what a list answer carries. */
data class PhotoTile(
    val id: String,
    val sortAt: Long = 0,
    /** Bumped by the service on every change; a tile only redraws when it moves. */
    val rev: Long = 0,
    val status: PhotoStatus = PhotoStatus.Unknown,
    val thumbUrl: String = "",
    val originalName: String = "",
    val publicState: PublicState = PublicState.Pending,
    /** Whether anybody had to clear it — "public" is not stamped on a photo nobody signed. */
    val hasApprovals: Boolean = false,
    val people: Int = 0,
    val names: List<String> = emptyList(),
    val moreNames: Int = 0,
    val unnamed: Int = 0,
    val errorCode: String = "",
    // -- the review queue's own extras, absent from the gallery's tiles
    val section: String? = null,
    /** Somebody else already discarded it, so agreeing costs nothing. */
    val discardIsFree: Boolean = false,
    /** The reader's own rows on this photo. */
    val myRows: List<ApprovalRow> = emptyList(),
    /** The other agents' rows, with a name and a state only. */
    val others: List<ApprovalRow> = emptyList(),
)

/** One photo in full — what the lightbox draws. */
data class Photo(
    val id: String,
    val status: PhotoStatus = PhotoStatus.Unknown,
    val width: Int = 0,
    val height: Int = 0,
    val previewUrl: String = "",
    val originalName: String = "",
    val shootLabel: String = "",
    val createdMillis: Long = 0,
    val uploadedBy: String? = null,
    val publicState: PublicState = PublicState.Pending,
    val people: Int = 0,
    val unnamed: Int = 0,
    /** More than 100 faces: only the first 100 were looked at. */
    val truncated: Boolean = false,
    /** The head-count section this photo charges an allowance against. */
    val section: String? = null,
    val errorCode: String = "",
    val faces: List<Face> = emptyList(),
    val approvals: List<ApprovalRow> = emptyList(),
)

/** A member's stored headshot. */
data class Headshot(val id: String, val url: String)

/** One section's allowance: the cap, what is spent, what is left. */
data class SectionAllowance(val limit: Int?, val used: Int = 0, val remaining: Int? = null)

/** An enrolled member of the production's cast. */
data class Member(
    val id: String,
    val name: String,
    val characterName: String = "",
    val headshots: List<Headshot> = emptyList(),
    /** The first headshot, for a list row. */
    val headshotUrl: String = "",
    val agentUserId: String? = null,
    /** False when photos of this person go public without anybody being asked. */
    val approvalRequired: Boolean = true,
    /** The agent's cap per section; a section left out is uncapped. */
    val discardLimits: Map<String, Int> = emptyMap(),
    val allowance: Map<String, SectionAllowance> = emptyMap(),
    val recognition: Recognition = Recognition.Auto,
    /** How many photos they are named in (production only). */
    val photoCount: Int = 0,
    /** Whether the reader is this member's agent. */
    val isMyClient: Boolean = false,
    val createdMillis: Long = 0,
    /** Faces named by hand that the system also recognises them from. */
    val learnedFaces: Int = 0,
)

/** A cast member the reader answers for, with what is left of their allowance. */
data class Client(
    val memberId: String,
    val name: String,
    val allowance: Map<String, SectionAllowance> = emptyMap(),
)

/** The matching numbers, 0 to 100. */
data class Thresholds(val auto: Int = 0, val suggest: Int = 0, val margin: Int = 0)

/** The production's settings for the tool. */
data class StillsSettings(
    /** Whether the first posting user has acknowledged the face-data notice. */
    val attested: Boolean = false,
    val viewerScope: ViewerScope = ViewerScope.Cleared,
    val defaultDiscardLimits: Map<String, Int> = emptyMap(),
    val thresholds: Thresholds = Thresholds(),
)

/** What files the service takes. */
data class UploadLimits(val types: List<String> = emptyList(), val maxBytes: Long = 0)

/** `GET /me` — who the reader is to this tool, and what the production allows. */
data class StillsMe(
    val userId: String? = null,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val scope: ViewerScope = ViewerScope.Cleared,
    /** The cast members this reader approves for. */
    val agentFor: List<Client> = emptyList(),
    val settings: StillsSettings = StillsSettings(),
    val storageSupported: Boolean = false,
    val regionSupported: Boolean = false,
    val upload: UploadLimits = UploadLimits(),
    /** How many photos are still being worked on. */
    val queueWaiting: Int = 0,
) {
    val usable: Boolean get() = storageSupported && regionSupported
}

/** One member in the gallery's "By member" list, with how many photos they are in. */
data class SummaryMember(val memberId: String, val name: String, val photos: Int?)

/** `GET /summary` — the counts behind the gallery's filters. */
data class StillsSummary(
    val total: Int? = null,
    /** Photos per head-count section, keyed "1".."5" and "6+". */
    val bySection: Map<String, Int> = emptyMap(),
    val needsNames: Int? = null,
    val noPeople: Int? = null,
    val failed: Int? = null,
    val processing: Int? = null,
    val approved: Int? = null,
    val pending: Int? = null,
    val blocked: Int? = null,
    val members: List<SummaryMember>? = null,
    val shoots: List<String> = emptyList(),
) {
    fun forState(state: PublicState): Int? = when (state) {
        PublicState.Approved -> approved
        PublicState.Pending -> pending
        PublicState.Blocked -> blocked
    }
}

/** A page of tiles, with the cursor for the next one. */
data class PhotoPage(
    val photos: List<PhotoTile> = emptyList(),
    val hasMore: Boolean = false,
    val next: String? = null,
    /** When the signed links in this answer stop working, in epoch millis. */
    val linksExpireAt: Long = 0,
)

/** The review queue's tabs, and how many are in each. */
data class ReviewCounts(val pending: Int = 0, val approved: Int = 0, val rejected: Int = 0, val all: Int = 0) {
    fun forTab(tab: ReviewTab): Int = when (tab) {
        ReviewTab.Pending -> pending
        ReviewTab.Approved -> approved
        ReviewTab.Rejected -> rejected
        ReviewTab.All -> all
    }
}

enum class ReviewTab(val wire: String) {
    Pending("pending"),
    Approved("approved"),
    Rejected("rejected"),
    All("all"),
}

/** A page of the reader's own queue. */
data class ReviewPage(
    val photos: List<PhotoTile> = emptyList(),
    val hasMore: Boolean = false,
    val next: String? = null,
    val counts: ReviewCounts? = null,
    /** Fresher allowances than the last `GET /me`, when the service sent them. */
    val clients: List<Client>? = null,
    val linksExpireAt: Long = 0,
)

/** One photo and the links that came with it. */
data class PhotoAnswer(val photo: Photo, val linksExpireAt: Long = 0)

/** What a decision answered: the photo as it now stands, and the new allowances. */
data class DecisionAnswer(val photo: Photo?, val allowances: List<Client>?)

/** A face the service thought looks like the one being hunted. */
data class SimilarFace(
    val photoId: String,
    val faceId: String,
    val similarity: Float,
    val cropUrl: String = "",
    val thumbUrl: String = "",
) {
    val key: String get() = "$photoId:$faceId"
}

/** A link to the original file, for a download. */
data class OriginalLink(val url: String, val name: String)

/** One face in a bulk apply, and whether it went through. */
data class ApplyResult(val photoId: String, val faceId: String, val ok: Boolean) {
    val key: String get() = "$photoId:$faceId"
}

/** What `PUT /photos/{id}/faces/{faceId}` is being told. */
sealed interface FaceEdit {
    data class Name(val memberId: String) : FaceEdit
    data object NotCast : FaceEdit
    data object Clear : FaceEdit
}

/** A file about to be uploaded, as the service is told about it. */
data class UploadDeclaration(
    val uniqueId: String,
    val name: String,
    val type: String,
    val size: Long,
    /** Size and a hash of the file's edges, so the service can spot a repeat. */
    val fingerprint: String = "",
)

/** The service's answer about one declared file. */
data class UploadLink(
    val uniqueId: String,
    val photoId: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** Not a photo the service takes; its message key says why. */
    val refused: String = "",
    /** This photo is already in the production. */
    val duplicateOf: String = "",
    /** It is already in storage: nothing to send. */
    val uploaded: Boolean = false,
)

/** What `POST /uploads/complete` found. */
data class UploadsConfirmed(val accepted: Set<String>, val missing: Set<String>)

/** One headshot the service was asked to sign for. */
data class HeadshotLink(
    val name: String,
    val headshotId: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val refused: String = "",
)

/** What the service made of one uploaded headshot. */
data class HeadshotVerdict(
    val headshotId: String,
    val ok: Boolean,
    val reason: String = "",
    /** Who it looked like, when that is why it was turned away. */
    val matchedName: String = "",
)

/** `POST /members/{id}/headshots/complete`. */
data class HeadshotsChecked(val member: Member?, val results: List<HeadshotVerdict>)

/** What a new member is enrolled with. */
data class MemberDraft(
    val name: String,
    val characterName: String = "",
    val approvalRequired: Boolean = true,
    val agentUserId: String? = null,
    val recognition: Recognition = Recognition.Auto,
    val consent: Boolean = false,
    val discardLimits: Map<String, Int> = emptyMap(),
)
