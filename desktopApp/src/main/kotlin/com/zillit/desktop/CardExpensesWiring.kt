package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PickedFile
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachment
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.PickKind
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import java.util.UUID

/**
 * The crew, for the card module's holder picker.
 *
 * A host seam rather than a repository call: the crew list belongs to the open
 * production, not to the card service, and every other module that picks a
 * person reads it the same way. The department comes along because a card
 * request carries `department_id`, and the server does not derive it.
 */
internal suspend fun AppGraph.Ready.cardPeople(): List<CardPerson> =
    hubUsers().filter { it.isAccepted }.map { user ->
        CardPerson(
            id = user.id,
            name = user.name,
            // Label keys off the hub roster; the settings page prints both.
            designation = user.designation.localised(),
            department = user.department.localised(),
            departmentId = user.departmentId,
        )
    }

/**
 * Picking a receipt or a statement, and putting it in the project's store.
 *
 * The card service takes a **pointer** on every route that involves a file —
 * it accepts no multipart upload of its own — so both halves are this
 * application's job: choose the file, store it, hand over the key.
 *
 * A cancelled picker answers success-with-null rather than a failure. The
 * person changed their mind, and a dialog apologising for that is worse than
 * saying nothing.
 */
internal fun AppGraph.Ready.cardAttachmentUploader(): CardAttachmentUploader {
    val picker = AwtAttachmentPicker()
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            val remote = remoteConfigRepository.current()
            val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
            val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
            if (access != null && secret != null) AwsCredentials(access, secret) else null
        },
        storage = storageTarget,
        // Its own prefix per kind: a receipt is the evidence behind a posting
        // and outlives the card it was charged to, and a statement outlives
        // the import that read it. A key that says what it is survives being
        // found by somebody with only the bucket in front of them.
        newKey = { fileName -> "card-expenses/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )

    return CardAttachmentUploader { kind ->
        var refusal: String? = null
        val chosen: PickedFile? = picker.pick(
            // Document rather than Image: a receipt is as often a PDF as a
            // photograph, and the kinds are checked below by extension, which
            // is the check that actually holds. The OS filter is a
            // convenience — drag-and-drop and "All files" both go round it.
            kind = PreviewKind.Document,
            multiple = false,
            maxBytes = kind.maxBytes,
            onRefused = { why ->
                refusal = when (why) {
                    is PickRefusal.TooLarge -> "${why.name} is over the ${kind.maxLabel} limit."
                    is PickRefusal.WrongKind -> "${why.name} is not a ${kind.noun}."
                }
            },
        ).firstOrNull()

        when {
            chosen == null && refusal != null -> ZillitResult.Failure(ZillitError.Unknown(refusal.orEmpty()))
            chosen == null -> ZillitResult.Success(null)
            !kind.accepts(chosen.name) -> ZillitResult.Failure(
                ZillitError.Unknown("${chosen.name} is not a ${kind.noun}. ${kind.acceptsLabel}."),
            )

            else -> when (
                val stored = uploader.upload(
                    chosen.name,
                    contentTypeFor(chosen.name, null),
                    chosen.bytes,
                )
            ) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success ->
                    ZillitResult.Success(CardAttachment(key = stored.data.media, fileName = chosen.name))
            }
        }
    }
}

/**
 * What each kind will take, checked by **extension rather than by type**.
 *
 * The statement formats are the reason: browsers and operating systems report
 * `.ofx` and `.qif` inconsistently — commonly as plain text or as an opaque
 * stream — so a content-type allowlist refuses perfectly good statements. The
 * web learnt this the hard way and checks the extension too.
 */
private fun PickKind.accepts(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in when (this) {
        PickKind.Receipt -> RECEIPT_EXTENSIONS
        PickKind.Statement -> STATEMENT_EXTENSIONS
    }
}

private val PickKind.noun: String
    get() = when (this) {
        PickKind.Receipt -> "receipt"
        PickKind.Statement -> "statement file"
    }

private val PickKind.acceptsLabel: String
    get() = when (this) {
        PickKind.Receipt -> str(S.desktop_receipt_types)
        PickKind.Statement -> str(S.desktop_statement_types)
    }

/**
 * A receipt is a photograph and a statement is a text export, so the caps
 * differ by an order of magnitude. Both are refused before the upload rather
 * than after it.
 */
private val PickKind.maxBytes: Long
    get() = when (this) {
        PickKind.Receipt -> RECEIPT_MAX_BYTES
        PickKind.Statement -> STATEMENT_MAX_BYTES
    }

private val PickKind.maxLabel: String
    get() = when (this) {
        PickKind.Receipt -> "10 MB"
        PickKind.Statement -> "20 MB"
    }

private val RECEIPT_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "pdf")
private val STATEMENT_EXTENSIONS = setOf("csv", "ofx", "qif")
private const val RECEIPT_MAX_BYTES = 10L * 1024 * 1024
private const val STATEMENT_MAX_BYTES = 20L * 1024 * 1024
