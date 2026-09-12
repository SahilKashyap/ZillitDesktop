package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exceptions, fraud, FX, shared links, the rules and the tab chips — each as
 * the web does it, and the few places where the web's own wire is the trap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankRecSurfacesFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val charge = BankException(
        id = "e1",
        periodId = "p1",
        type = ExceptionType.BankCharge,
        title = "Monthly service charge",
        transaction = BankTransaction(
            id = "t9",
            transactionDateMillis = 1_743_552_000_000,
            reference = "SVC-04",
            debit = 35.0,
        ),
    )

    /**
     * The Quick Add form is seeded from the line, and its effective date may
     * not fall on or before the cost report's lock.
     */
    @Test
    fun `a quick add is seeded from the line and refused inside the lock`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply { exceptionRows = listOf(charge) }
        val model = bankRecViewModel(repo, lookups = FakeLookups(locked = "2025-04-30")).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenExceptionQuickAdd("e1"))
        val seeded = model.state.value.exceptionsPage.quickAdd?.form
        assertEquals("2025-04-02", seeded?.date)
        assertEquals("SVC-04", seeded?.invoiceNumber)
        assertEquals(ExceptionType.BankCharge.guidance, seeded?.description)
        assertEquals(35.0, seeded?.amountValue)

        model.onEvent(BankRecEvent.EditExceptionQuickAdd(seeded!!.copy(effectiveDate = "2025-04-30", nominal = "7900")))
        model.onEvent(BankRecEvent.SubmitExceptionQuickAdd)
        runCurrent()
        assertTrue(repo.quickAdds.isEmpty())

        model.onEvent(BankRecEvent.EditExceptionQuickAdd(seeded.copy(effectiveDate = "2025-05-01", nominal = "7900")))
        model.onEvent(BankRecEvent.SubmitExceptionQuickAdd)
        runCurrent()

        val (id, form, fromWorkspace) = repo.quickAdds.single()
        assertEquals("e1", id)
        assertEquals("7900", form.nominal)
        assertFalse(fromWorkspace)
        assertNull(model.state.value.exceptionsPage.quickAdd)
    }

    /** Ignore, investigate and investigated are one click each, as on the web. */
    @Test
    fun `an exception's status changes in one click`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply { exceptionRows = listOf(charge) }
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.SetExceptionStatus("e1", ExceptionStatus.Ignored))
        runCurrent()

        assertEquals(listOf("e1" to ExceptionStatus.Ignored), repo.statusChanges)
        assertEquals(ExceptionStatus.Ignored, model.state.value.exceptions.single().status)
    }

    /** "All Open Periods" cannot export: the route takes a single period. */
    @Test
    fun `exceptions export only for one period`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.SetExceptionsPeriod("all"))
        model.onEvent(BankRecEvent.ExportExceptionsPdf)
        runCurrent()
        assertTrue(repo.exceptionExports.isEmpty())

        model.onEvent(BankRecEvent.SetExceptionsPeriod("p1"))
        model.onEvent(BankRecEvent.ExportExceptionsPdf)
        runCurrent()
        assertEquals(listOf("p1"), repo.exceptionExports)
    }

    /** Dismissing and escalating are both one click, and both land in the trail server-side. */
    @Test
    fun `dismissing and escalating an alert send at once`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply {
            alertRows = listOf(
                FraudAlert(id = "a1", periodId = "p1", alertType = FraudType.MandateFraud, riskScore = 88),
                FraudAlert(id = "a2", periodId = "p1", alertType = FraudType.SplitPayment, riskScore = 60),
            )
        }
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.FraudAlerts))
        runCurrent()

        model.onEvent(BankRecEvent.DismissAlert("a1"))
        runCurrent()
        model.onEvent(BankRecEvent.EscalateAlert("a2"))
        runCurrent()

        assertEquals(listOf("a1"), repo.dismissals)
        assertEquals(listOf("a2"), repo.escalations)
        assertEquals(
            listOf(FraudStatus.Dismissed, FraudStatus.Escalated),
            model.state.value.fraudAlerts.map { it.status },
        )
    }

    /** Choosing a bank resets the period filter, and the export carries the filters on screen. */
    @Test
    fun `the audit export carries the filters on screen`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenAuditLog)
        runCurrent()
        model.onEvent(BankRecEvent.FilterAuditLog(AuditFilters(periodId = "p1")))
        model.onEvent(BankRecEvent.FilterAuditLog(AuditFilters(bankAccountId = "b1", periodId = "p1")))
        assertEquals(AuditFilters(bankAccountId = "b1"), model.state.value.fraudPage.audit?.filters)

        model.onEvent(BankRecEvent.ExportAuditLog(AuditExportFormat.Csv))
        runCurrent()

        assertEquals(listOf(AuditExportFormat.Csv to AuditFilters(bankAccountId = "b1")), repo.auditExports)
    }

    /**
     * Post All goes row by row with the budget rate from Production Setup —
     * and not at all while any row is missing a rate.
     */
    @Test
    fun `posting every variance uses the project's rates, row by row`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply {
            rates = ProjectRates(defaultCode = "GBP", rates = mapOf("EUR" to 1.17))
            fxRows = listOf(
                FxVariance(
                    id = "v1",
                    periodId = "p1",
                    invoiceCurrency = "EUR",
                    foreignAmount = 1500.0,
                    bankRate = 1.19,
                ),
                FxVariance(id = "v2", periodId = "p1", invoiceCurrency = "USD", foreignAmount = 900.0, bankRate = 1.27),
            )
        }
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.PostAllFx)
        runCurrent()
        assertTrue(repo.fxPosts.isEmpty())

        repo.fxRows = repo.fxRows.filter { it.id == "v1" } +
            FxVariance(id = "v3", periodId = "p1", invoiceCurrency = "EUR", foreignAmount = 200.0, bankRate = 1.15)
        repo.failingFxPosts = setOf("v3")
        model.onEvent(BankRecEvent.Refresh)
        runCurrent()
        model.onEvent(BankRecEvent.PostAllFx)
        runCurrent()

        assertEquals(listOf("v1", "v3"), repo.fxPosts.map { it.first })
        assertEquals(listOf(1.17, 1.17), repo.fxPosts.map { it.second.budgetRate })
        assertEquals("Posted 1 of 2 — 1 failed", model.state.value.fxPage.postAllMessage)
    }

    /** A currency Production Setup has no rate for is typed at posting — and both rates are required. */
    @Test
    fun `a variance with no budget rate posts only once one is typed`() = runTest(dispatcher) {
        val repo = Fixtures.repo().apply {
            fxRows = listOf(
                FxVariance(id = "v2", periodId = "p1", invoiceCurrency = "USD", foreignAmount = 900.0, bankRate = 1.27),
            )
        }
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenFxPost("v2"))
        val post = model.state.value.fxPage.post
        assertEquals("", post?.budgetRate)
        assertEquals("7850", post?.nominalCode)
        model.onEvent(BankRecEvent.ConfirmFxPost)
        runCurrent()
        assertTrue(repo.fxPosts.isEmpty())

        model.onEvent(
            BankRecEvent.EditFxPost(nominalCode = "7850", costCentre = "PROD", budgetRate = "1.25", bankRate = "1.27"),
        )
        model.onEvent(BankRecEvent.ConfirmFxPost)
        runCurrent()

        val (id, posting) = repo.fxPosts.single()
        assertEquals("v2", id)
        assertEquals(1.25, posting.budgetRate)
        assertEquals(1.27, posting.bankRate)
        assertEquals("PROD", posting.costCentre)
    }

    /** The two rule halves save separately, each carrying only its own. */
    @Test
    fun `saving one rule section does not carry the other's edits`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        model.onEvent(BankRecEvent.OpenTab(BankTab.Settings))
        runCurrent()

        model.onEvent(BankRecEvent.ToggleMatchRule("amt_fuzzy_vendor_match", false))
        model.onEvent(BankRecEvent.ToggleFraudRule("duplicate_detection", false))
        model.onEvent(BankRecEvent.SaveMatchRules)
        runCurrent()

        assertEquals(false, repo.savedMatchRules.single()["amt_fuzzy_vendor_match"])
        assertTrue(repo.savedFraudRules.isEmpty())
        assertTrue(model.state.value.rules.fraudDirty)
        assertFalse(model.state.value.rules.matchDirty)
    }

    /** A link needs a recipient, an address, a period and something to show. */
    @Test
    fun `a shared link needs a recipient, a period and something to show`() = runTest(dispatcher) {
        val repo = Fixtures.repo()
        val model = bankRecViewModel(repo).also { it.start() }
        runCurrent()
        model.onEvent(BankRecEvent.OpenTab(BankTab.GuarantorPortal))
        runCurrent()

        model.onEvent(BankRecEvent.ComposePortalLink)
        assertEquals("p1", model.state.value.portal.draft?.periodId)
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertTrue(repo.createdLinks.isEmpty())

        val draft = PortalLinkDraft(
            recipientName = "James Whitford",
            recipientEmail = "j@example.com",
            periodId = "p1",
            permissions = emptySet(),
        )
        model.onEvent(BankRecEvent.EditPortalDraft(draft))
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertTrue(repo.createdLinks.isEmpty())

        model.onEvent(BankRecEvent.EditPortalDraft(draft.copy(permissions = setOf(PortalPermission.Balances))))
        model.onEvent(BankRecEvent.SavePortalLink)
        runCurrent()
        assertEquals(1, repo.createdLinks.size)
        assertNull(model.state.value.portal.draft)
    }

    /** The two options that name people are off unless somebody turns them on. */
    @Test
    fun `a new link does not show individual payments by default`() {
        val defaults = PortalPermission.defaults

        assertFalse(PortalPermission.TransactionDetail in defaults)
        assertFalse(PortalPermission.FraudAlerts in defaults)
        assertTrue(PortalPermission.Balances in defaults)
    }

    /** A tab's chip clears when it is looked at; Open Banking has no chip to clear. */
    @Test
    fun `opening a tab reads its chip`() = runTest(dispatcher) {
        val badges = FakeBadges()
        val model = bankRecViewModel(Fixtures.repo(), badges = badges).also { it.start() }
        runCurrent()

        model.onEvent(BankRecEvent.OpenTab(BankTab.OpenBanking))
        model.onEvent(BankRecEvent.OpenTab(BankTab.Exceptions))
        badges.flow.value = mapOf("bank_fraud_alerts" to 3)
        runCurrent()

        assertEquals(listOf("bank_overview", "bank_exceptions"), badges.reads)
        assertEquals(3, model.state.value.badges["bank_fraud_alerts"])
    }

    /** A deep link lands on the tab its tail names, as the web's URL would. */
    @Test
    fun `a route tail opens its tab`() = runTest(dispatcher) {
        val model = bankRecViewModel(Fixtures.repo()).also { it.start() }
        runCurrent()

        model.openRoute("/fx-variances")
        assertEquals(BankTab.FxVariances, model.state.value.tab)

        model.openRoute("/guarantor-portal?period=p1")
        assertEquals(BankTab.GuarantorPortal, model.state.value.tab)
    }
}
