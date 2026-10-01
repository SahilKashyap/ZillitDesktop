package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.MultipartSender
import com.zillit.desktop.feature.costumesetsync.domain.CrewMember
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.network.S3Presigner
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.StoredFile
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.ui.SyncOnsetToolProvider
import com.zillit.desktop.feature.costumesetsync.ui.SyncOnsetViewModel
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Costumes & Set Sync's host wiring. The service has no login of its own: the
 * project Bearer rides every call, and the production it is scoped to is the
 * one the app has open.
 */
internal fun AppGraph.Ready.buildCostumeSetSync(permissions: () -> ProjectPermissions): SyncOnsetViewModel {
    val projectId = { projectContext?.context?.value?.project?.projectId }
    return SyncOnsetViewModel(
        api = SyncOnsetApi(apiClient, config, projectId, multipart = costumeSetSyncMultipart()),
        viewer = { SyncViewer.from(permissions()) },
        rights = rightsRequests,
        events = socketEvents,
        projectId = projectId,
        userId = { projectContext?.context?.value?.profile?.userId.orEmpty() },
        host = costumeSetSyncHost(),
    )
}

/**
 * Costume photos, clips and files: the web's two steps — the file goes to the
 * project's storage on the app's S3 machinery, then the service is told the
 * `attachment` details. Stored files come back as presigned URLs, fetched on
 * the bare client (a presigned URL must NOT carry the API's encrypted headers).
 */
internal fun AppGraph.Ready.costumeSetSyncHost(): SyncHost = object : SyncHost {

    private val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })

    private val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName ->
            val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
            val stem = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val ext = fileName.substringAfterLast('.', "bin")
            "$projectId/film-tools/costume-set-sync/${System.currentTimeMillis()}_$stem.$ext"
        },
    )

    override suspend fun pick(extensions: Set<String>, multiple: Boolean): List<PickedFile> {
        val kind = when {
            extensions.isEmpty() -> PreviewKind.Document
            extensions.all { it in IMAGE_EXTENSIONS } -> PreviewKind.Image
            extensions.all { it in VIDEO_EXTENSIONS } -> PreviewKind.Video
            // Photos and clips together (Gallery): the open picker, narrowed after the choice.
            else -> PreviewKind.Document
        }
        return attachmentPicker.pick(kind, multiple = multiple, maxBytes = COSTUME_MAX_BYTES)
            .filter { extensions.isEmpty() || it.name.substringAfterLast('.', "").lowercase() in extensions }
            .map { PickedFile(name = it.name, bytes = it.bytes, mime = it.contentType) }
    }

    override suspend fun store(file: PickedFile): ZillitResult<StoredFile> =
        when (val up = uploader.upload(file.name, file.mime, file.bytes)) {
            is ZillitResult.Failure -> up
            is ZillitResult.Success -> ZillitResult.Success(
                StoredFile(
                    media = up.data.media,
                    name = file.name,
                    contentType = when {
                        file.isImage -> "image"
                        file.isVideo -> "video"
                        else -> "document"
                    },
                    contentSubtype = file.extension,
                    bucket = up.data.bucket,
                    region = up.data.region,
                    fileSize = file.bytes.size.toLong(),
                ),
            )
        }

    override suspend fun resolveUrl(media: String, bucket: String, region: String): String? {
        // A row without its own bucket/region lives in the production's storage.
        val fallback = (storageTarget.target() as? ZillitResult.Success)?.data
        return presigner.presignedGet(
            bucket = bucket.ifBlank { fallback?.bucket.orEmpty() },
            region = region.ifBlank { fallback?.region.orEmpty() },
            key = media,
        )
    }

    override suspend fun crew(): List<CrewMember> =
        projectContext?.context?.value?.users.orEmpty()
            .filter { it.status?.lowercase() !in INACTIVE_CREW && it.fullName.isNotBlank() }
            .map {
                CrewMember(
                    id = it.userId,
                    name = it.fullName,
                    department = it.department?.localised().orEmpty(),
                    email = it.email.orEmpty(),
                )
            }
            .sortedBy { it.name.lowercase() }

    override suspend fun fetch(url: String): ByteArray? = runCatching {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "signed fetch answered ${response.status}" }
        response.readRawBytes()
    }.getOrNull()

    override fun openUrl(url: String) = openInBrowser(url)

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, suggestedName, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val directory = dialog.directory ?: return@withContext false
        val chosen = dialog.file ?: return@withContext false
        runCatching { File(directory, chosen).writeBytes(bytes) }.isSuccess
    }

    override suspend fun open(fileName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "zillit-costumes").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            target.writeBytes(bytes)
            target.deleteOnExit()
            openSavedFile(target.absolutePath)
        }.isSuccess
    }
}

/** Statuses the service's own member check refuses. */
private val INACTIVE_CREW = setOf("pending", "rejected", "removed", "left")

/**
 * The script and schedule parses are multipart (`file` + `kind`), which `ApiClient` (JSON envelopes only) cannot
 * send. Same signed headers as [postForBytes]; the envelope that comes back is folded into an [Answer].
 */
internal fun AppGraph.Ready.costumeSetSyncMultipart() = MultipartSender { url, fileName, bytes, mime, fields ->
    runCatching {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson = null) { headers, bearer ->
            httpClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            fields.forEach { (key, value) -> append(key, value) }
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, mime)
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
        }
        val envelope = HttpClientFactory.json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        val status = (envelope?.get("status") as? JsonPrimitive)?.intOrNull
        val message = (envelope?.get("message") as? JsonPrimitive)?.contentOrNull
        if (response.status.isSuccess() && status == 1) {
            ZillitResult.Success(Answer(envelope["data"], message))
        } else {
            ZillitResult.Failure(ZillitError.Http(status = response.status.value, serverMessage = message))
        }
    }.getOrElse { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "upload failed")) }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "avi", "mkv", "3gp")

/** The web's own ceiling on one photo or clip. */
private const val COSTUME_MAX_BYTES: Long = 250L * 1024 * 1024

internal fun costumeSetSyncProvider(viewModel: SyncOnsetViewModel) = SyncOnsetToolProvider(viewModel)
