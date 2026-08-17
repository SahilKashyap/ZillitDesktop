package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.BasicRateDetails
import com.zillit.desktop.feature.dealmemo.domain.RateCardEntry
import com.zillit.desktop.feature.dealmemo.domain.RateCascade
import com.zillit.desktop.feature.dealmemo.domain.RateSource
import com.zillit.desktop.feature.dealmemo.domain.RateTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rate cascade, pinned.
 *
 * Getting this wrong puts a crew member on the wrong scale, which surfaces
 * weeks later as a payroll dispute rather than as anything the app would
 * notice. Each case here is a rule collective agreements actually rely on.
 */
class RateCascadeTest {

    private fun entry(
        department: String = "camera",
        designation: String = "gaffer",
        productionType: String? = "feature",
        minBudget: Double? = null,
        maxBudget: Double? = null,
        weekly: RateTier? = RateTier(2_400.0, 2_200.0, 3_000.0, 50.0),
        daily: RateTier? = RateTier(480.0, 440.0, 600.0, 10.0),
    ) = RateCardEntry(
        id = "rate-1",
        departmentIdentifier = department,
        designationIdentifier = designation,
        branchIdentifier = "bectu-camera",
        unionIdentifier = "bectu",
        productionType = productionType,
        currency = "GBP",
        minBudget = minBudget,
        maxBudget = maxBudget,
        hourly = null,
        daily = daily,
        weekly = weekly,
    )

    @Test
    fun `a designation's own rate wins over the agreement's scale`() {
        val agreement = BasicRateDetails(weekly = RateTier(2_000.0, 1_900.0, 2_500.0, 50.0))
        val resolved = RateCascade.resolve(entry(), agreement)

        assertEquals(2_400.0, resolved.weekly?.baseRate)
        assertEquals(RateSource.Designation, resolved.weekly?.baseSource)
    }

    @Test
    fun `a role publishing only hours still takes its rate from the agreement`() {
        // The commonest authoring shape: a negotiated designation carries its
        // contracted day length, and the union-wide scale is the rate.
        val hoursOnly = entry(weekly = RateTier(null, null, null, 55.0))
        val agreement = BasicRateDetails(weekly = RateTier(2_000.0, 1_900.0, 2_500.0, 50.0))
        val resolved = RateCascade.resolve(hoursOnly, agreement)

        assertEquals(2_000.0, resolved.weekly?.baseRate)
        assertEquals(RateSource.Agreement, resolved.weekly?.baseSource)
        // The hours stay the designation's — that is the half it published.
        assertEquals(55.0, resolved.weekly?.workHours)
    }

    @Test
    fun `the fallback is per field, not per tier`() {
        // Rate from the designation, envelope from the agreement: taking the
        // whole tier from one side would lose one of the two.
        val baseOnly = entry(weekly = RateTier(2_400.0, null, null, null))
        val agreement = BasicRateDetails(weekly = RateTier(2_000.0, 1_900.0, 2_500.0, 50.0))
        val resolved = RateCascade.resolve(baseOnly, agreement)

        assertEquals(2_400.0, resolved.weekly?.baseRate)
        assertEquals(RateSource.Designation, resolved.weekly?.baseSource)
        assertEquals(1_900.0, resolved.weekly?.minRate)
        assertEquals(RateSource.Agreement, resolved.weekly?.envelopeSource)
    }

    @Test
    fun `a role with no published rate resolves entirely to the agreement`() {
        val agreement = BasicRateDetails(
            weekly = RateTier(2_000.0, 1_900.0, 2_500.0, 50.0),
            daily = RateTier(400.0, null, null, 10.0),
        )
        val resolved = RateCascade.resolve(null, agreement)

        assertEquals(2_000.0, resolved.weekly?.baseRate)
        assertEquals(400.0, resolved.daily?.baseRate)
        assertTrue(resolved.hasAnything)
    }

    @Test
    fun `nothing published anywhere resolves to nothing`() {
        val resolved = RateCascade.resolve(null, null)

        assertNull(resolved.weekly)
        assertFalse(resolved.hasAnything)
    }

    @Test
    fun `the tier a deal binds to is the one whose hours match the agreement`() {
        // A card may publish both a continuous and a standard working day; the
        // agreement decides which contracted week this deal is under.
        val published = listOf(
            RateTier(480.0, null, null, 10.0, dayType = "CWD"),
            RateTier(520.0, null, null, 11.0, dayType = "SWD"),
        )

        assertEquals(520.0, RateCascade.pickTier(published, agreementHours = 11.0)?.baseRate)
        assertEquals(480.0, RateCascade.pickTier(published, agreementHours = 10.0)?.baseRate)
    }

    @Test
    fun `with no agreement hours the first published tier stands in`() {
        val published = listOf(RateTier(480.0, null, null, 10.0), RateTier(520.0, null, null, 11.0))

        assertEquals(480.0, RateCascade.pickTier(published, agreementHours = null)?.baseRate)
        // An hours value nothing matches falls back rather than refusing.
        assertEquals(480.0, RateCascade.pickTier(published, agreementHours = 12.0)?.baseRate)
        assertNull(RateCascade.pickTier(emptyList(), agreementHours = 10.0))
    }

    @Test
    fun `a budget envelope is inclusive at both ends and open where unset`() {
        val banded = entry(minBudget = 1_000_000.0, maxBudget = 5_000_000.0)

        assertTrue(banded.coversBudget(1_000_000.0))
        assertTrue(banded.coversBudget(5_000_000.0))
        assertFalse(banded.coversBudget(999_999.0))
        assertFalse(banded.coversBudget(5_000_001.0))
        // An unstated budget matches anything, which is what omitting it means.
        assertTrue(banded.coversBudget(null))
        // An entry with no bounds is the card's default scale.
        assertTrue(entry().coversBudget(50_000_000.0))
    }

    @Test
    fun `narrowing a card matches on the axes given and ignores the rest`() {
        val entries = listOf(
            entry(designation = "gaffer"),
            entry(designation = "best-boy"),
            entry(designation = "gaffer", productionType = "tv"),
            // A rate with no production type applies to every production.
            entry(designation = "gaffer", productionType = null),
        )

        val forFeature = RateCascade.applicable(
            entries,
            departmentIdentifier = "camera",
            designationIdentifier = "gaffer",
            productionType = "feature",
            budget = null,
        )
        assertEquals(2, forFeature.size)
    }

    @Test
    fun `a rate outside the agreement's envelope is caught`() {
        val resolved = RateCascade.resolve(entry(), null)

        assertTrue(RateCascade.withinEnvelope(resolved.weekly, 2_400.0))
        assertTrue(RateCascade.withinEnvelope(resolved.weekly, 2_200.0))
        assertFalse(RateCascade.withinEnvelope(resolved.weekly, 2_199.0))
        assertFalse(RateCascade.withinEnvelope(resolved.weekly, 3_001.0))
    }

    @Test
    fun `no envelope permits anything`() {
        // A production that publishes no floor cannot be under it.
        val open = RateCascade.resolve(entry(weekly = RateTier(2_400.0, null, null, 50.0)), null)

        assertTrue(RateCascade.withinEnvelope(open.weekly, 1.0))
        assertTrue(RateCascade.withinEnvelope(null, 1.0))
    }

    @Test
    fun `the day type comes from the designation before the agreement`() {
        val withDayType = entry(daily = RateTier(480.0, null, null, 10.0, dayType = "CWD"))
        val agreement = BasicRateDetails(daily = RateTier(400.0, null, null, 10.0), dayType = "SWD")

        assertEquals("CWD", RateCascade.resolve(withDayType, agreement).dayType)
        assertEquals("SWD", RateCascade.resolve(null, agreement).dayType)
    }
}
