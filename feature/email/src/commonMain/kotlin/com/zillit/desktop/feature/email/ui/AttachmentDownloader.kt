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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Where an attachment has got to. */
sealed interface AttachmentDownload {
    data object InProgress : AttachmentDownload

    /** [path] is shown so the user can find the file without the app's help. */
    data class Saved(val path: String) : AttachmentDownload

    data class Failed(val reason: String) : AttachmentDownload
}

/**
 * Fetching attachments and putting them on disk.
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
) {

    private val _state = MutableStateFlow<Map<String, AttachmentDownload>>(emptyMap())
    val state: StateFlow<Map<String, AttachmentDownload>> = _state

    val isAvailable: Boolean get() = store != null

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
        val target = store ?: return
        // Already downloading, or already on disk: a second click should not
        // produce "report (2).pdf".
        if (_state.value[attachment.id] != null) return

        set(attachment.id, AttachmentDownload.InProgress)

        when (val fetched = repository.attachment(attachment.id, messageId, folderName)) {
            is ZillitResult.Failure ->
                set(attachment.id, AttachmentDownload.Failed(fetched.error.localised()))

            is ZillitResult.Success -> {
                val bytes = decodeAttachment(fetched.data, decodeBase64)
                if (bytes == null) {
                    set(attachment.id, AttachmentDownload.Failed(str(S.desktop_email_attachment_file_empty)))
                    return
                }
                when (val saved = target.save(attachment.fileName, bytes)) {
                    is ZillitResult.Success -> set(attachment.id, AttachmentDownload.Saved(saved.data))
                    is ZillitResult.Failure ->
                        set(attachment.id, AttachmentDownload.Failed(saved.error.localised()))
                }
            }
        }
    }

    private fun set(id: String, state: AttachmentDownload) {
        _state.update { it + (id to state) }
    }
}
