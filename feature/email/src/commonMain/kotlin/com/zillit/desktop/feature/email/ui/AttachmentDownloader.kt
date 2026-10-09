package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.AttachmentStore
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.decodeAttachment
import com.zillit.desktop.feature.email.domain.decodeBase64Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Where an attachment has got to. */
sealed interface AttachmentDownload {
    data object InProgress : AttachmentDownload

    /** [path] is shown so the user can find the file without the app's help. */
    data class Saved(val path: String) : AttachmentDownload

    data class Failed(val reason: String) : AttachmentDownload
}

/**
 * Fetching attachments, showing them, and putting them on disk.
 *
 * Its own class rather than more of the mailbox's: downloading has nothing to do
 * with folders, syncing, selection or drafts, and it keeps its own state for its
 * own lifetime. The mailbox merely passes the state through to the reading pane.
 */
class AttachmentDownloader(
    private val repository: EmailRepository,
    /**
     * Null on a build with no filesystem access, which hides the affordance
     * rather than offering one that cannot work.
     */
    private val store: AttachmentStore?,
    private val decodeBase64: (String) -> ByteArray? = ::decodeBase64Default,
    /** How a previewed file is drawn — see [renderAttachmentPreview]. */
    private val render: (fileName: String, contentType: String?, bytes: ByteArray) -> PreviewBody =
        ::renderAttachmentPreview,
) {

    private val _state = MutableStateFlow<Map<String, AttachmentDownload>>(emptyMap())
    val state: StateFlow<Map<String, AttachmentDownload>> = _state

    private val _preview = MutableStateFlow<AttachmentPreview?>(null)

    /** The attachment open in the preview, if any. */
    val preview: StateFlow<AttachmentPreview?> = _preview

    /** The open preview's bytes, so its Download writes them rather than fetching again. */
    private var previewBytes: ByteArray? = null

    val isAvailable: Boolean get() = store != null

    /** A downloader with the same collaborators and none of this one's state — for a popped-out window. */
    fun fork(): AttachmentDownloader = AttachmentDownloader(repository, store, decodeBase64, render)

    /**
     * Fetches an attachment and saves it.
     *
     * Two steps that can each fail differently, so they report differently: the
     * server refusing the attachment and the disk refusing the write are not the
     * same problem for the person reading the message.
     *
     * Nothing is opened afterwards — see [AttachmentStore].
     */
    suspend fun download(attachment: EmailAttachment, messageId: String, folderName: String) {
        if (store == null) return
        // Already downloading, or already on disk: a second click should not
        // produce "report (2).pdf".
        if (_state.value[attachment.id] != null) return

        set(attachment.id, AttachmentDownload.InProgress)

        when (val fetched = fetch(attachment, messageId, folderName)) {
            is Fetched.Refused -> set(attachment.id, AttachmentDownload.Failed(fetched.reason))
            is Fetched.Bytes -> save(attachment, fetched.bytes)
        }
    }

    /**
     * Opens [attachment] in the preview: on screen at once, filled when the
     * bytes arrive and have been drawn.
     */
    suspend fun preview(attachment: EmailAttachment, messageId: String, folderName: String) {
        val opening = AttachmentPreview(attachment, messageId, folderName)
        _preview.value = opening
        previewBytes = null

        val fetched = fetch(attachment, messageId, folderName)
        // Closed, or another file opened, while this one was on its way.
        if (_preview.value !== opening) return

        when (fetched) {
            is Fetched.Refused -> _preview.value = opening.copy(failed = fetched.reason)
            is Fetched.Bytes -> {
                val body = withContext(Dispatchers.Default) {
                    render(attachment.fileName, attachment.contentType, fetched.bytes)
                }
                if (_preview.value !== opening) return
                previewBytes = fetched.bytes
                _preview.value = opening.copy(body = body)
            }
        }
    }

    fun closePreview() {
        _preview.value = null
        previewBytes = null
    }

    /** The preview's Download: the bytes already fetched, else a fresh download. */
    suspend fun downloadPreviewed() {
        val open = _preview.value ?: return
        val bytes = previewBytes
        if (bytes == null) {
            download(open.attachment, open.messageId, open.folderName)
            return
        }
        if (store == null || _state.value[open.attachment.id] != null) return
        set(open.attachment.id, AttachmentDownload.InProgress)
        save(open.attachment, bytes)
    }

    private suspend fun fetch(attachment: EmailAttachment, messageId: String, folderName: String): Fetched =
        when (val fetched = repository.attachment(attachment.id, messageId, folderName)) {
            is ZillitResult.Failure -> Fetched.Refused(fetched.error.localised())
            is ZillitResult.Success ->
                decodeAttachment(fetched.data, decodeBase64)?.let(Fetched::Bytes)
                    ?: Fetched.Refused(str(S.desktop_email_attachment_file_empty))
        }

    private suspend fun save(attachment: EmailAttachment, bytes: ByteArray) {
        val target = store ?: return
        when (val saved = target.save(attachment.fileName, bytes)) {
            is ZillitResult.Success -> set(attachment.id, AttachmentDownload.Saved(saved.data))
            is ZillitResult.Failure -> set(attachment.id, AttachmentDownload.Failed(saved.error.localised()))
        }
    }

    private fun set(id: String, state: AttachmentDownload) {
        _state.update { it + (id to state) }
    }

    private sealed interface Fetched {
        class Bytes(val bytes: ByteArray) : Fetched
        class Refused(val reason: String) : Fetched
    }
}
