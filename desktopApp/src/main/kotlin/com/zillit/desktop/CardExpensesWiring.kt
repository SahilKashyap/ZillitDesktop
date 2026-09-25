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
import com.zillit.desktop.feature.cardexpenses.domain.CardCompanyRef
import com.zillit.desktop.feature.cardexpenses.domain.CardCrewHost
import com.zillit.desktop.feature.cardexpenses.domain.CardNominal
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.PickKind
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import java.util.UUID
import com.zillit.desktop.feature.cardexpenses.data.CardBinaryPost
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.core.common.map
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardAssignmentRule
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardCompany
import com.zillit.desktop.feature.cardexpenses.domain.CardCurrency
import com.zillit.desktop.feature.cardexpenses.domain.CardReference
import com.zillit.desktop.feature.cardexpenses.domain.CardDepartment
import com.zillit.desktop.feature.cardexpenses.domain.CardHubSource
import com.zillit.desktop.feature.cardexpenses.domain.CardCurrencies
import com.zillit.desktop.feature.cardexpenses.domain.CardFiles
import com.zillit.desktop.feature.cardexpenses.domain.CardInboxHost
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptMedia
import com.zillit.desktop.feature.cardexpenses.domain.StatementFile
import com.zillit.desktop.feature.cardexpenses.domain.StoredStatement
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore

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
            // The assign and team pickers list the accounts team only, as the
            // web's; the module decides that from the identifier.
            departmentIdentifier = user.departmentIdentifier,
        )
    }

/**
 * The card register's exports — the web's Card Register and All Transactions
 * (PDF / XLSX). The service answers with the file's bytes, which the module's
 * JSON client cannot read, so the byte POST comes from the host, as Bank
 * Reconciliation's does; built here because [postForBytes] needs the ready graph.
 */
internal fun AppGraph.Ready.cardRepositoryWithExports(): CardRepository =
    CardRepositoryImpl(apiClient, config, CardBinaryPost { url, body -> postForBytes(url, body) })

/** Exports land in Downloads and open, as every other export in this application does. */
internal fun cardFiles() = CardFiles { fileName, bytes ->
    when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
        is ZillitResult.Failure -> saved
        is ZillitResult.Success -> {
            openSavedFile(saved.data)
            ZillitResult.Success(Unit)
        }
    }
}

/**
 * The production's bank accounts, for a fund request's "pay into" — the hub's
 * Production Setup banks, which the card service does not list itself.
 */
internal suspend fun AppGraph.Ready.cardBanks(): List<CardBank> =
    accountHubRepository.bankAccounts().getOrNull().orEmpty().map { bank ->
        CardBank(
            id = bank.id,
            name = bank.name,
            currency = bank.currencyCode.takeIf { it.isNotBlank() },
            symbol = bank.currencySymbol.takeIf { it.isNotBlank() },
        )
    }

/**
 * The production's money reference for the card forms and the dashboard: its
 * default currency and selected currencies with their rates (Production Setup
 * → Project Currencies), the companies whose names ride on the card tiles, and
 * the banks whose currency a provider pick seeds. Each read settles on its
 * own — a failing one leaves only its part empty.
 */
internal suspend fun AppGraph.Ready.cardReference(): CardReference {
    val currencies = accountHubRepository.currencies().getOrNull()
    val companies = accountHubRepository.companies().getOrNull().orEmpty()
    return CardReference(
        defaultCurrency = currencies?.defaultCode.orEmpty(),
        currencies = currencies?.currencies.orEmpty().map { CardCurrency(code = it.code, rate = it.rate) },
        companies = companies.map { CardCompany(id = it.id, name = it.name, bankIds = it.bankIds) },
        banks = cardBanks(),
    )
}

/**
 * The hub's lists and routes the card settings page needs: the production's
 * companies (for the provider's bank → company owner rule), its departments
 * (the coordinator and rule pickers), and the assignment-rules CRUD — all the
 * hub repository's already, so the card module asks through this seam rather
 * than calling the hub's routes a second time.
 */
internal fun AppGraph.Ready.cardHub(): CardHubSource = object : CardHubSource {
    override suspend fun companies(): List<CardCompany> =
        accountHubRepository.companies().getOrNull().orEmpty()
            .map { CardCompany(id = it.id, name = it.name, bankIds = it.bankIds) }

    override suspend fun departments(): List<CardDepartment> =
        hubDepartments().map { CardDepartment(id = it.id, name = it.name) }

    override suspend fun saveAssignmentRule(rule: CardAssignmentRule): ZillitResult<CardAssignmentRule> {
        val hubRule = AssignmentRule(
            id = rule.id,
            module = CARD_RULES_MODULE,
            departments = rule.departments,
            nominalCodes = rule.nominalCodes,
            amountMin = rule.amountMin,
            assignTo = rule.assignTo,
            isActive = rule.isActive,
            priority = rule.priority,
            persisted = rule.persisted,
        )
        val saved = if (rule.persisted) {
            accountHubRepository.updateAssignmentRule(hubRule)
        } else {
            accountHubRepository.createAssignmentRule(hubRule)
        }
        return saved.map { back ->
            CardAssignmentRule(
                id = back.id,
                departments = back.departments,
                nominalCodes = back.nominalCodes,
                amountMin = back.amountMin,
                assignTo = back.assignTo,
                isActive = back.isActive,
                priority = back.priority,
                persisted = true,
            )
        }
    }

    override suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> =
        accountHubRepository.deleteAssignmentRule(id)
}

/** The hub files the card tool's rules under this module (`SettingsPage.jsx:374`). */
private const val CARD_RULES_MODULE = "card_expenses"

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
                // The whole storage result, not only the key: a receipt is stored
                // as the web's AttachmentModel, and a bare key reads back on the
                // web as no attachment at all.
                is ZillitResult.Success -> ZillitResult.Success(
                    CardAttachment(
                        key = stored.data.media,
                        fileName = chosen.name,
                        bucket = stored.data.bucket,
                        region = stored.data.region,
                        contentType = stored.data.contentType,
                        contentSubtype = chosen.name.substringAfterLast('.', "").lowercase(),
                    ),
                )
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

/**
 * Import Statement, the Receipt Inbox and All Transactions' host seams.
 *
 * The statement is picked and stored in two steps, as the web's page does:
 * the file is held while the accountant states its currency, and nothing is
 * uploaded until Import is pressed. What the import route is handed is the
 * whole attachment model — `{media, bucket, region, name, content_type,
 * content_subtype}` (`attachmentUpload.js:53-61`) — not a bare key.
 */
internal fun AppGraph.Ready.cardInboxHost(): CardInboxHost {
    val picker = AwtAttachmentPicker()
    val store = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            val remote = remoteConfigRepository.current()
            val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
            val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
            if (access != null && secret != null) AwsCredentials(access, secret) else null
        },
        storage = storageTarget,
        newKey = { fileName -> "card-expenses/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val graph = this
    return object : CardInboxHost {
        override suspend fun pickStatement(): ZillitResult<StatementFile?> {
            var refusal: String? = null
            val chosen = picker.pick(
                kind = PreviewKind.Document,
                multiple = false,
                maxBytes = STATEMENT_MAX_BYTES,
                onRefused = { why ->
                    refusal = when (why) {
                        is PickRefusal.TooLarge -> "${why.name} is over the 20 MB limit."
                        // The format check itself is the module's, by extension.
                        is PickRefusal.WrongKind -> str(S.desktop_ce_inbox_only_statement_types)
                    }
                },
            ).firstOrNull()
            return when {
                chosen == null && refusal != null -> ZillitResult.Failure(ZillitError.Unknown(refusal.orEmpty()))
                chosen == null -> ZillitResult.Success(null)
                else -> ZillitResult.Success(StatementFile(chosen.name, chosen.bytes))
            }
        }

        override suspend fun storeStatement(file: StatementFile): ZillitResult<StoredStatement> =
            when (val stored = store.upload(file.name, contentTypeFor(file.name, null), file.bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> ZillitResult.Success(
                    StoredStatement(
                        media = stored.data.media,
                        bucket = stored.data.bucket.takeIf { it.isNotBlank() },
                        region = stored.data.region.takeIf { it.isNotBlank() },
                        name = file.name,
                        // The web's `media_type` for a statement, and its extension.
                        contentType = "document",
                        contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                    ),
                )
            }

        override suspend fun media(media: ReceiptMedia): ZillitResult<ByteArray> =
            graph.noticeMedia.fetch(
                com.zillit.desktop.feature.home.domain.NoticeAttachment(
                    media = media.key,
                    fileName = media.fileName,
                    bucket = media.bucket,
                    region = media.region,
                ),
                preview = false,
            )

        override suspend fun departments(): List<CardDepartment> =
            graph.hubDepartments().map { CardDepartment(id = it.id, name = it.name) }

        override suspend fun currencies(): CardCurrencies {
            val settings = graph.accountHubRepository.currencies().getOrNull() ?: return CardCurrencies()
            return CardCurrencies(
                defaultCode = settings.defaultCode?.takeIf { it.isNotBlank() },
                codes = settings.currencies.map { it.code }.filter { it.isNotBlank() },
            )
        }
    }
}

/**
 * What the card tool's crew pages need from the hub and the profile: the
 * production's companies (the receipt batch's `company_id` fallback, the web's
 * `resolveCardCompany`), its chart's postable codes (the Cost Code pickers,
 * `CoaCodeInput`), and whether it is a television production (Episode).
 */
internal fun AppGraph.Ready.cardCrewHost(): CardCrewHost = CardCrewHost(
    companies = {
        accountHubRepository.companies().getOrNull().orEmpty().map { company ->
            CardCompanyRef(id = company.id, bankIds = company.bankIds)
        }
    },
    nominals = {
        ChartOfAccounts.leaves(accountHubRepository.accounts(activeOnly = true).getOrNull().orEmpty())
            .map { account -> CardNominal(code = account.code, name = account.name) }
    },
    isTelevision = {
        projectContext?.context?.value?.project?.subType?.contains("television", ignoreCase = true) == true
    },
)
