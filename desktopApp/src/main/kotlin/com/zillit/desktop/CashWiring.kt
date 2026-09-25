package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PickedFile
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.data.CashBinaryPost
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashAttachment
import com.zillit.desktop.feature.cashexpenses.domain.CashAttachmentUploader
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrency
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartment
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashFiles
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Petty Cash's host seams for the register exports — the web's Floats and
 * Receipts Register (PDF / XLSX) and the History export.
 *
 * The service answers those with the file's bytes, which the module's JSON
 * client cannot read, so the byte POST comes from the host, as Bank
 * Reconciliation's does. Built here rather than in the graph's constructor
 * because [postForBytes] needs the ready graph.
 */
internal fun AppGraph.Ready.cashRepositoryWithExports(): CashRepository =
    CashRepositoryImpl(apiClient, config, CashBinaryPost { url, body -> postForBytes(url, body) })

/** Exports land in Downloads and open, as every other export in this application does. */
internal fun cashFiles() = CashFiles { fileName, bytes ->
    when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
        is ZillitResult.Failure -> saved
        is ZillitResult.Success -> {
            openSavedFile(saved.data)
            ZillitResult.Success(Unit)
        }
    }
}

// -- reference data -------------------------------------------------------------

/** Every reference seam the cash view model takes, from the ready graph. */
internal fun AppGraph.Ready.cashReferenceSources() = CashReferenceSources(
    currencies = { cashCurrencies() },
    departments = { cashDepartments() },
    chartAccounts = { cashChartAccounts() },
    uploader = cashAttachmentUploader(),
)

/**
 * Production Setup's Project Currencies and default — the same read the
 * invoices tool's money comes from (`InvoiceReferenceData`). A failed read
 * answers the empty set; the module then shows GBP, as the web's
 * `LEGACY_DEFAULT` does, until the default resolves.
 */
internal suspend fun AppGraph.Ready.cashCurrencies(): CashCurrencies {
    val settings = accountHubRepository.currencies().getOrNull() ?: return CashCurrencies()
    return CashCurrencies(
        currencies = settings.currencies.map { CashCurrency(code = it.code.uppercase(), symbol = it.symbol) },
        defaultCode = settings.defaultCode?.uppercase(),
    )
}

/**
 * The hub's department list. The name is the raw `department_name` — the
 * same string the crew list carries, which is what the module joins people
 * to departments by (`CashDepartments.withIds`); screens localise it.
 */
internal suspend fun AppGraph.Ready.cashDepartments(): List<CashDepartment> =
    hubDepartments().map { CashDepartment(id = it.id, name = it.name) }

/**
 * The chart of accounts for the nominal pickers — every row, inactive ones
 * included, so `wrapNominal` never mistakes a retired code for a new one;
 * [CashAccount.postable] says which may be coded against. A row is a leaf
 * when no other row names it as its parent. A failed read is no chart.
 */
internal suspend fun AppGraph.Ready.cashChartAccounts(): List<CashAccount> {
    val rows = accountHubRepository.accounts().getOrNull().orEmpty()
    val parents = rows.mapNotNullTo(mutableSetOf()) { it.parentId }
    return rows.filter { it.code.isNotBlank() }.map { row ->
        CashAccount(
            code = row.code,
            name = row.name,
            balanceSheet = row.costType.isBalanceSheet,
            postable = row.isPosting && row.isActive,
            leaf = row.id !in parents,
        )
    }
}

// -- badges -----------------------------------------------------------------------

/**
 * The cash tool's rows in the ledger: per tab, per row, and the reads.
 *
 * Each `level_1` is read from the slice its rows are filed under — an
 * accountant's under the Account Hub's tool, everyone else's under the cash
 * tool, and the coding queue always the latter (`CashExpensesModule.jsx:93-120`,
 * [CashBadges.toolFor]). [isAccountant] is asked on every change, because
 * which view the tool is showing follows the composition that entered it.
 */
internal fun AppGraph.Ready.cashBadges(isAccountant: () -> Boolean): TabBadgeSource = object : TabBadgeSource {
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun query(tool: String, groupBy: String, level1: String? = null) = BadgeDrilldownQuery(
        groupBy = groupBy,
        section = BadgeSections.TOOLS,
        tool = tool,
        unit = CashBadges.CASH_TOOL,
        level1 = level1,
    )

    /** Every `level_1` the viewer's slice holds, with the coding queue taken from the crew slice. */
    private fun perLevel(): Map<String, Int> {
        val accountant = isAccountant()
        val own = badgeStore.split(query(CashBadges.toolFor("", accountant), "level_1"))
        if (!accountant) return own
        val crew = badgeStore.split(query(CashBadges.CASH_TOOL, "level_1"))
        return own - CashBadges.CODING_QUEUE + listOfNotNull(
            crew[CashBadges.CODING_QUEUE]?.let { CashBadges.CODING_QUEUE to it },
        )
    }

    override val counts: Flow<Map<String, Int>> = badgeStore.counts
        .map { perLevel() }
        .distinctUntilChanged()

    override val entityCounts: Flow<Map<String, Map<String, Int>>> = badgeStore.counts
        .map {
            val accountant = isAccountant()
            perLevel().keys.associateWith { level1 ->
                badgeStore.split(query(CashBadges.toolFor(level1, accountant), "level_3", level1))
                    .filterKeys { it.isNotBlank() }
            }.filterValues { it.isNotEmpty() }
        }
        .distinctUntilChanged()

    override fun read(key: String) {
        val tool = CashBadges.toolFor(key, isAccountant())
        reads.launch { emitLevelRead(tool = tool, unit = CashBadges.CASH_TOOL, level1 = key) }
    }

    override fun readEntity(key: String, entityId: String, kind: String?) {
        val tool = CashBadges.toolFor(key, isAccountant())
        reads.launch {
            emitLevelRead(tool = tool, unit = CashBadges.CASH_TOOL, level1 = key, level2 = kind, level3 = entityId)
        }
    }
}

// -- receipts -----------------------------------------------------------------------

/**
 * Picking a receipt and putting it in the project's store — the card tool's
 * uploader (`cardAttachmentUploader`) with the cash key prefix, answering the
 * object the claim route takes (`uploadAttachment`'s shape).
 *
 * Images and PDF, 10 MB, checked by extension — the check that holds when a
 * file comes in round the OS filter. A cancelled picker is success-with-null.
 */
internal fun AppGraph.Ready.cashAttachmentUploader(): CashAttachmentUploader {
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
        newKey = { fileName -> "cash-expenses/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )

    return CashAttachmentUploader {
        var refusal: String? = null
        val chosen: PickedFile? = picker.pick(
            kind = PreviewKind.Document,
            multiple = false,
            maxBytes = RECEIPT_MAX_BYTES,
            onRefused = { why ->
                refusal = when (why) {
                    is PickRefusal.TooLarge -> "${why.name} is over the 10 MB limit."
                    is PickRefusal.WrongKind -> "${why.name}: ${str(S.desktop_receipt_types)}."
                }
            },
        ).firstOrNull()

        val extension = chosen?.name?.substringAfterLast('.', "")?.lowercase()
        when {
            chosen == null && refusal != null -> ZillitResult.Failure(ZillitError.Unknown(refusal.orEmpty()))
            chosen == null -> ZillitResult.Success(null)
            extension !in RECEIPT_EXTENSIONS -> ZillitResult.Failure(
                ZillitError.Unknown("${chosen.name}: ${str(S.desktop_receipt_types)}."),
            )

            else -> {
                val mime = contentTypeFor(chosen.name, null)
                when (val stored = uploader.upload(chosen.name, mime, chosen.bytes)) {
                    is ZillitResult.Failure -> stored
                    is ZillitResult.Success -> {
                        val (type, subtype) = CashAttachment.splitMime(stored.data.contentType.ifBlank { mime })
                        ZillitResult.Success(
                            CashAttachment(
                                media = stored.data.media,
                                bucket = stored.data.bucket,
                                region = stored.data.region,
                                name = chosen.name,
                                contentType = type,
                                contentSubtype = subtype,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val RECEIPT_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "pdf")
private const val RECEIPT_MAX_BYTES = 10L * 1024 * 1024
