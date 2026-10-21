package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.addashboard.data.AD_REFRESH_BY_EVENT
import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard an AD watches while a unit shoots.
 *
 * It had no subscription of any kind until 2026-09-09, so an artiste marked
 * present or a day submitted elsewhere never showed. These pin the fan-out the
 * web's handler map defines: each event refreshes exactly what the same action
 * performed locally would.
 */
class AdDashboardSyncTest {

    private fun kinds(event: String): Set<AdRefresh>? = AD_REFRESH_BY_EVENT[SocketEventName(event)]

    @Test
    fun `the register follows the artiste record`() {
        assertEquals(setOf(AdRefresh.Register), kinds("ad_dashboard:artiste:created"))
        assertEquals(setOf(AdRefresh.Register), kinds("ad_dashboard:artiste:updated"))
        assertEquals(setOf(AdRefresh.Register), kinds("ad_dashboard:artiste:deleted"))
    }

    /** Adding to the day can create the artiste, so both lists move. */
    @Test
    fun `adding to the day touches the day and the register`() {
        assertEquals(
            setOf(AdRefresh.Today, AdRefresh.Register),
            kinds("ad_dashboard:today_list:artiste_added"),
        )
        assertEquals(
            setOf(AdRefresh.Today, AdRefresh.Register),
            kinds("ad_dashboard:add_extras:artiste_added"),
        )
    }

    @Test
    fun `attendance and meals reload the open day`() {
        listOf(
            "ad_dashboard:shoot_schedule:artiste_present",
            "ad_dashboard:shoot_schedule:artiste_absent",
            "ad_dashboard:shoot_schedule:artiste_wrapped",
            "ad_dashboard:shoot_schedule:artiste_meal_added",
            "ad_dashboard:shoot_schedule:artiste_meal_updated",
            "ad_dashboard:shoot_schedule:artiste_meal_deleted",
            "ad_dashboard:ad_shoot_day:meal_added",
            "ad_dashboard:ad_shoot_day:meal_deleted",
        ).forEach { assertEquals(setOf(AdRefresh.Today), kinds(it), it) }
    }

    @Test
    fun `a day header change moves both the day and the days list`() {
        assertEquals(setOf(AdRefresh.Today, AdRefresh.Days), kinds("ad_dashboard:ad_shoot_day:updated"))
    }

    /**
     * Submitting a day is what puts a purchase order on it, so the queue moves
     * with it — the server derives that queue from the day headers.
     */
    @Test
    fun `submitting a day also moves the PO queue`() {
        assertEquals(
            setOf(AdRefresh.Today, AdRefresh.Days, AdRefresh.Queue),
            kinds("ad_dashboard:ad_shoot_day:submitted"),
        )
    }

    /** Both sides' verbs are kept exactly as they are sent; neither is unified. */
    @Test
    fun `query activity reloads the threads from either side`() {
        listOf(
            "ad_dashboard:supporting_artiste:query:opened",
            "ad_dashboard:supporting_artiste:query:replied",
            "ad_dashboard:supporting_artiste:query:resolved",
            "sa:query:opened",
            "sa:query:received",
            "sa:query:resolved",
        ).forEach { assertEquals(setOf(AdRefresh.Queries), kinds(it), it) }
    }

    /**
     * Rates and the agency lists move together: the pickers read the agencies
     * through the rate config, so a vendor change is a rates change here.
     */
    @Test
    fun `rates and vendor events reload the config`() {
        assertEquals(setOf(AdRefresh.Rates), kinds("ad_dashboard:ad_rate_config:updated"))
        listOf(
            "vendor:created",
            "vendor:updated",
            "vendor:verified",
            "vendor:unverified",
            "vendor:deleted",
        ).forEach { assertEquals(setOf(AdRefresh.Rates), kinds(it), it) }
    }

    /**
     * One event covers reports **and** templates.
     *
     * The backend sends no second event, so a remote template edit also
     * reloads an open report — the web fans the one event out to both of its
     * keys and so does this.
     */
    @Test
    fun `one event covers reports and templates`() {
        assertEquals(setOf(AdRefresh.Reports), kinds("ad_dashboard:ad_report:list"))
    }

    /**
     * Both spellings of the signature reset are registered.
     *
     * The web's own handler comment says the name is unconfirmed, so a reset
     * lands whichever it turns out to be rather than being missed.
     */
    @Test
    fun `a signature reset lands under either spelling`() {
        val expected = setOf(AdRefresh.Signatures, AdRefresh.Reports)
        assertEquals(expected, kinds("ad-report:signatures-voided"))
        assertEquals(expected, kinds("ad_dashboard:ad_report:signatures_voided"))
    }

    @Test
    fun `an event nobody sends is not subscribed`() {
        assertNull(kinds("ad_dashboard:artiste:wandered_off"))
    }

    /** Every refresh kind has at least one event that fires it, and one page that answers. */
    @Test
    fun `every refresh kind is both fired and answered`() {
        val fired = AD_REFRESH_BY_EVENT.values.flatten().toSet()
        assertEquals(AdRefresh.entries.toSet(), fired, "a kind nothing fires is dead code")

        val answered = AdDestination.entries.flatMap { it.refresh }.toSet()
        assertEquals(
            AdRefresh.entries.toSet(),
            answered,
            "a kind no page answers reloads nothing",
        )
    }

    /** Budget has nothing to reload, because it has nothing to show. */
    @Test
    fun `the placeholder page subscribes to nothing`() {
        assertTrue(AdDestination.Budget.refresh.isEmpty())
    }
}
