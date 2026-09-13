package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.callsheet.data.CallSheetRepositoryImpl
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.CallSheetPublishing
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.PickedDocument
import com.zillit.desktop.feature.callsheet.domain.SavedSignature
import com.zillit.desktop.feature.callsheet.domain.SavedSignatureSource
import com.zillit.desktop.feature.callsheet.domain.SheetBadgeSource
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.callsheet.domain.SheetWeatherSource
import com.zillit.desktop.feature.callsheet.ui.CallSheetToolProvider
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
import com.zillit.desktop.feature.callsheet.ui.SheetServices
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.FormSignatureRepositoryImpl
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.core.workspace.WorkspaceRoute
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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Call Sheet Creation — the tool, its host seams, and its window.
 *
 * The seams mirror the production report's (the web builds both tools from
 * the same workflow kit): the raw PDF stream, Document Distribution, the Home
 * call-sheet unit for the publish fan-out, storage for a drawn signature, the
 * badge ledger, OpenWeather, and — call sheet only — a one-to-one chat share
 * and the approver's saved signatures.
 */
internal fun AppGraph.Ready.buildCallSheet(permissions: () -> ProjectPermissions): CallSheetViewModel =
    CallSheetViewModel(
        repository = CallSheetRepositoryImpl(
            apiClient,
            config,
            bus = socketEvents,
            currentProjectId = { projectContext?.context?.value?.project?.projectId },
        ),
        services = SheetServices(
            delivery = callSheetDelivery(),
            publishing = callSheetPublishing(permissions),
            badges = callSheetBadges(),
            weather = callSheetWeather(),
            signatures = callSheetSavedSignatures(),
            company = {
                val project = projectContext?.context?.value?.project
                CompanySeed(projectName = project?.name.orEmpty(), companyName = project?.companyName.orEmpty())
            },
        ),
        resolveViewer = { callSheetViewer(permissions()) },
        projectIdProvider = { projectContext?.context?.value?.project?.projectId },
        membersProvider = { callSheetMembers() },
        nowMillis = System::currentTimeMillis,
    )

/** The tool window: crew photos on every face, "Chat with …" opening the one-to-one thread. */
internal fun callSheetToolProvider(
    viewModel: CallSheetViewModel,
    graph: AppGraph,
    chat: ChatViewModel?,
): CallSheetToolProvider = CallSheetToolProvider(
    viewModel,
    loadAvatar = { userId -> (graph as? AppGraph.Ready)?.let { crewFaceLoader(it)(userId) } },
    openChat = { navigator, userId, fullName ->
        chat?.onEvent(ChatEvent.OpenThread(CrewContact(userId = userId, fullName = fullName)))
        navigator.openInNewWindow(WorkspaceRoute.Tool(CHAT_TOOL_PATH))
    },
)

private fun AppGraph.Ready.callSheetViewer(permissions: ProjectPermissions): CallSheetViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return CallSheetViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
        // From the crew list, not the profile — the profile carries neither
        // department nor designation.
        designation = me?.designationText().orEmpty(),
    )
}

/**
 * The crew as the sheet's pickers and employee sections need them — with
 * their standing, so pickers leave out anyone who left or has not joined,
 * and with designations and departments as words, keyed by the label the
 * department came from.
 */
private fun AppGraph.Ready.callSheetMembers(): List<SheetMember> =
    projectContext?.context?.value?.users.orEmpty().map { user ->
        val departmentKey = user.department?.takeIf { it.isNotBlank() }.orEmpty()
        SheetMember(
            userId = user.userId,
            fullName = user.fullName,
            department = departmentKey.takeIf { it.isNotBlank() }?.let { Labels.translate(it) }.orEmpty(),
            departmentKey = departmentKey,
            designation = user.designationText().orEmpty(),
            status = user.status,
            isAdmin = user.isAdmin,
            avatarUrl = user.avatarUrl,
        )
    }

// PDF ----------------------------------------------------------------------------------------------

/**
 * The server-rendered PDF: fetched outside the enveloped client (the service
 * streams `application/pdf`), drawn to page images with PDFBox, saved to
 * Downloads.
 */
internal fun AppGraph.Ready.callSheetDelivery(): CallSheetDelivery = object : CallSheetDelivery {

    private val work = PdfBoxWork()
    private val base = config.apiV2(ZillitService.CallSheet).trimEnd('/')

    override suspend fun pdf(sheetId: String): ZillitResult<ByteArray> = runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get("$base/call-sheets/$sheetId/pdf") {
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

// Publishing --------------------------------------------------------------------------------------

/**
 * Where a call sheet goes beyond its own service, as the web sends it:
 * Document Distribution (stored first, then filed under `Call Sheet` or
 * `Draft Call Sheet`), the Home call-sheet unit (a document message, New
 * replacing the posts before it — `replacePreviousChats`), a one-to-one chat
 * (Send for Chat), and storage for a drawn signature.
 */
@Suppress("LongMethod") // One object, one method per seam.
internal fun AppGraph.Ready.callSheetPublishing(permissions: () -> ProjectPermissions): CallSheetPublishing =
    object : CallSheetPublishing {

        override fun canDistribute(): Boolean =
            permissions().canPost(DOC_DISTRIBUTION_TOOL) || projectContext?.context?.value?.isAdmin == true

        override suspend fun sendToDocumentDistribution(
            pdf: ByteArray,
            fileName: String,
            fromDraft: Boolean,
            dateMs: Long?,
        ): ZillitResult<Unit> {
            val stored = when (val upload = callSheetStorage().upload(fileName, PDF_TYPE, pdf) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            return when (
                val filed = FromToolPublisher(apiClient, config, canPost = ::canDistribute).publish(
                    FromToolFile(
                        folderPath = listOf(if (fromDraft) DRAFT_SHEET_FOLDER else SHEET_FOLDER),
                        name = fileName,
                        media = stored.media,
                        bucket = stored.bucket,
                        region = stored.region,
                        contentType = DOCUMENT,
                        contentSubtype = PDF,
                        thumbnail = PDF_THUMBNAIL,
                        fileSizeBytes = pdf.size.toLong(),
                        folderDate = dateMs?.takeIf { it > 0 }?.let { ymdOf(it) }
                            ?: java.time.LocalDate.now().toString(),
                    ),
                )
            ) {
                is ZillitResult.Failure -> filed
                is ZillitResult.Success -> ZillitResult.Success(Unit)
            }
        }

        /** `sendMSGModal` + `createChatData` into the Home call-sheet unit. */
        override suspend fun postToUnit(
            bytes: ByteArray,
            fileName: String,
            contentType: String,
            caption: String,
            replacePrevious: Boolean,
        ): ZillitResult<Unit> {
            val unit = when (val units = homeFeedRepository.loadUnits()) {
                is ZillitResult.Failure -> return units
                is ZillitResult.Success -> units.data.firstOrNull { it.kind == HomeUnitKind.CallSheet }
            } ?: return ZillitResult.Failure(ZillitError.Validation("Call sheet unit not found."))
            val stored = when (val upload = callSheetStorage().upload(fileName, contentType, bytes) {}) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (
                val posted = homeFeedRepository.postNotice(
                    unitId = unit.id,
                    text = caption,
                    localId = UUID.randomUUID().toString(),
                    attachment = UploadedNoticeMedia(
                        kind = NoticeKind.Document,
                        media = stored.media,
                        bucket = stored.bucket,
                        region = stored.region,
                        fileName = fileName,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                        // The placeholder the web stamps on every PDF message it posts.
                        thumbnail = if (extension == PDF) PDF_THUMBNAIL else null,
                    ),
                    location = null,
                    replacePrevious = replacePrevious,
                    replaceChatId = null,
                )
            ) {
                is ZillitResult.Failure -> posted
                is ZillitResult.Success -> ZillitResult.Success(Unit)
            }
        }

        override suspend fun pickPdf(): PickedDocument? =
            com.zillit.desktop.pickPdf()?.let { (name, bytes) -> PickedDocument(name, PDF_TYPE, bytes) }

        override suspend fun uploadSignature(png: ByteArray): ZillitResult<ApprovalDecision.Signature> =
            when (val upload = callSheetStorage().upload(SIGNATURE_FILE, "image/png", png) {}) {
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

        /** `sendCncMessage`: the rendered PDF as a document message in a private thread. */
        override suspend fun sendPdfToChat(pdf: ByteArray, fileName: String, receiverId: String): ZillitResult<Unit> {
            val stored = when (val upload = attachmentUploader.upload(fileName, PDF_TYPE, pdf)) {
                is ZillitResult.Failure -> return upload
                is ZillitResult.Success -> upload.data
            }
            return chatRepository.send(
                receiverId = receiverId,
                body = "",
                uniqueId = UUID.randomUUID().toString(),
                nowMillis = System.currentTimeMillis(),
                attachment = ChatAttachment(
                    media = stored.media,
                    name = stored.fileName,
                    contentType = PDF_TYPE,
                    bucket = stored.bucket,
                    region = stored.region,
                ),
            )
        }
    }

private fun ymdOf(epochMs: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
}

/** Project storage under the web's own key prefix for this tool. */
private fun AppGraph.Ready.callSheetStorage(): S3AttachmentUploader {
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
            "$projectId/film-tools/call-sheet/actual/$stem${System.currentTimeMillis()}$tail.$extension"
        },
    )
}

// Saved signatures ---------------------------------------------------------------------------------------

/** The approver's signatures saved in Forms & Signatures — `SignaturesModal`'s "Saved Signatures". */
private fun AppGraph.Ready.callSheetSavedSignatures(): SavedSignatureSource {
    val repository = FormSignatureRepositoryImpl(apiClient, config)
    return object : SavedSignatureSource {
        private var blocks = emptyMap<String, com.zillit.desktop.feature.formsignature.domain.SignatureBlock>()

        override suspend fun list(): ZillitResult<List<SavedSignature>> =
            when (val result = repository.signatures()) {
                is ZillitResult.Failure -> result
                is ZillitResult.Success -> {
                    blocks = result.data.associateBy { it.id }
                    ZillitResult.Success(
                        result.data.filter { it.image != null }.map { SavedSignature(it.id, it.isSignature, it.name) },
                    )
                }
            }

        override suspend fun image(signature: SavedSignature): ZillitResult<ByteArray> {
            val stored = blocks[signature.id]?.image
                ?: return ZillitResult.Failure(ZillitError.Validation("That signature has no image."))
            return noticeMedia.fetch(
                NoticeAttachment(
                    media = stored.media,
                    fileName = stored.name,
                    bucket = stored.bucket.takeIf { it.isNotBlank() },
                    region = stored.region.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )
        }
    }
}

// Badges --------------------------------------------------------------------------------------------

/**
 * The tool's ledger rows as leaves (unit, levels, sheet), and the reads the
 * web emits: an approval unit read whole (`notification:read`), a slice of one
 * by level (`notification:level:read`), a comment thread or tab by level.
 */
internal fun AppGraph.Ready.callSheetBadges(): SheetBadgeSource = object : SheetBadgeSource {
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
                tool = SheetBadges.TOOL,
                unit = unit,
                level1 = level1,
                level2 = level2,
            ),
        )

    /** Approval units split by level_1 (the approved unit's rows belong to two tabs); comments down to the sheet. */
    private fun leavesNow(): List<BadgeLeaf> {
        val units = split("unit")
        val leaves = mutableListOf<BadgeLeaf>()
        units.forEach { (unit, total) ->
            if (unit == SheetBadges.UNIT_COMMENT || total == 0) return@forEach
            val tabs = split("level_1", unit = unit)
            tabs.forEach { (tab, count) -> if (count > 0) leaves += BadgeLeaf(unit, level1 = tab, unread = count) }
            val remainder = total - tabs.values.sum()
            if (remainder > 0) leaves += BadgeLeaf(unit, unread = remainder)
        }
        val comment = SheetBadges.UNIT_COMMENT
        val commentTotal = units[comment] ?: 0
        if (commentTotal == 0) return leaves
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
        return leaves
    }

    private fun threads(docs: Map<String, Int>, total: Int, leaf: (String, Int) -> BadgeLeaf): List<BadgeLeaf> {
        val out = docs.map { (doc, count) -> leaf(doc, count) }
        val missing = total - docs.values.sum()
        return if (missing > 0) out + leaf("", missing) else out
    }

    override fun readUnit(unit: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        if (unit.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(
                        projectId = projectId,
                        module = SheetBadges.TOOL,
                        segment = unit,
                        timestamp = now,
                    ),
                    NotificationReadDto.serializer(),
                )
            }
            badgeStore.markRead(LedgerRead.Levels(tool = SheetBadges.TOOL, unit = unit))
        }
    }

    override fun readUnitLevel(unit: String, level1: String) = readLevels(unit, level1 = level1, level3 = null)

    override fun readCommentThread(sheetId: String) = readLevels(
        SheetBadges.UNIT_COMMENT,
        level1 = null,
        level3 = sheetId,
    )

    override fun readCommentTab(tab: String) = readLevels(SheetBadges.UNIT_COMMENT, level1 = tab, level3 = null)

    private fun readLevels(unit: String, level1: String?, level3: String?) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationLevelRead,
                    NotificationReadDto(
                        projectId = projectId,
                        tool = SheetBadges.TOOL,
                        module = SheetBadges.TOOL,
                        unit = unit,
                        segment = unit,
                        level1 = level1,
                        level3 = level3,
                        timestamp = now,
                        readTime = now,
                    ),
                    NotificationReadDto.serializer(),
                )
            }
            badgeStore.markRead(
                LedgerRead.Levels(tool = SheetBadges.TOOL, unit = unit, level1 = level1, level3 = level3),
            )
        }
    }
}

// Weather ---------------------------------------------------------------------------------------------

/** OpenWeather One Call 3.0 as the web calls it — no `units`, so Kelvin — handed back raw. */
private fun AppGraph.Ready.callSheetWeather(): SheetWeatherSource? {
    val key = config.weatherApiKey?.takeIf { it.isNotBlank() } ?: return null
    return SheetWeatherSource { lat, lng ->
        runCatching {
            val response = httpClient.get("$ONE_CALL_URL?lat=$lat&lon=$lng&appid=$key")
            check(response.status.isSuccess()) { "Weather service answered ${response.status.value}" }
            Json.parseToJsonElement(response.bodyAsText()) as JsonObject
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "Failed to fetch weather.")) },
        )
    }
}

private const val ONE_CALL_URL = "https://api.openweathermap.org/data/3.0/onecall"
private const val CHAT_TOOL_PATH = "/cnc"
private const val SHEET_FOLDER = "Call Sheet"
private const val DRAFT_SHEET_FOLDER = "Draft Call Sheet"
private const val DOCUMENT = "document"
private const val PDF = "pdf"
private const val PDF_TYPE = "application/pdf"
private const val SIGNATURE_FILE = "signature.png"
private const val KEY_SUFFIX = 6

/** The placeholder the web stamps on every PDF message it posts (`CallSheetApp:2941`). */
private const val PDF_THUMBNAIL = "6777b7ede9c303151d721ba9/home/actual/pdf1738063181064.png"
