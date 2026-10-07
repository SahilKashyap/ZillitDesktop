@file:Suppress("TooManyFunctions", "LargeClass", "CyclomaticComplexMethod", "MaxLineLength", "ReturnCount", "LongMethod", "ComplexCondition", "LongParameterList")
// The web's provider and its five screens' effects, in one view model: they
// share `me`, the cast list and the queue's count, and splitting them would
// mean four copies of the rights gate.

package com.zillit.desktop.feature.selectstills.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.data.HeadshotFlow
import com.zillit.desktop.feature.selectstills.data.HeadshotOutcome
import com.zillit.desktop.feature.selectstills.data.MAX_HEADSHOTS
import com.zillit.desktop.feature.selectstills.data.StillsEvents
import com.zillit.desktop.feature.selectstills.data.StillsFileReader
import com.zillit.desktop.feature.selectstills.data.StillsUploadQueue
import com.zillit.desktop.feature.selectstills.data.frameIds
import com.zillit.desktop.feature.selectstills.data.isForProject
import com.zillit.desktop.feature.selectstills.data.stillsHttpStatus
import com.zillit.desktop.feature.selectstills.data.stillsMessageKey
import com.zillit.desktop.feature.selectstills.data.stillsSyncEvents
import com.zillit.desktop.feature.selectstills.domain.Client
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.MemberDraft
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PhotoAnswer
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsRepository
import com.zillit.desktop.feature.selectstills.domain.StillsUrlCache
import com.zillit.desktop.feature.selectstills.domain.StillsViewer
import com.zillit.desktop.feature.selectstills.domain.Thresholds
import com.zillit.desktop.feature.selectstills.domain.Wrote
import com.zillit.desktop.feature.selectstills.domain.appendPage
import com.zillit.desktop.feature.selectstills.domain.idBatches
import com.zillit.desktop.feature.selectstills.domain.limitsFromInputs
import com.zillit.desktop.feature.selectstills.domain.limitsToInputs
import com.zillit.desktop.feature.selectstills.domain.patchList
import com.zillit.desktop.feature.selectstills.domain.removeIds
import com.zillit.desktop.feature.selectstills.domain.toQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The Select Stills tool.
 *
 * Two sources decide what the reader may do, and BOTH must agree:
 *   - the production's rights row ([StillsViewer]) — also the gate: nothing
 *     calls the service until it has confirmed an enabled, viewable tool,
 *     because a 403 from the service is read app-wide as "no longer a member";
 *   - the service's own answer, `GET /me` — `can_post`, `scope`, whose
 *     approver the reader is, and whether this production can use the tool.
 *
 * Every list load takes a sequence number and only applies if nothing newer
 * started meanwhile — a filter changed while a page was in flight must not be
 * overwritten by the older answer.
 *
 * The list is never reloaded for a socket frame: a frame names the photos that
 * changed, just those are asked for again with the current filters, and the
 * answer is merged (`patchList`). A big upload is hundreds of frames a minute.
 */
class StillsViewModel(
    private val repository: StillsRepository,
    /** Resolved on every [StillsEvent.Load]: view models are built before any production is open. */
    private val viewer: () -> StillsViewer = { StillsViewer() },
    /** The production's own crew; the host feeds it from the project context. */
    private val people: Flow<List<StillsPerson>> = emptyFlow(),
    /** The production that is open, for gating socket frames. */
    private val openProject: () -> String? = { null },
    val queue: StillsUploadQueue? = null,
    private val headshots: HeadshotFlow? = null,
    private val files: StillsFileReader? = null,
    /**
     * The OS file chooser, filtered to photos. Paths, not bytes: a card is
     * hundreds of forty-megabyte frames.
     */
    private val pickPhotos: suspend (multiple: Boolean) -> List<StillsPick> = { emptyList() },
    private val rights: RightsRequestBus? = null,
    private val events: SocketEventBus? = null,
    private val now: () -> Long = { 0 },
    private val urls: StillsUrlCache = StillsUrlCache(now),
) : ZillitViewModel<StillsUiState, StillsEvent, StillsEffect>(StillsUiState()) {

    private var galleryseq = 0
    private var reviewSeq = 0
    private var photoSeq = 0
    private var rightsPoll: Job? = null
    private var shootDebounce: Job? = null
    private var summaryJob: Job? = null
    private var reviewReloadJob: Job? = null
    private var linkRefreshJob: Job? = null
    private var summaryAt = 0L
    private var reviewReloadAt = 0L
    private var countedAt = 0L
    private var busyMoreGallery = false
    private var busyMoreReview = false

    init {
        launch { people.collect { crew -> setState { copy(crew = crew) } } }
        events?.let { bus ->
            launch {
                bus.onAny(stillsSyncEvents).collect { message ->
                    if (!message.payload.isForProject(openProject())) return@collect
                    when (message.event) {
                        StillsEvents.Photos -> onPhotoFrames(message.payload.frameIds("photo_ids"))
                        StillsEvents.Members -> onMemberFrame()
                        StillsEvents.Settings -> reloadMe()
                        else -> Unit
                    }
                }
            }
        }
    }

    @Suppress("LongMethod")
    override fun onEvent(event: StillsEvent) {
        when (event) {
            StillsEvent.Load -> load()
            StillsEvent.ReloadMe -> reloadMe()
            is StillsEvent.Open -> openPage(event.page)
            StillsEvent.DismissMessage -> setState { copy(notice = null, error = null) }
            StillsEvent.AskForRights -> rights?.ask(str(S.desktop_stk_tool_label), RightsKind.Post)
            StillsEvent.AcceptNotice -> acceptNotice()

            is StillsEvent.KindChanged -> filter { copy(kind = event.kind, size = null) }
            is StillsEvent.GroupSizeChanged -> filter { copy(size = event.size) }
            is StillsEvent.StateChanged -> filter { copy(state = event.state) }
            is StillsEvent.MemberChanged -> filter { copy(member = event.memberId) }
            is StillsEvent.AgentChanged -> filter { copy(agent = event.userId) }
            is StillsEvent.ShootTyped -> shootTyped(event.text)
            StillsEvent.ReloadGallery -> loadGallery()
            StillsEvent.LoadMorePhotos -> loadMorePhotos()

            is StillsEvent.ReviewTabChanged -> {
                closeLightbox()
                setState { copy(review = review.copy(tab = event.tab, draftFor = null, draftNote = "")) }
                loadReview()
            }
            is StillsEvent.ReviewMemberChanged -> {
                closeLightbox()
                setState { copy(review = review.copy(member = event.memberId, draftFor = null, draftNote = "")) }
                loadReview()
            }
            StillsEvent.ReloadReview -> loadReview()
            StillsEvent.LoadMoreReview -> loadMoreReview()
            is StillsEvent.CardDraft -> setState { copy(review = review.copy(draftFor = event.photoId, draftNote = event.note)) }
            is StillsEvent.DecideOnCard -> decideOnCard(event)

            is StillsEvent.OpenPhoto -> openPhoto(event)
            StillsEvent.ClosePhoto -> closeLightbox()
            is StillsEvent.StepPhoto -> stepPhoto(event.delta)
            StillsEvent.ReloadPhoto -> currentState.lightbox?.let { loadPhoto(it.photoId, quiet = false) }
            is StillsEvent.PickFace -> editLightbox { copy(activeFace = event.faceId) }
            is StillsEvent.SetFace -> writePhoto { photoId -> repository.setFace(photoId, event.faceId, event.edit) }
            is StillsEvent.AddPerson -> writePhoto { photoId -> repository.addPerson(photoId, event.memberId) }
            StillsEvent.DismissUnknown -> writePhoto { photoId -> repository.dismissUnknown(photoId) }
            is StillsEvent.DisputeFace -> writePhoto { photoId -> repository.disputeFace(photoId, event.faceId) }
            is StillsEvent.DecideInPhoto -> decideInPhoto(event.memberId, event.state, event.note)
            StillsEvent.AskWhy -> askWhy()
            is StillsEvent.WhyTyped -> editLightbox { copy(note = event.note) }
            StillsEvent.CancelWhy -> editLightbox { copy(asking = false, note = "") }
            StillsEvent.DownloadOriginal -> download()
            StillsEvent.RetryPhoto -> retryPhoto()
            StillsEvent.DeletePhoto -> deletePhoto()

            is StillsEvent.FindSimilar -> findSimilar(event.faceId, event.memberId)
            StillsEvent.CloseSimilar -> editLightbox { copy(similar = null) }
            is StillsEvent.ToggleSimilar -> editSimilar { copy(picked = if (event.key in picked) picked - event.key else picked + event.key) }
            is StillsEvent.PickAllSimilar -> editSimilar {
                copy(picked = if (event.all) items.orEmpty().take(MAX_APPLY).map { it.key }.toSet() else emptySet())
            }
            is StillsEvent.SimilarMemberChanged -> editSimilar { copy(memberId = event.memberId) }
            is StillsEvent.ApplySimilar -> applySimilar(event.asMember)

            is StillsEvent.CastSearch -> setState { copy(cast = cast.copy(query = event.query)) }
            is StillsEvent.OpenMember -> openMember(event.memberId, emptyList())
            StillsEvent.CloseMember -> setState { copy(memberDialog = null) }
            is StillsEvent.MemberName -> editMember { copy(name = event.text) }
            is StillsEvent.MemberCharacter -> editMember { copy(character = event.text) }
            is StillsEvent.MemberApproval -> editMember { copy(needsApproval = event.needed) }
            is StillsEvent.MemberAgentPicked -> editMember { copy(agent = event.userId) }
            is StillsEvent.MemberRecognition -> editMember { copy(recognition = event.mode) }
            is StillsEvent.MemberLimits -> editMember { copy(limits = event.limits) }
            StillsEvent.SaveMember -> saveMember()
            is StillsEvent.AddHeadshots -> addHeadshots(event.picks)
            StillsEvent.PickMemberHeadshots -> launch { addHeadshots(pickPhotos(true)) }
            is StillsEvent.DropMemberHeadshots -> addHeadshots(describe(event.paths))
            is StillsEvent.ForceHeadshot -> forceHeadshot(event.outcome)
            is StillsEvent.RemoveHeadshot -> removeHeadshot(event.headshotId)
            is StillsEvent.Ask -> editMember { copy(ask = event.ask) }
            StillsEvent.EraseFaceData -> eraseFaceData()
            is StillsEvent.RemoveMember -> removeMember(event.force)
            StillsEvent.LoadAgentCandidates -> loadAgentCandidates()

            is StillsEvent.StartEnroll -> setState {
                copy(
                    enroll = EnrollState(
                        name = event.seedName,
                        limits = limitsToInputs(me.settings.defaultDiscardLimits),
                        inDialog = true,
                        forFaceId = event.faceId,
                    ),
                )
            }
            StillsEvent.CancelEnroll -> setState { copy(enroll = if (enroll?.inDialog == true) null else freshEnroll()) }
            is StillsEvent.EnrollName -> editEnroll { copy(name = event.text) }
            is StillsEvent.EnrollCharacter -> editEnroll { copy(character = event.text) }
            is StillsEvent.EnrollApproval -> editEnroll { copy(needsApproval = event.needed) }
            is StillsEvent.EnrollAgent -> editEnroll { copy(agent = event.userId) }
            is StillsEvent.EnrollRecognition -> editEnroll { copy(recognition = event.mode) }
            is StillsEvent.EnrollLimits -> editEnroll { copy(limits = event.limits) }
            is StillsEvent.EnrollConsent -> editEnroll { copy(consent = event.agreed) }
            is StillsEvent.EnrollPicks -> editEnroll { copy(picks = (picks + event.picks).take(MAX_HEADSHOTS)) }
            is StillsEvent.DropEnrollPick -> editEnroll { copy(picks = picks.filterIndexed { index, _ -> index != event.index }) }
            StillsEvent.PickEnrollHeadshots -> launch {
                val picked = pickPhotos(true)
                if (picked.isNotEmpty()) editEnroll { copy(picks = (picks + picked).take(MAX_HEADSHOTS)) }
            }
            is StillsEvent.DropEnrollPaths -> describe(event.paths).takeIf { it.isNotEmpty() }?.let { picked ->
                editEnroll { copy(picks = (picks + picked).take(MAX_HEADSHOTS)) }
            }
            StillsEvent.Enrol -> enrol()

            is StillsEvent.ShootLabel -> setState { copy(upload = upload.copy(shootLabel = event.text)) }
            is StillsEvent.QueueFiles -> queueFiles(event.picks)
            is StillsEvent.QueueFolders -> queueFolders(event.paths)
            StillsEvent.PickUploads -> launch { queueFiles(pickPhotos(true)) }
            StillsEvent.ChooseFolder -> chooseFolder()
            is StillsEvent.RetryUpload -> queue?.retry(event.ref)
            is StillsEvent.UploadAnyway -> queue?.uploadAnyway(event.ref)
            StillsEvent.CancelUploads -> queue?.cancel()
            StillsEvent.ClearUploads -> queue?.clear()

            is StillsEvent.SettingsScope -> setState { copy(settings = settings.copy(scope = event.scope)) }
            is StillsEvent.SettingsLimits -> setState { copy(settings = settings.copy(limits = event.limits)) }
            is StillsEvent.SettingsNumber -> setState {
                copy(
                    settings = when (event.which) {
                        "auto" -> settings.copy(auto = event.value)
                        "suggest" -> settings.copy(suggest = event.value)
                        else -> settings.copy(margin = event.value)
                    },
                )
            }
            StillsEvent.SaveSettings -> saveSettings()
            is StillsEvent.AskWipe -> setState { copy(settings = settings.copy(askingWipe = event.asking)) }
            StillsEvent.WipeFaceData -> wipeFaceData()
        }
    }

    /**
     * Another production is open.
     *
     * Everything in this state belongs to the one that was: its photos, its
     * cast, its queue counts, and the reader's standing on it. Carrying any of
     * it over would show one shoot's stills under another's name, so the whole
     * state goes and the gate runs again.
     */
    fun onProjectChanged() {
        galleryseq += 1
        reviewSeq += 1
        photoSeq += 1
        rightsPoll?.cancel()
        shootDebounce?.cancel()
        summaryJob?.cancel()
        reviewReloadJob?.cancel()
        linkRefreshJob?.cancel()
        summaryAt = 0
        reviewReloadAt = 0
        countedAt = 0
        urls.clear()
        setState { StillsUiState(crew = crew) }
        load()
    }

    /** The production's rights row has arrived (or changed) — re-resolve and load. */
    fun onRightsChanged() {
        val resolved = viewer()
        if (resolved == currentState.viewer) return
        setState { copy(viewer = resolved) }
        if (resolved.canCall && currentState.meState != MeState.Ready) reloadMe()
    }

    // -- the gate ----------------------------------------------------------------------

    /**
     * Rights first: nothing may call the service until the tool's row has
     * confirmed it. The rights list can still be in flight when the tool
     * opens, so an unanswered list is asked again for a few seconds.
     */
    private fun load() {
        val resolved = viewer()
        setState { copy(viewer = resolved) }
        if (!resolved.resolved) {
            rightsPoll?.cancel()
            rightsPoll = launch {
                repeat(RIGHTS_POLLS) {
                    delay(RIGHTS_POLL_MS)
                    val again = viewer()
                    if (again.resolved) {
                        setState { copy(viewer = again) }
                        if (again.canCall) reloadMe()
                        return@launch
                    }
                }
            }
            return
        }
        if (resolved.canCall) reloadMe()
    }

    private fun reloadMe() {
        if (!currentState.viewer.canCall) return
        setState { copy(meState = if (meState == MeState.Ready) meState else MeState.Loading) }
        launch {
            when (val answer = repository.me()) {
                is ZillitResult.Success -> {
                    val first = currentState.meState != MeState.Ready
                    setState {
                        copy(
                            me = answer.data,
                            clientsOverride = null,
                            meState = MeState.Ready,
                            settings = settings.fromStored(answer.data.settings),
                            upload = upload,
                            enroll = enroll ?: if (viewer.canPost && answer.data.canPost) freshEnroll(answer.data) else null,
                        )
                    }
                    if (first) {
                        reloadMembers()
                        reloadReviewCounts()
                    }
                }
                // Keep what is on screen if it had answered before; a first
                // failure shows the retry panel.
                is ZillitResult.Failure -> setState {
                    copy(meState = if (meState == MeState.Ready) meState else MeState.Error)
                }
            }
        }
    }

    private fun reloadMembers() {
        if (!currentState.viewer.canCall) return
        launch {
            (repository.members() as? ZillitResult.Success)?.let { answer ->
                setState { copy(members = answer.data, membersLoaded = true) }
            }
        }
    }

    /** The queue's count, for the number beside "Publisher review" and the landing page. */
    private fun reloadReviewCounts() {
        val state = currentState
        if (!state.viewer.canCall || !state.isApprover) {
            // Nobody's agent: there is nothing to count, and the landing page
            // may stop waiting for it.
            setState { copy(reviewReady = true) }
            land()
            return
        }
        launch {
            val answer = repository.review(ReviewTab.Pending, limit = 1)
            if (answer is ZillitResult.Success) {
                answer.data.counts?.let { counts -> setState { copy(reviewCounts = counts) } }
            }
            setState { copy(reviewReady = true) }
            land()
        }
    }

    /**
     * Where the tool opens. A notification and the Film Tools tile both land on
     * the module's base page, and this picks one: an agent with photos waiting
     * goes to their queue (that is what the notification was about);
     * production and viewers go to the gallery; an agent who can see nothing
     * else goes to Review.
     */
    private fun land() {
        val state = currentState
        if (state.landed || !state.ready || !state.reviewReady) return
        val pages = state.pages
        val toReview = StillsPage.Review in pages &&
            (StillsPage.Photos !in pages || state.reviewCounts.pending > 0)
        val page = if (toReview) StillsPage.Review else StillsPage.Photos
        setState { copy(landed = true, page = page) }
        openPage(page)
    }

    private fun openPage(page: StillsPage) {
        if (page !in currentState.pages) return
        closeLightbox()
        setState { copy(page = page, landed = true) }
        when (page) {
            StillsPage.Photos -> {
                if (currentState.gallery.photos.isEmpty()) loadGallery()
                loadSummary(soon = false)
            }
            StillsPage.Review -> loadReview()
            StillsPage.Cast -> if (!currentState.membersLoaded) reloadMembers()
            StillsPage.Upload, StillsPage.Settings -> Unit
        }
    }

    private fun acceptNotice() {
        launch {
            setState { copy(cast = cast.copy(accepting = true), upload = upload.copy(accepting = true)) }
            val answer = repository.acceptNotice()
            setState { copy(cast = cast.copy(accepting = false), upload = upload.copy(accepting = false)) }
            if (say(answer)) reloadMe()
        }
    }

    private fun loadAgentCandidates() {
        if (currentState.agentIds != null || !currentState.canPost) return
        launch {
            (repository.agentCandidates() as? ZillitResult.Success)?.let { answer ->
                setState { copy(agentIds = answer.data) }
            }
        }
    }

    // -- the gallery -------------------------------------------------------------------

    private fun filter(change: PhotoFilters.() -> PhotoFilters) {
        closeLightbox()
        setState { copy(gallery = gallery.copy(filters = gallery.filters.change())) }
        loadGallery()
    }

    /** The shoot box filters as it is typed in, once the typing pauses. */
    private fun shootTyped(text: String) {
        setState { copy(gallery = gallery.copy(shootText = text)) }
        shootDebounce?.cancel()
        shootDebounce = launch {
            delay(SHOOT_AFTER_MS)
            if (currentState.gallery.filters.shoot.trim() == text.trim()) return@launch
            filter { copy(shoot = text) }
            loadSummary(soon = false)
        }
    }

    private fun loadGallery() {
        if (!currentState.viewer.canCall) return
        galleryseq += 1
        val mine = galleryseq
        setState { copy(gallery = gallery.copy(loading = true, failed = false)) }
        launch {
            val answer = repository.photos(currentState.gallery.filters, limit = PAGE)
            if (mine != galleryseq) return@launch
            when (answer) {
                is ZillitResult.Failure -> setState { copy(gallery = gallery.copy(loading = false, failed = true)) }
                is ZillitResult.Success -> {
                    val page = answer.data
                    setState {
                        copy(
                            gallery = gallery.copy(
                                loading = false,
                                failed = false,
                                photos = urls.stableTiles(page.photos, page.linksExpireAt),
                                hasMore = page.hasMore,
                                next = page.next,
                            ),
                        )
                    }
                    armLinkRefresh(page.linksExpireAt)
                }
            }
        }
    }

    private fun loadMorePhotos() {
        val gallery = currentState.gallery
        if (!gallery.hasMore || gallery.next == null || busyMoreGallery) return
        busyMoreGallery = true
        val mine = galleryseq
        launch {
            val answer = repository.photos(gallery.filters, limit = PAGE, before = gallery.next)
            busyMoreGallery = false
            if (mine != galleryseq || answer !is ZillitResult.Success) return@launch
            val page = answer.data
            setState {
                copy(
                    gallery = this.gallery.copy(
                        photos = appendPage(this.gallery.photos, urls.stableTiles(page.photos, page.linksExpireAt)),
                        hasMore = page.hasMore,
                        next = page.next ?: this.gallery.next,
                    ),
                )
            }
        }
    }

    /** Counts change with every photo; during an upload they refresh every few seconds. */
    private fun loadSummary(soon: Boolean) {
        if (!currentState.viewer.canCall) return
        summaryJob?.cancel()
        summaryJob = launch {
            if (soon) {
                val wait = (SUMMARY_EVERY_MS - (now() - summaryAt)).coerceAtLeast(0)
                delay(wait)
            }
            summaryAt = now()
            val shoot = currentState.gallery.filters.shoot
            (repository.summary(shoot) as? ZillitResult.Success)?.let { answer ->
                setState { copy(gallery = gallery.copy(summary = answer.data)) }
            }
        }
    }

    /** Ask again for just these photos, with the filters as they are, and merge the answer. */
    private fun refreshPhotos(ids: List<String>) {
        if (ids.isEmpty() || !currentState.viewer.canCall) return
        val mine = galleryseq
        launch {
            for (batch in idBatches(ids)) {
                val answer = repository.photos(currentState.gallery.filters, limit = ID_LIMIT, ids = batch)
                // The filters changed meanwhile: the load that started then has it all.
                if (mine != galleryseq) return@launch
                if (answer !is ZillitResult.Success) continue
                val page = answer.data
                val found = urls.stableTiles(page.photos, page.linksExpireAt)
                setState {
                    copy(gallery = gallery.copy(photos = patchList(gallery.photos, batch, found, gallery.hasMore)))
                }
                if (page.linksExpireAt > 0) armLinkRefresh(page.linksExpireAt)
            }
            loadSummary(soon = true)
        }
    }

    /**
     * The links in a list stop working after a while. Shortly before, every
     * loaded tile is asked for again (in the same batches), which brings new
     * ones.
     */
    private fun armLinkRefresh(expiresAt: Long) {
        if (expiresAt <= 0) return
        linkRefreshJob?.cancel()
        linkRefreshJob = launch {
            delay((expiresAt - now() - LINK_MARGIN_MS).coerceAtLeast(LINK_MIN_MS))
            refreshPhotos(currentState.gallery.photos.map { it.id })
        }
    }

    // -- the queue ---------------------------------------------------------------------

    /** Load from the top. [silent] keeps what is on screen (and as many photos as are loaded). */
    private fun loadReview(silent: Boolean = false) {
        if (!currentState.viewer.canCall) return
        reviewSeq += 1
        val mine = reviewSeq
        if (!silent) setState { copy(review = review.copy(loading = true)) }
        val review = currentState.review
        val limit = if (silent) minOf(ID_LIMIT, maxOf(PAGE, review.photos.size)) else PAGE
        launch {
            val answer = repository.review(review.tab, review.member, limit = limit)
            if (mine != reviewSeq) return@launch
            when (answer) {
                is ZillitResult.Failure -> setState {
                    copy(review = this.review.copy(loading = false, failed = if (silent) this.review.failed else true))
                }
                is ZillitResult.Success -> {
                    val page = answer.data
                    setState {
                        copy(
                            review = this.review.copy(
                                loading = false,
                                failed = false,
                                photos = urls.stableTiles(page.photos, page.linksExpireAt),
                                hasMore = page.hasMore,
                                next = page.next,
                                counts = page.counts ?: this.review.counts,
                            ),
                            clientsOverride = page.clients ?: clientsOverride,
                            // The count beside "Publisher review" is for every
                            // actor, so only an unfiltered answer may set it.
                            reviewCounts = if (this.review.member == null) page.counts ?: reviewCounts else reviewCounts,
                        )
                    }
                }
            }
        }
    }

    private fun loadMoreReview() {
        val review = currentState.review
        if (!review.hasMore || review.next == null || busyMoreReview) return
        busyMoreReview = true
        val mine = reviewSeq
        launch {
            val answer = repository.review(review.tab, review.member, limit = PAGE, before = review.next)
            busyMoreReview = false
            if (mine != reviewSeq || answer !is ZillitResult.Success) return@launch
            val page = answer.data
            setState {
                copy(
                    review = this.review.copy(
                        photos = appendPage(this.review.photos, urls.stableTiles(page.photos, page.linksExpireAt)),
                        hasMore = page.hasMore,
                        next = page.next ?: this.review.next,
                    ),
                )
            }
        }
    }

    /**
     * Photos arrive in bursts during an upload: the queue catches up every few
     * seconds, quietly, rather than once per frame.
     */
    private fun reviewSoon() {
        reviewReloadJob?.cancel()
        reviewReloadJob = launch {
            delay((REVIEW_EVERY_MS - (now() - reviewReloadAt)).coerceAtLeast(0))
            reviewReloadAt = now()
            loadReview(silent = true)
        }
    }

    /** A decision made on a card. One toast per photo would bury a run of them: only a refusal speaks. */
    private fun decideOnCard(event: StillsEvent.DecideOnCard) {
        setState { copy(review = review.copy(busyId = event.photoId)) }
        launch {
            val answer = repository.decide(event.photoId, event.memberId, event.state, event.note)
            setState { copy(review = review.copy(busyId = null)) }
            val ok = say(answer, quiet = true)
            if (ok) {
                (answer as ZillitResult.Success).data.value.allowances?.let { applyAllowances(it) }
                setState { copy(review = review.copy(draftFor = null, draftNote = "")) }
            }
            loadReview(silent = true)
        }
    }

    private fun applyAllowances(clients: List<Client>) = setState { copy(clientsOverride = clients) }

    // -- the lightbox ------------------------------------------------------------------

    private fun openPhoto(event: StillsEvent.OpenPhoto) {
        setState { copy(lightbox = LightboxState(photoId = event.photoId, ids = event.ids, run = event.run)) }
        loadPhoto(event.photoId, quiet = false)
    }

    private fun closeLightbox() {
        if (currentState.lightbox == null) return
        photoSeq += 1
        setState { copy(lightbox = null) }
    }

    private fun stepPhoto(delta: Int) {
        val box = currentState.lightbox ?: return
        val next = box.ids.getOrNull(box.index + delta) ?: return
        setState { copy(lightbox = box.copy(photoId = next, photo = null, loading = true, failed = false, activeFace = null, asking = false, note = "", similar = null)) }
        loadPhoto(next, quiet = false)
    }

    private fun loadPhoto(photoId: String, quiet: Boolean) {
        photoSeq += 1
        val mine = photoSeq
        if (!quiet) editLightbox { copy(loading = true, failed = false) }
        launch {
            val answer = repository.photo(photoId)
            if (mine != photoSeq || currentState.lightbox?.photoId != photoId) return@launch
            when (answer) {
                is ZillitResult.Success -> applyPhoto(answer.data)
                is ZillitResult.Failure ->
                    // Gone for good: on to the next one in the list, or close.
                    if (answer.error.stillsHttpStatus == NOT_FOUND) {
                        leavePhoto()
                    } else {
                        editLightbox { copy(loading = false, failed = !(quiet && photo != null)) }
                    }
            }
        }
    }

    private fun applyPhoto(answer: PhotoAnswer) {
        editLightbox { copy(photo = answer.photo, loading = false, failed = false, linksExpireAt = answer.linksExpireAt) }
        // The links in a photo's answer stop working after a while: ask again shortly before.
        val expires = answer.linksExpireAt
        if (expires <= 0) return
        launch {
            delay((expires - now() - LINK_MARGIN_MS).coerceAtLeast(PHOTO_LINK_MIN_MS))
            currentState.lightbox?.takeIf { it.photoId == answer.photo.id }?.let { loadPhoto(it.photoId, quiet = true) }
        }
    }

    /** Out of this photo for good (deleted, or not the reader's to see). */
    private fun leavePhoto() {
        val box = currentState.lightbox ?: return
        val at = box.index
        val next = if (at >= 0) box.ids.getOrNull(at + 1) ?: box.ids.getOrNull(at - 1) else null
        // The screen drops the tile too.
        setState {
            copy(
                gallery = gallery.copy(photos = removeIds(gallery.photos, listOf(box.photoId))),
                review = review.copy(photos = removeIds(review.photos, listOf(box.photoId))),
            )
        }
        loadSummary(soon = true)
        if (next != null) {
            setState { copy(lightbox = box.copy(photoId = next, ids = box.ids - box.photoId, photo = null, loading = true, activeFace = null, similar = null)) }
            loadPhoto(next, quiet = false)
        } else {
            closeLightbox()
        }
    }

    /** What every write in the lightbox ends with: show the outcome, take the photo it returned. */
    private fun writePhoto(call: suspend (String) -> ZillitResult<Wrote<PhotoAnswer>>) {
        val photoId = currentState.lightbox?.photoId ?: return
        editLightbox { copy(busy = true) }
        launch {
            val answer = call(photoId)
            editLightbox { copy(busy = false) }
            when (answer) {
                is ZillitResult.Success -> {
                    say(answer)
                    applyPhoto(answer.data.value)
                    refreshPhotos(listOf(photoId))
                }
                is ZillitResult.Failure -> {
                    say(answer)
                    // Somebody changed it meanwhile (or it is gone): show what is there now.
                    val status = answer.error.stillsHttpStatus
                    if (status == CONFLICT || status == NOT_FOUND) loadPhoto(photoId, quiet = true)
                }
            }
        }
    }

    /** The D key, or a click on Discard: open the reason box. */
    private fun askWhy() {
        val box = currentState.lightbox ?: return
        val row = box.photo?.approvals?.singleOrNull { it.canDecide } ?: return
        editLightbox { copy(asking = true, note = row.note) }
    }

    private fun decideInPhoto(memberId: String, state: Decision, note: String) {
        val box = currentState.lightbox ?: return
        val photoId = box.photoId
        val at = box.index
        val nextId = if (at >= 0) box.ids.getOrNull(at + 1) else null
        editLightbox { copy(busy = true) }
        launch {
            val answer = repository.decide(photoId, memberId, state, note)
            editLightbox { copy(busy = false) }
            // One toast per photo would bury a run of decisions: only a refusal speaks.
            if (!say(answer, quiet = true)) {
                val status = (answer as? ZillitResult.Failure)?.error?.stillsHttpStatus
                if (status == CONFLICT || status == NOT_FOUND) loadPhoto(photoId, quiet = true)
                return@launch
            }
            val data = (answer as ZillitResult.Success).data.value
            data.allowances?.let { applyAllowances(it) }
            editLightbox { copy(asking = false, note = "") }
            data.photo?.let { photo -> editLightbox { copy(photo = photo) } }
            refreshPhotos(listOf(photoId))
            reloadReviewCounts()
            if (currentState.page == StillsPage.Review) loadReview(silent = true)

            val left = data.photo?.approvals?.count { it.canDecide && it.state == Decision.Pending } ?: 0
            if (box.run && state != Decision.Pending && left == 0) {
                if (nextId != null) {
                    setState { copy(lightbox = currentState.lightbox?.copy(photoId = nextId, photo = null, loading = true, activeFace = null, asking = false, note = "", similar = null)) }
                    loadPhoto(nextId, quiet = false)
                } else {
                    closeLightbox()
                }
            }
        }
    }

    private fun download() {
        val photoId = currentState.lightbox?.photoId ?: return
        editLightbox { copy(busy = true) }
        launch {
            val answer = repository.originalLink(photoId)
            editLightbox { copy(busy = false) }
            when (answer) {
                is ZillitResult.Success -> sendEffect(StillsEffect.Download(answer.data.url, answer.data.name))
                is ZillitResult.Failure -> say(answer)
            }
        }
    }

    private fun retryPhoto() {
        val photoId = currentState.lightbox?.photoId ?: return
        editLightbox { copy(busy = true) }
        launch {
            val answer = repository.retryPhoto(photoId)
            editLightbox { copy(busy = false) }
            if (say(answer)) {
                refreshPhotos(listOf(photoId))
                loadPhoto(photoId, quiet = true)
            }
        }
    }

    private fun deletePhoto() {
        val photoId = currentState.lightbox?.photoId ?: return
        editLightbox { copy(busy = true, deleting = true) }
        launch {
            val answer = repository.deletePhoto(photoId)
            editLightbox { copy(busy = false, deleting = false) }
            if (say(answer)) leavePhoto()
        }
    }

    // -- find this person --------------------------------------------------------------

    private fun findSimilar(faceId: String, memberId: String?) {
        val photoId = currentState.lightbox?.photoId ?: return
        editLightbox { copy(similar = SimilarState(faceId = faceId, faceMemberId = memberId, memberId = memberId.orEmpty())) }
        launch {
            val answer = repository.findSimilar(photoId, faceId)
            if (currentState.lightbox?.similar?.faceId != faceId) return@launch
            when (answer) {
                is ZillitResult.Success -> editSimilar { copy(items = answer.data) }
                is ZillitResult.Failure -> {
                    say(answer)
                    editLightbox { copy(similar = null) }
                }
            }
        }
    }

    private fun applySimilar(asMember: Boolean) {
        val box = currentState.lightbox ?: return
        val panel = box.similar ?: return
        val chosen = panel.items.orEmpty().filter { it.key in panel.picked }
        if (chosen.isEmpty()) return
        editSimilar { copy(busy = true) }
        launch {
            val answer = repository.applyToFaces(
                items = chosen.take(MAX_APPLY).map { it.photoId to it.faceId },
                memberId = if (asMember) panel.memberId else null,
            )
            editSimilar { copy(busy = false) }
            if (!say(answer)) return@launch
            val failed = (answer as ZillitResult.Success).data.value.filterNot { it.ok }.map { it.key }.toSet()
            refreshPhotos(listOf(box.photoId))
            loadPhoto(box.photoId, quiet = true)
            if (failed.isEmpty()) {
                editLightbox { copy(similar = null) }
            } else {
                // The ones that could not be done stay listed (and ticked), with the rest gone.
                editSimilar { copy(items = items.orEmpty().filter { it.key in failed }, picked = failed) }
            }
        }
    }

    // -- the cast ----------------------------------------------------------------------

    private fun openMember(memberId: String, results: List<HeadshotOutcome>) {
        setState { copy(memberDialog = MemberDialogState(memberId = memberId, results = results)) }
        launch {
            val answer = repository.member(memberId)
            if (currentState.memberDialog?.memberId != memberId) return@launch
            when (answer) {
                is ZillitResult.Success -> applyMember(answer.data)
                is ZillitResult.Failure -> {
                    say(answer)
                    setState { copy(memberDialog = null) }
                }
            }
        }
    }

    private fun applyMember(member: Member) = editMember {
        copy(
            member = member,
            loading = false,
            name = member.name,
            character = member.characterName,
            needsApproval = member.approvalRequired,
            agent = member.agentUserId.orEmpty(),
            recognition = member.recognition,
            limits = limitsToInputs(member.discardLimits),
        )
    }

    /**
     * One Save for the card, where the web saved each field as it changed: a
     * new agent is told at once that photos are waiting on them, so that is not
     * sent for a slip of the hand in a list. The steps run in the web's order,
     * and stop at the first refusal.
     */
    private fun saveMember() {
        val dialog = currentState.memberDialog ?: return
        val member = dialog.member ?: return
        val limits = limitsFromInputs(dialog.limits) ?: return
        editMember { copy(busy = true) }
        launch {
            var last: ZillitResult<Wrote<Member>>? = null
            var saved = member

            val name = dialog.name.trim().takeIf { it != member.name }
            val character = dialog.character.trim().takeIf { it != member.characterName }
            val recognition = dialog.recognition.takeIf { it != member.recognition }
            val approval = dialog.needsApproval.takeIf { it != member.approvalRequired }
            if (name != null || character != null || recognition != null || approval != null) {
                last = repository.updateMember(member.id, name, character, recognition, approval)
                (last as? ZillitResult.Success)?.let { saved = it.data.value }
            }
            val wantedAgent = if (dialog.needsApproval) dialog.agent else ""
            if (last.isOk() && wantedAgent != member.agentUserId.orEmpty()) {
                last = repository.setMemberAgent(member.id, wantedAgent.takeIf { it.isNotBlank() })
                (last as? ZillitResult.Success)?.let { saved = it.data.value }
            }
            if (last.isOk() && dialog.needsApproval && limits != member.discardLimits) {
                last = repository.setMemberLimits(member.id, limits)
                (last as? ZillitResult.Success)?.let { saved = it.data.value }
            }
            editMember { copy(busy = false) }

            if (last == null) {
                // Nothing changed: the card simply closes.
                setState { copy(memberDialog = null) }
                return@launch
            }
            val ok = say(last)
            if (saved !== member) {
                applyMember(saved)
                reloadMembers()
            }
            if (ok) setState { copy(memberDialog = null) }
        }
    }

    private fun addHeadshots(picks: List<StillsPick>) {
        if (picks.isEmpty()) return
        val dialog = currentState.memberDialog ?: return
        val member = dialog.member ?: return
        val room = MAX_HEADSHOTS - member.headshots.size
        if (room <= 0 || headshots == null) return
        editMember { copy(busy = true) }
        launch {
            val outcome = headshots.add(member.id, picks.take(room))
            outcome.failure?.let { setState { copy(error = it.localised()) } }
            outcome.member?.let(::applyMember)
            editMember { copy(busy = false, results = outcome.results) }
            reloadMembers()
        }
    }

    private fun forceHeadshot(result: HeadshotOutcome) {
        val dialog = currentState.memberDialog ?: return
        val member = dialog.member ?: return
        if (headshots == null || result.path.isBlank()) return
        editMember { copy(busy = true) }
        launch {
            val pick = files?.describe(result.path) ?: StillsPick(result.path, result.name, 0, "")
            val outcome = headshots.add(member.id, listOf(pick), force = true)
            outcome.failure?.let { setState { copy(error = it.localised()) } }
            outcome.member?.let(::applyMember)
            editMember { copy(busy = false, results = results.filterNot { it === result } + outcome.results) }
            reloadMembers()
        }
    }

    private fun removeHeadshot(headshotId: String) {
        val dialog = currentState.memberDialog ?: return
        val member = dialog.member ?: return
        // A ✕ pressed once waits for the second press.
        if (dialog.confirmShot != headshotId) {
            editMember { copy(confirmShot = headshotId) }
            return
        }
        editMember { copy(confirmShot = null, busy = true) }
        launch {
            val answer = repository.removeHeadshot(member.id, headshotId)
            editMember { copy(busy = false) }
            if (say(answer)) {
                applyMember((answer as ZillitResult.Success).data.value)
                reloadMembers()
            }
        }
    }

    private fun eraseFaceData() {
        val member = currentState.memberDialog?.member ?: return
        editMember { copy(busy = true) }
        launch {
            val answer = repository.eraseMemberFaceData(member.id)
            editMember { copy(busy = false, ask = MemberAsk.None) }
            if (say(answer)) {
                applyMember((answer as ZillitResult.Success).data.value)
                reloadMembers()
            }
        }
    }

    private fun removeMember(force: Boolean) {
        val member = currentState.memberDialog?.member ?: return
        editMember { copy(busy = true) }
        launch {
            val answer = repository.removeMember(member.id, force)
            editMember { copy(busy = false) }
            // Named in photos: say what removing them does, and ask again.
            val failure = (answer as? ZillitResult.Failure)?.error
            if (!force && failure?.stillsHttpStatus == CONFLICT && failure.stillsMessageKey == MEMBER_IN_USE) {
                editMember { copy(ask = MemberAsk.RemoveInUse) }
                return@launch
            }
            editMember { copy(ask = MemberAsk.None) }
            if (say(answer)) {
                reloadMembers()
                setState { copy(memberDialog = null) }
            }
        }
    }

    // -- enrolment ---------------------------------------------------------------------

    private fun freshEnroll(me: com.zillit.desktop.feature.selectstills.domain.StillsMe = currentState.me) =
        EnrollState(limits = limitsToInputs(me.settings.defaultDiscardLimits))

    private fun enrol() {
        val form = currentState.enroll ?: return
        val limits = limitsFromInputs(form.limits) ?: return
        if (form.busy || form.name.isBlank() || !form.consent || !currentState.me.settings.attested) return
        editEnroll { copy(busy = true) }
        launch {
            val answer = repository.createMember(
                MemberDraft(
                    name = form.name,
                    characterName = form.character,
                    approvalRequired = form.needsApproval,
                    agentUserId = form.agent.takeIf { form.needsApproval && it.isNotBlank() },
                    recognition = form.recognition,
                    consent = form.consent,
                    discardLimits = limits,
                ),
            )
            if (!say(answer)) {
                editEnroll { copy(busy = false) }
                return@launch
            }
            var saved = (answer as ZillitResult.Success).data.value
            var results = emptyList<HeadshotOutcome>()
            if (form.picks.isNotEmpty() && headshots != null) {
                val outcome = headshots.add(saved.id, form.picks)
                outcome.failure?.let { setState { copy(error = it.localised()) } }
                outcome.member?.let { saved = it }
                results = outcome.results
            }
            editEnroll { copy(busy = false) }
            reloadMembers()
            // Name the face that started this, now that they exist.
            form.forFaceId?.let { faceId ->
                writePhoto { photoId -> repository.setFace(photoId, faceId, FaceEdit.Name(saved.id)) }
            }
            setState { copy(enroll = if (form.inDialog) null else freshEnroll()) }
            // A headshot it turned away: the card opens on it, so it can be put right.
            if (results.any { !it.ok }) openMember(saved.id, results)
        }
    }

    // -- uploads -----------------------------------------------------------------------

    /**
     * Dropped paths as files. A headshot is one picture, so a dropped folder
     * is walked through just as the upload's is — the reader meant what is in
     * it, not the folder.
     */
    private fun describe(paths: List<String>): List<StillsPick> {
        val reader = files ?: return emptyList()
        return paths.flatMap { path -> reader.walkPhotos(path) }
    }

    private fun queueFiles(picks: List<StillsPick>) {
        val queue = queue ?: return
        if (picks.isEmpty()) {
            setState { copy(error = str(S.desktop_stk_upload_nothing)) }
            return
        }
        val outcome = queue.add(
            picks = picks,
            shootLabel = currentState.upload.shootLabel,
            types = currentState.me.upload.types,
            maxBytes = currentState.me.upload.maxBytes,
        )
        when {
            outcome.added == 0 && outcome.refused == 0 -> setState { copy(error = str(S.desktop_stk_upload_nothing)) }
            outcome.refused == 1 -> setState { copy(error = str(S.desktop_stk_upload_some_refused_one)) }
            outcome.refused > 1 -> setState { copy(error = str(S.desktop_stk_upload_some_refused, outcome.refused)) }
        }
    }

    /** A drop, which may be whole folders: a card is usually dragged in as one. */
    private fun queueFolders(paths: List<String>) {
        val reader = files ?: return
        launch { queueFiles(paths.flatMap { reader.walkPhotos(it) }) }
    }

    private fun chooseFolder() {
        val reader = files ?: return
        launch {
            val folder = chooseStillsFolder(str(S.desktop_stk_upload_choose_folder)) ?: return@launch
            queueFiles(reader.walkPhotos(folder))
        }
    }

    // -- settings ----------------------------------------------------------------------

    /**
     * Somebody else saved: show theirs (this reader's unsaved typing loses,
     * which is the lesser surprise on a page two people rarely edit at once).
     */
    private fun SettingsFormState.fromStored(stored: com.zillit.desktop.feature.selectstills.domain.StillsSettings) = copy(
        scope = stored.viewerScope,
        limits = limitsToInputs(stored.defaultDiscardLimits),
        auto = stored.thresholds.auto.toString(),
        suggest = stored.thresholds.suggest.toString(),
        margin = stored.thresholds.margin.toString(),
    )

    private fun saveSettings() {
        val form = currentState.settings
        val stored = currentState.me.settings
        val limits = limitsFromInputs(form.limits) ?: return
        val numbers = form.numbers() ?: return
        val scope = form.scope.takeIf { it != stored.viewerScope }
        val newLimits = limits.takeIf { it != stored.defaultDiscardLimits }
        val newNumbers = numbers.takeIf { it != stored.thresholds }
        if (scope == null && newLimits == null && newNumbers == null) return
        setState { copy(settings = settings.copy(busy = true)) }
        launch {
            val answer = repository.updateSettings(scope, newLimits, newNumbers)
            setState { copy(settings = settings.copy(busy = false)) }
            if (say(answer)) reloadMe()
        }
    }

    private fun wipeFaceData() {
        setState { copy(settings = settings.copy(busy = true)) }
        launch {
            val answer = repository.deleteAllFaceData()
            setState { copy(settings = settings.copy(busy = false, askingWipe = false)) }
            if (say(answer)) {
                reloadMe()
                reloadMembers()
            }
        }
    }

    // -- socket ------------------------------------------------------------------------

    private fun onPhotoFrames(ids: List<String>) {
        if (ids.isEmpty()) return
        val state = currentState
        if (state.page == StillsPage.Photos) refreshPhotos(ids)
        if (state.page == StillsPage.Review) reviewSoon()
        state.lightbox?.takeIf { it.photoId in ids }?.let { loadPhoto(it.photoId, quiet = true) }
        // The count beside "Review" and the "still processing" figure are
        // refreshed at most every few seconds.
        if (now() - countedAt < COUNT_EVERY_MS) return
        countedAt = now()
        reloadReviewCounts()
        if (state.canPost) reloadMe()
    }

    private fun onMemberFrame() {
        reloadMembers()
        reloadMe()
        if (currentState.page == StillsPage.Review) reviewSoon()
    }

    // -- plumbing ----------------------------------------------------------------------

    private fun editLightbox(change: LightboxState.() -> LightboxState) =
        setState { copy(lightbox = lightbox?.change()) }

    private fun editSimilar(change: SimilarState.() -> SimilarState) =
        setState { copy(lightbox = lightbox?.copy(similar = lightbox.similar?.change())) }

    private fun editMember(change: MemberDialogState.() -> MemberDialogState) =
        setState { copy(memberDialog = memberDialog?.change()) }

    private fun editEnroll(change: EnrollState.() -> EnrollState) =
        setState { copy(enroll = enroll?.change()) }

    private fun ZillitResult<*>?.isOk(): Boolean = this == null || this is ZillitResult.Success

    /**
     * Toast the outcome of a write from the backend's own `message` key — this
     * app never authors success or error copy.
     *
     * [quiet] skips the success line (a run of keep / discard decisions would
     * otherwise stack one per photo); a refusal always speaks.
     *
     * @return whether the call succeeded.
     */
    private fun say(answer: ZillitResult<*>, quiet: Boolean = false): Boolean = when (answer) {
        is ZillitResult.Success -> {
            // Only a write carries words, and only the service's own: a key it
            // had nothing to say about stays silent rather than being dressed
            // in a sentence this app invented.
            val said = (answer.data as? Wrote<*>)?.message
                ?.takeIf { it.isNotBlank() }
                ?.localisedMessage()
                ?.takeIf { it.isNotBlank() }
            if (!quiet && said != null) setState { copy(notice = said) }
            true
        }
        is ZillitResult.Failure -> {
            setState { copy(error = answer.error.localised()) }
            false
        }
    }

    private fun SettingsFormState.numbers(): Thresholds? {
        val auto = auto.trim().toIntOrNull() ?: return null
        val suggest = suggest.trim().toIntOrNull() ?: return null
        val margin = margin.trim().toIntOrNull() ?: return null
        if (auto !in 0..FULL || suggest !in 0..FULL || margin !in 0..FULL) return null
        if (suggest > auto || margin > HALF) return null
        return Thresholds(auto, suggest, margin)
    }

    private companion object {
        const val PAGE = 60
        const val ID_LIMIT = 200
        const val MAX_APPLY = 200
        const val SUMMARY_EVERY_MS = 3000L
        const val REVIEW_EVERY_MS = 4000L
        const val COUNT_EVERY_MS = 5000L
        const val SHOOT_AFTER_MS = 350L
        const val LINK_MARGIN_MS = 90_000L
        const val LINK_MIN_MS = 60_000L
        const val PHOTO_LINK_MIN_MS = 30_000L
        const val RIGHTS_POLLS = 10
        const val RIGHTS_POLL_MS = 500L
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val FULL = 100
        const val HALF = 50
        const val MEMBER_IN_USE = "still_kills_member_in_use"
    }
}

/**
 * Whether the settings form's three numbers are usable, for the Save button.
 *
 * All three are a similarity out of a hundred; "suggest" may not sit above
 * "name automatically" (the system would propose a name it would have given
 * anyway), and the lead over the next match is capped at half.
 */
internal fun SettingsFormState.numbersOk(): Boolean {
    val values = listOf(auto, suggest, margin).map { it.trim().toIntOrNull() }
    if (values.any { it == null || it !in 0..FULL_SCALE }) return false
    val (auto, suggest, margin) = values.map { it ?: 0 }
    return suggest <= auto && margin <= MAX_MARGIN
}

private const val FULL_SCALE = 100
private const val MAX_MARGIN = 50
