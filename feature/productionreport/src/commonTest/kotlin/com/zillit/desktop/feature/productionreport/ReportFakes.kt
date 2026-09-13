package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.CellKind
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.PageCell
import com.zillit.desktop.feature.productionreport.domain.PageRow
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReminderRequest
import com.zillit.desktop.feature.productionreport.domain.ReportBadgeSource
import com.zillit.desktop.feature.productionreport.domain.ReportChatOpener
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportDelivery
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportPublishing
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportRepository
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.ReviewAssignee
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
import com.zillit.desktop.feature.productionreport.ui.ReportEffect
import com.zillit.desktop.feature.productionreport.ui.ReportServices
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    val author = ReportViewer(
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
        status: ReportStatus = ReportStatus.Draft,
        createdById: String = "me",
        approvals: List<ApprovalRequest> = emptyList(),
    ) = ReportSummary(
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
        payload = ComposeReport.defaultTemplate("2026-09-13"),
        isCreateYourOwn = createYourOwn,
    )

    fun document() = SheetPayload(
        rows = listOf(PageRow(0, cells = listOf(PageCell(0, title = "Scenes", kind = CellKind.Table)))),
    )
}

/** A report service that answers from memory and records what it was asked. */
@Suppress("TooManyFunctions") // One override per server operation.
internal class FakeReportRepository : ReportRepository {
    /** Live frames the lists answer; a test emits into it. */
    val liveEvents = MutableSharedFlow<ReportSyncEvent>()
    override val events: Flow<ReportSyncEvent> get() = liveEvents

    var metadata = SheetMetadata()
    var stock: List<StockTemplate> = emptyList()
    var rows: (ReportQuery) -> List<ReportSummary> = { emptyList() }
    val details = mutableMapOf<String, ReportDetail>()
    val thread = mutableListOf<ReportComment>()
    var deleteAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit)

    val queries = mutableListOf<ReportQuery>()
    val metadataWrites = mutableListOf<MetadataUpdate>()
    val detailRequests = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val created = mutableListOf<Pair<String, SheetPayload>>()
    val revisions = mutableListOf<String>()
    val signatureSends = mutableListOf<Pair<String, List<ReviewAssignee>>>()
    val commentSends = mutableListOf<Pair<String, List<ReviewAssignee>>>()
    val approvals = mutableListOf<Pair<String, ApprovalDecision>>()
    val reminders = mutableListOf<Pair<String, ReminderRequest>>()
    val publishes = mutableListOf<Pair<String, Boolean>>()
    val commentAdds = mutableListOf<Pair<String, String>>()
    val commentAuthors = mutableListOf<SheetMember?>()
    val commentEdits = mutableListOf<Triple<String, String, String>>()
    val commentDeletes = mutableListOf<Pair<String, String>>()

    private val ok = ZillitResult.Success(Unit)

    override suspend fun metadata(projectId: String): ZillitResult<SheetMetadata> = ZillitResult.Success(metadata)

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

    override suspend fun reports(query: ReportQuery): ZillitResult<List<ReportSummary>> {
        queries += query
        return ZillitResult.Success(rows(query))
    }

    override suspend fun report(id: String): ZillitResult<ReportDetail> {
        detailRequests += id
        val detail = details[id] ?: ReportDetail(Samples.row(id, "Report $id"), SheetPayload())
        return ZillitResult.Success(detail)
    }

    override suspend fun create(
        projectId: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary> {
        created += name to payload
        return ZillitResult.Success(Samples.row("new${created.size}", name, createdById = createdById))
    }

    override suspend fun saveRevision(
        id: String,
        name: String,
        payload: SheetPayload,
        createdBy: String,
        createdById: String,
    ): ZillitResult<ReportSummary?> {
        revisions += id
        return ZillitResult.Success(null)
    }

    override suspend fun delete(id: String): ZillitResult<Unit> {
        if (deleteAnswer is ZillitResult.Success) deleted += id
        return deleteAnswer
    }

    override suspend fun submitForApproval(
        id: String,
        approvers: List<ReviewAssignee>,
        createdBy: String,
    ): ZillitResult<Unit> {
        signatureSends += id to approvers
        return ok
    }

    override suspend fun submitForInternalApproval(
        id: String,
        approvers: List<ReviewAssignee>,
        createdBy: String,
    ): ZillitResult<Unit> {
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
    ): ZillitResult<Unit> {
        publishes += id to continuation
        return ok
    }

    var commentReads = 0

    override suspend fun comments(id: String): ZillitResult<List<ReportComment>> {
        commentReads += 1
        return ZillitResult.Success(thread.toList())
    }

    override suspend fun addComment(id: String, author: SheetMember?, text: String): ZillitResult<ReportComment?> {
        commentAdds += id to text
        commentAuthors += author
        val comment = ReportComment(
            id = "c${thread.size + 1}",
            authorId = author?.userId.orEmpty(),
            authorName = author?.fullName.orEmpty(),
            text = text,
        )
        thread += comment
        return ZillitResult.Success(comment)
    }

    override suspend fun editComment(id: String, commentId: String, text: String): ZillitResult<ReportComment?> {
        commentEdits += Triple(id, commentId, text)
        thread.replaceAll { if (it.id == commentId) it.copy(text = text) else it }
        return ZillitResult.Success(null)
    }

    override suspend fun deleteComment(id: String, commentId: String): ZillitResult<Unit> {
        commentDeletes += id to commentId
        thread.removeAll { it.id == commentId }
        return ok
    }
}

/** Library, chat and signature storage, recorded. */
internal class FakePublishing : ReportPublishing {
    var distributes = true
    var signatureAnswer: ZillitResult<ApprovalDecision.Signature> =
        ZillitResult.Success(
            ApprovalDecision.Signature(media = "p/sig.png", thumbnail = "", bucket = "b", region = "eu"),
        )

    /** File name, from-draft, shoot date. */
    val library = mutableListOf<Triple<String, Boolean, String?>>()

    /** File name, replace-previous. */
    val chatPosts = mutableListOf<Pair<String, Boolean>>()

    override fun canDistribute(): Boolean = distributes

    override suspend fun sendToDocumentDistribution(
        pdf: ByteArray,
        fileName: String,
        fromDraft: Boolean,
        dateYmd: String?,
    ): ZillitResult<Unit> {
        library += Triple(fileName, fromDraft, dateYmd)
        return ZillitResult.Success(Unit)
    }

    override suspend fun postToChat(pdf: ByteArray, fileName: String, replacePrevious: Boolean): ZillitResult<Unit> {
        chatPosts += fileName to replacePrevious
        return ZillitResult.Success(Unit)
    }

    override suspend fun attachDocuments(replacePrevious: Boolean): ZillitResult<Int> = ZillitResult.Success(0)

    override suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature> = signatureAnswer
}

internal class FakeDelivery : ReportDelivery {
    override suspend fun pdf(reportId: String): ZillitResult<ByteArray> = ZillitResult.Success(byteArrayOf(1, 2, 3))

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>> =
        ZillitResult.Success(emptyList())

    override suspend fun savePdf(fileName: String, pdf: ByteArray): ZillitResult<Unit> = ZillitResult.Success(Unit)
}

internal class FakeBadges : ReportBadgeSource {
    val threadsRead = mutableListOf<String>()

    override fun readCommentThread(reportId: String) {
        threadsRead += reportId
    }
}

/** One view model over the fakes, with its toasts collected from the first frame. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReportHarness(private val dispatcher: TestDispatcher) {
    val repository = FakeReportRepository()
    val publishing = FakePublishing()
    val badges = FakeBadges()
    val chats = mutableListOf<Pair<String, String>>()
    val toasts = mutableListOf<ReportEffect.Toast>()

    fun start(scope: TestScope, viewer: ReportViewer = Samples.author, hasChat: Boolean = false): ReportViewModel {
        val vm = ReportViewModel(
            repository = repository,
            services = ReportServices(
                delivery = FakeDelivery(),
                publishing = publishing,
                callSheets = PublishedCallSheetLookup { null },
                badges = badges,
                chat = ReportChatOpener { userId, fullName ->
                    chats += userId to fullName
                    true
                },
            ),
            resolveViewer = { viewer },
            projectIdProvider = { Samples.PROJECT },
            membersProvider = { Samples.crew },
            nowMillis = { Samples.NOW },
            hasChat = hasChat,
        )
        scope.backgroundScope.launch(dispatcher) {
            vm.effects.collect { effect -> if (effect is ReportEffect.Toast) toasts += effect }
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
