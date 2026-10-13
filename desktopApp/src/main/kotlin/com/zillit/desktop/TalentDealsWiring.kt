package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.talentdeals.data.TalentDealsRepositoryImpl
import com.zillit.desktop.feature.talentdeals.domain.PickedTalentFile
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentCountry
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsToolProvider
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsViewModel
import com.zillit.desktop.feature.talentdeals.ui.TalentFileHost
import com.zillit.desktop.feature.talentdeals.ui.TalentPdfPage
import com.zillit.desktop.feature.talentdeals.ui.TalentReference
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Talent Deals tool's host seams — who the viewer is, the file chooser and
 * the production's file store, and the territory and currency lists — kept out
 * of `main.kt`.
 *
 * The contract routes sit on the Deal Memo host; Exhibit G's signing rows on
 * the AD dashboard's.
 */
internal fun AppGraph.Ready.buildTalentDeals(permissions: () -> ProjectPermissions): TalentDealsViewModel =
    TalentDealsViewModel(
        repository = TalentDealsRepositoryImpl(
            apiClient = apiClient,
            config = config,
            bus = socketEvents,
            currentProjectId = { projectContext?.context?.value?.project?.projectId },
        ),
        resolveViewer = { talentDealsViewer(permissions()) },
        files = talentFileHost(),
        reference = talentReference(),
    )

internal fun talentDealsProvider(viewModel: TalentDealsViewModel): TalentDealsToolProvider =
    TalentDealsToolProvider(viewModel)

/**
 * The web's `useTalentDealsRights` inputs: the `talent_deals_tool` row of the
 * tool grid (admin override folded in, as Deal Memo's), the profile
 * department, and the member's standing — only an explicit `pending` restricts.
 */
private fun AppGraph.Ready.talentDealsViewer(permissions: ProjectPermissions): TalentDealsViewer {
    val context = projectContext?.context?.value
    val userId = context?.profile?.userId.orEmpty()
    val me = context?.user(userId)
    return TalentDealsViewer(
        userId = userId,
        departmentIdentifier = me?.department,
        hasPostingAccess = permissions.canPost(TALENT_DEALS_TOOL),
        hasViewAccess = permissions.canView(TALENT_DEALS_TOOL),
        hasDownloadAccess = permissions.canDownload(TALENT_DEALS_TOOL),
        memberStatus = me?.status,
        rightsLoaded = permissions !== ProjectPermissions.Empty,
    )
}

/**
 * Territories after the wizard's 41 come from the Add Company country list; currencies
 * from Production Setup and the core catalogue.
 */
private fun AppGraph.Ready.talentReference(): TalentReference = object : TalentReference {
    override suspend fun countries(): List<TalentCountry> =
        accountHubRepository.isdCodes().getOrNull().orEmpty().map { TalentCountry(code = it.code, name = it.name) }

    override suspend fun currencies(): TalentCurrencies {
        val settings = accountHubRepository.currencies().getOrNull()
        val catalogue = accountHubRepository.currencyCatalogue().getOrNull().orEmpty()
        return TalentCurrencies(
            selected = settings?.currencies?.map { it.code }.orEmpty(),
            defaultCode = settings?.defaultCode,
            catalogue = catalogue.map {
                TalentCurrency(code = it.code, name = it.name, symbol = it.symbol, country = it.country)
            },
        )
    }
}

/**
 * The contract's file work on the app's own machinery: the OS chooser, writes
 * through the email module's S3 uploader, signed reads through the notice
 * reader, PDF pages through PDFBox, and Downloads.
 *
 * Uploaded files go under `{projectId}/film-tools/talent-deals/actual/…`, the
 * web's `<projectId><route>/actual/<name><time><random>_.<ext>` shape with the
 * route fixed, so a contract uploaded here sits where the web's would.
 */
private fun AppGraph.Ready.talentFileHost(): TalentFileHost {
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            val remote = remoteConfigRepository.current()
            val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
            val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
            if (access != null && secret != null) AwsCredentials(access, secret) else null
        },
        storage = storageTarget,
        newKey = { fileName ->
            val project = projectContext?.context?.value?.project?.projectId.orEmpty().ifBlank { "unknown-project" }
            "$project/film-tools/talent-deals/actual/${talentKeyName(fileName)}"
        },
    )
    return object : TalentFileHost {
        override suspend fun pick(extensions: List<String>): PickedTalentFile? = pickTalentFile(extensions)

        override suspend fun upload(file: PickedTalentFile): ZillitResult<TalentAttachment> =
            uploader.upload(file.name, file.mimeType, file.bytes).map { stored ->
                TalentAttachment(
                    media = stored.media,
                    bucket = stored.bucket.orEmpty(),
                    region = stored.region.orEmpty(),
                    name = file.name,
                    contentType = TalentAttachment.typeOf(file.name),
                    contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                )
            }

        override suspend fun fetch(attachment: TalentAttachment): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(
                    media = attachment.media,
                    fileName = attachment.displayName,
                    bucket = attachment.bucket.takeIf { it.isNotBlank() },
                    region = attachment.region.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )

        override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<TalentPdfPage>> =
            talentPdfPages(pdf, targetWidthPx)

        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }
    }
}

private val talentPdfWork = PdfBoxWork()

private fun talentPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<TalentPdfPage>> =
    when (val pages = talentPdfWork.renderPages(pdf, targetWidthPx)) {
        is ZillitResult.Failure -> pages
        is ZillitResult.Success -> ZillitResult.Success(
            pages.data.map { TalentPdfPage(it.page, it.imageBytes, it.widthPx, it.heightPx) },
        )
    }

/** The OS chooser, limited to [extensions]; nothing when cancelled, unreadable or of another type. */
private suspend fun pickTalentFile(extensions: List<String>): PickedTalentFile? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, str(S.desktop_choose_a_file), FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
    dialog.isVisible = true
    dialog.files.orEmpty()
        .firstOrNull { file -> file.isFile && extensions.any { file.name.endsWith(".$it", ignoreCase = true) } }
        ?.let { file ->
            runCatching { PickedTalentFile(file.name, file.readBytes(), talentMimeOf(file.name)) }.getOrNull()
        }
}

private fun talentMimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

/** `<name without spaces><time><random>_.<ext>`, then anything outside `[A-Za-z0-9._]` dropped. */
private fun talentKeyName(fileName: String): String {
    val compact = fileName.replace(" ", "")
    val ext = compact.substringAfterLast('.', "")
    val stem = if (ext.isEmpty()) compact else compact.substringBeforeLast('.')
    val random = (1..TALENT_KEY_RANDOM_CHARS).map { TALENT_KEY_ALPHABET.random() }.joinToString("")
    val name = "$stem${System.currentTimeMillis()}$random" + if (ext.isEmpty()) "" else "_.$ext"
    return name.filter { it.isLetterOrDigit() && it.code < TALENT_ASCII_LIMIT || it == '.' || it == '_' }
}

/** The web's `TOOLS_NAME.talent_deals_tool` (`useDealMemoRights.js` `TALENT_DEALS_TOOL_IDENTIFIER`). */
private const val TALENT_DEALS_TOOL = "talent_deals_tool"
private const val TALENT_KEY_RANDOM_CHARS = 6
private const val TALENT_KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val TALENT_ASCII_LIMIT = 128
