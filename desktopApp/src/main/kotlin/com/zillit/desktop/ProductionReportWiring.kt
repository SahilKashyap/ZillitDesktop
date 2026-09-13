package com.zillit.desktop

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.callsheet.data.CallSheetRepositoryImpl
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.ui.BoardToolProvider
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.productionreport.data.ReportRepositoryImpl
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReportBadgeSource
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportDelivery
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportPublishing
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.ReportWeatherSource
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetPdfPage
import com.zillit.desktop.feature.productionreport.ui.ProductionReportToolProvider
import com.zillit.desktop.feature.productionreport.ui.ReportServices
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One report engine, three tools: the production report, and the AD / Wrap
 * reports that ride the same service under `shared.reportType`.
 */
internal fun AppGraph.Ready.buildReport(
    kind: ReportKind,
    permissions: () -> ProjectPermissions,
    @Suppress("UNUSED_PARAMETER") today: () -> kotlinx.datetime.LocalDate,
): ReportViewModel = ReportViewModel(
    repository = ReportRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    services = ReportServices(
        delivery = productionReportDelivery(),
        publishing = productionReportPublishing(permissions),
        callSheets = productionReportCallSheets(CallSheetRepositoryImpl(apiClient, config)),
        badges = productionReportBadges(),
        weather = productionReportWeather(),
    ),
    kind = kind,
    resolveViewer = { productionReportViewer(permissions(), kind) },
    projectIdProvider = { projectContext?.context?.value?.project?.projectId },
    membersProvider = { reportMembers() },
    nowMillis = System::currentTimeMillis,
    // Only the production report has a unit chat; AD and Wrap open on the manager.
    hasChat = kind == ReportKind.Production,
)

/**
 * The production report tool's unit chat — the web's `ProductionReportV2`,
 * which is the Home unit-chat on the report service: the tool's own unit,
 * routes straight off `production-report/` (no `chat/`), and
 * `production_report:message:*` live events.
 */
internal fun AppGraph.Ready.productionReportChatFeed(permissions: () -> ProjectPermissions): HomeFeedViewModel =
    boardFeed(
        board = PRODUCTION_REPORT_BOARD,
        toolIdentifier = ReportKind.Production.toolIdentifier,
        permissions = permissions,
        service = ZillitService.ProductionReport,
        chatSegment = "",
    )

/** The board key the report chat's feed and its live events share. */
internal const val PRODUCTION_REPORT_BOARD = "production-report"

/**
 * The tool window: crew photos on every face, "Chat with …" opening the
 * one-to-one thread in Chat & Calls, and — for the production report — the
 * unit chat in the Chat workspace, drawn by the same board every notice
 * board uses.
 */
internal fun reportToolProvider(
    viewModel: ReportViewModel,
    graph: AppGraph,
    chat: ChatViewModel?,
    unitChat: HomeFeedViewModel? = null,
    boardContext: HomeBoardContext = HomeBoardContext(),
): ProductionReportToolProvider {
    val board = unitChat?.let { feed ->
        BoardToolProvider(
            path = "${viewModel.kind.path}/chat",
            title = viewModel.kind.title,
            icon = ZillitToolIcons.ProductionReport,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    return ProductionReportToolProvider(
        viewModel,
        loadAvatar = { userId -> (graph as? AppGraph.Ready)?.let { crewFaceLoader(it)(userId) } },
        chat = board?.let { host ->
            @Composable { route: WorkspaceRoute, navigator: WindowNavigator -> host.Content(route, navigator) }
        },
        openChat = { navigator, userId, fullName ->
            chat?.onEvent(ChatEvent.OpenThread(CrewContact(userId = userId, fullName = fullName)))
            navigator.openInNewWindow(WorkspaceRoute.Tool(CHAT_PATH))
        },
    )
}

private fun AppGraph.Ready.productionReportViewer(permissions: ProjectPermissions, kind: ReportKind): ReportViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return ReportViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
        designation = me?.designationText().orEmpty(),
        toolIdentifier = kind.toolIdentifier,
    )
}

/**
 * The crew as the report's pickers and crew sections need them — with their
 * standing, so approver and recipient lists leave out anyone who left or has
 * not joined, and with designations and departments as words, not label keys.
 */
private fun AppGraph.Ready.reportMembers(): List<SheetMember> =
    projectContext?.context?.value?.users.orEmpty().map { user ->
        SheetMember(
            userId = user.userId,
            fullName = user.fullName,
            department = user.department?.takeIf { it.isNotBlank() }?.let { Labels.translate(it) }.orEmpty(),
            designation = user.designationText().orEmpty(),
            status = user.status,
            isAdmin = user.isAdmin,
            avatarUrl = user.avatarUrl,
        )
    }

// PDF ----------------------------------------------------------------------------------------------

/**
 * The server-rendered PDF: fetched outside the enveloped client, drawn to
 * page images with PDFBox for the viewer, and saved to Downloads.
 */
internal fun AppGraph.Ready.productionReportDelivery(): ReportDelivery = object : ReportDelivery {

    private val work = PdfBoxWork()
    private val base = config.apiV2(ZillitService.ProductionReport).trimEnd('/')

    override suspend fun pdf(reportId: String): ZillitResult<ByteArray> = runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get("$base/production-reports/$reportId/pdf") {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
        }
        check(response.status.isSuccess()) { "PDF fetch answered ${response.status}" }
        response.readRawBytes()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "PDF fetch failed")) },
    )

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SheetPdfPage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { page -> SheetPdfPage(page.page, page.imageBytes, page.widthPx, page.heightPx) },
            )
        }

    override suspend fun savePdf(fileName: String, pdf: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, pdf)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

/**
 * The report's seed source: the newest PUBLISHED call sheet, read through the
 * call-sheet module and re-parsed into the report module's payload type via
 * the wire shape both share.
 */
internal fun productionReportCallSheets(
    callSheets: CallSheetRepository,
): PublishedCallSheetLookup = PublishedCallSheetLookup { projectId ->
    val query = SheetQuery(projectId = projectId, statuses = listOf(CallSheetStatus.Published))
    val published = when (val sheets = callSheets.sheets(query)) {
        is ZillitResult.Failure -> return@PublishedCallSheetLookup null
        is ZillitResult.Success -> sheets.data
    }
    val newest = published.maxByOrNull { it.publishedOn ?: it.updatedOn ?: 0L }
        ?: return@PublishedCallSheetLookup null
    when (val detail = callSheets.sheet(newest.id)) {
        is ZillitResult.Failure -> null
        is ZillitResult.Success ->
            com.zillit.desktop.feature.productionreport.data.PayloadWire.parse(
                com.zillit.desktop.feature.callsheet.data.PayloadWire.emit(detail.data.payload),
            )
    }
}

// Publishing --------------------------------------------------------------------------------------

/**
 * Where a report goes beyond its own service, as the web sends it: Document
 * Distribution (stored first, then filed by its keys under `Production Report`
 * or `Draft Production Report`), the tool's unit chat (a document message,
 * "New" replacing the posts before it), and storage for a drawn signature.
 */
internal fun AppGraph.Ready.productionReportPublishing(permissions: () -> ProjectPermissions): ReportPublishing =
    object : ReportPublishing {

        override fun canDistribute(): Boolean =
            permissions().canPost(DOC_DISTRIBUTION_TOOL) || projectContext?.context?.value?.isAdmin == true

        override suspend fun sendToDocumentDistribution(
            pdf: ByteArray,
            fileName: String,
            fromDraft: Boolean,
            dateYmd: String?,
        ): ZillitResult<Unit> {
            val stored = when (val upload = reportStorage().upload(fileName, PDF_TYPE, pdf) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            return when (
                val filed = FromToolPublisher(apiClient, config, canPost = ::canDistribute).publish(
                    FromToolFile(
                        folderPath = listOf(if (fromDraft) DRAFT_REPORT_FOLDER else REPORT_FOLDER),
                        name = fileName,
                        media = stored.media,
                        bucket = stored.bucket,
                        region = stored.region,
                        contentType = DOCUMENT,
                        contentSubtype = PDF,
                        thumbnail = PDF_THUMBNAIL,
                        fileSizeBytes = pdf.size.toLong(),
                        folderDate = dateYmd?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString(),
                    ),
                )
            ) {
                is ZillitResult.Failure -> filed
                is ZillitResult.Success -> ZillitResult.Success(Unit)
            }
        }

        override suspend fun postToChat(
            pdf: ByteArray,
            fileName: String,
            replacePrevious: Boolean,
        ): ZillitResult<Unit> =
            postDocument(fileName, PDF_TYPE, pdf, replacePrevious)

        override suspend fun attachDocuments(replacePrevious: Boolean): ZillitResult<Int> {
            val files = FilePicker().pick()
            files.forEachIndexed { index, file ->
                val posted = postDocument(
                    file.name,
                    file.contentType,
                    file.bytes,
                    replacePrevious = replacePrevious && index == 0,
                )
                if (posted is ZillitResult.Failure) return posted
            }
            return ZillitResult.Success(files.size)
        }

        override suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature> =
            when (val upload = reportStorage().upload(SIGNATURE_FILE, "image/png", png) {}) {
                is ZillitResult.Failure -> upload
                is ZillitResult.Success -> ZillitResult.Success(
                    ApprovalDecision.Signature(
                        media = upload.data.media,
                        thumbnail = upload.data.media,
                        bucket = upload.data.bucket,
                        region = upload.data.region,
                    ),
                )
            }

        /** `sendMSGModal` + `createProductionReportChatData`: a document message into the tool's unit. */
        private suspend fun postDocument(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
            replacePrevious: Boolean,
        ): ZillitResult<Unit> {
            val unitId = permissions().access(PRODUCTION_REPORT_TOOL).unitId?.takeIf { it.isNotBlank() }
                ?: return ZillitResult.Failure(ZillitError.Validation("Production report unit not found."))
            val stored = when (val upload = reportStorage().upload(fileName, contentType, bytes) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            val empty = when (val cipher = noticeDecryptor.encryptToHex("")) {
                is ZillitResult.Failure -> return cipher
                is ZillitResult.Success -> cipher.data
            }
            val body = chatDocumentBody(unitId, stored, fileName, bytes.size.toLong(), empty, replacePrevious)
            return when (
                val sent = apiClient.envelope(
                    verb = HttpVerb.Post,
                    url = "${config.apiV2(ZillitService.ProductionReport)}production-report",
                    module = RequestModule.ProjectUser,
                    body = body,
                )
            ) {
                is ZillitResult.Failure -> sent
                is ZillitResult.Success -> if (sent.data.status == 1) {
                    ZillitResult.Success(Unit)
                } else {
                    ZillitResult.Failure(ZillitError.Http(status = 200, serverMessage = sent.data.message))
                }
            }
        }
    }

/** The unit-chat document body the web builds for a published report or an attached file. */
private fun chatDocumentBody(
    unitId: String,
    stored: StoredFile,
    fileName: String,
    size: Long,
    emptyCipher: String,
    replacePrevious: Boolean,
): JsonObject {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return buildJsonObject {
        put("unit_id", unitId)
        put("message", emptyCipher)
        put("message_translation", emptyCipher)
        put("message_type", DOCUMENT)
        put(
            "attachment",
            buildJsonObject {
                put("media", stored.media)
                put("thumbnail", if (extension == PDF) PDF_THUMBNAIL else stored.media)
                put("content_type", DOCUMENT)
                put("content_subtype", extension)
                put("height", 0)
                put("width", 0)
                put("duration", 0)
                put("bucket", stored.bucket)
                put("region", stored.region)
                put("caption", emptyCipher)
                put("name", fileName.replace(Regex("\\s"), ""))
                put("file_size", size.toString())
            },
        )
        put("message_group", System.currentTimeMillis())
        put(
            "location",
            buildJsonObject {
                put("lat", 0)
                put("long", 0)
            },
        )
        put("unique_id", UUID.randomUUID().toString())
        put("comments", buildJsonArray { })
        put("replacePreviousChats", replacePrevious)
    }
}

/** Project storage under the web's own key prefix for this tool. */
private fun AppGraph.Ready.reportStorage(): S3AttachmentUploader {
    val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
    return S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            val remote = remoteConfigRepository.current()
            val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
            val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
            if (access != null && secret != null) AwsCredentials(access, secret) else null
        },
        storage = storageTarget,
        newKey = { name ->
            val stem = name.substringBeforeLast('.').replace(" ", "").replace(Regex("[^A-Za-z0-9.]"), "")
            val extension = name.substringAfterLast('.', "bin")
            val tail = UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(KEY_SUFFIX)
            "$projectId/film-tools/production-report-v2/actual/$stem${System.currentTimeMillis()}$tail.$extension"
        },
    )
}

// Badges --------------------------------------------------------------------------------------------

/**
 * The tool's ledger rows, rebuilt as leaves (unit, levels, report) from the
 * store's grouped counts, and the reads the web emits: an approval unit read
 * whole (`notification:read`), a comment thread or tab by level
 * (`notification:level:read`) — each clearing the local ledger at once.
 */
internal fun AppGraph.Ready.productionReportBadges(): ReportBadgeSource = object : ReportBadgeSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val leaves: Flow<List<BadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun split(
        groupBy: String,
        unit: String? = null,
        level1: String? = null,
        level2: String? = null,
    ): Map<String, Int> =
        badgeStore.split(
            BadgeDrilldownQuery(
                groupBy = groupBy,
                tool = ReportBadges.TOOL,
                unit = unit,
                level1 = level1,
                level2 = level2,
            ),
        )

    private fun leavesNow(): List<BadgeLeaf> {
        val units = split("unit")
        val plain = units.filterKeys { it != ReportBadges.UNIT_COMMENT }.map { (unit, count) -> BadgeLeaf(
            unit,
            unread = count,
        ) }
        val comment = ReportBadges.UNIT_COMMENT
        val commentTotal = units[comment] ?: 0
        if (commentTotal == 0) return plain
        val leaves = mutableListOf<BadgeLeaf>()
        val tabs = split("level_1", unit = comment)
        tabs.forEach { (tab, tabCount) ->
            val subs = split("level_2", unit = comment, level1 = tab)
            subs.forEach { (sub, subCount) ->
                leaves += threads(
                    split("level_3", unit = comment, level1 = tab, level2 = sub),
                    subCount,
                ) { doc, count ->
                    BadgeLeaf(comment, tab, sub, doc, count)
                }
            }
            val tabDocs = split("level_3", unit = comment, level1 = tab)
            val subDocs = subs.keys.map { split("level_3", unit = comment, level1 = tab, level2 = it) }
            val loose = tabDocs
                .mapValues { (doc, count) -> count - subDocs.sumOf { it[doc] ?: 0 } }
                .filterValues { it > 0 }
            leaves += threads(
                loose,
                tabCount - subs.values.sum(),
            ) { doc, count -> BadgeLeaf(comment, tab, "", doc, count) }
        }
        val remainder = commentTotal - tabs.values.sum()
        if (remainder > 0) leaves += BadgeLeaf(comment, unread = remainder)
        return plain + leaves
    }

    /** A leaf per report, and one without a report for rows the ledger filed with none. */
    private fun threads(docs: Map<String, Int>, total: Int, leaf: (String, Int) -> BadgeLeaf): List<BadgeLeaf> {
        val out = docs.map { (doc, count) -> leaf(doc, count) }
        val missing = total - docs.values.sum()
        return if (missing > 0) out + leaf("", missing) else out
    }

    override fun readUnits(units: List<String>) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            units.filter { it.isNotBlank() }.forEach { unit ->
                val now = System.currentTimeMillis()
                runCatching {
                    socketEvents.emit(
                        ZillitSocketEvents.Badges.NotificationRead,
                        NotificationReadDto(
                            projectId = projectId,
                            module = ReportBadges.TOOL,
                            segment = unit,
                            timestamp = now,
                        ),
                        NotificationReadDto.serializer(),
                    )
                }
                badgeStore.markRead(LedgerRead.Levels(tool = ReportBadges.TOOL, unit = unit))
            }
        }
    }

    override fun readCommentThread(reportId: String) = readComments(level1 = null, level3 = reportId)

    override fun readCommentTab(tab: String) = readComments(level1 = tab, level3 = null)

    private fun readComments(level1: String?, level3: String?) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationLevelRead,
                    NotificationReadDto(
                        projectId = projectId,
                        tool = ReportBadges.TOOL,
                        module = ReportBadges.TOOL,
                        unit = ReportBadges.UNIT_COMMENT,
                        segment = ReportBadges.UNIT_COMMENT,
                        level1 = level1,
                        level3 = level3,
                        timestamp = now,
                        readTime = now,
                    ),
                    NotificationReadDto.serializer(),
                )
            }
            badgeStore.markRead(
                LedgerRead.Levels(
                    tool = ReportBadges.TOOL,
                    unit = ReportBadges.UNIT_COMMENT,
                    level1 = level1,
                    level3 = level3,
                ),
            )
        }
    }
}

// Weather ---------------------------------------------------------------------------------------------

/** OpenWeather One Call 3.0 as the web calls it — no `units`, so Kelvin — handed back raw. */
private fun AppGraph.Ready.productionReportWeather(): ReportWeatherSource? {
    val key = config.weatherApiKey?.takeIf { it.isNotBlank() } ?: return null
    return ReportWeatherSource { lat, lng ->
        runCatching {
            val response = httpClient.get("$ONE_CALL?lat=$lat&lon=$lng&appid=$key")
            check(response.status.isSuccess()) { "Weather service answered ${response.status.value}" }
            Json.parseToJsonElement(response.bodyAsText()) as JsonObject
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "Failed to fetch weather.")) },
        )
    }
}

private const val ONE_CALL = "https://api.openweathermap.org/data/3.0/onecall"
private const val CHAT_PATH = "/cnc"
private const val PRODUCTION_REPORT_TOOL = "production_report_tool"
private const val REPORT_FOLDER = "Production Report"
private const val DRAFT_REPORT_FOLDER = "Draft Production Report"
private const val DOCUMENT = "document"
private const val PDF = "pdf"
private const val PDF_TYPE = "application/pdf"
private const val SIGNATURE_FILE = "signature.png"
private const val KEY_SUFFIX = 6

/** The placeholder the web stamps on every PDF message it posts (`App:2760`). */
private const val PDF_THUMBNAIL = "6777b7ede9c303151d721ba9/home/actual/pdf1738063181064.png"
