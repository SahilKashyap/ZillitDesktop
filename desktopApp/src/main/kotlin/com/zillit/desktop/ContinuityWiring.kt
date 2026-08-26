package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.continuity.data.ContinuityRepositoryImpl
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.ui.ContinuityToolProvider
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import com.zillit.desktop.feature.continuity.ui.decodeImageBitmap
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

/**
 * Continuity's host seams: files up through the app's S3 machinery under the
 * web's own key prefix (`/film-tools/continuity`), down through the
 * signed-storage path, thumbnails decoded for the cards.
 */
internal fun AppGraph.Ready.continuityTransfer(): ContinuityTransfer = object : ContinuityTransfer {

    override suspend fun upload(file: PickedContinuityFile): ZillitResult<ContinuityAttachment> {
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
            newKey = { name ->
                val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9. ]"), "").replace(" ", "")
                val ext = name.substringAfterLast('.', "bin")
                "$projectId/film-tools/continuity/actual/${stem}_${System.currentTimeMillis()}.$ext"
            },
        )
        return when (val stored = uploader.upload(file.name, file.contentType, file.bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                ContinuityAttachment(
                    media = stored.data.media,
                    // Images are their own thumbnail; the web renders videos and documents from a placeholder.
                    thumbnail = if (file.isImage) stored.data.media else "",
                    contentType = when {
                        file.isImage -> "image"
                        file.isVideo -> "video"
                        else -> "document"
                    },
                    contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                    name = file.name.replace(Regex("\\s"), ""),
                    bucket = stored.data.bucket.orEmpty(),
                    region = stored.data.region.orEmpty(),
                    fileSize = file.bytes.size.toString(),
                ),
            )
        }
    }

    override suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean): ZillitResult<ByteArray> =
        noticeMedia.fetch(attachment.toNotice(), preview = preview)

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

private fun ContinuityAttachment.toNotice() = NoticeAttachment(
    media = media,
    fileName = name,
    thumbnail = thumbnail.ifBlank { media },
    bucket = bucket,
    region = region,
)

internal fun AppGraph.Ready.buildContinuity(permissions: () -> ProjectPermissions) = ContinuityViewModel(
    repository = ContinuityRepositoryImpl(
        apiClient,
        config,
        localise = { it.localised() },
        bus = socketEvents,
    ),
    transfer = continuityTransfer(),
    resolveViewer = {
        val context = projectContext?.context?.value
        ContinuityViewer.from(
            permissions = permissions(),
            userId = context?.profile?.userId.orEmpty(),
            departmentId = context?.profile?.departmentId.orEmpty(),
            isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
        )
    },
    // The server sends the department's label key; the dictionary turns it into its display name.
    departmentName = { id ->
        val profile = projectContext?.context?.value?.profile
        profile?.departmentName?.takeIf { profile.departmentId == id }?.localised()
    },
    newUniqueId = { UUID.randomUUID().toString() },
    nowMillis = System::currentTimeMillis,
)

internal fun AppGraph.Ready.continuityProvider(viewModel: ContinuityViewModel, scope: CoroutineScope) =
    ContinuityToolProvider(
        viewModel = viewModel,
        onPickFiles = { onPicked ->
            scope.launch {
                onPicked(FilePicker().pick().map { PickedContinuityFile(it.name, it.contentType, it.bytes) })
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
        formatDate = ::continuityDate,
    )

/** "Aug 20, 2026" from epoch ms; "—" for none. */
internal fun continuityDate(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(MONTH_ABBREV)
    return "$month ${d.dayOfMonth.toString().padStart(2, '0')}, ${d.year}"
}

private const val MONTH_ABBREV = 3
