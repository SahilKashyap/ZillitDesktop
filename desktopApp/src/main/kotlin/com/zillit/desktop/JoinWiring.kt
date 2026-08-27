package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.ChosenPhoto
import com.zillit.desktop.feature.auth.domain.JoinPhoto
import com.zillit.desktop.feature.auth.domain.JoinPhotoStore
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLConnection
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Picks one image from disk.
 *
 * AWT's `FileDialog` for the same reason the mail composer uses it: it is the
 * real macOS panel, where `JFileChooser` draws its own and looks a decade out
 * of date. Null when the user cancels, when the file cannot be read, or when
 * it is not an image — a PDF named `.jpg` would upload happily and then show
 * as a broken avatar on every crew list.
 */
internal suspend fun chooseJoinPhoto(): ChosenPhoto? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, "Choose a photo", FileDialog.LOAD)
    dialog.isMultipleMode = false
    // Advisory on macOS, enforced by the type check below on every platform.
    dialog.setFilenameFilter { _, name -> name.hasImageExtension() }
    dialog.isVisible = true

    dialog.files.orEmpty().firstOrNull()?.let(::readPhoto)
}

private fun readPhoto(file: File): ChosenPhoto? = try {
    val contentType = URLConnection.guessContentTypeFromName(file.name).orEmpty()
    when {
        !file.isFile -> null

        // Refused before reading: pulling a huge file into memory to then
        // reject it would hang the window first.
        file.length() > MAX_PHOTO_BYTES -> {
            ZillitLog.w(TAG) { "photo refused: ${file.length()} bytes" }
            null
        }

        !contentType.startsWith("image/") && !file.name.hasImageExtension() -> {
            ZillitLog.w(TAG) { "photo refused: not an image" }
            null
        }

        else -> ChosenPhoto(
            name = file.name,
            contentType = contentType.ifBlank { "image/jpeg" },
            bytes = file.readBytes(),
        )
    }
} catch (error: java.io.IOException) {
    ZillitLog.w(TAG) { "could not read the chosen photo: ${error.message}" }
    null
}

private fun String.hasImageExtension(): Boolean =
    IMAGE_EXTENSIONS.any { endsWith(it, ignoreCase = true) }

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".heic", ".webp", ".gif", ".bmp")

private val UNSAFE = Regex("[^A-Za-z0-9._-]")

/** Generous for a portrait, small enough that a mis-picked raw file is refused. */
private const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

private const val TAG = "Join"
