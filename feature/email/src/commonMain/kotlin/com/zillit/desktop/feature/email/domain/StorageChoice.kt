package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/** Where a production's files go. Decided per production, by the server. */
enum class StorageKind { Aws, Box }

/**
 * Reads the production's storage setting.
 *
 * `BOX` exactly, case-insensitively, and anything else — including a missing
 * value — means AWS. Defaulting the *other* way would send a production's files
 * to a Box tenant it may not have.
 */
fun storageKindOf(storageType: String?): StorageKind =
    if (storageType?.equals("BOX", ignoreCase = true) == true) StorageKind.Box else StorageKind.Aws

/** What Box needs before it will accept a file. */
data class BoxSettings(
    /** Box's tenant, exchanged for an upload token. */
    val enterpriseClientId: String,
    /**
     * The folder to upload into.
     *
     * `0` is Box's own root. The web sends an *empty* parent id for email
     * attachments, which Box's API does not document as valid — so this falls
     * back to the root rather than copying a value that looks like an oversight.
     */
    val folderId: String = BOX_ROOT_FOLDER,
) {
    val isUsable: Boolean get() = enterpriseClientId.isNotBlank()
}

const val BOX_ROOT_FOLDER = "0"

/**
 * Picks the uploader for the open production.
 *
 * A production is on one or the other, and which is not known until it opens —
 * so this cannot be decided when the graph is built.
 */
class RoutingAttachmentUploader(
    private val kind: () -> StorageKind,
    private val aws: AttachmentUploader,
    private val box: AttachmentUploader,
) : AttachmentUploader {

    override suspend fun upload(
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ): ZillitResult<StoredFile> = when (kind()) {
        StorageKind.Aws -> aws.upload(fileName, contentType, bytes, onProgress)
        StorageKind.Box -> box.upload(fileName, contentType, bytes, onProgress)
    }
}
