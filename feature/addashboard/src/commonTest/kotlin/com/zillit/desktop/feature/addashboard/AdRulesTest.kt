package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AD dashboard's own rules — the ones that decide whether a day can be
 * changed and what an artiste is booked as.
 */
class AdRulesTest {

    // -- dates -----------------------------------------------------------------

    /**
     * Every date on this service is UTC midnight, deliberately: a unit
     * shooting across midnight in another zone still files one day. A local
     * midnight puts artistes on the wrong date.
     */
    @Test
    fun `a moment flattens to the UTC midnight that contains it`() {
        val midMorning = 1_772_800_000_000L
        val midnight = AdDates.utcMidnight(midMorning)

        assertEquals(0L, midnight % AdDates.MILLIS_PER_DAY)
        assertTrue(midnight <= midMorning)
        assertTrue(midMorning - midnight < AdDates.MILLIS_PER_DAY)
    }

    @Test
    fun `a midnight is already flat`() {
        val midnight = 1_772_755_200_000L

        assertEquals(midnight, AdDates.utcMidnight(midnight))
    }

    @Test
    fun `the last millisecond of a day still belongs to it`() {
        val midnight = 1_772_755_200_000L
        val lastMoment = midnight + AdDates.MILLIS_PER_DAY - 1

        assertEquals(midnight, AdDates.utcMidnight(lastMoment))
    }

    /** Dates before the epoch must floor downwards, not truncate towards zero. */
    @Test
    fun `a pre-epoch moment floors to the midnight below it`() {
        val beforeEpoch = -1L

        val midnight = AdDates.utcMidnight(beforeEpoch)

        assertEquals(-AdDates.MILLIS_PER_DAY, midnight)
        assertTrue(midnight <= beforeEpoch)
    }

    // -- the lock --------------------------------------------------------------

    /**
     * The single source of truth every editor derives its read-only flag
     * from. Submitting a day locks it for everyone, including whoever sent it.
     */
    @Test
    fun `submitted, approved and published are locked, the rest are not`() {
        val locked = AdDayStatus.entries.filter { it.locked }.map { it.wire }

        assertEquals(listOf("submitted", "approved", "published"), locked)
    }

    @Test
    fun `a day is editable exactly while it is unlocked`() {
        assertTrue(AdShootDay(id = "d1", status = AdDayStatus.Draft).editable)
        assertTrue(AdShootDay(id = "d1", status = AdDayStatus.InProgress).editable)
        assertTrue(AdShootDay(id = "d1", status = AdDayStatus.Wrapped).editable)
        assertFalse(AdShootDay(id = "d1", status = AdDayStatus.Submitted).editable)
        assertFalse(AdShootDay(id = "d1", status = AdDayStatus.Published).editable)
    }

    /** An unrecognised status must not lock a day nobody can then unlock. */
    @Test
    fun `an unknown status leaves the day editable`() {
        assertEquals(AdDayStatus.Unknown, AdDayStatus.from("gone_home"))
        assertTrue(AdShootDay(id = "d1", status = AdDayStatus.from("gone_home")).editable)
    }

    // -- the register ----------------------------------------------------------

    /**
     * A verified artiste has been through onboarding and may already carry
     * signed days, so the delete control is not offered for them.
     */
    @Test
    fun `a verified artiste cannot be deleted`() {
        fun artiste(status: ArtisteStatus) = Artiste(id = "a1", name = "Ada", status = status)

        assertFalse(artiste(ArtisteStatus.Verified).deletable)
        assertTrue(artiste(ArtisteStatus.Pending).deletable)
        assertTrue(artiste(ArtisteStatus.Draft).deletable)
        assertTrue(artiste(ArtisteStatus.Blocked).deletable)
    }

    @Test
    fun `an unrecognised artiste status reads as pending`() {
        assertEquals(ArtisteStatus.Pending, ArtisteStatus.from("archived"))
        assertEquals(ArtisteStatus.Pending, ArtisteStatus.from(null))
    }

    /** Every artiste is booked as something; a blank cell is less use than the commonest. */
    @Test
    fun `an unrecognised category falls to General SA`() {
        assertEquals(ArtisteCategory.StandIn, ArtisteCategory.from("stand_in"))
        assertEquals(ArtisteCategory.GeneralSa, ArtisteCategory.from("stunt_double"))
        assertEquals(ArtisteCategory.GeneralSa, ArtisteCategory.from(null))
    }

    @Test
    fun `every category round-trips through its wire value`() {
        ArtisteCategory.entries.forEach {
            assertEquals(it, ArtisteCategory.from(it.wire), "${it.wire} did not survive")
        }
    }

    /**
     * Engagement defaults by where the artiste came from: an external one is
     * an agency booking, an internal one is on the production's payroll.
     */
    @Test
    fun `engagement falls back on whether the artiste is external`() {
        assertEquals(EngagementType.Agency, EngagementType.from(null, isExternal = true))
        assertEquals(EngagementType.DirectPaye, EngagementType.from(null, isExternal = false))
        // An explicit value wins over the guess either way.
        assertEquals(EngagementType.DirectPaye, EngagementType.from("direct_paye", isExternal = true))
        assertEquals(EngagementType.Agency, EngagementType.from("agency", isExternal = false))
    }

    // -- the day's rows --------------------------------------------------------

    private fun entry(signStatus: String = "") =
        SupportingArtistDay(id = "s1", signStatus = signStatus)

    @Test
    fun `a day is signed either way it can be signed`() {
        assertTrue(entry("typed").signed)
        assertTrue(entry("external_sign").signed)
        assertFalse(entry("").signed)
        assertFalse(entry("pending").signed)
    }

    @Test
    fun `how it was signed is named for the column that says so`() {
        assertEquals("Typed signature", entry("typed").signMethod)
        assertEquals("External signature", entry("external_sign").signMethod)
        assertEquals("", entry("").signMethod, "an unsigned day names no method")
    }

    @Test
    fun `attendance falls to unknown rather than booked`() {
        assertEquals(AttendanceStatus.Present, AttendanceStatus.from("present"))
        assertEquals(AttendanceStatus.NoShow, AttendanceStatus.from("no_show"))
        // Not Booked: "we do not know" and "they are booked" are different
        // facts, and a call sheet that invents the second is worse.
        assertEquals(AttendanceStatus.Unknown, AttendanceStatus.from("wandered_off"))
        assertEquals(AttendanceStatus.Unknown, AttendanceStatus.from(null))
    }
}
