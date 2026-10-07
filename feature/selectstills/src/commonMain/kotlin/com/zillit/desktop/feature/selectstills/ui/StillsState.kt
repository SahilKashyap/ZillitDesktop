package com.zillit.desktop.feature.selectstills.ui

import com.zillit.desktop.feature.selectstills.data.HeadshotOutcome
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewCounts
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.SimilarFace
import com.zillit.desktop.feature.selectstills.domain.StillsMe
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsSummary
import com.zillit.desktop.feature.selectstills.domain.StillsViewer
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import com.zillit.desktop.core.strings.S

/**
 * Which page of the tool is open. The web mounts these as nested routes
 * (`/film-tools/still-kills/photos` and friends); nothing outside the tool
 * links to them, so one window owns all five.
 *
 * The order is the web's top bar: Enroll · Upload · Photos · Review ·
 * Settings. `short` is the label a narrow window fits all five of.
 */
enum class StillsPage(val path: String, val title: String, val short: String) {
    Cast("cast", S.desktop_stk_nav_cast, S.desktop_stk_nav_cast_short),
    Upload("upload", S.desktop_stk_nav_upload, S.upload),
    Photos("photos", S.desktop_photos, S.desktop_photos),
    Review("review", S.desktop_stk_nav_review, S.av_review),
    Settings("settings", S.settings, S.settings),
}

/** How far `GET /me` has got. Nothing but the gate is drawn until it is ready. */
enum class MeState { Idle, Loading, Ready, Error }

/** A person from the production's own crew list, as the pickers show them. */
data class StillsPerson(
    val id: String,
    val fullName: String,
    val designation: String = "",
    val department: String = "",
) {
    /** The second line in a picker: "Gaffer · Electrical". */
    val subtitle: String get() = listOf(designation, department).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The gallery page. */
data class GalleryState(
    val filters: PhotoFilters = PhotoFilters(),
    /** The shoot box as it is typed; the filter follows once the typing pauses. */
    val shootText: String = "",
    val photos: List<PhotoTile> = emptyList(),
    val hasMore: Boolean = false,
    val next: String? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val summary: StillsSummary? = null,
) {
    /**
     * The counts are for the whole production (or one shoot): beside a member
     * or an agent they would be the wrong numbers, so they step aside.
     */
    val counted: Boolean get() = filters.member.isNullOrBlank() && filters.agent.isNullOrBlank()
}

/** The reader's own queue. */
data class ReviewState(
    val tab: ReviewTab = ReviewTab.Pending,
    /** One of the reader's actors, when they have more than one. */
    val member: String? = null,
    val photos: List<PhotoTile> = emptyList(),
    val hasMore: Boolean = false,
    val next: String? = null,
    val counts: ReviewCounts? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The photo a decision is in flight for. */
    val busyId: String? = null,
    /**
     * The reason being typed on a card. Here, not in the card: the grid is
     * windowed, and a card that scrolls away must not take the sentence with it.
     */
    val draftFor: String? = null,
    val draftNote: String = "",
)

/** The Enroll page's list and its search. */
data class CastState(val query: String = "", val accepting: Boolean = false)

/** The Upload page. */
data class UploadState(val shootLabel: String = "", val accepting: Boolean = false)

/** The Settings page's form, as typed. */
data class SettingsFormState(
    val scope: ViewerScope = ViewerScope.Cleared,
    val limits: Map<String, String> = emptyMap(),
    val auto: String = "",
    val suggest: String = "",
    val margin: String = "",
    val busy: Boolean = false,
    val askingWipe: Boolean = false,
)

/** The enrolment card — on the Enroll page, and in the dialog a face opens. */
data class EnrollState(
    val name: String = "",
    val character: String = "",
    val needsApproval: Boolean = true,
    val agent: String = "",
    val recognition: Recognition = Recognition.Auto,
    val limits: Map<String, String> = emptyMap(),
    val consent: Boolean = false,
    val picks: List<StillsPick> = emptyList(),
    val busy: Boolean = false,
    /** Open as a dialog over the lightbox, seeded from "or type new name". */
    val inDialog: Boolean = false,
    /** The face to name as the new member, once they exist. */
    val forFaceId: String? = null,
)

/** What a member's card is asking before it acts. */
enum class MemberAsk { None, Erase, Remove, RemoveInUse }

/** A member's profile card. */
data class MemberDialogState(
    val memberId: String,
    val member: Member? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val name: String = "",
    val character: String = "",
    val needsApproval: Boolean = true,
    val agent: String = "",
    val recognition: Recognition = Recognition.Auto,
    val limits: Map<String, String> = emptyMap(),
    /** What the service said about headshots sent just now. */
    val results: List<HeadshotOutcome> = emptyList(),
    /** A ✕ pressed once: the second press removes that headshot. */
    val confirmShot: String? = null,
    val ask: MemberAsk = MemberAsk.None,
)

/** The lightbox over the gallery or the queue. */
data class LightboxState(
    val photoId: String,
    /** The ids being walked, for ← →. */
    val ids: List<String> = emptyList(),
    /** In a review queue: after a decision, go on to the next photo. */
    val run: Boolean = false,
    val photo: Photo? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val deleting: Boolean = false,
    /** Which face's box and row are tied together right now. */
    val activeFace: String? = null,
    /** The reason being typed, when a discard asked why. */
    val asking: Boolean = false,
    val note: String = "",
    /** Open over the lightbox: "find this person in other photos". */
    val similar: SimilarState? = null,
    val linksExpireAt: Long = 0,
) {
    val index: Int get() = ids.indexOf(photoId)
}

/** The "find this person in other photos" panel. */
data class SimilarState(
    val faceId: String,
    val faceMemberId: String? = null,
    val items: List<SimilarFace>? = null,
    val picked: Set<String> = emptySet(),
    val memberId: String = "",
    val busy: Boolean = false,
)

/** Everything the Select Stills screens read. */
data class StillsUiState(
    val viewer: StillsViewer = StillsViewer(),
    val page: StillsPage = StillsPage.Photos,
    /** False until the landing page has been chosen from the queue's count. */
    val landed: Boolean = false,
    val meState: MeState = MeState.Idle,
    val me: StillsMe = StillsMe(),
    /**
     * The allowances a decision returned, newer than the last `GET /me`.
     * Dropped each time the service is asked again.
     */
    val clientsOverride: List<Client>? = null,
    val crew: List<StillsPerson> = emptyList(),
    /** Who can be made an approver; null until the service has been asked. */
    val agentIds: Set<String>? = null,
    val members: List<Member> = emptyList(),
    val membersLoaded: Boolean = false,
    val reviewCounts: ReviewCounts = ReviewCounts(),
    /** Whether the queue's count has answered once — the landing page waits for it. */
    val reviewReady: Boolean = false,
    val gallery: GalleryState = GalleryState(),
    val review: ReviewState = ReviewState(),
    val cast: CastState = CastState(),
    val upload: UploadState = UploadState(),
    val settings: SettingsFormState = SettingsFormState(),
    val enroll: EnrollState? = null,
    val memberDialog: MemberDialogState? = null,
    val lightbox: LightboxState? = null,
    /** The outcome of a write, from the service's own words. */
    val notice: String? = null,
    /** Something this client stopped before calling, or a refusal. */
    val error: String? = null,
) {
    val ready: Boolean get() = meState == MeState.Ready

    /** Posting needs the rights row AND the service to agree. */
    val canPost: Boolean get() = ready && viewer.canPost && me.canPost

    val isAdmin: Boolean get() = ready && me.isAdmin

    /** The cast members the reader approves for. */
    val clients: List<Client> get() = clientsOverride ?: me.agentFor

    val isApprover: Boolean get() = clients.isNotEmpty()

    val membersById: Map<String, Member> get() = members.associateBy { it.id }

    val crewById: Map<String, StillsPerson> get() = crew.associateBy { it.id }

    /**
     * Which pages this reader gets. The service has the last word on everything
     * behind them; this only decides what is offered.
     *
     *   production (posting rights)  Enroll · Upload · Photos · Review · Settings
     *   an agent without posting     Review — and nothing else, unless the
     *                                production opened the gallery to viewers
     *   anybody else who can view    Photos (cleared ones) · Enroll (the list)
     */
    val pages: Set<StillsPage>
        get() {
            val gallery = canPost || me.scope != ViewerScope.Clients
            return buildSet {
                if (gallery) add(StillsPage.Photos)
                if (canPost) add(StillsPage.Upload)
                if (gallery) add(StillsPage.Cast)
                // Production gets the link too, as in the web's bar, where all
                // four are always there; somebody who is nobody's agent finds
                // an empty queue.
                if (isApprover || canPost) add(StillsPage.Review)
                if (canPost) add(StillsPage.Settings)
            }
        }

    /** The kinds the gallery's tab row offers. */
    val galleryKinds: List<PhotoKind>
        get() = com.zillit.desktop.feature.selectstills.domain.STILLS_KINDS +
            if (canPost) com.zillit.desktop.feature.selectstills.domain.STILLS_POSTING_KINDS else emptyList()

    /** To somebody who only ever sees cleared photos, a publication filter is noise. */
    val showsPublicationFilter: Boolean get() = me.scope != ViewerScope.Cleared
}

/** Everything the screens can do. */
sealed interface StillsEvent {
    data object Load : StillsEvent
    data object ReloadMe : StillsEvent
    data class Open(val page: StillsPage) : StillsEvent
    data object DismissMessage : StillsEvent
    data object AskForRights : StillsEvent
    data object AcceptNotice : StillsEvent

    // -- the gallery
    data class KindChanged(val kind: PhotoKind) : StillsEvent
    data class GroupSizeChanged(val size: String?) : StillsEvent
    data class StateChanged(val state: PublicState?) : StillsEvent
    data class MemberChanged(val memberId: String?) : StillsEvent
    data class AgentChanged(val userId: String?) : StillsEvent
    data class ShootTyped(val text: String) : StillsEvent
    data object ReloadGallery : StillsEvent
    data object LoadMorePhotos : StillsEvent

    // -- the queue
    data class ReviewTabChanged(val tab: ReviewTab) : StillsEvent
    data class ReviewMemberChanged(val memberId: String?) : StillsEvent
    data object ReloadReview : StillsEvent
    data object LoadMoreReview : StillsEvent
    data class CardDraft(val photoId: String?, val note: String) : StillsEvent

    /** Keep or discard one of the reader's actors in one photo, from a card. */
    data class DecideOnCard(
        val photoId: String,
        val memberId: String,
        val state: Decision,
        val note: String = "",
    ) : StillsEvent

    // -- the lightbox
    data class OpenPhoto(val photoId: String, val ids: List<String>, val run: Boolean) : StillsEvent
    data object ClosePhoto : StillsEvent
    data class StepPhoto(val delta: Int) : StillsEvent
    data object ReloadPhoto : StillsEvent
    data class PickFace(val faceId: String?) : StillsEvent
    data class SetFace(val faceId: String, val edit: FaceEdit) : StillsEvent
    data class AddPerson(val memberId: String) : StillsEvent
    data object DismissUnknown : StillsEvent
    data class DisputeFace(val faceId: String) : StillsEvent
    data class DecideInPhoto(val memberId: String, val state: Decision, val note: String = "") : StillsEvent

    /** The D key, or a click on Discard: open the reason box. */
    data object AskWhy : StillsEvent
    data class WhyTyped(val note: String) : StillsEvent
    data object CancelWhy : StillsEvent
    data object DownloadOriginal : StillsEvent
    data object RetryPhoto : StillsEvent
    data object DeletePhoto : StillsEvent

    // -- find this person
    data class FindSimilar(val faceId: String, val memberId: String?) : StillsEvent
    data object CloseSimilar : StillsEvent
    data class ToggleSimilar(val key: String) : StillsEvent
    data class PickAllSimilar(val all: Boolean) : StillsEvent
    data class SimilarMemberChanged(val memberId: String) : StillsEvent
    data class ApplySimilar(val asMember: Boolean) : StillsEvent

    // -- the cast
    data class CastSearch(val query: String) : StillsEvent
    data class OpenMember(val memberId: String) : StillsEvent
    data object CloseMember : StillsEvent
    data class MemberName(val text: String) : StillsEvent
    data class MemberCharacter(val text: String) : StillsEvent
    data class MemberApproval(val needed: Boolean) : StillsEvent
    data class MemberAgentPicked(val userId: String) : StillsEvent
    data class MemberRecognition(val mode: Recognition) : StillsEvent
    data class MemberLimits(val limits: Map<String, String>) : StillsEvent
    data object SaveMember : StillsEvent
    data class AddHeadshots(val picks: List<StillsPick>) : StillsEvent

    /** Opens the OS picker for headshots — for the open member's card. */
    data object PickMemberHeadshots : StillsEvent

    /** Headshots dropped on the open member's card. */
    data class DropMemberHeadshots(val paths: List<String>) : StillsEvent
    data class ForceHeadshot(val outcome: HeadshotOutcome) : StillsEvent
    data class RemoveHeadshot(val headshotId: String) : StillsEvent
    data class Ask(val ask: MemberAsk) : StillsEvent
    data object EraseFaceData : StillsEvent
    data class RemoveMember(val force: Boolean) : StillsEvent
    data object LoadAgentCandidates : StillsEvent

    // -- enrolment
    data class StartEnroll(val seedName: String, val faceId: String?) : StillsEvent
    data object CancelEnroll : StillsEvent
    data class EnrollName(val text: String) : StillsEvent
    data class EnrollCharacter(val text: String) : StillsEvent
    data class EnrollApproval(val needed: Boolean) : StillsEvent
    data class EnrollAgent(val userId: String) : StillsEvent
    data class EnrollRecognition(val mode: Recognition) : StillsEvent
    data class EnrollLimits(val limits: Map<String, String>) : StillsEvent
    data class EnrollConsent(val agreed: Boolean) : StillsEvent
    data class EnrollPicks(val picks: List<StillsPick>) : StillsEvent
    data class DropEnrollPick(val index: Int) : StillsEvent

    /** Opens the OS picker for the enrolment card's headshots. */
    data object PickEnrollHeadshots : StillsEvent

    /** Headshots dropped on the enrolment card. */
    data class DropEnrollPaths(val paths: List<String>) : StillsEvent
    data object Enrol : StillsEvent

    // -- uploads
    data class ShootLabel(val text: String) : StillsEvent
    data class QueueFiles(val picks: List<StillsPick>) : StillsEvent
    data class QueueFolders(val paths: List<String>) : StillsEvent

    /** Opens the OS picker for photos to upload. */
    data object PickUploads : StillsEvent

    /** Opens the OS folder chooser — "or choose a folder". */
    data object ChooseFolder : StillsEvent
    data class RetryUpload(val ref: String?) : StillsEvent
    data class UploadAnyway(val ref: String) : StillsEvent
    data object CancelUploads : StillsEvent
    data object ClearUploads : StillsEvent

    // -- settings
    data class SettingsScope(val scope: ViewerScope) : StillsEvent
    data class SettingsLimits(val limits: Map<String, String>) : StillsEvent
    data class SettingsNumber(val which: String, val value: String) : StillsEvent
    data object SaveSettings : StillsEvent
    data class AskWipe(val asking: Boolean) : StillsEvent
    data object WipeFaceData : StillsEvent
}

/** A one-shot thing the screen must do rather than render. */
sealed interface StillsEffect {
    /** A file the reader asked to download has a link: open it. */
    data class Download(val url: String, val name: String) : StillsEffect
}
