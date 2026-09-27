package com.zillit.desktop.feature.invoices

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.ClientAddress
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.Company
import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.InvoiceAnalytics
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceProjectInfo
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LinkedPo
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.domain.VendorSpend
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.CreditField
import com.zillit.desktop.feature.invoices.ui.CreditNoteForm
import com.zillit.desktop.feature.invoices.ui.CreditNotesUi
import com.zillit.desktop.feature.invoices.ui.EnterInvoiceForm
import com.zillit.desktop.feature.invoices.ui.EnterTab
import com.zillit.desktop.feature.invoices.ui.InvoicesScreen
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.SalesInvoiceDraft
import com.zillit.desktop.feature.invoices.ui.SalesUi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The screens this parity pass added or rebuilt — Credit Notes (list, form,
 * preview), Sales, Pre-approval, Payments (grouped and Wires), Posted,
 * Analytics and Enter Invoice — compose in both themes, with the web's words
 * on them. `INVOICES_SHOTS=<dir>` writes a picture of each for eyeballing.
 */
@OptIn(ExperimentalTestApi::class)
class InvoicesParityScreensRenderTest {

    @Test
    fun `every rebuilt screen composes in both themes`() {
        scenes().forEach { (name, scene) ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) { InvoicesScreen(state = scene.state, onEvent = {}, nowMs = NOW) }
                    }
                    scene.expect.forEach { text ->
                        // Any number of matches will do: "MATCHED" is also inside "UNMATCHED".
                        val found = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                        assertTrue(found, "$name (${if (dark) "dark" else "light"}): no \"$text\"")
                    }
                }
            }
        }
    }

    @Test
    fun `parity screenshots when asked for`() {
        val dir = System.getenv("INVOICES_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        scenes().forEach { (name, scene) ->
            val rendered = ImageComposeScene(width = 2560, height = 1500, density = Density(2f)) {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    InvoicesScreen(state = scene.state, onEvent = {}, nowMs = NOW)
                }
            }
            repeat(3) { rendered.render(it * 500_000_000L) }
            val image = rendered.render(2_000_000_000L)
            File(dir, "parity-$name.png").writeBytes(image.encodeToData()!!.bytes)
            rendered.close()
        }
    }

    private class Scene(val state: InvoicesUiState, val expect: List<String>)

    @Suppress("LongMethod") // A fixture list: one short scene per rebuilt screen.
    private fun scenes(): List<Pair<String, Scene>> = listOf(
        "credits" to Scene(
            base.copy(
                page = AccountantPage.Credits,
                creditNotes = listOf(
                    NOTE,
                    NOTE.copy(id = "c2", type = CreditNoteType.Dispute, status = CreditNoteStatus.Disputed),
                ),
            ),
            listOf("New Credit Note", "New Dispute", "Newest First"),
        ),
        "credit-form" to Scene(
            base.copy(
                page = AccountantPage.Credits,
                credit = CreditNotesUi(
                    form = CreditNoteForm(
                        vendorId = "v1",
                        lines = LineDraft(listOf(LINE), flagged = setOf("l1")),
                        errors = mapOf(CreditField.EffectiveDate to "Effective date is required"),
                        attachments = listOf(CreditAttachment("proof.pdf", 2048)),
                    ),
                ),
            ),
            listOf("Against Invoice Ref", "Create Credit Note", "Effective date is required", "proof.pdf"),
        ),
        "dispute-locked" to Scene(
            base.copy(
                page = AccountantPage.Credits,
                credit = CreditNotesUi(
                    form = CreditNoteForm(editingId = "c2", type = CreditNoteType.Dispute, locked = true),
                ),
            ),
            listOf("Edit Dispute", "Locked period"),
        ),
        "credit-preview" to Scene(
            base.copy(
                page = AccountantPage.Credits,
                creditNotes = listOf(NOTE),
                credit = CreditNotesUi(preview = NOTE),
            ),
            listOf("Apply Credit Note", "TAX AMT", "GROSS TOTAL", "AGAINST INVOICE"),
        ),
        "sales-list" to Scene(
            base.copy(page = AccountantPage.Sales, salesInvoices = salesRows()),
            listOf("Sales Invoices", "Create Invoice", "Invoice List"),
        ),
        "sales-preview" to Scene(
            base.copy(
                page = AccountantPage.Sales,
                salesInvoices = salesRows(),
                sales = SalesUi(
                    preview = salesRows().first(),
                    project = InvoiceProjectInfo(
                        projectName = "The Long Winter",
                        companyName = "Prod Co Ltd",
                        companyAddress = "12 Wardour Street, London W1D 6QF",
                        companyPhone = "+44 20 7946 0000",
                        companyEmail = "accounts@prodco.test",
                    ),
                ),
            ),
            listOf("BILLED TO", "Gross Total", "Mark Sent", "View PDF"),
        ),
        "sales-sheet" to Scene(
            base.copy(
                page = AccountantPage.Sales,
                salesDraft = SalesInvoiceDraft(
                    clientName = "Channel 4",
                    invoiceDate = "2026-09-23",
                    lines = LineDraft(listOf(LINE.copy(account = ""))),
                    lineError = "Line 1: account",
                ),
            ),
            listOf("Line 1: account", "Invoice Date"),
        ),
        "pre-approval" to Scene(
            base.copy(page = AccountantPage.Matching, invoices = matching()),
            listOf("MATCHED", "UNMATCHED", "awaiting query", "No PO · Urgent Wire Request"),
        ),
        "payments-grouped" to Scene(
            base.copy(page = AccountantPage.Payments, invoices = payable(), selected = setOf("p1")),
            listOf("Lamps Ltd", "Select invoices per vendor"),
        ),
        "payments-wires" to Scene(
            base.copy(page = AccountantPage.Payments, paymentTab = PaymentTab.Wires, invoices = payable()),
            listOf("Mark Paid", "Wires are paid manually"),
        ),
        "posted" to Scene(
            base.copy(page = AccountantPage.Posted, invoices = posted()),
            listOf("AWAITING PAYMENT", "TAX TOTAL", "with a split"),
        ),
        "analytics" to Scene(
            base.copy(
                page = AccountantPage.Analytics,
                analytics = InvoiceAnalytics(
                    vendors = listOf(VendorSpend("Lamps Ltd", "£800.00", 40.0)),
                    departmentSpend = listOf(VendorSpend("d1", "£1,200.00", 60.0)),
                ),
            ),
            listOf("Spend by Department", "Cost Report Impact of AP", "£1,200.00"),
        ),
        "enter-manual" to Scene(
            base.copy(
                page = AccountantPage.Inbox,
                enter = EnterInvoiceForm(tab = EnterTab.Manual, companyId = "co1", paid = true),
            ),
            listOf("Already paid", "Prod Co"),
        ),
    )

    private val base = InvoicesUiState(
        viewer = InvoiceViewer(
            canView = true,
            ready = true,
            departmentIdentifier = "department_accounts",
            designationIdentifier = "designation_production_accountant_accounts",
            overrideFlag = true,
        ),
        vendors = mapOf("v1" to Vendor("v1", "Lamps Ltd", email = "a@lamps.test"), "v2" to Vendor("v2", "Grip Co")),
        companies = listOf(Company("co1", "Prod Co")),
        banks = listOf(BankAccount("b1", "Main", entityId = "co1")),
    )

    private fun salesRows() = listOf(
        SalesInvoice(
            id = "s1",
            reference = "SI-482193",
            clientName = "Channel 4 Television Corporation",
            grossAmount = 14_400.0,
            currency = "GBP",
            status = SalesInvoiceStatus.Draft,
            invoiceDateMs = NOW,
            dueDateMs = NOW + 30L * 86_400_000L,
            createdAtMs = NOW,
            createdBy = "u1",
            payTerms = "30 days",
            clientAddress = ClientAddress(
                line1 = "124 Horseferry Road",
                city = "London",
                postalCode = "SW1P 2TX",
                country = "United Kingdom",
            ),
            lineItems = listOf(
                CodedLine("sl1", description = "Facility recharge — Stage 4", account = "7100", amount = 8_000.0, taxRate = 20.0),
                CodedLine("sl2", description = "Placement fee — episode 3", account = "7200", amount = 4_000.0, taxRate = 20.0),
            ),
            lineTaxAmounts = mapOf("sl1" to 1_600.0, "sl2" to 800.0),
        ),
        SalesInvoice(
            id = "s2",
            reference = "SI-482194",
            clientName = "Sky Studios",
            grossAmount = 7_200.0,
            currency = "GBP",
            status = SalesInvoiceStatus.Sent,
            invoiceDateMs = NOW - 40L * 86_400_000L,
            dueDateMs = NOW - 10L * 86_400_000L,
            createdAtMs = NOW - 40L * 86_400_000L,
        ),
        SalesInvoice(
            id = "s3",
            reference = "SI-482195",
            clientName = "Netflix UK",
            grossAmount = 22_500.0,
            currency = "GBP",
            status = SalesInvoiceStatus.Paid,
            invoiceDateMs = NOW - 60L * 86_400_000L,
            dueDateMs = NOW - 30L * 86_400_000L,
            createdAtMs = NOW - 60L * 86_400_000L,
        ),
    )

    private fun matching() = listOf(
        Invoice(
            id = "m1",
            invoiceNumber = "INV-1",
            vendorId = "v1",
            status = InvoiceStatus.Matching,
            linkedPos = listOf(LinkedPo("p", "PO-7", "v1", 100.0)),
        ),
        Invoice(
            id = "m2",
            invoiceNumber = "INV-2",
            vendorId = "v2",
            status = InvoiceStatus.Matching,
            poNumber = "PO-TYPED",
        ),
        Invoice(
            id = "m3",
            invoiceNumber = "INV-3",
            vendorId = "v2",
            status = InvoiceStatus.Matching,
            payMethod = PayMethod.Wire,
        ),
        Invoice(id = "m4", invoiceNumber = "INV-4", vendorId = "v1", status = InvoiceStatus.Held),
    )

    private fun payable() = listOf(
        Invoice(
            id = "p1",
            invoiceNumber = "INV-5",
            vendorId = "v1",
            grossAmount = 120.0,
            status = InvoiceStatus.ReadyToPay,
        ),
        Invoice(
            id = "p2",
            invoiceNumber = "INV-6",
            vendorId = "v1",
            grossAmount = 80.0,
            status = InvoiceStatus.ReadyToPay,
        ),
        Invoice(
            id = "p3",
            invoiceNumber = "INV-7",
            vendorId = "v2",
            grossAmount = 50.0,
            status = InvoiceStatus.ReadyToPay,
            payMethod = PayMethod.Wire,
        ),
    )

    private fun posted() = listOf(
        Invoice(
            id = "q1",
            invoiceNumber = "INV-8",
            grossAmount = 120.0,
            netAmount = 100.0,
            taxAmount = 20.0,
            status = InvoiceStatus.ReadyToPay,
        ),
        Invoice(id = "q2", invoiceNumber = "INV-9", grossAmount = 60.0, status = InvoiceStatus.Paid),
    )

    private companion object {
        const val NOW = 1_790_164_800_000L

        val LINE = CodedLine("l1", description = "Lamps", account = "2400", amount = 100.0, taxRate = 20.0)

        val NOTE = CreditNote(
            id = "c1",
            reference = "CN-001",
            vendorId = "v1",
            vendorName = "Lamps Ltd",
            reason = "Overcharged",
            grossAmount = 120.0,
            currency = "GBP",
            againstInvoice = "INV-1",
            effectiveDateMs = NOW,
            lineItems = listOf(LINE),
            attachments = listOf(
                CreditAttachment(
                    "proof.pdf",
                    2048,
                    stored = InvoiceAttachment("k", "b", "r", "proof.pdf", "file", "pdf"),
                ),
            ),
        )
    }
}
