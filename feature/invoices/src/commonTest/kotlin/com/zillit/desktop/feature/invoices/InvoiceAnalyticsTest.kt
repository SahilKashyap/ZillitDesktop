package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.parseAnalytics
import com.zillit.desktop.feature.invoices.domain.AgeingBucket
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The analytics payload, and the creditors report's ageing. */
class InvoiceAnalyticsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `analytics decodes the server's camelCase and its formatted amounts`() {
        val analytics = parseAnalytics(
            json.parseToJsonElement(
                """
                {"stats":{"totalApSpend":"£18,000.00","totalApSubtitle":"production total",
                  "avgInvoice":"£420.00","onTimePayment":"92%","apDays":"14"},
                 "summary":{"postedAmount":"£10,000.00","pendingAmount":"£8,000.00",
                  "unknownAmount":"£0.00","projectedAmount":"£18,000.00"},
                 "depts":[{"code":"d1","name":"Camera","posted":"£5,000","pending":"£900",
                  "unknown":"£0","projected":"£5,900","variance":"+18%","over":true}],
                 "suppliers":[{"name":"Panavision","amount":"£9,000.00","pct":50}],
                 "totals":{"posted":"£10,000.00","pending":"£8,000.00","unknown":"£0.00",
                  "projected":"£18,000.00"}}
                """.trimIndent(),
            ),
        )
        assertEquals("£18,000.00", analytics.stats.totalApSpend)
        assertEquals("production total", analytics.stats.totalApSubtitle)
        assertEquals("£8,000.00", analytics.summary.pending)
        val department = analytics.departments.single()
        assertEquals("Camera", department.name)
        assertEquals("+18%", department.variance)
        assertTrue(department.isOver)
        assertEquals(50.0, analytics.vendors.single().percent)
        assertEquals("£18,000.00", analytics.totals.projected)
    }

    @Test
    fun `an empty analytics body is an empty page`() {
        val empty = parseAnalytics(json.parseToJsonElement("{}"))
        assertEquals("", empty.stats.totalApSpend)
        assertTrue(empty.departments.isEmpty())
        assertTrue(empty.vendors.isEmpty())
    }

    /** Age is measured from the invoice date, and the boundaries are inclusive. */
    @Test
    fun `the ageing buckets are the web's`() {
        val now = 100L * DAY
        assertEquals(AgeingBucket.Current, AgeingBucket.of(now - 29 * DAY, now))
        assertEquals(AgeingBucket.Days30, AgeingBucket.of(now - 30 * DAY, now))
        assertEquals(AgeingBucket.Days30, AgeingBucket.of(now - 59 * DAY, now))
        assertEquals(AgeingBucket.Days60, AgeingBucket.of(now - 60 * DAY, now))
        assertEquals(AgeingBucket.Current, AgeingBucket.of(null, now), "an undated invoice is current")
    }

    @Test
    fun `creditors group by vendor, by age, biggest first`() {
        val now = 100L * DAY
        val rows = Creditors.rows(
            invoices = listOf(
                invoice("i1", "v1", 100.0, now - 5 * DAY),
                invoice("i2", "v1", 200.0, now - 40 * DAY),
                invoice("i3", "v1", 300.0, now - 90 * DAY),
                invoice("i4", "v2", 50.0, now - 1 * DAY),
            ),
            nowMs = now,
            nameOf = { if (it.vendorId == "v1") "Panavision" else "Movietech" },
            termsOf = { if (it.vendorId == "v1") "45 days" else null },
        )
        assertEquals(listOf("Panavision", "Movietech"), rows.map { it.vendor }, "the biggest debt first")
        val panavision = rows.first()
        assertEquals(100.0, panavision.current)
        assertEquals(200.0, panavision.days30)
        assertEquals(300.0, panavision.days60)
        assertEquals(600.0, panavision.total)
        assertEquals("45 days", panavision.terms)
        assertEquals("30 days", rows.last().terms, "a vendor with no terms takes the web's default")
    }

    /** The four statuses the web counts as owed. */
    @Test
    fun `the open statuses are the web's four`() {
        assertEquals(
            listOf(InvoiceStatus.Approved, InvoiceStatus.Override, InvoiceStatus.Entry, InvoiceStatus.ReadyToPay),
            Creditors.OPEN_STATUSES,
        )
    }

    private fun invoice(id: String, vendorId: String, gross: Double, dateMs: Long) = Invoice(
        id = id,
        vendorId = vendorId,
        grossAmount = gross,
        currency = "GBP",
        invoiceDateMs = dateMs,
        status = InvoiceStatus.Approved,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
