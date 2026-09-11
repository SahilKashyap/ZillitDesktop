package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitRealtimeEndpoint
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.feature.bankrec.data.BankRecRepositoryImpl
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.StatementUploader
import com.zillit.desktop.feature.bankrec.ui.BankRecToolProvider
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import java.util.UUID

/** Statements are spreadsheets and CSVs, and none of them is large. */
private const val STATEMENT_MAX_BYTES = 20L * 1024 * 1024

/**
 * Bank Reconciliation.
 *
 * The statement file is the host's job on both halves: choosing it, and
 * putting it in storage. The service takes only the pointer — it does not
 * accept an upload of its own — so nothing here streams bytes to it.
 */
internal fun AppGraph.Ready.buildBankRec() = BankRecViewModel(
    repository = BankRecRepositoryImpl(apiClient, config),
    events = socketEvents,
    uploader = statementUploader(),
    portalUrl = ::guarantorPortalUrl,
)

internal fun bankRecProvider(viewModel: BankRecViewModel) = BankRecToolProvider(viewModel)

/**
 * Picks a statement and stores it, answering with the attachment record.
 *
 * A cancelled picker is a success carrying null, not a failure: the person
 * changed their mind, which is not something to apologise for.
 */
private fun AppGraph.Ready.statementUploader(): StatementUploader {
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
        // Its own prefix: a statement is the evidence behind a reconciliation
        // and outlives the import that read it, so a key that says what it is
        // survives being found by somebody with only the bucket in front of
        // them.
        newKey = { fileName -> "bank-statement/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )

    return StatementUploader {
        var refusal: String? = null
        val chosen = picker.pick(
            kind = PreviewKind.Document,
            multiple = false,
            maxBytes = STATEMENT_MAX_BYTES,
            onRefused = { why ->
                refusal = when (why) {
                    is PickRefusal.TooLarge -> "${why.name} is over the 20 MB limit."
                    is PickRefusal.WrongKind -> "${why.name} is not a statement file."
                }
            },
        ).firstOrNull()

        when {
            chosen == null && refusal != null ->
                ZillitResult.Failure(ZillitError.Unknown(refusal.orEmpty()))

            chosen == null -> ZillitResult.Success(null)

            else -> {
                val contentType = contentTypeFor(chosen.name)
                when (val stored = uploader.upload(chosen.name, contentType, chosen.bytes)) {
                    is ZillitResult.Failure -> stored
                    is ZillitResult.Success -> ZillitResult.Success(
                        StatementUpload(
                            media = stored.data.media,
                            bucket = stored.data.bucket,
                            region = stored.data.region,
                            fileName = chosen.name,
                            contentType = contentType,
                            contentSubtype = chosen.name.substringAfterLast('.', "").lowercase(),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Where a shared link takes its recipient.
 *
 * The web application's own origin, because the portal is a page there rather
 * than a screen in this application: a recipient has no Zillit desktop and
 * would not be able to open one.
 */
private fun AppGraph.Ready.guarantorPortalUrl(token: String): String {
    val origin = runCatching { config.realtimeUrl(ZillitRealtimeEndpoint.Socket) }
        .getOrNull()
        ?.trimEnd('/')
        .orEmpty()
    return "$origin/guarantor-portal/$token"
}
