package com.zillit.desktop.feature.tasks.domain

/**
 * The two calls that are **not** the service's: the bytes of an attachment.
 *
 * `POST /tasks/:id/attachments/uploads` hands back a signed link and the
 * exact headers it was signed for. The bytes then go straight to storage with
 * those headers and **nothing else** — no `moduledata`, no Bearer: the link
 * is the permission, and a header the signature does not cover makes storage
 * refuse a request it would otherwise have taken (the same rule the Drive's
 * multipart uploader works under).
 *
 * Reading is the mirror image: a thumbnail's signed link is fetched plainly,
 * because it is storage's URL and not ours.
 *
 * A seam rather than a call of its own, because the API client only speaks
 * the service's envelope and this is the one place in the module that has to
 * leave it. The host supplies it; a test or a headless harness supplies
 * [Unsupported], and adding an attachment then simply refuses.
 */
interface AttachmentBytes {

    /** True once storage has the bytes. */
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String): Boolean

    /** The bytes behind a signed link, or null when it could not be fetched. */
    suspend fun get(url: String): ByteArray?

    companion object {
        /** No host: nothing can be added, and no thumbnail loads. */
        val Unsupported: AttachmentBytes = object : AttachmentBytes {
            override suspend fun put(
                url: String,
                headers: Map<String, String>,
                bytes: ByteArray,
                contentType: String,
            ): Boolean = false

            override suspend fun get(url: String): ByteArray? = null
        }
    }
}

/** Step 1's answer: where to put the bytes, and the headers it was signed for. */
data class AttachmentUploadTicket(
    val key: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A file the host handed over — chosen in a dialog, pasted, or dropped on the
 * description.
 *
 * Read into memory, unlike the Drive's picked files: nothing here is larger
 * than 25 MB (what the checks admit before shrinking), and both an image's
 * shrinking and the PUT want the bytes anyway.
 */
class PickedTaskFile(val name: String, val mimeType: String, val bytes: ByteArray) {

    /** Never prints the bytes. */
    override fun toString(): String = "PickedTaskFile(name=$name, type=$mimeType, size=${bytes.size})"
}
