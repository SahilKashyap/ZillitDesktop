package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.S3Presigner
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.sides.domain.FiledSides
import com.zillit.desktop.feature.sides.domain.SidesDistribution
import com.zillit.desktop.feature.sides.domain.SidesDocDist
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/**
 * Sides' host seams: uploads on the app's S3 machinery, signed-URL fetches on
 * the bare client (a presigned URL must NOT carry the API's encrypted
 * headers), call-sheet previews presigned client-side (the sides service has
 * no call-sheet download route), rendering on the documents tool's PDFBox.
 */
internal fun AppGraph.Ready.sidesTransfer(): SidesTransfer = object : SidesTransfer {

    private val work = PdfBoxWork()

    private val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })

    private val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName ->
            "sides/${UUID.randomUUID()}/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        },
    )

    override suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment> {
        val subtype = SidesRules.contentSubtype(fileName)
        val contentType = if (subtype == "fdx") "application/xml" else "application/pdf"
        return when (val stored = uploader.upload(fileName, contentType, bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                StoredAttachment(
                    media = stored.data.media,
                    name = fileName,
                    bucket = stored.data.bucket,
                    region = stored.data.region,
                    fileSizeBytes = bytes.size.toLong(),
                    contentSubtype = subtype,
                ),
            )
        }
    }

    override suspend fun fetch(url: String): ZillitResult<ByteArray> = runCatching {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "signed fetch answered ${response.status}" }
        response.readRawBytes()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "fetch failed")) },
    )

    override suspend fun presign(attachment: StoredAttachment): ZillitResult<String> {
        if (attachment.isBlank) return ZillitResult.Failure(ZillitError.Unknown("This call sheet has no file"))
        // A row without its own bucket/region lives in the production's storage.
        val fallback = (storageTarget.target() as? ZillitResult.Success)?.data
        val bucket = attachment.bucket.ifBlank { fallback?.bucket.orEmpty() }
        val region = attachment.region.ifBlank { fallback?.region.orEmpty() }
        val url = presigner.presignedGet(bucket = bucket, region = region, key = attachment.media)
            ?: return ZillitResult.Failure(ZillitError.Unknown("No file storage is configured for this production"))
        return ZillitResult.Success(url)
    }

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SidesPdfPage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { page ->
                    SidesPdfPage(
                        page = page.page,
                        imageBytes = page.imageBytes,
                        widthPx = page.widthPx,
                        heightPx = page.heightPx,
                    )
                },
            )
        }
}

/**
 * Sides' hand-off to Document Distribution: the same gate every tool reads
 * (posting rights on the library, or project admin) and the library's
 * from-tool route, which takes the storage keys and re-uploads nothing.
 *
 * The folder is the cross-platform root `Sides` — the server resolves
 * folders BY NAME, so the spelling is the contract.
 */
internal fun AppGraph.Ready.sidesDocDist(permissions: () -> ProjectPermissions): SidesDocDist =
    object : SidesDocDist {

        override fun canDistribute(): Boolean {
            val grid = permissions()
            // The soft default every client has: a grid that has not loaded
            // yet permits, so the action is not hidden for the first beat
            // after a production opens (the web's `useDocumentDistribution-
            // Rights`, and this app's own `DocDistViewer.from`).
            if (!grid.access(DOC_DISTRIBUTION_TOOL).enabled && grid.visibleTools.isEmpty()) return true
            return grid.canPost(DOC_DISTRIBUTION_TOOL) || grid.isAdmin ||
                projectContext?.context?.value?.isAdmin == true
        }

        override suspend fun distribute(file: SidesDistribution): ZillitResult<FiledSides> = FromToolPublisher(
            apiClient,
            config,
            canPost = { canDistribute() },
        ).publishDocument(
            FromToolFile(
                folderPath = file.folderPath,
                name = file.fileName,
                media = file.media,
                bucket = file.bucket,
                region = file.region,
                contentType = SIDES_CONTENT_TYPE,
                contentSubtype = file.contentSubtype,
                fileSizeBytes = file.fileSizeBytes,
                folderDate = file.folderDate,
            ),
        ).map { filed -> FiledSides(documentId = filed.id, folderId = filed.folderId) }
    }

/** The library's `content_type` for a document, as every tool sends it. */
private const val SIDES_CONTENT_TYPE = "document"

/**
 * The raw authed GET the scenes routes need: they answer bare JSON, which the
 * enveloped ApiClient would refuse as a missing `data` field.
 *
 * It rides [signedRawResponse] rather than building headers itself, so it
 * makes the same Bearer-vs-`moduledata` decision `ApiClient.envelope` makes
 * and heals one expired-token 401 the same way. It did build them itself
 * until now, and the token-auth migration left it behind: every scene fetch
 * sent legacy `moduledata`, develop answered `401`, and because this call
 * rides the unlogged storage client the refusal was invisible in
 * `~/.zillit/logs` — the only symptom was "Something went wrong." the moment
 * the Generate Sides form expanded the active script's current version. See
 * `RawHttp.kt` for the same gap, found app-wide in the raw exports.
 */
internal fun AppGraph.Ready.sidesRawGet(): suspend (String) -> ZillitResult<String> = { url ->
    runCatching {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson = null) { headers, bearer ->
            httpClient.get(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
            }
        }
        if (!response.status.isSuccess()) {
            throw SceneFetchDeclined(response.status.value, sceneDeclineMessage(response.bodyAsText()))
        }
        response.bodyAsText()
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        // The service's own reason, not a blanket "Something went wrong." — the
        // same mapping every raw call uses.
        onFailure = { failure ->
            val declined = failure as? SceneFetchDeclined
            ZillitResult.Failure(
                rawExportError(declined?.httpStatus, declined?.serverMessage, failure.message ?: "scene fetch failed"),
            )
        },
    )
}

/** A scenes route that refused, carrying whatever reason it gave. */
private class SceneFetchDeclined(
    val httpStatus: Int,
    val serverMessage: String?,
) : RuntimeException(serverMessage ?: "scene fetch answered $httpStatus")

/** The refusal's `message` key, when the body carried one. */
private fun sceneDeclineMessage(body: String): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }

/** A PDF, or PDF/Final Draft, picker for the sides forms. */
internal suspend fun pickSidesDocument(pdfOnly: Boolean): PickedDoc? = withContext(Dispatchers.IO) {
    val title = if (pdfOnly) str(S.desktop_choose_pdf) else str(S.desktop_choose_pdf_or_fdx)
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> if (pdfOnly) SidesRules.isPdf(name) else SidesRules.isPdfOrFdx(name) }
    dialog.isVisible = true
    val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return@withContext null
    if (file.length() > MAX_SIDES_DOC_BYTES) return@withContext null
    runCatching { PickedDoc(name = file.name, bytes = file.readBytes()) }.getOrNull()
}

/** The save-to-disk half of a download: a native save dialog seeded with the suggested name. */
internal suspend fun saveSidesFile(fileName: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, str(S.desktop_save_sides), FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext
    val chosen = dialog.file ?: return@withContext
    runCatching { File(directory, chosen).writeBytes(bytes) }
}

private const val MAX_SIDES_DOC_BYTES = 100L * 1024 * 1024
