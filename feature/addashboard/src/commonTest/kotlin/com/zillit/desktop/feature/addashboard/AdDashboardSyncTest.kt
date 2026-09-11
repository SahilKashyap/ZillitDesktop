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
 * It had no subscription of any kind, so an artiste marked present or a day
 * submitted elsewhere never showed. These pin the fan-out the web's handler
 * map defines, and the two exclusions.
 */
class AdDashboardSyncTest {

    private fun kinds(event: String) = AD_REFRESH_BY_EVENT[SocketEventName(event)]

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

    /** Submitting locks the day's rows and changes its row in the list. */
    @Test
    fun `a day header change moves both the day and the days list`() {
        assertEquals(setOf(AdRefresh.Today, AdRefresh.Days), kinds("ad_dashboard:ad_shoot_day:updated"))
        assertEquals(setOf(AdRefresh.Today, AdRefresh.Days), kinds("ad_dashboard:ad_shoot_day:submitted"))
    }

    /** Reports, templates and rates have no page here to reload. */
    @Test
    fun `the unported pages are not subscribed`() {
        assertNull(kinds("ad_dashboard:ad_report:list"))
        assertNull(kinds("ad_dashboard:report_template:list"))
        assertNull(kinds("ad_dashboard:ad_rate_config:updated"))
    }

    /** Queries belong to the portal's own page, in its own module. */
    @Test
    fun `query events are not this map's business`() {
        assertNull(kinds("ad_dashboard:supporting_artiste:query:opened"))
    }

    /** Every page answers to exactly one kind, and every kind has a page. */
    @Test
    fun `each page maps to its own refresh kind`() {
        assertEquals(
            AdRefresh.entries.toSet(),
            AdDestination.entries.map { it.refresh }.toSet(),
        )
        assertTrue(AD_REFRESH_BY_EVENT.values.flatten().toSet() == AdRefresh.entries.toSet())
    }
}
