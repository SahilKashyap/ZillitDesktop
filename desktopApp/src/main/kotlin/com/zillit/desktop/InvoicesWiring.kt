package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.invoices.data.InvoicesRepositoryImpl
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.ui.InvoicesToolProvider
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The invoices tool's host seams: the OS file picker, the S3 upload under the
 * web's own key prefix (`/film-tools/account-hub/invoices/inbox`), the signed
 * fetch for previews, and the Downloads folder.
 */
internal fun AppGraph.Ready.invoiceFiles(): InvoiceFiles = object : InvoiceFiles {

    override suspend fun pick(): List<PickedInvoiceFile> =
        FilePicker().pick().map { PickedInvoiceFile(it.name, it.contentType, it.bytes) }

    override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> {
        val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
        val uploader = S3AttachmentUploader(
            httpClient = httpClient,
            credentials = {
                val remote = remoteConfigRepository.current()
                val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
                val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
                if (access != null && secret != null) AwsCredentials(access, secret) else null
            },
            storage = storageTarget,
            newKey = { name ->
                val stem = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9. ]"), "").replace(" ", "")
                val ext = name.substringAfterLast('.', "bin")
                "$projectId/film-tools/account-hub/invoices/inbox/actual/${stem}_${System.currentTimeMillis()}.$ext"
            },
        )
        return when (val stored = uploader.upload(file.name, file.contentType, file.bytes)) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(
                InvoiceAttachment(
                    media = stored.data.media,
                    bucket = stored.data.bucket.orEmpty(),
                    region = stored.data.region.orEmpty(),
                    name = file.name.replace(Regex("\\s"), ""),
                    // A category, not a MIME type — the service stores it as such.
                    contentType = if (file.contentType.startsWith("image/")) "image" else "document",
                    contentSubtype = file.extension,
                ),
            )
        }
    }

    override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
        noticeMedia.fetch(
            NoticeAttachment(
                media = attachment.media,
                fileName = attachment.name,
                thumbnail = attachment.media,
                bucket = attachment.bucket,
                region = attachment.region,
            ),
            preview = false,
        )

    override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(name, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
}

/**
 * Per-production reference data the invoices tool reads synchronously: the
 * department list (Enter Invoice picker, names on rows) and the project's
 * default currency. Fetched in the background when a production opens; empty
 * until then, and re-fetched on a switch.
 */
private class InvoiceReferenceData(private val graph: AppGraph.Ready, scope: CoroutineScope) {
    private val departmentsById = AtomicReference<Map<String, String>>(emptyMap())
    private val currency = AtomicReference("")
    private var loadedFor: String? = null

    init {
        scope.launch {
            graph.projectContext?.context?.collect { context ->
                val projectId = context.project?.projectId ?: return@collect
                if (projectId == loadedFor) return@collect
                loadedFor = projectId
                departmentsById.set(emptyMap())
                currency.set("")
                (graph.adminRepository.departments() as? ZillitResult.Success)?.data?.let { rows ->
                    departmentsById.set(rows.associate { it.id to it.name.localised() })
                }
                (graph.accountHubRepository.currencies() as? ZillitResult.Success)?.data?.defaultCode?.let {
                    currency.set(it)
                }
            }
        }
    }

    fun departments(): Map<String, String> = departmentsById.get()
    fun departmentName(id: String): String? = departmentsById.get()[id]
    fun currency(): String = currency.get()
}

internal fun AppGraph.Ready.buildInvoices(
    permissions: () -> ProjectPermissions,
    scope: CoroutineScope,
): InvoicesViewModel {
    val reference = InvoiceReferenceData(this, scope)
    return InvoicesViewModel(
        repository = InvoicesRepositoryImpl(
            apiClient,
            config,
            bus = socketEvents,
            currentProjectId = { projectContext?.context?.value?.project?.projectId },
        ),
        files = invoiceFiles(),
        resolveViewer = {
            val context = projectContext?.context?.value
            val profile = context?.profile
            InvoiceViewer.from(
                permissions = permissions(),
                userId = profile?.userId.orEmpty(),
                departmentId = profile?.departmentId.orEmpty(),
                // The untranslated keys ("accounts_department_label"), which is what the web substring-matches.
                departmentIdentifier = profile?.departmentName.orEmpty(),
                designationIdentifier = profile?.designationName.orEmpty(),
                isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
            )
        },
        projectCurrency = { reference.currency() },
        resolveUser = { userId -> projectContext?.context?.value?.user(userId)?.fullName },
        departmentName = { id -> reference.departmentName(id) },
        nowMillis = System::currentTimeMillis,
        departments = { reference.departments() },
    )
}

internal fun invoicesProvider(viewModel: InvoicesViewModel) = InvoicesToolProvider(viewModel = viewModel)
