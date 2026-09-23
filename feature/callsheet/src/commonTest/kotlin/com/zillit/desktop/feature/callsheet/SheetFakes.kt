package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.CellKind
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.PageCell
import com.zillit.desktop.feature.callsheet.domain.PageRow
import com.zillit.desktop.feature.callsheet.domain.AccessPage
import com.zillit.desktop.feature.callsheet.domain.BadgeKind
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
import com.zillit.desktop.feature.callsheet.domain.PickedDocument
import com.zillit.desktop.feature.callsheet.domain.ReminderRequest
import com.zillit.desktop.feature.callsheet.domain.SheetBadgeSource
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.UnitMessage
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetPublishing
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.ReviewAssignee
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.domain.ToolAccessGrant
import com.zillit.desktop.feature.callsheet.ui.SheetEffect
import com.zillit.desktop.feature.callsheet.ui.SheetServices
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/** The people and rows the view model tests share. */
internal object Samples {
    const val PROJECT = "p1"

    /** 2026-09-13 09:00 UTC. */
    const val NOW = 1_789_290_000_000L

    val author = CallSheetViewer(
        userId = "me",
        displayName = "Author",
        designation = "2nd AD",
        canPost = true,
        ready = true,
    )

    val crew = listOf(
        SheetMember("me", "Author", department = "Production", designation = "2nd AD", status = "accepted"),
        SheetMember("u2", "Uma", department = "Production", designation = "Producer", status = "accepted"),
        SheetMember("u3", "Vic", department = "Direction", designation = "Director", status = "accepted"),
    )

    fun row(
        id: String,
        name: String,
        status: CallSheetStatus = CallSheetStatus.Draft,
        createdById: String = "me",
        approvals: List<ApprovalRequest> = emptyList(),
    ) = CallSheetSummary(
        id = id,
        serialNo = id,
        name = name,
        status = status,
        createdBy = "Author",
        createdById = createdById,
        approvals = approvals,
    )

    fun stock(identifier: String, name: String, createYourOwn: Boolean = false) = StockTemplate(
        identifier = identifier,
        displayName = name,
        payload = ComposeSheet.defaultTemplate(NOW),
        isCreateYourOwn = createYourOwn,
    )

    fun document() = SheetPayload(
        rows = listOf(PageRow(0, cells = listOf(PageCell(0, title = "Scenes", kind = CellKind.Table)))),
    )
}

/** A call-sheet service that answers from memory and records what it was asked. */
@Suppress("TooManyFunctions") // One override per server operation.
internal class FakeSheetRepository : CallSheetRepository {
    /** Live frames the lists answer; a test emits into it. */
    val liveEvents = MutableSharedFlow<SheetSyncEvent>()
    override val events: Flow<SheetSyncEvent> get() = liveEvents

    var metadata = SheetMetadata()

    /** Set to make the metadata GET FAIL (a settled failure, not "not yet"). */
    var metadataAnswer: ZillitResult<SheetMetadata>? = null
    var stock: List<StockTemplate> = emptyList()
    var rows: (SheetQuery) -> List<CallSheetSummary> = { emptyList() }
    val details = mutableMapOf<String, CallSheetDetail>()
    val thread = mutableListOf<SheetComment>()
    var deleteAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit)

    val queries = mutableListOf<SheetQuery>()
    val metadataWrites = mutableListOf<MetadataUpdate>()
    val detailRequests = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val created = mutableListOf<Pair<String, SheetPayload>>()
    val revisions = mutableListOf<String>()
    val signatureSends = mutableListOf<String>()
    val commentSends = mutableListOf<Pair<String, List<ReviewAssignee>>>()
    val approvals = mutableListOf<Pair<String, ApprovalDecision>>()
    val reminders = mutableListOf<Pair<String, ReminderRequest>>()
    val publishes = mutableListOf<Pair<String, Boolean>>()
    val publishNotes = mutableListOf<String>()
    var accessPage = AccessPage(emptyList(), 0)
    val accessWrites = mutableListOf<Pair<String, Boolean>>()
    val commentAdds = mutableListOf<Pair<String, String>>()
    val commentAuthors = mutableListOf<SheetMember?>()
    val commentEdits = mutableListOf<Triple<String, String, String>>()
    val commentDeletes = mutableListOf<Pair<String, String>>()

    private val ok = ZillitResult.Success(Unit)

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> =
        metadataAnswer ?: ZillitResult.Success(metadata)

    override suspend fun saveMetadata(projectId: String, update: MetadataUpdate): ZillitResult<SheetMetadata?> {
        metadataWrites += update
        return ZillitResult.Success(null)
    }

    override suspend fun stockTemplates(): ZillitResult<List<StockTemplate>> = ZillitResult.Success(stock)

    override suspend fun savedTemplates(): ZillitResult<List<SavedTemplate>> = ZillitResult.Success(emptyList())

    override suspend fun savedTemplate(id: String): ZillitResult<SavedTemplate> =
        ZillitResult.Success(SavedTemplate(id, "Saved", payload = Samples.document()))

    override suspend fun createTemplate(payload: SheetPayload): ZillitResult<SavedTemplate?> =
        ZillitResult.Success(SavedTemplate("t1", "Draft Template 1"))

    override suspend fun updateTemplate(id: String, payload: SheetPayload): ZillitResult<Unit> = ok

    override suspend fun deleteTemplate(id: String): ZillitResult<Unit> = ok

    override suspend fun sheets(query: SheetQuery): ZillitResult<List<CallSheetSummary>> {
        queries += query
        return ZillitResult.Success(rows(query))
    }

    override suspend fun sheet(id: String): ZillitResult<CallSheetDetail> {
        detailRequests += id
        val detail = details[id] ?: CallSheetDetail(Samples.row(id, "Sheet $id"), SheetPayload())
        return ZillitResult.Success(detail)
    }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<CallSheetSummary> {
        created += name to payload
        return ZillitResult.Success(Samples.row("new${created.size}", name, createdById = createdById))
    }

    override suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<Unit> {
        revisions += id
        return ok
    }

    override suspend fun delete(id: String): ZillitResult<Unit> {
        if (deleteAnswer is ZillitResult.Success) deleted += id
        return deleteAnswer
    }

    override suspend fun submitForApproval(id: String): ZillitResult<Unit> {
        signatureSends += id
        return ok
    }

    override suspend fun submitForInternalApproval(id: String, approvers: List<ReviewAssignee>): ZillitResult<Unit> {
        commentSends += id to approvers
        return ok
    }

    override suspend fun approve(requestId: String, decision: ApprovalDecision): ZillitResult<Unit> {
        approvals += requestId to decision
        return ok
    }

    override suspend fun reject(requestId: String, reason: String): ZillitResult<Unit> = ok

    override suspend fun sendReminder(id: String, reminder: ReminderRequest): ZillitResult<Unit> {
        reminders += id to reminder
        return ok
    }

    override suspend fun publish(
        id: String,
        publishedBy: String,
        publishedById: String,
        continuation: Boolean,
        notes: String,
    ): ZillitResult<Unit> {
        publishes += id to continuation
        publishNotes += notes
        return ok
    }

    var commentReads = 0

    override suspend fun comments(id: String): ZillitResult<List<SheetComment>> {
        commentReads += 1
        return ZillitResult.Success(thread.toList())
    }

    override suspend fun addComment(
        id: String,
        author: SheetMember?,
        fallbackName: String,
        text: String,
    ): ZillitResult<SheetComment?> {
        commentAdds += id to text
        commentAuthors += author
        val comment = SheetComment(
            id = "c${thread.size + 1}",
            authorId = author?.userId.orEmpty(),
            authorName = author?.fullName ?: fallbackName,
            text = text,
        )
        thread += comment
        return ZillitResult.Success(comment)
    }

    override suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<SheetComment?> {
        commentEdits += Triple(id, commentId, text)
        thread.replaceAll { if (it.id == commentId) it.copy(text = text) else it }
        return ZillitResult.Success(null)
    }

    override suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit> {
        commentDeletes += id to commentId
        thread.removeAll { it.id == commentId }
        return ok
    }

    override suspend fun accessPage(page: Int, limit: Int, currentUserId: String?): ZillitResult<AccessPage> =
        ZillitResult.Success(accessPage)

    override suspend fun setToolAccess(userId: String, enabled: Boolean): ZillitResult<ToolAccessGrant> {
        accessWrites += userId to enabled
        return ZillitResult.Success(ToolAccessGrant(enabled, enabled, enabled))
    }
}

/** Library, chat and signature storage, recorded. */
internal class FakePublishing : CallSheetPublishing {
    var distributes = true
    var signatureAnswer: ZillitResult<ApprovalDecision.Signature> =
        ZillitResult.Success(
            ApprovalDecision.Signature(media = "p/sig.png", thumbnail = "", bucket = "b", region = "eu"),
        )

    /** File name, from-draft, shoot date. */
    val library = mutableListOf<Triple<String, Boolean, Long?>>()

    /** File name, replace-previous. */
    val unitPosts = mutableListOf<Pair<String, Boolean>>()

    /** The `replace_chat_id` of each unit post, null when none was sent. */
    val replaceChatIds = mutableListOf<String?>()

    /** The Home call-sheet unit's messages the Replace picker reads. */
    var unitMessages: ZillitResult<List<UnitMessage>> = ZillitResult.Success(emptyList())
    var unitPostAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit)

    /** File name, receiver. */
    val chatSends = mutableListOf<Pair<String, String>>()

    /** What the next file picker answers. */
    var picked: PickedDocument? = null

    override fun canDistribute(): Boolean = distributes

    override suspend fun sendToDocumentDistribution(
        pdf: ByteArray,
        fileName: String,
        fromDraft: Boolean,
        dateMs: Long?,
    ): ZillitResult<Unit> {
        library += Triple(fileName, fromDraft, dateMs)
        return ZillitResult.Success(Unit)
    }

    override suspend fun unitMessages(): ZillitResult<List<UnitMessage>> = unitMessages

    override suspend fun postToUnit(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        caption: String,
        replacePrevious: Boolean,
        replaceChatId: String?,
    ): ZillitResult<Unit> {
        unitPosts += fileName to replacePrevious
        replaceChatIds += replaceChatId
        return unitPostAnswer
    }

    override suspend fun pickPdf(): PickedDocument? = picked

    override suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature> = signatureAnswer

    override suspend fun sendPdfToChat(pdf: ByteArray, fileName: String, receiverId: String): ZillitResult<Unit> {
        chatSends += fileName to receiverId
        return ZillitResult.Success(Unit)
    }
}

internal class FakeDelivery : CallSheetDelivery {
    override suspend fun pdf(sheetId: String): ZillitResult<ByteArray> = ZillitResult.Success(byteArrayOf(1, 2, 3))

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>> =
        ZillitResult.Success(emptyList())

    override suspend fun savePdf(fileName: String, pdf: ByteArray): ZillitResult<Unit> = ZillitResult.Success(Unit)
}

/** One read of one sheet's badges of one kind on one surface. */
internal data class BadgeRead(val surface: BadgeSurface, val kind: BadgeKind, val sheetId: String)

internal class FakeBadges : SheetBadgeSource {
    /** The unread ledger the host would stream; tests seed it so a read has something to clear. */
    override val leaves = MutableStateFlow<List<BadgeLeaf>>(emptyList())
    val reads = mutableListOf<BadgeRead>()

    override fun read(surface: BadgeSurface, kind: BadgeKind, sheetId: String) {
        reads += BadgeRead(surface, kind, sheetId)
    }
}

/** One view model over the fakes, with its toasts collected from the first frame. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SheetHarness(private val dispatcher: TestDispatcher) {
    val repository = FakeSheetRepository()
    val publishing = FakePublishing()
    val badges = FakeBadges()
    val toasts = mutableListOf<SheetEffect.Toast>()

    fun start(scope: TestScope, viewer: CallSheetViewer = Samples.author): CallSheetViewModel {
        val vm = CallSheetViewModel(
            repository = repository,
            services = SheetServices(
                delivery = FakeDelivery(),
                publishing = publishing,
                badges = badges,
            ),
            resolveViewer = { viewer },
            projectIdProvider = { Samples.PROJECT },
            membersProvider = { Samples.crew },
            nowMillis = { Samples.NOW },
        )
        scope.backgroundScope.launch(dispatcher) {
            vm.effects.collect { effect -> if (effect is SheetEffect.Toast) toasts += effect }
        }
        scope.runCurrent()
        vm.start()
        scope.settle()
        return vm
    }
}

/** Runs everything queued, then the effect collector the idle check skips. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    advanceUntilIdle()
    runCurrent()
}
