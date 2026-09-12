package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.parseDuplicates
import com.zillit.desktop.feature.invoices.data.parseOverview
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoiceNavGroup
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's payload and the accountant's sidebar — the web's
 * `OverviewPage` reads and `NAV_SECTIONS`.
 */
class InvoiceOverviewTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(body: String) = parseOverview(json.parseToJsonElement(body))

    /** This route answers camelCase, alone among the service's routes; the web reads it that way too. */
    @Test
    fun `the overview decodes the server's camelCase`() {
        val overview = parse(
            """
            {"statCards":{"totalInvoices":42,"awaitingMatch":3,"awaitingMatchAmount":"£1,200.00",
              "inApproval":5,"overdueCount":2,"overdueAmount":"£800.00","readyToPay":7,
              "readyToPayAmount":"£9,000.00","dueThisWeek":"£2,500.00","dueThisWeekCount":4,
              "totalAP":"£18,000.00","supplierCount":11},
             "pipeline":[{"id":"inbox","label":"Inbox","count":3,"color":"amber"},
                         {"id":"paid","label":"Paid","count":0,"color":"green"}],
             "costReportSummary":{"pendingCount":8,"pendingNet":"£4,000.00","overBudgetDepts":1,
               "underBudgetDepts":6,"rows":[{"dept":"d1","budgetFmt":"£5,000","pendingFmt":"£900",
               "projectedFmt":"£5,900","variance":18}]},
             "supplierAlerts":[{"severity":"danger","title":"Panavision on stop","sub":"3 overdue",
               "urgency":"today","href":"/invoices/register","btnLabel":"Review"}],
             "pendingActions":[{"count":3,"label":"Invoices to match","variant":"amber",
               "href":"/invoices/matching"}],
             "totalActions":3,
             "recentActivity":[{"status":"Paid","variant":"green","supplier":"Movietech","ref":"INV-9",
               "desc":"Grip package","amount":"£400.00"}]}
            """.trimIndent(),
        )

        assertEquals(42, overview.stats.totalInvoices)
        assertEquals("£1,200.00", overview.stats.awaitingMatchAmount)
        assertEquals(11, overview.stats.vendorCount)
        assertEquals(listOf("inbox", "paid"), overview.pipeline.map { it.id })
        assertEquals(3, overview.pipeline.first().count)
        assertEquals(1, overview.costReport.overBudgetDepts)
        assertEquals("£5,900", overview.costReport.rows.single().projected)
        assertTrue(overview.costReport.rows.single().isOver)
        assertEquals("Panavision on stop", overview.vendorAlerts.single().title)
        assertEquals("Review", overview.vendorAlerts.single().buttonLabel)
        assertEquals(3, overview.totalActions)
        assertEquals("Movietech", overview.recentActivity.single().vendor)
    }

    /** A server that switches to the house spelling must not blank the dashboard. */
    @Test
    fun `snake_case is read too, and an empty body is an empty dashboard`() {
        val overview = parse("""{"stat_cards":{"total_invoices":4,"supplier_count":2},"total_actions":1}""")
        assertEquals(4, overview.stats.totalInvoices)
        assertEquals(2, overview.stats.vendorCount)
        assertEquals(1, overview.totalActions)

        val empty = parse("{}")
        assertEquals(0, empty.stats.totalInvoices)
        assertTrue(empty.pipeline.isEmpty())
        assertTrue(empty.recentActivity.isEmpty())
    }

    @Test
    fun `duplicate flags decode, and one without an id is dropped`() {
        val flags = parseDuplicates(
            json.parseToJsonElement(
                """
                [{"id":"f1","status":"pending","similarity_score":92,"invoice_ref":"INV-12",
                  "supplier_name":"Panavision","invoice_amount":"1200.50","duplicate_ref":"INV-9",
                  "duplicate_status":"paid","duplicate_date":"2026-03-03",
                  "match_reasons":["same vendor","same amount"]},
                 {"status":"pending"}]
                """.trimIndent(),
            ),
        )
        val flag = flags.single()
        assertEquals("f1", flag.id)
        assertTrue(flag.isPending)
        assertFalse(flag.isConfirmed)
        assertEquals(92, flag.similarityScore)
        assertEquals(1200.50, flag.invoiceAmount)
        assertEquals(listOf("same vendor", "same amount"), flag.matchReasons)
        assertEquals("INV-9", flag.duplicateRef)
    }

    /** The sidebar is the web's, group for group and row for row. */
    @Test
    fun `the sidebar carries every web row in its groups`() {
        assertEquals(
            listOf("overview", "inbox", "register", "matching", "approval", "process", "payments", "posted",
                "credits", "creditors", "suppliers", "sales", "accruals", "analytics", "reports", "settings"),
            AccountantPage.entries.map { it.id },
        )
        assertEquals(
            listOf("Invoice Inbox", "Invoice Register", "Invoices Pre-approval"),
            AccountantPage.entries.filter { it.group == InvoiceNavGroup.ReceiveAndMatch }.map { it.label },
        )
        assertNull(InvoiceNavGroup.Start.label, "the web's first group has no heading")
    }

    /** Settings is the senior accountant's row — the web's `senior: true`. */
    @Test
    fun `settings is hidden from an accountant who is not senior`() {
        val junior = InvoiceViewer(departmentIdentifier = "department_accounts", designationIdentifier = "assistant")
        val senior = junior.copy(designationIdentifier = "production_accountant")
        assertFalse(AccountantPage.Settings in AccountantPage.visibleTo(junior))
        assertTrue(AccountantPage.Settings in AccountantPage.visibleTo(senior))
        assertEquals(AccountantPage.entries.size - 1, AccountantPage.visibleTo(junior).size)
    }

    /** A pipeline stage and a server href both name where a click goes. */
    @Test
    fun `a stage and an href resolve to their pages`() {
        assertEquals(AccountantPage.Inbox, AccountantPage.forPipelineStage("inbox"))
        assertEquals(AccountantPage.Payments, AccountantPage.forPipelineStage("ready_to_pay"))
        assertEquals(AccountantPage.Register, AccountantPage.forPipelineStage("paid"))
        assertEquals(AccountantPage.Register, AccountantPage.forPipelineStage("nothing-like-this"))

        assertEquals(AccountantPage.Register, AccountantPage.forHref("/film-tools/account-hub/invoices/register"))
        assertEquals(AccountantPage.Inbox, AccountantPage.forHref("/invoices/inbox"))
        assertEquals(
            AccountantPage.Settings,
            AccountantPage.forHref("/invoices/settings"),
            "every row the web has now opens a page here too",
        )
        assertNull(AccountantPage.forHref("/vendors"))
    }
}
