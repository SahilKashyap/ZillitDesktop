package com.zillit.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.crewlist.data.CrewListRepositoryImpl
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CrewListHost
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewPdfPage
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.PickedLogo
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The Crew List (`crewlist` on the units host), and everything it borrows from
 * the app: the Info board's unit and cipher for publishing, the signed reader
 * and PDFBox for the viewer, Downloads, the library's from-tool route, the admin
 * service's departments, the account hub's dial codes, and storage for the logo.
 */
internal fun AppGraph.Ready.buildCrewList(
    permissions: () -> ProjectPermissions,
): CrewListViewModel = CrewListViewModel(
    repository = crewListRepository(permissions),
    resolveViewer = { CrewListViewer.from(permissions(), isOtherProject = isOtherProduction()) },
    host = crewListHost(permissions),
    translate = { key -> key.localised() },
    text = ::crewListText,
    selfUserId = { projectContext?.context?.value?.profile?.userId.orEmpty() },
    projectId = { projectContext?.context?.value?.project?.projectId },
    rights = rightsRequests,
)

/**
 * The repository, for the open production — or, from the widget, for another
 * one ([options] carries its project and the reader's id there).
 */
internal fun AppGraph.Ready.crewListRepository(
    permissions: () -> ProjectPermissions,
    options: () -> CallOptions = { CallOptions() },
    projectId: () -> String? = { projectContext?.context?.value?.project?.projectId },
) = CrewListRepositoryImpl(
    apiClient = apiClient,
    config = config,
    bus = socketEvents,
    currentProjectId = projectId,
    callOptions = options,
    rawPost = { url, body -> crewRawPost(url, body, options()) },
    // The Info board's unit is its row in the tools grid, as the Info board itself reads it.
    infoUnitId = { permissions().access(CrewListViewer.INFO_TOOL).unitId },
    encrypt = { plain -> noticeDecryptor.encryptToHex(plain) },
    nowMillis = System::currentTimeMillis,
    newId = { UUID.randomUUID().toString() },
)

/** The production's wording for a key, when the dictionary has one; the web's English otherwise. */
private fun crewListText(key: String, fallback: String): String =
    Labels.current.exact(key)?.takeIf { it.isNotBlank() } ?: fallback

/** An `other` production is a workspace, not a shoot: its roster is a Staff List. */
private fun AppGraph.Ready.isOtherProduction(): Boolean =
    projectContext?.context?.value?.project?.type.equals(OTHER_PROJECT_TYPE, ignoreCase = true)

/**
 * A signed POST read raw — `crewlist/html` answers `text/html`, which the
 * envelope client cannot read. `postForBytes`, with the call's production (the
 * widget's) carried into the headers. A JSON body on the wire is the service
 * declining: its message is surfaced.
 */
private suspend fun AppGraph.Ready.crewRawPost(
    url: String,
    body: JsonObject,
    options: CallOptions,
): ZillitResult<ByteArray> {
    val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
    val headers = headerProvider.headersFor(RequestModule.ProjectUser, bodyJson, options.projectId, options.userId)
    return runCatching {
        val response = httpClient.post(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            contentType(ContentType.Application.Json)
            setBody(bodyJson)
        }
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (isJson || !response.status.isSuccess()) {
            error(declineMessage(bytes) ?: "The preview could not be rendered (${response.status.value}).")
        }
        bytes
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { thrown ->
            ZillitResult.Failure(ZillitError.Validation(thrown.message ?: str(S.desktop_preview_not_rendered)))
        },
    )
}

private fun declineMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }

internal fun AppGraph.Ready.crewListHost(permissions: () -> ProjectPermissions): CrewListHost {
    val picker = AwtAttachmentPicker()
    // Its own prefix: a letterhead logo belongs to the production's paperwork.
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName -> "company-logo/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val faces = crewFaceLoader(this)

    return object : CrewListHost {
        override suspend fun fetchPdf(pdf: CrewListPdf): ZillitResult<ByteArray> = noticeMedia.fetch(
            NoticeAttachment(media = pdf.media, fileName = pdf.fileName, bucket = pdf.bucket, region = pdf.region),
            preview = false,
        )

        override suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<CrewPdfPage>> =
            withContext(Dispatchers.IO) {
                PdfBoxWork().renderPages(pdf, widthPx).map { pages ->
                    pages.mapNotNull { page ->
                        decodeImageBitmap(page.imageBytes)?.let { CrewPdfPage(it, page.widthPx, page.heightPx) }
                    }
                }
            }

        override suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }

        /**
         * `documents/from-tool` under the cross-platform root "Crew List" (no
         * episode or scene leaf), dated today — the web's
         * `distributeCrewListToDocDist` and Android's `DocDistTool.CREW_LIST`.
         */
        override suspend fun distribute(pdf: CrewListPdf): ZillitResult<Unit> = FromToolPublisher(
            apiClient,
            config,
            canPost = { permissions().let { it.canPost(DOC_DISTRIBUTION_TOOL) || it.isAdmin } },
        ).publish(
            FromToolFile(
                folderPath = listOf(DOC_DIST_CREW_LIST_ROOT),
                name = pdf.fileName,
                media = pdf.media,
                bucket = pdf.bucket,
                region = pdf.region,
                contentType = "document",
                contentSubtype = pdf.contentSubtype,
                thumbnail = pdf.thumbnail.ifBlank { null },
                fileSizeBytes = pdf.fileSize.toDoubleOrNull()?.toLong() ?: 0,
                folderDate = EpochDate.isoDate(System.currentTimeMillis()),
            ),
        ).map { }

        override suspend fun departments(): ZillitResult<List<OrderedDepartment>> =
            adminRepository.departments().map { rows -> rows.map { OrderedDepartment(it.id, it.name.localised()) } }

        override suspend fun reorderDepartments(ids: List<String>): ZillitResult<Unit> =
            adminRepository.reorderDepartments(ids)

        /** The service's list, or the web's own copy when the service cannot be reached. */
        override suspend fun dialCodes(): List<DialCode> =
            accountHubRepository.isdCodes().getOrNull().orEmpty().ifEmpty { IsdCountries.bundled }
                .filter { it.dialCode.isNotBlank() }
                .map { DialCode(name = it.name, dialCode = it.dialCode, isoCode = it.code) }

        // No size refusal: the web's "up to 2 MB" is a hint its upload never enforces.
        override suspend fun pickLogo(): PickedLogo? = picker.pick(kind = PreviewKind.Image, multiple = false)
            .firstOrNull()
            ?.let { PickedLogo(it.name, it.bytes) }

        override suspend fun uploadLogo(file: PickedLogo): ZillitResult<CompanyLogo> =
            uploader.upload(file.name, contentTypeFor(file.name, null), file.bytes).map { stored ->
                CompanyLogo(media = stored.media, bucket = stored.bucket, region = stored.region)
            }

        override suspend fun logoImage(logo: CompanyLogo): ImageBitmap? = noticeMedia.fetch(
            NoticeAttachment(media = logo.media, bucket = logo.bucket, region = logo.region),
            preview = false,
        ).getOrNull()?.let(::decodeImageBitmap)

        override suspend fun decode(bytes: ByteArray): ImageBitmap? = decodeImageBitmap(bytes)

        override suspend fun face(userId: String): ImageBitmap? = faces(userId)
    }
}

/** The library's root folder for crew lists — the exact string every client files under. */
private const val DOC_DIST_CREW_LIST_ROOT = "Crew List"

private const val OTHER_PROJECT_TYPE = "other"
