package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.forms.FormTemplateSource
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.feature.accounthub.domain.AgreementUploads
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PDF_CONTENT_TYPE = "application/pdf"

/**
 * Production Setup's agreement documents: the file dialog and the upload.
 *
 * Neither belongs to the account-hub service, so both are the host's — the
 * module only learns what came back. Assembled from what the app already
 * owns: the shared picker for choosing, the email module's S3 uploader for
 * writing, exactly as Documents & Signature does.
 *
 * The picked bytes are held here between the pick and the upload rather than
 * being carried through the view model. The user names and describes each
 * file before sending it, so there is a gap between the two, and a domain
 * type carrying a `ByteArray` through state would make every state comparison
 * an array comparison.
 */
internal fun AppGraph.Ready.agreementFiles(): AgreementFiles {
    val picker = AwtAttachmentPicker()
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }
    val agreementUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "agreement-document/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    // Its own prefix: the terms document is issued with every purchase order,
    // and a key that says what it is survives being read by somebody who only
    // has the bucket in front of them.
    // Its own prefix again: a budget file is read once by the parser and kept
    // as the version's provenance, which is a different life from a document
    // the production issues.
    val budgetUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "budget-import/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val termsUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "po-terms/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val pending = ConcurrentHashMap<String, ByteArray>()

    return object : AgreementFiles {

        override suspend fun pick(
            purpose: SetupUpload,
            multiple: Boolean,
            onRefused: (String) -> Unit,
        ): List<PickedAgreementFile> {
            // The picker's own document filter is broad; the PDF-only rule is
            // this surface's, so it is applied after the choice as well —
            // a dialog filter is a hint, not a guarantee.
            val chosen = picker.pick(
                kind = PreviewKind.Document,
                multiple = multiple,
                maxBytes = purpose.maxBytes,
                onRefused = { refusal ->
                    onRefused(
                        when (refusal) {
                            is PickRefusal.TooLarge -> "${refusal.name} is over the 20 MB limit."
                            is PickRefusal.WrongKind -> "${refusal.name} is not a document."
                        },
                    )
                },
            )
            return chosen.mapNotNull { file ->
                val refusal = purpose.refuse(file.name, file.bytes.size.toLong())
                if (refusal != null) {
                    onRefused("${file.name}: $refusal")
                    null
                } else {
                    val handle = UUID.randomUUID().toString()
                    pending[handle] = file.bytes
                    PickedAgreementFile(name = file.name, bytes = file.bytes.size.toLong(), handle = handle)
                }
            }
        }

        override suspend fun upload(
            file: PickedAgreementFile,
            caption: String,
            purpose: SetupUpload,
        ): ZillitResult<AgreementDocument> {
            val bytes = pending[file.handle]
                ?: return ZillitResult.Failure(
                    com.zillit.desktop.core.common.ZillitError.Unknown(
                        "${file.name} is no longer available — pick it again.",
                    ),
                )
            val uploader = when (purpose) {
                SetupUpload.Agreement -> agreementUploader
                SetupUpload.PurchaseOrderTerms -> termsUploader
                SetupUpload.BudgetImport -> budgetUploader
            }
            val contentType = contentTypeFor(file.name)
            return when (val stored = uploader.upload(file.name, contentType, bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> {
                    // Only once it is safely stored: a retry after a failure
                    // has to find the bytes still here.
                    pending.remove(file.handle)
                    ZillitResult.Success(
                        AgreementDocument(
                            description = caption,
                            name = file.name,
                            media = stored.data.media,
                            bucket = stored.data.bucket,
                            region = stored.data.region,
                            contentType = contentType,
                            contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                            fileSize = file.bytes,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The period a hub report opens on: this calendar year so far.
 *
 * Computed here rather than in the module because a year boundary needs a
 * calendar and a zone, and the hub carries neither. The zone is the machine's,
 * which is the year the person reading the report is in.
 */
internal fun defaultReportPeriod(): ReportPeriod {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val start = now.toLocalDate().withDayOfYear(1).atStartOfDay(zone)
    return ReportPeriod(
        startMillis = start.toInstant().toEpochMilli(),
        endMillis = now.toInstant().toEpochMilli(),
    )
}

/**
 * What a picked setup file is, by its extension.
 *
 * The picker does not report one, and a budget uploaded as `application/pdf`
 * is a spreadsheet the parser refuses to open. Shared with the bank statement
 * import, which faces exactly the same spreadsheets.
 */
internal fun contentTypeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> PDF_CONTENT_TYPE
        "csv" -> "text/csv"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

/**
 * Reads a module's form template.
 *
 * Handed to the module whose form it describes — Purchase Orders, Petty Cash —
 * because the template is the account hub's document while the form is theirs.
 * A failure answers with an empty template, which is what "show every field"
 * means: a form must not blank its own controls because a fetch failed.
 */
internal fun AppGraph.Ready.formTemplateFor(
    module: FormModule,
): suspend () -> ZillitResult<FormTemplate> {
    val source = FormTemplateSource(apiClient, config)
    return { source.template(module) }
}

/**
 * The production's departments, by id.
 *
 * From the admin service, which is where every other screen that needs them
 * reads them. An empty answer is a working state: the pay breakdown's scope
 * picker then shows the ids it already holds rather than dropping a scope it
 * cannot put a name to.
 */
internal suspend fun AppGraph.Ready.departmentNames(): Map<String, String> =
    when (val loaded = adminRepository.departments()) {
        is ZillitResult.Success -> loaded.data.associate { it.id to it.name }
        is ZillitResult.Failure -> emptyMap()
    }
