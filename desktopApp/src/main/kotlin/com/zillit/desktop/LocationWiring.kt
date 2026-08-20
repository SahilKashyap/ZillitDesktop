package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.location.data.LocationRepositoryImpl
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import com.zillit.desktop.feature.location.ui.LocationToolProvider
import com.zillit.desktop.feature.location.ui.LocationViewModel
import com.zillit.desktop.feature.location.ui.decodeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The location library's host seams: photos and videos up through the app's
 * S3 machinery under the web's own key prefix (`/film-tools/location`), down
 * through the signed-storage path, thumbnails decoded for the gallery.
 */
internal fun AppGraph.Ready.locationTransfer(): LocationTransfer = object : LocationTransfer {

    override suspend fun upload(file: PickedLocationFile): ZillitResult<MediaAttachment> {
        val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
        val uploader = S3AttachmentUploader(
            httpClient = httpClient,
            credentials = {
                val remote = remoteConfigRepository.current()
                val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
                val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
                if (access != null && secret != null) AwsCredentials(access, secret) else null
            },
            storage = storageTarget,
            // The web's key: `{projectId}{pathname}/actual/{name}` with only
            // `[A-Za-z0-9. ]` kept and a timestamp before the extension.
            newKey = { name ->
                val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9. ]"), "").replace(" ", "")
                val ext = name.substringAfterLast('.', "bin")
                "$projectId/film-tools/location/actual/${stem}_${System.currentTimeMillis()}.$ext"
            },
        )
        return when (val stored = uploader.upload(file.name, file.contentType, file.bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                MediaAttachment(
                    media = stored.data.media,
                    thumbnail = stored.data.media,
                    contentType = if (file.isVideo) "video" else "image",
                    contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                    name = file.name.replace(Regex("\\s"), ""),
                    bucket = stored.data.bucket.orEmpty(),
                    region = stored.data.region.orEmpty(),
                    fileSize = file.bytes.size.toString(),
                ),
            )
        }
    }

    override suspend fun fetch(attachment: MediaAttachment): ZillitResult<ByteArray> =
        noticeMedia.fetch(attachment.toNotice(), preview = false)

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

private fun MediaAttachment.toNotice() = NoticeAttachment(
    media = media,
    fileName = name,
    thumbnail = thumbnail,
    bucket = bucket,
    region = region,
)

internal fun AppGraph.Ready.buildLocation(permissions: () -> ProjectPermissions) = LocationViewModel(
    repository = LocationRepositoryImpl(apiClient, config, newUniqueId = { UUID.randomUUID().toString() }),
    transfer = locationTransfer(),
    resolveViewer = {
        val context = projectContext?.context?.value
        LocationViewer.from(
            permissions = permissions(),
            userId = context?.profile?.userId.orEmpty(),
            isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
        )
    },
    nowMillis = System::currentTimeMillis,
)

internal fun AppGraph.Ready.locationProvider(viewModel: LocationViewModel,
    scope: CoroutineScope) = LocationToolProvider(
    viewModel = viewModel,
    onPickFile = { onPicked ->
        scope.launch {
            val picked = FilePicker().pick().firstOrNull()
            onPicked(picked?.let { PickedLocationFile(it.name, it.contentType, it.bytes) })
        }
    },
    // Thumbnails ride the notice-media cache; the viewer asks for the full file.
    loadImage = { attachment, preview ->
        (noticeMedia.fetch(attachment.toNotice(), preview = preview) as? ZillitResult.Success)
            ?.data?.let(::decodeImageBitmap)
    },
    resolveUser = { userId ->
        projectContext?.context?.value?.user(userId)?.authorLine()
    },
)
