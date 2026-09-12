package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.forms.FormTemplateSource
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementFiles
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.purchaseorder.domain.PoTermsFiles
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PoPickedFile
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoCompany
import com.zillit.desktop.feature.purchaseorder.domain.PoProjectSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoTaxType
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.accounthub.domain.AgreementUploads
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubBadgeCounts
import com.zillit.desktop.feature.accounthub.domain.HubBadges
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubDesignation
import com.zillit.desktop.feature.accounthub.domain.HubDocumentOpener
import com.zillit.desktop.feature.accounthub.domain.HubExportReport
import com.zillit.desktop.feature.accounthub.domain.HubExporter
import com.zillit.desktop.feature.accounthub.domain.HubFiles
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.core.network.S3Presigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
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
    // And again: an order's own paperwork is evidence about one transaction,
    // not a document the production issues, so it does not belong under the
    // terms prefix.
    val poAttachmentUploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName -> "purchase-order/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
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
                            is PickRefusal.TooLarge -> "${refusal.name}: ${purpose.tooLarge}"
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
                SetupUpload.PurchaseOrderAttachment -> poAttachmentUploader
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

// -- the hub's people ---------------------------------------------------------

/**
 * The crew, for every hub picker — approvers, payroll groups, closing-package
 * recipients.
 *
 * Two sources joined: the project context carries names, departments and
 * designations for everyone the production has ever listed; the admin crew
 * list carries each person's standing. The web offers only accepted members,
 * and a picker that offered someone who left the production would be assigning
 * approvals to nobody. A failed crew read leaves everyone accepted rather than
 * nobody — an empty picker is a broken screen, a slightly generous one is not.
 */
internal suspend fun AppGraph.Ready.hubUsers(): List<HubUser> {
    val context = projectContext?.context?.value
    val standing = when (val crew = adminRepository.crew()) {
        is ZillitResult.Success -> crew.data.associate { it.userId to it.status }
        is ZillitResult.Failure -> emptyMap()
    }
    return context?.users.orEmpty().map { user ->
        HubUser(
            id = user.userId,
            name = user.fullName,
            email = user.email.orEmpty(),
            // The context's department is the identifier-ish string the web
            // matches "accounts" against; it doubles as the display name.
            department = user.department.orEmpty(),
            departmentIdentifier = user.department.orEmpty(),
            designation = user.designation.orEmpty(),
            isAdmin = user.isAdmin,
            status = (standing[user.userId] ?: CrewStatus.Accepted).wire.ifBlank { HubUser.ACCEPTED },
            avatarUrl = user.avatarUrl,
        )
    }
}

/** Departments with their designations, for the payroll groups and the approver scope picker. */
internal suspend fun AppGraph.Ready.hubDepartments(): List<HubDepartment> =
    when (val loaded = adminRepository.departments()) {
        is ZillitResult.Success -> loaded.data.map { department ->
            HubDepartment(
                id = department.id,
                name = department.name,
                identifier = department.name.trim().lowercase().replace(Regex("\\s+"), "_").let { "department_$it" },
                designations = department.jobTitles.map { HubDesignation(id = it.id, name = it.name) },
            )
        }
        is ZillitResult.Failure -> emptyList()
    }

// -- badges ---------------------------------------------------------------------

/**
 * The sidebar's counts, from the notification ledger.
 *
 * Accountants read the hub's own ledger by unit — the web's
 * `groupBy: "unit", tool: "account_hub_label"`; department users read each
 * spend tool's own total. Both are re-split whenever the ledger changes.
 */
internal fun AppGraph.Ready.hubBadges(scope: CoroutineScope): StateFlow<HubBadgeCounts> =
    badgeStore.counts
        .map { counts ->
            HubBadgeCounts(
                hubUnits = badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = HubBadges.HUB_TOOL)),
                tools = HUB_TOOL_WIRES.associateWith { counts[it] },
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, HubBadgeCounts.Empty)

private val HUB_TOOL_WIRES = listOf(
    HubBadges.HUB_TOOL,
    HubBadges.PO_UNIT,
    HubBadges.INVOICES_TOOL,
    HubBadges.INVOICES_UNIT,
    HubBadges.CARD_UNIT,
    HubBadges.CASH_UNIT,
    HubBadges.BANK_RECON_UNIT,
)

// -- exports and documents ------------------------------------------------------------

/**
 * The server-rendered report exports — `POST …/cost-reports/{report}/export/{format}`.
 *
 * Bytes, not an envelope, so it goes through the host's raw POST rather than
 * the JSON client; the filters travel in the body so the file matches the
 * screen it was exported from.
 */
internal fun AppGraph.Ready.hubExporter(): HubExporter = HubExporter {
        report: HubExportReport,
        format: ExportFormat,
        body: JsonObject,
    ->
    postForBytes("${config.apiV2(ZillitService.CostReport)}cost-reports/${report.path}/export/${format.wire}", body)
}

/** Where an export lands — the Downloads folder, then opened in the OS. */
internal fun hubFiles(): HubFiles {
    val store = DownloadsAttachmentStore()
    return HubFiles { fileName: String, bytes: ByteArray ->
        when (val saved = store.save(fileName, bytes)) {
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
            is ZillitResult.Failure -> saved
        }
    }
}

/**
 * Opens a stored budget file or agreement in the browser.
 *
 * A presigned GET, because the documents live in a private bucket the app
 * holds keys for and the OS browser does not — the same route Document
 * Distribution takes.
 */
internal fun AppGraph.Ready.hubDocumentOpener(): HubDocumentOpener {
    val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })
    return HubDocumentOpener { document ->
        val url = presigner.presignedGet(bucket = document.bucket, region = document.region, key = document.media)
        if (url == null) {
            // Named for what is missing: no bucket, no key, or no AWS keys in
            // the remote config — the three ways a document can be unreachable.
            ZillitResult.Failure(
                ZillitError.Unknown(
                    "${document.name.ifBlank { "This document" }} cannot be opened — " +
                        "its storage location or the app's storage keys are missing.",
                ),
            )
        } else {
            openInBrowser(url)
            ZillitResult.Success(Unit)
        }
    }
}

// -- the setup tour ---------------------------------------------------------------------

/** The tours dismissed on this device, one key per production and scope — the web's localStorage flag. */
private val TourSeen = PreferenceKey.StringKey("account_hub.tour_seen", "", PreferenceScope.User)

internal suspend fun AppGraph.Ready.tourSeen(key: String): Boolean =
    preferences.get(TourSeen).split(',').any { it == key }

internal suspend fun AppGraph.Ready.markTourSeen(key: String) {
    val seen = preferences.get(TourSeen).split(',').filter { it.isNotBlank() }.toMutableSet()
    if (seen.add(key)) preferences.set(TourSeen, seen.joinToString(","))
}

// -- the purchase order tool's Settings tab ------------------------------------

/**
 * Who a rule can assign to, and which departments it can name — the web's
 * `useAccountHubUsers().AVAILABLE_USERS` (accepted members of the accounts
 * department) and `useDepartments()`, off the same crew list the hub reads.
 */
internal fun AppGraph.Ready.poSettingsPeople(): PoSettingsPeople = object : PoSettingsPeople {
    override suspend fun team(): List<PoTeamMember> = hubUsers()
        .filter { it.isAccepted && it.departmentIdentifier.contains(ACCOUNTS, ignoreCase = true) }
        .map { PoTeamMember(id = it.id, name = it.name, role = it.roleLabel) }

    override suspend fun departments(): List<PoDepartment> =
        hubDepartments().map { PoDepartment(id = it.id, name = it.name) }

    /**
     * Everyone accepted on the production, not just the accounts team.
     *
     * An order's raiser is usually in another department, and a detail panel
     * that prints their ObjectId instead of their name is one nobody can read.
     */
    override suspend fun everyone(): List<PoTeamMember> = hubUsers()
        .filter { it.isAccepted }
        .map { PoTeamMember(id = it.id, name = it.name, role = it.roleLabel) }
}

/** The terms document goes through the same picker and store the hub's Production Setup uses. */
internal fun AppGraph.Ready.poTermsFiles(): PoTermsFiles = poFiles(SetupUpload.PurchaseOrderTerms)

/**
 * An order's own paperwork — a quote, a signed copy, a delivery note.
 *
 * The same seam and the same store as the terms document, with the wider accept
 * rule: see [SetupUpload.PurchaseOrderAttachment].
 */
internal fun AppGraph.Ready.poAttachmentFiles(): PoTermsFiles = poFiles(SetupUpload.PurchaseOrderAttachment)

private fun AppGraph.Ready.poFiles(purpose: SetupUpload): PoTermsFiles {
    val files = agreementFiles()
    return object : PoTermsFiles {
        override suspend fun pick(onRefused: (String) -> Unit): PoPickedFile? =
            files.pick(purpose, multiple = false, onRefused = onRefused)
                .firstOrNull()
                ?.let { PoPickedFile(name = it.name, bytes = it.bytes, handle = it.handle) }

        override suspend fun upload(file: PoPickedFile): ZillitResult<PoAttachment> = files.upload(
            PickedAgreementFile(name = file.name, bytes = file.bytes, handle = file.handle),
            caption = "",
            purpose = purpose,
        ).map { stored ->
            PoAttachment(
                media = stored.media,
                name = stored.name,
                contentType = stored.contentType,
                bucket = stored.bucket,
                region = stored.region,
            )
        }
    }
}

private const val ACCOUNTS = "accounts"

/**
 * Companies, tax types, departments and currencies for the purchase order
 * form — the web's `ProjectSettingsProvider`, which fetches them once on PO
 * entry and shares them between both role views.
 *
 * They belong to the account hub's service rather than the purchase order one,
 * which is why the PO module takes them as a seam instead of fetching them.
 * Failures answer an empty list: the form's selectors then offer nothing, which
 * is honest, where an error page over a working form would not be.
 */
internal fun AppGraph.Ready.poProjectSettings(): PoProjectSettings = object : PoProjectSettings {

    override suspend fun companies(): List<PoCompany> =
        accountHubRepository.companies().getOrNull().orEmpty().map { PoCompany(id = it.id, name = it.name) }

    override suspend fun taxTypes(): List<PoTaxType> =
        accountHubRepository.taxTypes().getOrNull().orEmpty().map { tax ->
            PoTaxType(
                // The identifier is what an order's `tax_type` stores; the
                // label is what a reader sees. Falling back to the label as the
                // id would save a line that could not be read back.
                id = tax.identifier.ifBlank { tax.label },
                name = tax.label.ifBlank { tax.identifier },
                rate = tax.rate,
                recoverable = tax.isRecoverable,
            )
        }

    override suspend fun departments(): List<PoDepartment> =
        hubDepartments().map { PoDepartment(id = it.id, name = it.name) }

    /**
     * The production's currencies, its default first.
     *
     * The order matters: the form pre-fills from the head of this list, and a
     * production trading in three currencies should default to the one it keeps
     * its books in.
     */
    override suspend fun currencies(): List<String> {
        val settings = accountHubRepository.currencies().getOrNull() ?: return emptyList()
        val codes = settings.currencies.map { it.code }.filter { it.isNotBlank() }
        val default = settings.defaultCode?.takeIf { it.isNotBlank() }
        return if (default != null) listOf(default) + codes.filterNot { it == default } else codes
    }
}
