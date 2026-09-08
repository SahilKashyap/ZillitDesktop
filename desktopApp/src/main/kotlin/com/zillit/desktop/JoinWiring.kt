package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.ChosenPhoto
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.JoinPhotoStore
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import java.util.UUID

/**
 * The join form's profile picture: somewhere to put it, and a way to choose it.
 *
 * Both are host concerns. The auth module knows only that a picture can be
 * stored and that storing yields four strings; S3, AWS signing and the native
 * file dialog all live here, as they do for e-signature and the boards.
 */
internal fun AppGraph.Ready.joinPhotoStore(): JoinPhotoStore {
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }

    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName ->
            "profile-picture/${UUID.randomUUID()}/${fileName.replace(UNSAFE, "_")}"
        },
    )

    return JoinPhotoStore { photo ->
        when (val stored = uploader.upload(photo.name, photo.contentType, photo.bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                JoinPhoto(
                    media = stored.data.media,
                    // The same key twice, which is what Android sends:
                    // `imagePath = key; thumbPath = key`. The server derives
                    // its own small copy; a second upload of the same bytes
                    // under a different name would just cost the user time on
                    // a unit's hotel wifi.
                    thumbnail = stored.data.media,
                    bucket = stored.data.bucket,
                    region = stored.data.region,
                ),
            )
        }
    }
}

/**
 * Picks one image from disk — the app's shared attach dialog, filtered to
 * photos. Null when the user cancels, or when what they chose is not an
 * image: a PDF named `.jpg` would upload happily and then show as a broken
 * avatar on every crew list, and the picker checks the type after reading.
 */
internal suspend fun chooseJoinPhoto(): ChosenPhoto? =
    attachmentPicker
        .pick(com.zillit.desktop.core.media.PreviewKind.Image, multiple = false, maxBytes = MAX_PHOTO_BYTES)
        .firstOrNull()
        ?.let { ChosenPhoto(name = it.name, contentType = it.contentType, bytes = it.bytes) }

private val UNSAFE = Regex("[^A-Za-z0-9._-]")

/** Generous for a portrait, small enough that a mis-picked raw file is refused. */
private const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

