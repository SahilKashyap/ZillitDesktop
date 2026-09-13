package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.ZillitRealtimeEndpoint
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.dealmemo.data.DealFileFetcher
import com.zillit.desktop.feature.dealmemo.data.DealMemoRepositoryImpl
import com.zillit.desktop.feature.dealmemo.data.DealReferenceSource
import com.zillit.desktop.feature.dealmemo.domain.DealPerson
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeCounts
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.DealDocumentStore
import com.zillit.desktop.feature.dealmemo.ui.DealFileSaver
import com.zillit.desktop.feature.dealmemo.ui.DealMemoBadgeSource
import com.zillit.desktop.feature.dealmemo.ui.DealMemoToolProvider
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealProductionData
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.DealSavedSignature
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.esignature.data.EsignRepositoryImpl
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import java.awt.FileDialog
import java.awt.Frame
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Deal Memo tool's host seams — who the viewer is, the crew directory,
 * the badge ledger, the file exports and the photos — kept out of `main.kt`.
 */
internal fun AppGraph.Ready.buildDealMemos(permissions: () -> ProjectPermissions): DealMemoViewModel =
    DealMemoViewModel(
        repository = dealMemoRoutes(),
        reference = DealReferenceSource(apiClient, config),
        viewer = { dealMemoViewer(permissions()) },
        loadPeople = { dealMemoPeople() },
        badges = dealMemoBadges(),
        files = dealMemoFiles(),
        clock = System::currentTimeMillis,
        productionData = dealMemoProduction(),
        store = dealMemoDocuments(),
    )

/**
 * The production data the deal page names things with: the project and its
 * legal company, the web app's origin for share links, the production
 * entities, the joinable units, the ISD list and the chart of accounts —
 * each owned by another module, so fetched here.
 */
private fun AppGraph.Ready.dealMemoProduction(): DealProductionData = object : DealProductionData {
    override fun project(): DealProjectInfo {
        val project = projectContext?.context?.value?.project
        return DealProjectInfo(
            projectName = project?.name.orEmpty(),
            companyName = project?.companyName.orEmpty(),
            productionType = project?.subType.orEmpty(),
        )
    }

    override fun webOrigin(): String? =
        runCatching { config.realtimeUrl(ZillitRealtimeEndpoint.Socket) }.getOrNull()?.trimEnd('/')?.ifBlank { null }

    override suspend fun companies(): List<DealCompany> =
        accountHubRepository.companies().getOrNull().orEmpty().map { DealCompany(it.id, it.name) }

    /**
     * A crew record names its unit by either id the row carries, so both are
     * listed — `unit_id` first, the one a deal's Unit picker stores.
     */
    override suspend fun units(): List<DealUnit> {
        val projectId = projectContext?.context?.value?.project?.projectId
        return unitRepository.joinUnits(projectId).getOrNull().orEmpty().flatMap { unit ->
            listOfNotNull(unit.unitId, unit.id).distinct().map { id -> DealUnit(id, unit.name) }
        }
    }

    override suspend fun countries(): List<DealCountry> =
        accountHubRepository.isdCodes().getOrNull().orEmpty().map { DealCountry(it.code, it.dialCode, it.name) }

    override suspend fun chartOfAccounts(): ZillitResult<List<DealCoaAccount>> =
        accountHubRepository.accounts().map { accounts ->
            accounts.map { account ->
                DealCoaAccount(
                    code = account.code,
                    name = account.name,
                    lineType = account.lineType.wire,
                    posting = account.isPosting,
                )
            }
        }
}

/**
 * The deal page's file work on the app's own machinery: presigned reads
 * through the notice reader, writes through the email module's S3 uploader,
 * and the signer's saved signatures from the E-Signature library — the same
 * library the web's picker shows.
 *
 * Uploaded files go under `{projectId}/film-tools/deal-memo/actual/…`, the
 * web's `<projectId><route>/actual/<name><time><random>_.<ext>` shape with the
 * route fixed, so a crew member's passport and a signed copy sit where the
 * web's would.
 */
private fun AppGraph.Ready.dealMemoDocuments(): DealDocumentStore {
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName ->
            val project = projectContext?.context?.value?.project?.projectId.orEmpty().ifBlank { "unknown-project" }
            "$project/film-tools/deal-memo/actual/${dealMemoKeyName(fileName)}"
        },
    )
    val signatures = EsignRepositoryImpl(apiClient = apiClient, config = config, today = { "" })
    val signatureTransfer = esignTransfer()

    return object : DealDocumentStore {
        override suspend fun fetch(attachment: DealAttachment): ZillitResult<ByteArray> {
            val media = attachment.media
                ?: return ZillitResult.Failure(ZillitError.Validation("This file has no stored copy."))
            return noticeMedia.fetch(
                NoticeAttachment(
                    media = media,
                    fileName = attachment.name ?: media.substringAfterLast('/'),
                    bucket = attachment.bucket?.takeIf { it.isNotBlank() },
                    region = attachment.region?.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )
        }

        override suspend fun upload(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
        ): ZillitResult<DealAttachment> =
            uploader.upload(fileName, contentType, bytes).map { stored ->
                DealAttachment(
                    buildJsonObject {
                        put("media", stored.media)
                        put("bucket", stored.bucket)
                        put("region", stored.region)
                        put("name", fileName)
                    },
                )
            }

        /** Signatures only — the deal page has no initials to place. */
        override suspend fun savedSignatures(): ZillitResult<List<DealSavedSignature>> =
            signatures.savedSignatures().map { saved ->
                saved.filter { it.isSignature }.map { signature ->
                    DealSavedSignature(
                        id = signature.id,
                        image = signature.image?.let { image ->
                            DealAttachment(
                                buildJsonObject {
                                    put("media", image.media)
                                    put("bucket", image.bucket)
                                    put("region", image.region)
                                    put("name", image.name)
                                    put("content_type", image.contentType)
                                    put("content_subtype", image.contentSubtype)
                                },
                            )
                        },
                    )
                }
            }

        override suspend fun saveSignature(png: ByteArray): ZillitResult<Unit> =
            when (val stored = signatureTransfer.store("${UUID.randomUUID()}.png", "image/png", png)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> signatures.saveSignature(
                    isSignature = true,
                    image = stored.data.copy(contentType = "image", contentSubtype = "png"),
                )
            }

        override suspend fun deleteSignature(id: String): ZillitResult<Unit> = signatures.deleteSavedSignature(id)

        override suspend fun pickFiles(extensions: List<String>, multiple: Boolean): List<PickedDealFile> =
            pickDealFiles(extensions, multiple)
    }
}

/** `<name without spaces><time><random>_.<ext>`, then anything outside `[A-Za-z0-9.]` dropped. */
private fun dealMemoKeyName(fileName: String): String {
    val compact = fileName.replace(" ", "")
    val ext = compact.substringAfterLast('.', "")
    val stem = if (ext.isEmpty()) compact else compact.substringBeforeLast('.')
    val random = (1..KEY_RANDOM_CHARS).map { KEY_ALPHABET.random() }.joinToString("")
    val name = "$stem${System.currentTimeMillis()}$random" + if (ext.isEmpty()) "" else "_.$ext"
    return name.filter { it.isLetterOrDigit() && it.code < ASCII_LIMIT || it == '.' || it == '_' }
}

/** The OS chooser, limited to [extensions]; nothing when cancelled or unreadable. */
private suspend fun pickDealFiles(extensions: List<String>, multiple: Boolean): List<PickedDealFile> =
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Choose a file", FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        dialog.isVisible = true
        dialog.files.orEmpty().filter { it.isFile }.mapNotNull { file ->
            runCatching { PickedDealFile(file.name, file.readBytes(), mimeOf(file.name)) }.getOrNull()
        }
    }

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

private const val KEY_RANDOM_CHARS = 6
private const val KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val ASCII_LIMIT = 128

/**
 * The deal-memo routes, with the host's raw POST for the register exports —
 * built here rather than taken from the graph, because the exports need the
 * ready graph's HTTP client and the graph's copy is made before it exists.
 */
private fun AppGraph.Ready.dealMemoRoutes(): DealMemoRepositoryImpl = DealMemoRepositoryImpl(
    apiClient = apiClient,
    config = config,
    bus = socketEvents,
    currentProjectId = { projectContext?.context?.value?.project?.projectId },
    files = DealFileFetcher { url, body -> postForBytes(url, body ?: JsonObject(emptyMap())) },
)

internal fun AppGraph.Ready.dealMemoProvider(viewModel: DealMemoViewModel): DealMemoToolProvider =
    DealMemoToolProvider(viewModel, loadAvatar = { userId -> crewFaceLoader(this)(userId) })

/**
 * The web's `useDealMemoRights` inputs: the tool grid's posting and view flags
 * (admin override folded in), the profile department, and the member's
 * standing — only an explicit `pending` restricts.
 */
private fun AppGraph.Ready.dealMemoViewer(permissions: ProjectPermissions): DealMemoViewer {
    val context = projectContext?.context?.value
    val userId = context?.profile?.userId.orEmpty()
    val me = context?.user(userId)
    return DealMemoViewer(
        userId = userId,
        departmentIdentifier = me?.department,
        hasPostingAccess = permissions.canPost(DEAL_MEMO_TOOL),
        hasViewAccess = permissions.canView(DEAL_MEMO_TOOL),
        memberStatus = me?.status,
        rightsLoaded = permissions !== ProjectPermissions.Empty,
    )
}

/** Everyone the production lists, for names, departments and the Notices group of crew who have left. */
private fun AppGraph.Ready.dealMemoPeople(): Map<String, DealPerson> =
    projectContext?.context?.value?.users.orEmpty().associate { user ->
        user.userId to DealPerson(
            userId = user.userId,
            fullName = user.fullName,
            departmentName = user.department,
            designationName = user.designation,
            status = user.status,
        )
    }

/**
 * The tool's badges from the ledger: tab counts per unit, per-deal counts by
 * `level_3`. Reads go to the server as `notification:level:read` with the
 * web's shape — `tool`, the unit as `segment` and `unit`, an explicit null
 * `level_1`, and the deal as `level_3` — and clear the local ledger at once.
 */
private fun AppGraph.Ready.dealMemoBadges(): DealMemoBadgeSource = object : DealMemoBadgeSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val counts: Flow<DealBadgeCounts> = badgeStore.counts
        .map {
            val tabs = badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = DEAL_MEMO_BADGE_TOOL))
            DealBadgeCounts(
                tabs = DealBadgeUnit.entries.associateWith { tabs[it.wire] ?: 0 },
                perDeal = DealBadgeUnit.entries.associateWith { unit ->
                    badgeStore.split(
                        BadgeDrilldownQuery(groupBy = "level_3", tool = DEAL_MEMO_BADGE_TOOL, unit = unit.wire),
                    )
                },
            )
        }
        .distinctUntilChanged()

    override fun readTab(unit: DealBadgeUnit) = read(unit, dealId = null)

    override fun readDeal(unit: DealBadgeUnit, dealId: String) = read(unit, dealId)

    private fun read(unit: DealBadgeUnit, dealId: String?) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationLevelRead,
                    NotificationReadDto(
                        projectId = projectId,
                        tool = DEAL_MEMO_BADGE_TOOL,
                        module = DEAL_MEMO_BADGE_TOOL,
                        segment = unit.wire,
                        unit = unit.wire,
                        level3 = dealId,
                        timestamp = now,
                        readTime = now,
                    ),
                    NotificationReadDto.serializer(),
                )
            }
            badgeStore.markRead(LedgerRead.Levels(tool = DEAL_MEMO_BADGE_TOOL, unit = unit.wire, level3 = dealId))
        }
    }
}

/** An export lands in Downloads and opens, as every other register export does. */
private fun dealMemoFiles(): DealFileSaver {
    val store = DownloadsAttachmentStore()
    return DealFileSaver { fileName, bytes ->
        when (val saved = store.save(fileName, bytes)) {
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
            is ZillitResult.Failure -> saved
        }
    }
}

/** The web's `TOOLS_NAME.deal_memo_tool` (`useDealMemoRights.js:36`). */
private const val DEAL_MEMO_TOOL = "deal_memo_tool"

/** The badge ledger's name for the tool — distinct from its rights identifier. */
private const val DEAL_MEMO_BADGE_TOOL = "deal_memo_label"
