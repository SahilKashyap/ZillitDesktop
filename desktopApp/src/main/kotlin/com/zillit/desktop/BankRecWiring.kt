package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitRealtimeEndpoint
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.bankrec.data.BankRecBinaryPost
import com.zillit.desktop.feature.bankrec.data.BankRecRepositoryImpl
import com.zillit.desktop.feature.bankrec.domain.BankRecBadges
import com.zillit.desktop.feature.bankrec.domain.BankRecDirectory
import com.zillit.desktop.feature.bankrec.domain.BankRecFiles
import com.zillit.desktop.feature.bankrec.domain.BankRecLookups
import com.zillit.desktop.feature.bankrec.domain.BankRecPerson
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.NominalCode
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.StatementFiles
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.TaxOption
import com.zillit.desktop.feature.bankrec.ui.BankRecToolProvider
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Bank Reconciliation.
 *
 * The host fills the seams the module cannot reach on its own: the statement
 * file (choosing it, and putting it in storage — the service takes only the
 * pointer), the exported PDFs and CSVs, the crew's names, and the production
 * settings that belong to the account hub.
 */
internal fun AppGraph.Ready.buildBankRec() = BankRecViewModel(
    repository = BankRecRepositoryImpl(apiClient, config, BankRecBinaryPost { url, body -> postForBytes(url, body) }),
    events = socketEvents,
    statements = statementFiles(),
    portalUrl = ::guarantorPortalUrl,
    files = bankRecFiles(),
    directory = bankRecDirectory(),
    lookups = bankRecLookups(),
    badges = bankRecBadges(),
)

internal fun bankRecProvider(viewModel: BankRecViewModel) = BankRecToolProvider(viewModel)

/**
 * Picks a statement, then stores it when the import starts.
 *
 * A cancelled picker is a success carrying null, not a failure: the person
 * changed their mind, which is not something to apologise for.
 */
private fun AppGraph.Ready.statementFiles(): StatementFiles {
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
        // and outlives the import that read it.
        newKey = { fileName -> "bank-statement/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )

    return object : StatementFiles {
        override suspend fun pick(): ZillitResult<PickedStatement?> {
            var refusal: String? = null
            val chosen = picker.pick(
                kind = PreviewKind.Document,
                multiple = false,
                maxBytes = StatementFiles.MAX_BYTES,
                onRefused = { why ->
                    refusal = when (why) {
                        is PickRefusal.TooLarge -> "${why.name} is over the 20 MB limit."
                        is PickRefusal.WrongKind -> "${why.name} is not a statement file."
                    }
                },
            ).firstOrNull()
            return when {
                chosen != null -> ZillitResult.Success(PickedStatement(chosen.name, chosen.bytes))
                refusal != null -> ZillitResult.Failure(ZillitError.Validation(refusal.orEmpty()))
                else -> ZillitResult.Success(null)
            }
        }

        override suspend fun upload(file: PickedStatement): ZillitResult<StatementUpload> {
            val contentType = contentTypeFor(file.name)
            return when (val stored = uploader.upload(file.name, contentType, file.bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> ZillitResult.Success(
                    StatementUpload(
                        media = stored.data.media,
                        bucket = stored.data.bucket,
                        region = stored.data.region,
                        fileName = file.name,
                        // The account hub's attachment shape: a statement is a
                        // "document", and its subtype is the extension.
                        contentType = "document",
                        contentSubtype = file.extension,
                    ),
                )
            }
        }
    }
}

/** Exports land in Downloads and open, as every other export in this application does. */
private fun bankRecFiles() = BankRecFiles { fileName, bytes ->
    when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
        is ZillitResult.Failure -> saved
        is ZillitResult.Success -> {
            openSavedFile(saved.data)
            ZillitResult.Success(Unit)
        }
    }
}

/** Names for the ids the service stores — a signer, an audit line's actor, the preparer. */
private fun AppGraph.Ready.bankRecDirectory(): BankRecDirectory = object : BankRecDirectory {
    override fun person(userId: String): BankRecPerson? =
        projectContext?.context?.value?.user(userId)?.let { user ->
            BankRecPerson(name = user.fullName, designation = user.designation.orEmpty())
                .takeIf { it.name.isNotBlank() }
        }

    override fun me(): BankRecPerson? {
        val context = projectContext?.context?.value ?: return null
        val profile = context.profile ?: return null
        val crew = context.user(profile.userId)
        return BankRecPerson(
            name = profile.fullName.ifBlank { crew?.fullName.orEmpty() },
            designation = crew?.designation.orEmpty(),
        ).takeIf { it.name.isNotBlank() }
    }
}

/**
 * The account hub's production settings, as the quick forms read them.
 *
 * Each answers empty on failure — see [BankRecLookups]: a selector with nothing
 * in it is honest, an error over a form that otherwise works is not.
 */
private fun AppGraph.Ready.bankRecLookups(): BankRecLookups = object : BankRecLookups {
    override suspend fun taxTypes(): List<TaxOption> =
        accountHubRepository.taxTypes().getOrNull().orEmpty()
            .filter { it.identifier.isNotBlank() }
            .map { tax ->
                TaxOption(
                    identifier = tax.identifier,
                    label = tax.label.ifBlank { tax.identifier },
                    // Stored as a percentage, `20` or `20%`.
                    ratePercent = tax.value.trim().removeSuffix("%").toDoubleOrNull(),
                    country = tax.countryCode.orEmpty(),
                )
            }

    override suspend fun nominalCodes(): List<NominalCode> =
        accountHubRepository.accounts(activeOnly = true).getOrNull().orEmpty()
            .filter { account ->
                account.isPosting && account.code.isNotBlank() &&
                    (account.lineType == CoaLineType.Category || account.lineType == CoaLineType.SubCategory)
            }
            .map { NominalCode(code = it.code, name = it.name) }

    override suspend fun lockedThrough(): String? =
        accountHubRepository.periodLock().getOrNull()?.lockedThrough?.takeIf { it.isNotBlank() }

    override suspend fun departments(): Map<String, String> = departmentNames()

    override fun company(): CompanyDetails {
        val project = projectContext?.context?.value?.project
        return CompanyDetails(
            projectName = project?.name.orEmpty(),
            companyName = project?.companyName.orEmpty(),
        )
    }
}

/**
 * The tab chips — every row the ledger files under the account hub's
 * `bank_recon_label` unit, split by `level_1`, which is the tab.
 *
 * A tab's read goes to the server the way the web's `emitBankTabRead` sends it
 * — `tool`, the unit as `segment`, and the tab as `level_1` — and clears the
 * local ledger at once, so the chip does not wait for the echo.
 */
private fun AppGraph.Ready.bankRecBadges(): BankRecBadges = object : BankRecBadges {
    override val counts: Flow<Map<String, Int>> = badgeStore.counts
        .map { badgeStore.split(BadgeDrilldownQuery(groupBy = "level_1", tool = HUB_TOOL, unit = BANK_RECON_UNIT)) }
        .distinctUntilChanged()

    override suspend fun markRead(level1: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        val now = System.currentTimeMillis()
        socketEvents.emit(
            ZillitSocketEvents.Badges.NotificationLevelRead,
            NotificationReadDto(
                projectId = projectId,
                tool = HUB_TOOL,
                segment = BANK_RECON_UNIT,
                level1 = level1,
                timestamp = now,
                readTime = now,
            ),
            NotificationReadDto.serializer(),
        )
        badgeStore.markRead(LedgerRead.Levels(tool = HUB_TOOL, unit = BANK_RECON_UNIT, level1 = level1))
    }
}

private const val HUB_TOOL = "account_hub_label"
private const val BANK_RECON_UNIT = "bank_recon_label"

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
