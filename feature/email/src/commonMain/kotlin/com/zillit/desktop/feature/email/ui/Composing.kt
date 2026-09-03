package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.PickedFile
import com.zillit.desktop.feature.email.domain.SignatureRepository

/**
 * Everything writing a message needs.
 *
 * Bundled because the composer had grown to ten constructor parameters, and a
 * ten-parameter constructor is one where call sites get an argument in the
 * wrong position and nothing complains. These are all *collaborators* — the
 * per-message arguments (mode, what is being replied to) stay separate,
 * because they are the part that differs per window.
 */
data class Composing(
    val repository: EmailRepository,
    val drafts: DraftRepository,
    val contacts: ContactRepository,
    val signatures: SignatureRepository,
    /** Crew on this production, for To/Cc/Bcc suggestions. */
    val crew: () -> List<EmailContact> = { emptyList() },
    /** Null on a build with no file storage, which hides the Attach button. */
    val uploader: AttachmentUploader? = null,
    /** Opens the system file chooser. Suspends while it is up. */
    val chooseFiles: suspend () -> List<PickedFile> = { emptyList() },
    /**
     * The same chooser, filtered to one kind from the attach sheet. Defaults
     * to the untyped one so a host (or test) wiring only that still works.
     */
    val chooseFilesOf: suspend (PreviewKind) -> List<PickedFile> = { chooseFiles() },
    val newAttachmentId: () -> String = { "attachment" },
    /** Reply-all drops this address, so a reply never goes to its sender. */
    val selfAddress: () -> String = { "" },
)
