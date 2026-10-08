package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.session.ProjectContextLoader
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.remoteconfig.RemoteConfigRepository
import com.zillit.desktop.core.database.UserSnapshot
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadgeLeaf
import com.zillit.desktop.feature.documentdistribution.domain.DocDistCrewMember
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadges
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistSignature
import com.zillit.desktop.feature.documentdistribution.domain.DocDistTransfer
import com.zillit.desktop.feature.documentdistribution.domain.DocumentStorage
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.LocalFolderTree
import com.zillit.desktop.feature.documentdistribution.domain.Thumbnails
import com.zillit.desktop.feature.documentdistribution.domain.thumbnailScale
import com.zillit.desktop.feature.documentdistribution.domain.walkLocalPaths
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.StorageTargetSource
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/** [signRawResponse], with the session and project already bound. */
internal typealias RawSigner = suspend (
    module: RequestModule,
    url: String,
    bodyJson: String?,
    perform: suspend (headers: Map<String, String>, bearer: String?) -> HttpResponse,
) -> HttpResponse

/**
 * Document Distribution's byte-level seams on the app's machinery: storage
 * PUTs signed with the workspace's AWS keys, the signed S3 fetch, and the
 * doc-dist routes that answer a file rather than an envelope — which need
 * the encrypted headers on a raw HTTP call, since `ApiClient` only speaks
 * envelopes.
 */
internal class AppDocDistTransfer(
    private val storageClient: HttpClient,
    /**
     * Signs a raw call the way an envelope call is signed: a Bearer token when the
     * session is in token mode, `moduledata` otherwise, one renewal on a 401. Sent
     * with the legacy header alone, every route answered 401
     * `libs_moduledata_not_accepted` once the server stopped accepting it.
     */
    private val sign: RawSigner,
    private val credentials: suspend () -> Pair<String, String>?,
    private val storage: StorageTargetSource,
    private val noticeMedia: NoticeMediaSource,
) : DocDistTransfer {

    override suspend fun putObject(key: String, contentType: String, bytes: ByteArray): ZillitResult<DocumentStorage> {
        val uploader = S3AttachmentUploader(
            httpClient = storageClient,
            credentials = { credentials()?.let { (access, secret) -> AwsCredentials(access, secret) } },
            storage = storage,
            // The repository chose the key — `document-distribution/{uuid}/{name}`, Android's shape.
            newKey = { key },
        )
        return when (val stored = uploader.upload(key.substringAfterLast('/'), contentType, bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                DocumentStorage(key = stored.data.media, bucket = stored.data.bucket, region = stored.data.region),
            )
        }
    }

    override suspend fun fetchObject(storage: DocumentStorage): ZillitResult<ByteArray> =
        noticeMedia.fetch(
            NoticeAttachment(media = storage.key, bucket = storage.bucket, region = storage.region),
            preview = false,
        )

    override suspend fun getBytes(url: String): ZillitResult<ByteArray> = rawCall {
        sign(RequestModule.ProjectUser, url, null) { headers, bearer ->
            storageClient.get(url) { authorise(headers, bearer) }
        }
    }

    override suspend fun postBytes(url: String, body: JsonObject): ZillitResult<ByteArray> = rawCall {
        val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
        sign(RequestModule.ProjectUser, url, bodyJson) { headers, bearer ->
            storageClient.post(url) {
                authorise(headers, bearer)
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
        }
    }

    /**
     * The LOCAL-storage upload: the bytes multipart to the service. The
     * body hash covers a JSON body, so a multipart call rides without one —
     * the web's interceptor does the same for its `FormData` posts.
     */
    override suspend fun postMultipart(
        url: String,
        fields: Map<String, String>,
        file: LocalFile,
    ): ZillitResult<JsonElement> {
        val bytes = rawCall {
            sign(RequestModule.ProjectUser, url, null) { headers, bearer ->
                storageClient.post(url) {
                    authorise(headers, bearer)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                fields.forEach { (name, value) -> append(name, value) }
                                append(
                                    "file",
                                    file.bytes,
                                    Headers.build {
                                        append(HttpHeaders.ContentType, file.contentType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                    },
                                )
                            },
                        ),
                    )
                }
            }
        }
        return when (bytes) {
            is ZillitResult.Failure -> bytes
            is ZillitResult.Success -> runCatching {
                val envelope = HttpClientFactory.json.parseToJsonElement(bytes.data.decodeToString()).jsonObject
                if (envelope["status"]?.jsonPrimitive?.content == "0") {
                    throw Declined(envelope["message"]?.jsonPrimitive?.content ?: "The upload was refused")
                }
                envelope["data"] ?: envelope
            }.fold(
                onSuccess = { ZillitResult.Success(it) },
                onFailure = {
                    ZillitResult.Failure(ZillitError.Unknown(it.message ?: "The upload answered nothing usable"))
                },
            )
        }
    }

    /**
     * A signed call whose answer should be a file. A JSON body on the wire
     * means the service declined — "nothing to stamp", a permission key,
     * `merged_pdf_too_large` — and its `message` key is carried as an HTTP
     * error so it is shown in the person's language, as the web does with the
     * key it reads off a failed blob call.
     */
    private suspend fun rawCall(call: suspend () -> HttpResponse): ZillitResult<ByteArray> = try {
        val response = call()
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        val key = if (bytes.size < MAX_ENVELOPE_BYTES || !response.status.isSuccess()) envelopeKey(bytes) else null
        // A JSON body on a 200 is a refusal only when its own status says so: an upload
        // answers a success envelope, which may carry a message of its own.
        when {
            !response.status.isSuccess() -> ZillitResult.Failure(
                ZillitError.Http(status = response.status.value, serverMessage = key, technical = "raw call"),
            )
            isJson && key != null && !envelopeSucceeded(bytes) -> ZillitResult.Failure(
                ZillitError.Http(status = response.status.value, serverMessage = key, technical = "refused"),
            )
            else -> ZillitResult.Success(bytes)
        }
    } catch (timeout: HttpRequestTimeoutException) {
        ZillitLog.w(TAG) { "raw call timed out: ${timeout.message}" }
        ZillitResult.Failure(ZillitError.Timeout("raw call"))
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        ZillitLog.w(TAG) { "raw call failed: ${failure.message}" }
        ZillitResult.Failure(ZillitError.Unknown(failure.message ?: "The request failed"))
    }

    private fun envelopeSucceeded(bytes: ByteArray): Boolean = runCatching {
        HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["status"]?.jsonPrimitive?.content
    }.getOrNull() == "1"

    private fun envelopeKey(bytes: ByteArray): String? = runCatching {
        HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Plain headers, plus the Bearer token when the session is in token mode. */
    private fun io.ktor.client.request.HttpRequestBuilder.authorise(headers: Map<String, String>, bearer: String?) {
        headers.forEach { (name, value) -> this.headers.append(name, value) }
        bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private class Declined(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "DocDist"

        /** A refusal is a few hundred bytes; a real JSON file (a vCard is not) is bigger. */
        const val MAX_ENVELOPE_BYTES = 4096
    }
}

/** The tool's host, over the graph's signatures, crew list and department catalogue. */
internal fun AppGraph.Ready.docDistHost(): DocDistHost = AppDocDistHost(
    signatures = signatureRepository,
    crew = { projectContext?.context?.value?.users.orEmpty() },
    departments = {
        (adminRepository.departments() as? ZillitResult.Success)?.data.orEmpty().map { it.name }
    },
)

/**
 * What Document Distribution asks the machine for: the OS file dialog,
 * PDFBox for the preview, Downloads for saved copies, the clipboard, the
 * mail service's signatures, and the production's crew and departments.
 */
internal class AppDocDistHost(
    private val signatures: SignatureRepository,
    /** The project context's crew — already loaded, never fetched here. */
    private val crew: () -> List<UserSnapshot> = { emptyList() },
    /** The admin catalogue's department names, as keys. */
    private val departments: suspend () -> List<String> = { emptyList() },
) : DocDistHost {

    private val picker = AwtAttachmentPicker()
    private val pdf = PdfBoxWork()

    override suspend fun pickFiles(): List<LocalFile> =
        picker.pick(PreviewKind.Document, multiple = true, maxBytes = MAX_UPLOAD_BYTES)
            .map { LocalFile(name = it.name, contentType = it.contentType, bytes = it.bytes) }

    override fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
        when (val pages = this.pdf.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(pages.data.map { it.imageBytes })
        }

    override suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        DownloadsAttachmentStore().save(fileName, bytes)

    override suspend fun joinPdfs(parts: List<ByteArray>): ZillitResult<ByteArray> =
        withContext(Dispatchers.IO) { pdf.join(parts) }

    override suspend fun openForPrinting(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(Files.createTempDirectory("zillit-print").toFile(), fileName)
                file.writeBytes(bytes)
                // Cleared when the app exits; the viewer holds its own handle meanwhile.
                file.deleteOnExit()
                file.parentFile.deleteOnExit()
                openSavedFile(file.absolutePath)
            }.fold(
                onSuccess = { ZillitResult.Success(Unit) },
                onFailure = {
                    ZillitResult.Failure(ZillitError.Validation(str(S.desktop_docdist_downloads_unavailable)))
                },
            )
        }

    override suspend fun pdfPageCount(pdf: ByteArray): Int = withContext(Dispatchers.IO) {
        (this@AppDocDistHost.pdf.pageCount(pdf) as? ZillitResult.Success)?.data ?: 0
    }

    override suspend fun renderPdfPage(pdf: ByteArray, page: Int, widthPx: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            (this@AppDocDistHost.pdf.renderPage(pdf, page, widthPx) as? ZillitResult.Success)?.data?.imageBytes
        }

    override suspend fun pdfThumbnail(pdf: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        (
            this@AppDocDistHost.pdf.jpegThumbnail(pdf, Thumbnails.JPEG_QUALITY) { width, height ->
                thumbnailScale(width, height)
            } as? ZillitResult.Success
            )?.data
    }

    /**
     * macOS's Open panel in directory mode (`apple.awt.fileDialogForDirectories`),
     * which is what AWT offers for a folder; elsewhere a Swing chooser restricted
     * to directories. The tree is walked, never read — see [walkLocalPaths].
     */
    override suspend fun pickFolder(): LocalFolderTree? = withContext(Dispatchers.IO) {
        val folder = runCatching { chooseFolder(str(S.drive_upload_folder)) }.getOrNull() ?: return@withContext null
        walkLocalPaths(listOf(folder))
    }

    private fun chooseFolder(title: String): File? =
        if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
            System.setProperty(MAC_DIRECTORIES, "true")
            try {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
                dialog.isMultipleMode = false
                dialog.isVisible = true
                dialog.files.firstOrNull()?.takeIf(File::isDirectory)
            } finally {
                System.setProperty(MAC_DIRECTORIES, "false")
            }
        } else {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.takeIf(File::isDirectory)
            } else {
                null
            }
        }

    override fun openFile(path: String) = openSavedFile(path)

    override fun copyToClipboard(text: String) = copyTextToClipboard(text)

    override suspend fun signatures(): List<DocDistSignature> =
        (signatures.signatures() as? ZillitResult.Success)?.data.orEmpty().map {
            DocDistSignature(id = it.id, title = it.title, bodyHtml = it.body, useForNew = it.useForNew)
        }

    override fun crew(): List<DocDistCrewMember> = crew.invoke().map { user ->
        DocDistCrewMember(
            userId = user.userId,
            name = user.fullName,
            mailboxAddress = user.mailboxAddress,
            email = user.email,
            // Designations arrive as keys (`gaffer_label`, `{designation:…}`).
            job = user.designation?.localised().orEmpty(),
            department = user.department?.localised()?.trim().orEmpty(),
            status = user.status,
        )
    }

    /** Translated, one per name ignoring case, alphabetical — the web's `useDepartments`. */
    override suspend fun departments(): List<String> = departments.invoke()
        .map { it.localised().trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }

    private companion object {
        /** Well past the 25 MB send cap; a picker that admits a 2 GB file hangs before refusing it. */
        const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024

        const val MAC_DIRECTORIES = "apple.awt.fileDialogForDirectories"
    }
}

/** The transfer over the app's storage pieces; a function so the graph's constructor stays one line. */
internal fun docDistTransfer(
    storageClient: HttpClient,
    sign: RawSigner,
    remoteConfig: RemoteConfigRepository,
    storage: StorageTargetSource,
    noticeMedia: NoticeMediaSource,
): DocDistTransfer = AppDocDistTransfer(
    storageClient = storageClient,
    sign = sign,
    credentials = { awsKeyPair(remoteConfig) },
    storage = storage,
    noticeMedia = noticeMedia,
)

/** Anything but `LOCAL` stores in S3 — the phones' rule. */
internal fun ProjectContextLoader?.docDistUsesS3(): Boolean =
    this?.context?.value?.project?.storageType?.equals("LOCAL", ignoreCase = true) != true

// Badges --------------------------------------------------------------------------------------------

/**
 * The tool's ledger rows as leaves, and its three reads.
 *
 * Every unread `document_distribution_label` row becomes one leaf with the
 * folder path it sits in, read off the wire row the way
 * `getDocumentDistributionBadgeCounts` reads it: `level_1..3` (skipping the
 * `root` marker), the `levels[]` tail (`level_4:<id>` strings, or objects),
 * and — for a folder's own event — its `reference_id`. The reads are the
 * web's `emitScopedRead` (`Library.jsx:157-165`): the folder's own events
 * on entry, one file's on preview, a whole unit for a side section — the
 * entity reads sent as `notification:read`, which honours `reference_id`
 * (ZL-18873), rather than the level read the web still sends.
 */
internal fun AppGraph.Ready.docDistBadges(): DocDistBadges = object : DocDistBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val leaves: Flow<List<DocDistBadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun leavesNow(): List<DocDistBadgeLeaf> =
        badgeStore.unreadRows(BadgeSections.TOOLS)
            .filter { it.tool == DocDistBadges.TOOL }
            .groupingBy { row -> Triple(row.unit, row.referenceId, row.docDistAncestry()) }
            .eachCount()
            .map { (key, count) -> DocDistBadgeLeaf(key.first, key.second, key.third, count) }

    override fun readFolder(folderId: String) {
        scope.launch { emitReferenceRead(DocDistBadges.TOOL, folderId, unit = DocDistBadges.UNIT_FOLDER) }
    }

    override fun readFile(fileId: String) {
        scope.launch { emitReferenceRead(DocDistBadges.TOOL, fileId) }
    }

    override fun readUnit(unit: String) {
        scope.launch { emitLevelRead(tool = DocDistBadges.TOOL, unit = unit) }
    }
}

/** Every folder id a row is filed under — see [docDistBadges]. */
private fun NotificationRecord.docDistAncestry(): Set<String> {
    val ids = mutableSetOf<String>()
    listOf(level1, level2, level3).forEach { if (it.isNotBlank() && it != DOC_DIST_ROOT) ids += it }
    val row = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    (row?.get("levels") as? JsonArray)?.forEach { entry ->
        val id = when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.substringAfter(':')
            is JsonObject -> entry.values.lastOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
        if (!id.isNullOrBlank() && id != DOC_DIST_ROOT) ids += id
    }
    val ownFolder = unit == DocDistBadges.UNIT_FOLDER && referenceId.isNotBlank() && referenceId != DOC_DIST_ROOT
    if (ownFolder) ids += referenceId
    return ids
}

private const val DOC_DIST_ROOT = "root"
