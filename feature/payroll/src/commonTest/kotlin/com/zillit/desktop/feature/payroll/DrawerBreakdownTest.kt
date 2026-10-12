package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.toTimecard
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.DrawerBreakdown
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardAttachment
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the accountant's crew drawer lists below the open day, and the invoice shapes behind its footer. */
class DrawerBreakdownTest {

    /** Monday 2026-08-03, midnight UTC. */
    private val week = 1_785_715_200_000L
    private val day = PayPeriod.DAY_MILLIS

    private fun worked(index: Int, vararg rates: PayLine, type: String = "SWD") =
        TimecardDay(date = week + index * day, dayType = type, rates = rates.toList())

    // -- which day opens ------------------------------------------------------------------

    @Test
    fun `the drawer opens on today when today is in the week`() {
        val wednesdayNoon = week + 2 * day + 12 * 3_600_000
        assertEquals(2, DrawerBreakdown.defaultDay(week, listOf(worked(0)), wednesdayNoon))
        // The last day is still the week.
        assertEquals(6, DrawerBreakdown.defaultDay(week, emptyList(), week + 6 * day + 1))
    }

    @Test
    fun `a week that is over opens on its first day with anything on it`() {
        val later = week + 20 * day
        assertEquals(3, DrawerBreakdown.defaultDay(week, listOf(worked(3), worked(5)), later))
        assertEquals(0, DrawerBreakdown.defaultDay(week, emptyList(), later), "nothing at all: the first day")
        assertEquals(3, DrawerBreakdown.defaultDay(week, listOf(worked(3)), week - 3 * day), "a week not begun")
        assertEquals(0, DrawerBreakdown.defaultDay(null, emptyList(), later))
    }

    // -- the day cards --------------------------------------------------------------------

    @Test
    fun `every day on the document is a card, in date order, basic apart from the overtime`() {
        val late = worked(
            4,
            PayLine(identifier = "overtime", label = "OT", rateAmount = 60.0, workDuration = 90),
            PayLine(identifier = "basic", label = "Basic", rateAmount = 400.0),
        ).copy(
            loginTime = week + 4 * day + 6 * 3_600_000,
            logoutTime = week + 4 * day + 19 * 3_600_000,
            minutesWorked = 690,
            allowances = listOf(PayLine(identifier = "kit", label = "Kit", rateAmount = 25.0, isRental = true)),
        )
        val cards = DrawerBreakdown.dayCards(listOf(late, worked(1, PayLine(identifier = "basic", rateAmount = 300.0))))
        assertEquals(listOf("Tuesday", "Friday"), cards.map { it.weekday })
        val friday = cards.last()
        assertEquals(485.0, friday.total)
        assertEquals("Basic", friday.basic?.label)
        assertEquals(listOf("OT"), friday.ots.map { it.label })
        assertEquals(listOf("Kit"), friday.allowances.map { it.label })
        assertEquals("06:00", friday.callIn)
        assertEquals("19:00", friday.callOut)
        assertTrue(friday.showsTimes)
    }

    @Test
    fun `a rest day recedes, shows no times and a basic that paid nothing is not a row`() {
        val rest = worked(2, PayLine(identifier = "basic", rateAmount = 0.0), type = "REST")
            .copy(loginTime = week + 2 * day + 7 * 3_600_000)
        val sickUnpaid = worked(3, type = "Sick (Unpaid)")
        val untyped = TimecardDay(date = week + 4 * day, dayType = null)
        val cards = DrawerBreakdown.dayCards(listOf(rest, sickUnpaid, untyped))
        assertTrue(cards.all { it.isRest })
        assertFalse(cards.first().showsTimes, "a rest day's times are not drawn, even when it logged one")
        assertNull(cards.first().basic)
    }

    @Test
    fun `a day with no date cannot be placed and is left out`() {
        assertTrue(DrawerBreakdown.dayCards(listOf(TimecardDay(date = null, dayType = "SWD"))).isEmpty())
    }

    // -- the merged section ---------------------------------------------------------------

    @Test
    fun `daily allowances stay on their day card, the days' extras and the week's own lines are merged`() {
        val monday = worked(0).copy(
            allowances = listOf(PayLine(identifier = "perdiem", label = "Per Diem", rateAmount = 25.0)),
            extras = listOf(PayLine(identifier = "stunt", label = "Stunt", rateAmount = 40.0, kind = "upgrade")),
        )
        val card = PayrollTimecard(
            id = "t",
            userId = "u",
            status = TimecardStatus.Approved,
            weekStarting = week,
            days = listOf(monday),
            weeklyAllowances = listOf(
                PayLine(identifier = "mileage", label = "Mileage", rateAmount = 0.5, qty = 100.0),
                PayLine(identifier = "kit", label = "Kit", rateAmount = 30.0, isRental = true),
            ),
            weeklyExtras = listOf(PayLine(label = "Meal Money", rateAmount = 10.0)),
        )
        val merged = DrawerBreakdown.mergedEntries(card)
        assertEquals(listOf("Mileage"), merged.allowances.map { it.label }, "no Per Diem: it has its day card")
        assertEquals(50.0, merged.allowances.single().amount)
        assertFalse(merged.allowances.single().daily)
        assertEquals(listOf("Kit"), merged.rentals.map { it.label })
        assertEquals(listOf("Stunt", "Meal Money"), merged.extras.map { it.label })
        assertEquals(listOf("upgrade", "extra"), merged.extras.map { it.kind })
        assertTrue(merged.extras.first().daily)
        assertNull(merged.allowances.single().kind, "only an extra carries a kind")
    }

    // -- the pills ------------------------------------------------------------------------

    @Test
    fun `a claim is cash when it came from a batch, and a deduction rate prints as the web does`() {
        assertTrue(DrawerBreakdown.isCash(ClaimLine("c", "Taxi", 20.0, null, null, "b1")))
        assertFalse(DrawerBreakdown.isCash(ClaimLine("c", "Taxi", 20.0, null, null, null)))
        assertFalse(DrawerBreakdown.isCash(ClaimLine("c", "Taxi", 20.0, null, null, "")))
        assertEquals("12%", DrawerBreakdown.percentLabel(12.0))
        assertEquals("7.5%", DrawerBreakdown.percentLabel(7.5))
    }

    // -- the invoice, in both shapes the wire carries --------------------------------------

    private fun timecard(attachment: String) = assertNotNull(
        (Json.parseToJsonElement("""{"_id":"t","user_id":"u","status":"approved","attachment":$attachment}""")
            as JsonObject).toTimecard(),
    )

    @Test
    fun `an invoice with a link opens as it stands`() {
        val link = timecard("""{"name":"inv.pdf","url":"https://x/inv.pdf"}""").attachment
        assertEquals(TimecardAttachment(name = "inv.pdf", url = "https://x/inv.pdf"), link)
        assertTrue(assertNotNull(link).isOpenable)
        assertFalse(link.isStored)
    }

    @Test
    fun `an invoice the owner's card uploaded is a stored file to presign`() {
        val stored = assertNotNull(
            timecard(
                """{"media":"u/inv.pdf","bucket":"zillit","region":"eu-west-2","name":"inv.pdf",
                    "content_type":"document","content_subtype":"pdf"}""",
            ).attachment,
        )
        assertEquals("u/inv.pdf", stored.media)
        assertEquals("zillit", stored.bucket)
        assertEquals("eu-west-2", stored.region)
        assertNull(stored.url)
        assertTrue(stored.isStored)
        assertTrue(stored.isOpenable)
    }

    @Test
    fun `a stored invoice missing its bucket cannot be presigned`() {
        val partial = assertNotNull(
            timecard("""{"media":"u/inv.pdf","region":"eu-west-2","name":"inv.pdf"}""").attachment,
        )
        assertFalse(partial.isStored)
        assertFalse(partial.isOpenable)
    }

    @Test
    fun `an older bare link and an empty attachment`() {
        assertEquals("https://x/y.pdf", timecard("\"https://x/y.pdf\"").attachment?.url)
        assertNull(timecard("{}").attachment)
        assertNull(timecard("null").attachment)
    }

    @Test
    fun `an extra line carries its kind off the wire`() {
        val doc = Json.parseToJsonElement(
            """{"_id":"t","user_id":"u","status":"approved","weekly_additional_fees":
               [{"label":"Stunt","rate_amount":40,"kind":"upgrade"}]}""",
        ) as JsonObject
        assertEquals("upgrade", doc.toTimecard()?.weeklyExtras?.single()?.kind)
    }
}
