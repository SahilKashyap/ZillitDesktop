package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.domain.FormBadgeLeaf
import com.zillit.desktop.feature.formsignature.domain.FormSignatureBadges
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.externalusers.data.ExternalUsersRepositoryImpl
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.formsignature.data.FormSignatureRepositoryImpl
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.formsignature.domain.CrewPerson
import com.zillit.desktop.feature.formsignature.domain.ExternalSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.ui.FormSignatureToolProvider
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import com.zillit.desktop.feature.formsignature.ui.PickKind
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.home.ui.BoardToolProvider
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable

/**
 * Documents & Signature's storage seam, assembled from what the app already
 * owns: the email module's S3 uploader for writes, the notice reader for
 * reads. This tool never learns where the bytes live.
 *
 * Two key shapes, per the web:
 *
 *  - documents go under a fresh `sign-document/{uuid}/…` key — the server
 *    stores the descriptor verbatim, so the shape only has to be collision
 *    free;
 *  - signature PNGs go under `{projectId}/sign/…`, the exact prefix the web
 *    writes, because a signature is long-lived reference data other clients
 *    may resolve by convention.
 *
 * AWS only, like the drive's uploader: productions on Box storage will
 * refuse the upload with the credentials error rather than misroute it.
 */
internal fun AppGraph.Ready.formSignatureTransfer(): SignFileTransfer {
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }

    val documentUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "sign-document/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val signatureUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName ->
            val project = projectContext?.context?.value?.project?.projectId.orEmpty()
                .ifBlank { "unknown-project" }
            "$project/sign/${fileName.safeKeyPart()}"
        },
    )

    return object : SignFileTransfer {
        override suspend fun store(
            purpose: UploadPurpose,
            fileName: String,
            contentType: String,
            bytes: ByteArray,
        ): ZillitResult<StoredDocument> {
            val uploader = when (purpose) {
                UploadPurpose.Document -> documentUploader
                UploadPurpose.SignatureImage -> signatureUploader
            }
            return when (val stored = uploader.upload(fileName, contentType, bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> ZillitResult.Success(
                    StoredDocument(
                        media = stored.data.media,
                        thumbnail = stored.data.media,
                        bucket = stored.data.bucket,
                        region = stored.data.region,
                        name = fileName,
                    ),
                )
            }
        }

        override suspend fun fetch(document: StoredDocument): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(
                    media = document.media,
                    fileName = document.name,
                    bucket = document.bucket.takeIf { it.isNotBlank() },
                    region = document.region.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )
    }
}

/** Shared with [agreementFiles], which writes keys the same way. */
internal fun String.safeKeyPart(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_KEY_NAME)

private const val MAX_KEY_NAME = 120
private const val MAX_PDF_BYTES = 100L * 1024 * 1024


/** A single-PDF picker — shared by the budget, call sheet, distribution and e-signature flows. */
internal suspend fun pickPdf(): Pair<String, ByteArray>? = pickFormFile(PickKind.PdfOnly)

/** A single-file picker for the send (PDF only) and library (PDF or Word) flows. */
internal suspend fun pickFormFile(kind: PickKind): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
    val allowed = when (kind) {
        PickKind.PdfOnly -> setOf("pdf")
        PickKind.PdfOrWord -> setOf("pdf", "doc", "docx")
    }
    val title = if (kind == PickKind.PdfOnly) "Choose a PDF" else "Choose a document"
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in allowed }
    dialog.isVisible = true
    val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return@withContext null
    if (file.extension.lowercase() !in allowed) return@withContext null
    if (file.length() > MAX_PDF_BYTES) {
        // Refused here rather than after a doomed upload: the web caps its
        // documents well below this, and a 200 MB "contract" is a mistake.
        return@withContext null
    }
    runCatching { file.name to file.readBytes() }.getOrNull()
}

/**
 * What the tool borrows from the app: the crew list and the external-users
 * directory for names, Downloads and the printer for a finished document,
 * the media service for a Word file that must become a PDF before it is
 * signed, and the browser for the admin guide.
 */
internal fun AppGraph.Ready.formSignatureHost(): FormSignatureHost = object : FormSignatureHost {
    private val externals = ExternalUsersRepositoryImpl(apiClient, config)

    override suspend fun externalSigners(): List<ExternalSigner> =
        when (val got = externals.list(ExternalUserBucket.All, System.currentTimeMillis(), older = false)) {
            is ZillitResult.Success -> got.data.map {
                ExternalSigner(id = it.id, fullName = it.fullName, email = it.email)
            }
            is ZillitResult.Failure -> emptyList()
        }

    override fun crew(userId: String): CrewPerson? =
        projectContext?.context?.value?.user(userId)?.let { user ->
            CrewPerson(
                fullName = user.fullName,
                designation = user.designationText().orEmpty(),
                status = user.status.orEmpty(),
            )
        }

    override suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }

    /** Saved to Downloads, then handed to the OS print flow; opened plainly where printing is unsupported. */
    override suspend fun print(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> withContext(Dispatchers.IO) {
                val file = java.io.File(saved.data)
                val desktop = Desktop.getDesktop()
                val printed = desktop.isSupported(Desktop.Action.PRINT) && runCatching { desktop.print(file) }.isSuccess
                if (!printed) openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }

    /** The web's `convertDocumentToPdfBase64`: `POST file-processing/convert-to/pdf`, multipart; answers PDF bytes. */
    override suspend fun convertToPdf(fileName: String, bytes: ByteArray): ZillitResult<ByteArray> = runCatching {
        val url = "${config.apiV2(ZillitService.Media)}file-processing/convert-to/pdf"
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null, null)
        val response = httpClient.post(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            this.headers.append(HttpHeaders.CacheControl, "no-cache")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, wordMime(fileName))
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    },
                ),
            )
        }
        val body = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (!response.status.isSuccess() || isJson) {
            error("The document could not be converted to PDF (${response.status.value}).")
        }
        body
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitLog.w(FORM_SIGN_TAG) { "convert-to/pdf failed: ${thrown::class.simpleName}" }
            ZillitResult.Failure(
                ZillitError.Validation(thrown.message ?: "The document could not be converted to PDF."),
            )
        },
    )

    override fun openGuide() = openInBrowser(FormSignatureHost.GUIDE_URL)
}

private fun wordMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "application/octet-stream"
}

/**
 * The tool's ledger rows as leaves, and its reads.
 *
 * A standard form opened reads its rows under `all_forms` the way
 * `FormDetailsV2.jsx:690-706` does; a documents tab shown reads each leaf
 * the tab is made of — the for-signature unit sliced by `level_1` as
 * `DocumentsForSignature.jsx:95-108` reads it, and the older whole units
 * the web still sums into the tile (`utils.js:212-223`) read whole, or
 * they would sit under the tile with nothing to clear them. The discussion
 * room left reads the general unit under the room (`utils.js:295-316`).
 */
internal fun AppGraph.Ready.formSignatureBadges(): FormSignatureBadges = object : FormSignatureBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val leaves: Flow<List<FormBadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun leavesNow(): List<FormBadgeLeaf> =
        badgeStore.unreadRows(BadgeSections.TOOLS)
            .filter { it.tool == FormSignatureBadges.TOOL }
            .groupingBy { Triple(it.unit, it.level1, it.level2) }
            .eachCount()
            .map { (key, count) -> FormBadgeLeaf(key.first, key.second, key.third, count) }

    override fun readStandardForm(formId: String) {
        scope.launch {
            emitLevelRead(
                tool = FormSignatureBadges.TOOL,
                unit = FormSignatureBadges.UNIT_GENERAL,
                level1 = FormSignatureBadges.LEVEL_ALL_FORMS,
                level2 = formId,
            )
        }
    }

    override fun readDocumentsTab(leaves: List<FormBadgeLeaf>) {
        scope.launch {
            leaves.map { it.unit to it.level1 }.distinct().forEach { (unit, level1) ->
                if (unit == FormSignatureBadges.UNIT_FOR_SIGNATURE) {
                    emitLevelRead(tool = FormSignatureBadges.TOOL, unit = unit, level1 = level1)
                } else {
                    emitLevelRead(tool = FormSignatureBadges.TOOL, unit = unit)
                }
            }
        }
    }

    override fun readChat(unitId: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(
                        projectId = projectId,
                        segment = FormSignatureBadges.UNIT_GENERAL,
                        level1 = unitId,
                        module = FormSignatureBadges.TOOL,
                        timestamp = System.currentTimeMillis(),
                    ),
                    NotificationReadDto.serializer(),
                )
            }.onFailure { ZillitLog.w(FORM_SIGN_TAG) { "chat read not sent: ${it::class.simpleName}" } }
            badgeStore.markRead(
                com.zillit.desktop.core.badges.LedgerRead.Levels(
                    tool = FormSignatureBadges.TOOL,
                    unit = FormSignatureBadges.UNIT_GENERAL,
                    level1 = unitId,
                ),
            )
        }
    }
}

/**
 * "Chat with Admins" / "Chat with Users" — the Home board engine on the
 * `form-signature` segment of the unit host, its one tab the room the tool's
 * view model fetched. The web hard-codes posting on for this room
 * (`FormChatDiscussionMain.jsx:66`); an admin must first pick who they are
 * answering, and a post without that pick is refused with the web's text.
 */
internal fun AppGraph.Ready.formSignatureChatFeed(
    viewModel: FormSignatureViewModel,
    permissions: () -> ProjectPermissions,
): HomeFeedViewModel = boardFeed(
    board = "form-signature",
    toolIdentifier = FormSignatureViewer.TOOL_IDENTIFIER,
    units = {
        val unit = viewModel.currentState.chat.unit
        val access = permissions().access(FormSignatureViewer.TOOL_IDENTIFIER)
        ZillitResult.Success(
            listOfNotNull(
                unit?.let {
                    HomeUnit(
                        id = it.id,
                        identifier = FormSignatureViewer.TOOL_IDENTIFIER,
                        unitName = it.name.ifBlank { "Discussion" },
                        canView = true,
                        canPost = true,
                        canDownload = access.canDownload || permissions().isAdmin,
                        enabled = true,
                    )
                },
            ),
        )
    },
    receiver = { viewModel.currentState.chat.receiver?.userId },
    decorate = { delegate ->
        ReceiverGatedFeed(delegate) {
            val state = viewModel.currentState
            val unit = state.chat.unit
            if (unit != null && unit.answers(state.currentUserId) && state.chat.receiver == null) {
                "If you want to send a new message to a User, first make a selection from ‘Select User’. " +
                    "And if you want to reply to any message click on arrow and select ‘Reply’"
            } else {
                null
            }
        }
    },
)

/** The room's posting rule, in front of the repository: a message when a post must be refused, else through. */
private class ReceiverGatedFeed(
    private val delegate: HomeFeedRepository,
    private val refusal: () -> String?,
) : HomeFeedRepository by delegate {

    override suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia?,
        location: GeoPoint?,
    ): ZillitResult<Notice> = refusal()?.let { ZillitResult.Failure(ZillitError.Validation(it)) }
        ?: delegate.postNotice(unitId, text, localId, attachment, location)

    @Suppress("LongParameterList") // The interface's own shape.
    override suspend fun postNotice(
        unitId: String,
        text: String,
        localId: String,
        attachment: UploadedNoticeMedia?,
        location: GeoPoint?,
        replacePrevious: Boolean?,
        replaceChatId: String?,
    ): ZillitResult<Notice> = refusal()?.let { ZillitResult.Failure(ZillitError.Validation(it)) }
        ?: delegate.postNotice(unitId, text, localId, attachment, location, replacePrevious, replaceChatId)

    override suspend fun postComment(
        noticeId: String,
        unitId: String,
        text: String,
    ): ZillitResult<List<NoticeComment>> =
        refusal()?.let { ZillitResult.Failure(ZillitError.Validation(it)) }
            ?: delegate.postComment(noticeId, unitId, text)
}

/** The board as the tool embeds it — `BoardToolProvider`'s own content, on the tool's route. */
internal fun formSignatureChatBoard(
    feed: HomeFeedViewModel,
    board: HomeBoardContext,
    badges: kotlinx.coroutines.flow.StateFlow<com.zillit.desktop.core.badges.BadgeCounts>?,
): @Composable (WorkspaceRoute, WindowNavigator) -> Unit {
    val provider = BoardToolProvider(
        path = "${com.zillit.desktop.feature.formsignature.ui.FORM_SIGNATURE_PATH}/chat",
        title = "Discussion",
        icon = ZillitIcons.Chat,
        feedViewModel = feed,
        board = board,
        badges = badges,
    )
    return { route, navigator -> provider.Content(route, navigator) }
}

/** The tool's view model, with every seam the app lends it. */
internal fun AppGraph.Ready.buildFormSignature(permissions: () -> ProjectPermissions): FormSignatureViewModel =
    FormSignatureViewModel(
        repository = FormSignatureRepositoryImpl(apiClient, config, bus = socketEvents),
        transfer = formSignatureTransfer(),
        pdfWork = PdfBoxWork(),
        resolveViewer = {
            val context = projectContext?.context?.value
            val me = context?.profile?.userId
            FormSignatureViewer.from(
                permissions = permissions(),
                // The web hides both document tiles while the member's standing is `pending`.
                isPending = context?.user(me)?.status == "pending",
            )
        },
        currentUserId = { projectContext?.context?.value?.profile?.userId.orEmpty() },
        newId = { UUID.randomUUID().toString() },
        host = formSignatureHost(),
        badges = formSignatureBadges(),
        rights = rightsRequests,
    )

/** The tool provider: the picker on IO, the host's seams, and the discussion board embedded. */
internal fun AppGraph.Ready.formSignatureProvider(
    viewModel: FormSignatureViewModel,
    permissions: () -> ProjectPermissions,
    scope: CoroutineScope,
    board: HomeBoardContext,
): FormSignatureToolProvider {
    val chatFeed = formSignatureChatFeed(viewModel, permissions)
    return FormSignatureToolProvider(
        viewModel = viewModel,
        onPickFile = { kind, onPicked -> scope.launch { onPicked(pickFormFile(kind)) } },
        host = formSignatureHost(),
        chatBoard = formSignatureChatBoard(chatFeed, board, badgeStore.counts),
    )
}

private const val FORM_SIGN_TAG = "FormSignature"
