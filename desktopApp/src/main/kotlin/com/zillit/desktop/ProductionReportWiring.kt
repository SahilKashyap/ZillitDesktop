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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.chat.domain.ChatAttachment
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
import com.zillit.desktop.feature.productionreport.data.PayloadWire
import com.zillit.desktop.feature.productionreport.data.ReportRepositoryImpl
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.CallSheetForDay
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReplaceTarget
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
        callSheets = productionReportCallSheets(),
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
 * The tool window: crew photos on every face and — for the production
 * report — the unit chat in the Chat workspace, drawn by the same board every
 * notice board uses. "Send for Chat" goes through the chat REPOSITORY (see
 * [productionReportPublishing]), so the window needs no chat view model.
 */
internal fun reportToolProvider(
    viewModel: ReportViewModel,
    graph: AppGraph,
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
 * not joined, with designations and departments as words, and with the
 * designation's raw label KEY, which a reminder's `sent_by_role` carries.
 */
private fun AppGraph.Ready.reportMembers(): List<SheetMember> =
    projectContext?.context?.value?.users.orEmpty().map { user ->
        SheetMember(
            userId = user.userId,
            fullName = user.fullName,
            department = user.department?.takeIf { it.isNotBlank() }?.let { Labels.translate(it) }.orEmpty(),
            designation = user.designationText().orEmpty(),
            designationKey = user.designation.orEmpty(),
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

// Call sheet seed --------------------------------------------------------------------------------------

/**
 * The call sheet PUBLISHED for the report's shoot day — `GET
 * /call-sheets/by-day?date=<ms>` on the call-sheet service, the date as
 * LOCAL midnight (how the call sheet writes its own `shared.date`; the server
 * matches on the UTC day, so a UTC-midnight epoch misses east of UTC). The
 * answer is the same shape as `GET /call-sheets/:id`, re-parsed into the
 * report module's payload type via the wire shape both share.
 *
 * `call_sheet_not_found_for_day` is "none published" (the prompt); any other
 * refusal or a transport failure is "couldn't ask" (no prompt).
 */
internal fun AppGraph.Ready.productionReportCallSheets(): PublishedCallSheetLookup =
    PublishedCallSheetLookup { _, dateYmd ->
        val dateMs = runCatching {
            LocalDate.parse(dateYmd).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrNull() ?: return@PublishedCallSheetLookup CallSheetForDay.Unavailable
        val url = "${config.apiV2(ZillitService.CallSheet).trimEnd('/')}/call-sheets/by-day"
        when (
            val envelope = apiClient.envelope(
                verb = HttpVerb.Get,
                url = url,
                module = RequestModule.ProjectUser,
                queryParameters = mapOf("date" to dateMs),
            )
        ) {
            is ZillitResult.Failure -> CallSheetForDay.Unavailable
            is ZillitResult.Success -> callSheetForDay(envelope.data.status, envelope.data.message, envelope.data.data)
        }
    }

/** The by-day envelope, read the way the web's `getPublishedCallSheetByDay` reads it. */
internal fun callSheetForDay(status: Int?, message: String?, data: JsonElement?): CallSheetForDay {
    if (message == CALL_SHEET_NOT_FOUND_FOR_DAY) return CallSheetForDay.None
    if (status != 1) return CallSheetForDay.Unavailable
    val sheet = (data as? JsonObject)?.let { it["call_sheet"] ?: it["callSheet"] } as? JsonObject
        ?: return CallSheetForDay.None
    val revision = (sheet["currentRevision"] ?: sheet["current_revision"]) as? JsonObject
    val payload = revision?.get("payload") ?: sheet["payload"]
    return CallSheetForDay.Found(PayloadWire.parse(payload))
}

private const val CALL_SHEET_NOT_FOUND_FOR_DAY = "call_sheet_not_found_for_day"

// Publishing --------------------------------------------------------------------------------------

/**
 * Where a report goes beyond its own service, as the web sends it: Document
 * Distribution (stored first, then filed by its keys under `Production Report`
 * or `Draft Production Report`), the tool's unit chat (a document message —
 * New retiring the posts before it, Replace retiring the one it names), a
 * crew member's 1:1 chat ("Send for Chat"), and storage for a drawn signature.
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

        /**
         * `fetchPRChatMessageData({unitId, timeStamp: now, page: 0, limit: 300})`
         * — the newest page of the unit chat, filtered to its live documents.
         */
        override suspend fun replaceableDocuments(): ZillitResult<List<ReplaceTarget>> {
            val unitId = permissions().access(PRODUCTION_REPORT_TOOL).unitId?.takeIf { it.isNotBlank() }
                ?: return ZillitResult.Success(emptyList())
            val chat = config.apiV2(ZillitService.ProductionReport).trimEnd('/')
            return when (
                val envelope = apiClient.envelope(
                    verb = HttpVerb.Get,
                    url = "$chat/production-report/$unitId/${System.currentTimeMillis()}/previous",
                    module = RequestModule.ProjectUser,
                    queryParameters = mapOf("page" to 0, "limit" to REPLACE_PAGE_LIMIT),
                )
            ) {
                is ZillitResult.Failure -> envelope
                is ZillitResult.Success -> if (envelope.data.status == 1) {
                    ZillitResult.Success(replaceableDocuments(envelope.data.data))
                } else {
                    ZillitResult.Success(emptyList())
                }
            }
        }

        override suspend fun postToChat(
            pdf: ByteArray,
            fileName: String,
            replacePrevious: Boolean,
            replaceChatId: String?,
        ): ZillitResult<Unit> =
            postDocument(fileName, PDF_TYPE, pdf, replacePrevious, replaceChatId)

        override suspend fun attachDocuments(replacePrevious: Boolean): ZillitResult<Int> {
            val files = FilePicker().pick()
            files.forEachIndexed { index, file ->
                val posted = postDocument(
                    file.name,
                    file.contentType,
                    file.bytes,
                    replacePrevious = replacePrevious && index == 0,
                    replaceChatId = null,
                )
                if (posted is ZillitResult.Failure) return posted
            }
            return ZillitResult.Success(files.size)
        }

        /**
         * `sendProductionReportForChat`: the PDF into storage, then the chat
         * module's own sender (encryption and scope inside), as a document
         * message with the PDF's placeholder tile — the web's first-page
         * thumbnail is a nicety it treats as optional.
         */
        override suspend fun sendPdfToChat(userId: String, pdf: ByteArray, fileName: String): ZillitResult<Unit> {
            val stored = when (val upload = reportStorage().upload(fileName, PDF_TYPE, pdf) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            return chatRepository.send(
                receiverId = userId,
                body = "",
                uniqueId = UUID.randomUUID().toString(),
                nowMillis = System.currentTimeMillis(),
                isGroup = false,
                attachment = ChatAttachment(
                    media = stored.media,
                    name = fileName,
                    contentType = PDF_TYPE,
                    bucket = stored.bucket,
                    region = stored.region,
                    thumbnail = PDF_THUMBNAIL,
                    sizeBytes = pdf.size.toLong(),
                ),
            )
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
            replaceChatId: String?,
        ): ZillitResult<Unit> {
            val unitId = permissions().access(PRODUCTION_REPORT_TOOL).unitId?.takeIf { it.isNotBlank() }
                ?: return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_production_report_unit_not_found)))
            val stored = when (val upload = reportStorage().upload(fileName, contentType, bytes) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            val empty = when (val cipher = noticeDecryptor.encryptToHex("")) {
                is ZillitResult.Failure -> return cipher
                is ZillitResult.Success -> cipher.data
            }
            val body = chatDocumentBody(
                unitId,
                stored,
                fileName,
                bytes.size.toLong(),
                empty,
                replacePrevious,
                replaceChatId,
            )
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

/**
 * `buildReplaceableDocuments`: the `{chat_id, label}` options a Replace may
 * retire, newest first. DOCUMENT messages only (the server acts on nothing
 * else), not deleted, not archived (already moved to History), keyed on
 * `_id` and NEVER `unique_id` — a `unique_id` target finds nothing and the
 * post quietly appends. Ordered by `created` descending rather than
 * reversed: the `/previous` API answers newest-first.
 */
internal fun replaceableDocuments(rows: JsonElement?): List<ReplaceTarget> =
    (rows as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { row ->
            row.string("message_type") == DOCUMENT &&
                (row["attachment"] as? JsonObject)?.string("media")?.isNotBlank() == true &&
                !row.truthy("deleted") && !row.truthy("archived")
        }
        .sortedByDescending { (it["created"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        .mapNotNull { row ->
            val id = row.string("_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val attachment = row["attachment"] as? JsonObject
            val label = attachment?.string("name")?.takeIf { it.isNotBlank() }
                ?: attachment?.string("original_file_name")?.takeIf { it.isNotBlank() }
                ?: str(S.docusign_send_confirm_untitled)
            ReplaceTarget(chatId = id, label = label)
        }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

/** JavaScript truthiness for a flag the wire writes as a boolean, a timestamp or a string. */
private fun JsonObject.truthy(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    val content = primitive.content
    return when {
        primitive.isString -> content.isNotEmpty()
        else -> content != "false" && content != "0" && content != "null" && content.isNotEmpty()
    }
}

/** The unit-chat document body the web builds for a published report or an attached file. */
@Suppress("LongParameterList") // The wire's fields, named.
private fun chatDocumentBody(
    unitId: String,
    stored: StoredFile,
    fileName: String,
    size: Long,
    emptyCipher: String,
    replacePrevious: Boolean,
    replaceChatId: String?,
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
        // The wipe flag follows the CHOICE (New), never the presence of a target; snake_case reaches the server as-is.
        put("replacePreviousChats", replacePrevious)
        if (!replaceChatId.isNullOrBlank()) put("replace_chat_id", replaceChatId)
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
 * store's grouped counts — badges v2: per-tab units, `level_1` the Approvals
 * sub-tab, `level_2` report|comment, `level_3` the report id — and the one
 * read the web emits: one report's badges of one kind on one surface
 * (`notification:level:read`, never unit-wide), clearing the local ledger at
 * once.
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

    /** Only the three v2 units are walked; legacy and tool-chat units never count in Manage. */
    private fun leavesNow(): List<BadgeLeaf> {
        val units = split("unit").filterKeys { it in ReportBadges.UNITS }
        return units.flatMap { (unit, _) ->
            if (unit == ReportBadges.UNIT_APPROVAL) {
                split("level_1", unit = unit).keys.flatMap { level1 -> kindLeaves(unit, level1) }
            } else {
                kindLeaves(unit, level1 = null)
            }
        }
    }

    /** One leaf per report under a kind, plus one nameless leaf for rows the ledger filed without a report. */
    private fun kindLeaves(unit: String, level1: String?): List<BadgeLeaf> =
        split("level_2", unit = unit, level1 = level1).flatMap { (level2, total) ->
            val docs = split("level_3", unit = unit, level1 = level1, level2 = level2)
            val named = docs.map { (doc, count) -> BadgeLeaf(unit, level1.orEmpty(), level2, doc, count) }
            val missing = total - docs.values.sum()
            if (missing > 0) named + BadgeLeaf(unit, level1.orEmpty(), level2, "", missing) else named
        }

    override fun readBadge(surface: BadgeSurface, kind: BadgeKind, reportId: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        if (reportId.isBlank()) return
        // Finalized comments live under received / sent, so that is two reads.
        val scopes = if (surface == BadgeSurface.Finalized && kind == BadgeKind.Comment) {
            listOf(BadgeSurface.Received, BadgeSurface.Sent)
        } else {
            listOf(surface)
        }
        scope.launch {
            scopes.forEach { target ->
                val unit = ReportBadges.unitOf(target)
                val level1 = target.wire.takeIf { target.isApproval }
                val now = System.currentTimeMillis()
                runCatching {
                    socketEvents.emit(
                        ZillitSocketEvents.Badges.NotificationLevelRead,
                        NotificationReadDto(
                            projectId = projectId,
                            tool = ReportBadges.TOOL,
                            module = ReportBadges.TOOL,
                            unit = unit,
                            segment = unit,
                            level1 = level1,
                            level2 = kind.wire,
                            level3 = reportId,
                            timestamp = now,
                            readTime = now,
                        ),
                        NotificationReadDto.serializer(),
                    )
                }
                badgeStore.markRead(
                    LedgerRead.Levels(
                        tool = ReportBadges.TOOL,
                        unit = unit,
                        level1 = level1,
                        level2 = kind.wire,
                        level3 = reportId,
                    ),
                )
            }
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
private const val PRODUCTION_REPORT_TOOL = "production_report_tool"
private const val REPORT_FOLDER = "Production Report"
private const val DRAFT_REPORT_FOLDER = "Draft Production Report"
private const val DOCUMENT = "document"
private const val PDF = "pdf"
private const val PDF_TYPE = "application/pdf"
private const val SIGNATURE_FILE = "signature.png"
private const val KEY_SUFFIX = 6
private const val REPLACE_PAGE_LIMIT = 300

/** The placeholder the web stamps on every PDF message it posts (`App:2760`). */
private const val PDF_THUMBNAIL = "6777b7ede9c303151d721ba9/home/actual/pdf1738063181064.png"
