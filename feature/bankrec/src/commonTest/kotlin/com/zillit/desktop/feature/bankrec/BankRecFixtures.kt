package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecBadges
import com.zillit.desktop.feature.bankrec.domain.BankRecFiles
import com.zillit.desktop.feature.bankrec.domain.BankRecLookups
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.NominalCode
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.StatementFiles
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.TaxOption
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The rows most tests start from: one period open, one signed off, a line of each kind. */
internal object Fixtures {
    val openPeriod = BankPeriod(
        id = "p1",
        periodMillis = 1_743_465_600_000,
        bankAccountId = "b1",
        status = PeriodStatus.InProgress,
        totalTxns = 3,
        matchedCount = 1,
    )

    val closedPeriod = openPeriod.copy(
        id = "p0",
        periodMillis = 1_740_787_200_000,
        status = PeriodStatus.Complete,
        signedAtMillis = 1_743_000_000_000,
    )

    val account = BankAccountRef(id = "b1", name = "Barclays Production", currencyCode = "GBP")

    val unmatched = BankTransaction(
        id = "t1",
        periodId = "p1",
        vendorName = "Panavision",
        debit = 1200.0,
        status = TxnStatus.Unmatched,
    )

    val suggested = BankTransaction(
        id = "t2",
        periodId = "p1",
        vendorName = "Kodak",
        debit = 400.0,
        status = TxnStatus.Suggested,
        matchConfidence = 92,
        matchedInvoiceIds = listOf("inv-2"),
    )

    val invoice = LedgerEntry(
        id = "led-1",
        entityId = "inv-1",
        kind = LedgerEntryKind.Invoice,
        vendorName = "Panavision",
        grossAmount = 1200.0,
    )

    val suggestedInvoice = invoice.copy(id = "led-2", entityId = "inv-2", vendorName = "Kodak", grossAmount = 400.0)

    fun repo() = FakeBankRecRepository().apply {
        periodRows = listOf(openPeriod, closedPeriod)
        accountRows = listOf(account)
        workspaceData = WorkspaceData(
            transactions = listOf(unmatched, suggested),
            ledger = listOf(invoice, suggestedInvoice),
        )
    }
}

/** A picker that answers what the test sets, and an upload that always lands. */
internal class FakeStatementFiles(var picked: PickedStatement? = null) : StatementFiles {
    val uploads = mutableListOf<PickedStatement>()

    override suspend fun pick(): ZillitResult<PickedStatement?> = ZillitResult.Success(picked)

    override suspend fun upload(file: PickedStatement): ZillitResult<StatementUpload> {
        uploads += file
        return ZillitResult.Success(StatementUpload(media = "bank-statement/k/${file.name}", fileName = file.name))
    }
}

/** Exports saved, by name. */
internal class FakeFiles : BankRecFiles {
    val saved = mutableListOf<String>()

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        saved += fileName
        return ZillitResult.Success(Unit)
    }
}

/** Tab counts the test sets, and the reads the module sends. */
internal class FakeBadges : BankRecBadges {
    val flow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val reads = mutableListOf<String>()

    override val counts: Flow<Map<String, Int>> = flow

    override suspend fun markRead(level1: String) {
        reads += level1
    }
}

internal class FakeLookups(
    private val locked: String? = null,
    private val taxes: List<TaxOption> = emptyList(),
    private val codes: List<NominalCode> = emptyList(),
) : BankRecLookups {
    override suspend fun taxTypes(): List<TaxOption> = taxes
    override suspend fun nominalCodes(): List<NominalCode> = codes
    override suspend fun lockedThrough(): String? = locked
    override suspend fun departments(): Map<String, String> = emptyMap()
    override fun company(): CompanyDetails = CompanyDetails(
        projectName = "The Long Night",
        companyName = "Night Films Ltd",
    )
}

@Suppress("LongParameterList")
internal fun bankRecViewModel(
    repo: FakeBankRecRepository,
    statements: StatementFiles? = FakeStatementFiles(),
    files: BankRecFiles? = FakeFiles(),
    lookups: BankRecLookups = FakeLookups(),
    badges: BankRecBadges? = null,
) = BankRecViewModel(
    repository = repo,
    statements = statements,
    files = files,
    lookups = lookups,
    badges = badges,
)
