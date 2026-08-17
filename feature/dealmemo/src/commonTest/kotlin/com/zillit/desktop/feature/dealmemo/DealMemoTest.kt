package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.Deal
import com.zillit.desktop.feature.dealmemo.domain.DealRates
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DealViewer
import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import com.zillit.desktop.feature.dealmemo.ui.DealDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun deal(
    rates: DealRates = DealRates(weeklyRate = 2_000.0),
    amendedAt: Long? = null,
    acknowledgedAt: Long? = null,
    status: DealStatus = DealStatus.Active,
) = Deal(
    id = "deal-1",
    userId = "user-1",
    crewName = "Ada",
    email = null,
    departmentId = null,
    departmentName = "Camera",
    designation = "Gaffer",
    status = status,
    currency = "GBP",
    rates = rates,
    startDate = null,
    endDate = null,
    unionName = null,
    agreementName = null,
    nominalCode = null,
    notes = null,
    amendedAt = amendedAt,
    acknowledgedAt = acknowledgedAt,
    createdAt = null,
)

class DealAcknowledgementTest {

    @Test
    fun `a deal nobody has confirmed is not acknowledged`() {
        assertFalse(deal().acknowledged)
        assertFalse(deal().awaitingReacknowledgement)
    }

    @Test
    fun `a confirmed deal that was never amended stays acknowledged`() {
        val confirmed = deal(acknowledgedAt = 1_000)
        assertTrue(confirmed.acknowledged)
        assertFalse(confirmed.awaitingReacknowledgement)
    }

    @Test
    fun `an amendment after the confirmation invalidates it`() {
        // The person agreed to different terms, so the agreement no longer
        // covers what the deal now says.
        val amended = deal(acknowledgedAt = 1_000, amendedAt = 2_000)

        assertFalse(amended.acknowledged)
        assertTrue(amended.awaitingReacknowledgement)
    }

    @Test
    fun `re-confirming after an amendment restores it`() {
        val reconfirmed = deal(amendedAt = 2_000, acknowledgedAt = 3_000)

        assertTrue(reconfirmed.acknowledged)
        assertFalse(reconfirmed.awaitingReacknowledgement)
    }

    @Test
    fun `a confirmation at the same instant as the amendment counts`() {
        assertTrue(deal(amendedAt = 2_000, acknowledgedAt = 2_000).acknowledged)
    }

    @Test
    fun `an amendment with no prior confirmation is not awaiting one`() {
        // Nobody has agreed to anything yet, so there is nothing to re-confirm
        // — the deal simply has not been acknowledged.
        val amended = deal(amendedAt = 2_000, acknowledgedAt = null)

        assertFalse(amended.acknowledged)
        assertFalse(amended.awaitingReacknowledgement)
    }
}

class DealRatesTest {

    @Test
    fun `a weekly rate is the estimated week`() {
        assertEquals(2_000.0, DealRates(weeklyRate = 2_000.0).estimatedWeek)
    }

    @Test
    fun `a daily rate times the agreed days stands in for a weekly one`() {
        assertEquals(2_400.0, DealRates(dailyRate = 400.0, daysPerWeek = 6.0).estimatedWeek)
    }

    @Test
    fun `a weekly rate wins over a computed one`() {
        val both = DealRates(weeklyRate = 2_000.0, dailyRate = 400.0, daysPerWeek = 6.0)
        assertEquals(2_000.0, both.estimatedWeek)
    }

    @Test
    fun `neither known yields zero, which the screen renders as unknown`() {
        assertEquals(0.0, DealRates().estimatedWeek)
        // A daily rate with no agreed days cannot be turned into a week.
        assertEquals(0.0, DealRates(dailyRate = 400.0).estimatedWeek)
    }
}

class DealAccessTest {

    @Test
    fun `only senior accountants may write deals`() {
        assertTrue(
            DealViewer("u", "department_accounts", "designation_production_accountant_accounts").canWriteDeals,
        )
        assertTrue(DealViewer("u", "department_accounts", "Financial Controller").canWriteDeals)
        assertFalse(DealViewer("u", "department_accounts", "Assistant Accountant").canWriteDeals)
        assertFalse(DealViewer("u", "department_camera", "Financial Controller").canWriteDeals)
    }

    @Test
    fun `crew see their own deal and nothing else`() {
        val crew = DealViewer("u", "department_camera", null)

        assertTrue(DealDestination.MyDeal.visibleTo(crew))
        // Everyone's rates in one list is exactly what must not leak.
        assertFalse(DealDestination.AllDeals.visibleTo(crew))
        assertFalse(DealDestination.Create.visibleTo(crew))
    }

    @Test
    fun `entering from the tools grid closes the production list`() {
        val fromGrid = DealViewer("u", "department_accounts", "Financial Controller", enteredAsTool = true)

        assertFalse(fromGrid.canWriteDeals)
        assertFalse(DealDestination.AllDeals.visibleTo(fromGrid))
    }

    @Test
    fun `a new deal names the first thing wrong with it`() {
        val blank = NewDeal("", "", null, null, null, DealRates(), null, null, null, null, null, null)
        assertEquals("Choose the crew member this deal is for.", blank.validationError())

        val noRate = blank.copy(userId = "user-1")
        assertEquals("A deal needs a weekly or a daily rate.", noRate.validationError())

        val backwards = noRate.copy(
            rates = DealRates(weeklyRate = 2_000.0),
            startDate = 2_000,
            endDate = 1_000,
        )
        assertEquals("The end date cannot be before the start date.", backwards.validationError())

        assertNull(backwards.copy(endDate = 3_000).validationError())
        // A daily rate alone is enough.
        assertNull(noRate.copy(rates = DealRates(dailyRate = 400.0)).validationError())
    }

    @Test
    fun `unknown statuses degrade rather than throw`() {
        assertEquals(DealStatus.Unknown, DealStatus.from("brand_new"))
        assertEquals(DealStatus.Acknowledged, DealStatus.from("ACKNOWLEDGED"))
        assertTrue(DealStatus.Active.isLive)
        assertFalse(DealStatus.Draft.isLive)
        assertTrue(DealStatus.Draft.isEditable)
    }
}
