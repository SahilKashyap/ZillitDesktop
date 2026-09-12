package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.domain.AlertInvoice
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecDirectory
import com.zillit.desktop.feature.bankrec.domain.BankRecPerson
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudSignal
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FraudVendor
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.FxStatus
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.NominalCode
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
import com.zillit.desktop.feature.bankrec.domain.PreviewException
import com.zillit.desktop.feature.bankrec.domain.PreviewFraudAlert
import com.zillit.desktop.feature.bankrec.domain.PreviewFx
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.TaxOption
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.LookupState
import com.zillit.desktop.feature.bankrec.ui.PortalState
import com.zillit.desktop.feature.bankrec.ui.RulesState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceState

/** A production mid-reconciliation — enough of every kind of row that each tab draws its busy state. */
internal object RenderFixtures {

    private const val APRIL = 1_743_465_600_000L
    private const val MARCH = 1_740_787_200_000L
    private const val DAY = 86_400_000L

    val account = BankAccountRef(
        id = "b1",
        name = "Barclays Production",
        bankName = "Barclays",
        holderName = "Night Films Ltd",
        currencyCode = "GBP",
        accountNumber = "43917755",
        sortCode = "204891",
        iban = "GB29 BARC 2048 9143 9177 55",
        nominalCode = "1200",
    )

    val april = BankPeriod(
        id = "p1",
        periodMillis = APRIL,
        bankAccountId = "b1",
        status = PeriodStatus.InProgress,
        totalTxns = 47,
        matchedCount = 39,
        suggestedCount = 3,
        unmatchedCount = 3,
        fraudCount = 2,
        openingBank = 218_400.0,
        closingBank = 184_220.55,
        closingZillit = 183_904.10,
        difference = 316.45,
        openingDateMillis = APRIL,
        closingDateMillis = APRIL + 29 * DAY,
    )

    val march = april.copy(
        id = "p0",
        periodMillis = MARCH,
        status = PeriodStatus.Complete,
        totalTxns = 52,
        matchedCount = 52,
        suggestedCount = 0,
        unmatchedCount = 0,
        fraudCount = 0,
        closingBank = 218_400.0,
        closingZillit = 218_400.0,
        difference = 0.0,
        signedBy = "u1",
        signedByName = "Priya Raman",
        signedByDesignation = "production_accountant",
        signedAtMillis = MARCH + 33 * DAY,
        signOffNotes = "All lines matched. Barclays confirmed the March service charge in writing.",
    )

    val transactions = listOf(
        BankTransaction(
            id = "t1", periodId = "p1", transactionDateMillis = APRIL + 2 * DAY, vendorName = "Panavision UK",
            reference = "INV-2025-0412", trReference = "BACS", paymentMethod = "bacs", debit = 12_400.0,
            status = TxnStatus.Matched, matchConfidence = 100, matchedInvoiceIds = listOf("inv-1"),
        ),
        BankTransaction(
            id = "t2", periodId = "p1", transactionDateMillis = APRIL + 4 * DAY, vendorName = "Framestore VFX Ltd",
            reference = "INV-2025-1944", debit = 52_800.0, status = TxnStatus.Suggested, matchConfidence = 92,
            matchedInvoiceIds = listOf("inv-2"),
        ),
        BankTransaction(
            id = "t3", periodId = "p1", transactionDateMillis = APRIL + 6 * DAY,
            description = "BARCLAYS SERVICE CHARGE",
            debit = 35.0, status = TxnStatus.Unmatched, exceptionId = "e1", exceptionType = "bank_charge",
            exceptionTitle = "Bank Charges",
        ),
        BankTransaction(
            id = "t4", periodId = "p1", transactionDateMillis = APRIL + 8 * DAY, vendorName = "Thames Valley Catering",
            reference = "TVC-0344", debit = 20_000.0, status = TxnStatus.Unmatched,
            fraudType = FraudType.RoundLargePayment, fraudStatus = FraudStatus.Active, fraudScore = 81,
        ),
        BankTransaction(
            id = "t5", periodId = "p1", transactionDateMillis = APRIL + 9 * DAY, vendorName = "Arri Rental Berlin",
            reference = "AR-DE-2211", debit = 1_287.40, currency = "EUR", status = TxnStatus.Unmatched,
            fx = FxDetail(foreignAmount = 1_500.0, currency = "EUR", budgetRate = 1.17, bankRate = 1.165, gain = -5.51),
        ),
        BankTransaction(
            id = "t6", periodId = "p1", transactionDateMillis = APRIL + 11 * DAY, description = "INTEREST PAID",
            credit = 42.18, status = TxnStatus.Unmatched,
        ),
    )

    val ledger = listOf(
        LedgerEntry(
            id = "led-1", entityId = "inv-1", kind = LedgerEntryKind.Invoice, vendorName = "Panavision UK",
            invoiceNumber = "INV-2025-0412", payMethod = "bacs", grossAmount = 12_400.0,
            invoiceDateMillis = APRIL + DAY, transactionIds = listOf("t1"), ledgerStatus = "paid",
        ),
        LedgerEntry(
            id = "led-2", entityId = "inv-2", kind = LedgerEntryKind.Invoice, vendorName = "Framestore VFX Ltd",
            invoiceNumber = "INV-2025-1944", payMethod = "bacs", grossAmount = 52_800.0,
            invoiceDateMillis = APRIL + 3 * DAY,
        ),
        LedgerEntry(
            id = "led-3", entityId = "inv-3", kind = LedgerEntryKind.Invoice, vendorName = "Mercury Post Production",
            invoiceNumber = "MPP-2025-910", payMethod = "chaps", grossAmount = 18_400.0,
            invoiceDateMillis = APRIL + 12 * DAY,
        ),
        LedgerEntry(
            id = "led-4", entityId = "q-1", kind = LedgerEntryKind.Transaction, title = "Bank charges",
            ledgerDescription = listOf("7900", "Bank charges"), debit = 35.0, dateMillis = APRIL + 6 * DAY,
        ),
    )

    val exceptions = listOf(
        BankException(
            id = "e1", periodId = "p1", type = ExceptionType.BankCharge, title = "Bank Charges",
            transaction = transactions[2],
        ),
        BankException(
            id = "e2", periodId = "p1", type = ExceptionType.Interest, title = "Interest received",
            transaction = transactions[5],
        ),
        BankException(
            id = "e3", periodId = "p1", type = ExceptionType.FxPayment, title = "Arri Rental Berlin",
            transaction = transactions[4],
        ),
        BankException(
            id = "e4", periodId = "p1", type = ExceptionType.Payroll, status = ExceptionStatus.Ignored,
            title = "Payroll Wk 14", transaction = transactions[2].copy(id = "t7", debit = 84_610.0),
        ),
    )

    val alerts = listOf(
        FraudAlert(
            id = "a1", periodId = "p1", alertType = FraudType.RoundLargePayment, status = FraudStatus.Active,
            title = "Round-number payment — Thames Valley Catering",
            description = "An exact £20,000.00 payment with no invoice reference.",
            riskScore = 81, createdAtMillis = APRIL + 8 * DAY,
            signals = listOf(
                FraudSignal("Round amount", "Exactly £20,000.00 — above the £10,000 threshold"),
                FraudSignal("No invoice reference", "The payment carries no invoice number"),
            ),
            invoices = listOf(
                AlertInvoice(
                    id = "inv-9",
                    invoiceNumber = "TVC-0344",
                    supplierName = "Thames Valley Catering",
                    grossAmount = 2_100.0,
                ),
            ),
            vendor = FraudVendor(name = "Thames Valley Catering", sortCode = "401122", accountNumber = "11223344"),
            transaction = transactions[3],
        ),
        FraudAlert(
            id = "a2", periodId = "p1", alertType = FraudType.MandateFraud, status = FraudStatus.Escalated,
            title = "Bank details changed — ProCam London", riskScore = 64, createdAtMillis = APRIL + 5 * DAY,
            transaction = transactions[1].copy(id = "t8", vendorName = "ProCam London Ltd", debit = 6_200.0),
        ),
    )

    val variances = listOf(
        FxVariance(
            id = "v1", periodId = "p1", invoiceCurrency = "EUR", foreignAmount = 1_500.0, budgetAmount = 1_282.05,
            paidAmount = 1_287.55, variance = -5.50, bankRate = 1.165, createdAtMillis = APRIL + 9 * DAY,
            vendorName = "Arri Rental Berlin", reference = "AR-DE-2211",
        ),
        FxVariance(
            id = "v2", periodId = "p1", invoiceCurrency = "USD", foreignAmount = 9_800.0, budgetAmount = 7_716.54,
            paidAmount = 7_656.25, variance = 60.29, bankRate = 1.28, createdAtMillis = APRIL + 14 * DAY,
            vendorName = "Kodak Motion Picture", reference = "KMP-77120",
        ),
        FxVariance(
            id = "v3", periodId = "p1", invoiceCurrency = "EUR", foreignAmount = 640.0, budgetAmount = 547.01,
            paidAmount = 545.07, variance = 1.94, budgetRate = 1.17, bankRate = 1.174, status = FxStatus.Posted,
            createdAtMillis = APRIL + 3 * DAY, vendorName = "Cinelab Paris", reference = "CLP-0981",
        ),
    )

    val links = listOf(
        PortalLink(
            id = "l1", token = "tok-1", recipientName = "James Whitford",
            recipientEmail = "j.whitford@filmfinances.com",
            periodId = "p0", periodLabel = "Mar 2025", views = 4, createdAtMillis = MARCH + 34 * DAY,
            expiresAtMillis = MARCH + 64 * DAY, lastViewedAtMillis = MARCH + 36 * DAY,
            permissions = listOf(
                PortalPermission.Balances,
                PortalPermission.ReconciliationStatus,
                PortalPermission.Exceptions,
            ),
        ),
        PortalLink(
            id = "l2", token = "tok-2", recipientName = "Sofia Marchetti", recipientEmail = "sofia@rai.it",
            periodId = "p0", periodLabel = "Mar 2025", status = PortalStatus.Revoked,
            createdAtMillis = MARCH + 35 * DAY, permissions = listOf(PortalPermission.FxVariance),
        ),
    )

    val preview = PortalPreview(
        period = march,
        bankAccountName = "Barclays Production",
        bankAccountHolder = "Night Films Ltd",
        bankAccountCurrency = "GBP",
        projectName = "The Long Night",
        exceptions = listOf(
            PreviewException(title = "Bank Charges", status = ExceptionStatus.Resolved, debit = 35.0),
            PreviewException(title = "Tax payment", debit = 12_402.0),
        ),
        fraudAlerts = listOf(
            PreviewFraudAlert(title = "Duplicate Payment — Vantage Scaffolding", status = FraudStatus.Dismissed,
                description = "Two payments of £3,100.00 within seven days; the second was a reissued cheque."),
        ),
        fxVariances = listOf(
            PreviewFx(
                currency = "EUR",
                foreignAmount = 1_500.0,
                paidAmount = 1_287.55,
                variance = -5.5,
                budgetRate = 1.17,
                bankRate = 1.165,
            ),
            PreviewFx(
                currency = "USD",
                foreignAmount = 9_800.0,
                paidAmount = 7_656.25,
                variance = 60.29,
                budgetRate = 1.27,
                bankRate = 1.28,
            ),
        ),
        transactions = transactions,
        ledgerEntries = ledger,
    )

    val audit = listOf(
        FraudAuditEntry(
            id = "au1", action = "created", performedBy = "u1", bankAccountId = "b1", periodId = "p1",
            createdAtMillis = APRIL + 8 * DAY, fraudType = "round_large_payment", riskScore = 81,
            transaction = transactions[3], periodMillis = APRIL, bankName = "Barclays", bankSortCode = "204891",
            bankAccountNumber = "43917755",
        ),
        FraudAuditEntry(
            id = "au2", action = "statement_import", performedBy = "u1", bankAccountId = "b1", periodId = "p1",
            createdAtMillis = APRIL + DAY, file = "barclays-april.csv", periodMillis = APRIL, bankName = "Barclays",
        ),
    )

    val people = BankRecDirectory { id ->
        if (id == "u1") BankRecPerson("Priya Raman", "production_accountant") else null
    }

    fun state(tab: BankTab): BankRecUiState = BankRecUiState(
        tab = tab,
        periods = listOf(april, march),
        periodsLoading = false,
        bankAccounts = listOf(account),
        rates = ProjectRates(defaultCode = "GBP", rates = mapOf("EUR" to 1.17)),
        exceptions = exceptions,
        exceptionsLoading = false,
        fraudAlerts = alerts,
        fraudLoading = false,
        fxVariances = variances,
        fxLoading = false,
        badges = mapOf("bank_exceptions" to 3, "bank_fraud_alerts" to 1),
        historyAccountId = "b1",
        workspace = WorkspaceState(periodId = "p1", transactions = transactions, ledger = ledger),
        portal = PortalState(links = links, loaded = true, selectedPeriodId = "p0", preview = preview),
        rules = RulesState(loaded = true),
        lookups = LookupState(
            taxTypes = listOf(TaxOption("GB_vat_20", "VAT", 20.0, "GB"), TaxOption("GB_zero", "Zero rated", 0.0, "GB")),
            nominalCodes = listOf(NominalCode("7900", "Bank charges"), NominalCode("7850", "FX gains and losses")),
            lockedThrough = "2025-03-31",
            company = CompanyDetails(projectName = "The Long Night", companyName = "Night Films Ltd"),
        ),
    )
}
