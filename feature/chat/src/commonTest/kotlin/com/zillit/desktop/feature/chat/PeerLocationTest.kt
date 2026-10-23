package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.database.UserLocation
import com.zillit.desktop.core.database.UserLocationDay
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.LocationAccess
import com.zillit.desktop.feature.chat.domain.locationAccess
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contact panel's Location gate — Android's
 * `LocationTrackingViewExtensions.handleUserLocationTracking`, which the
 * web's `InfoSider.handleLocationRedirect` states a shorter version of.
 */
class PeerLocationTest {

    private val zone = TimeZone.UTC

    /** 2026-10-21 is a Wednesday — `day_of_week` 3, counting Sunday as 0. */
    private fun at(hour: Int, minute: Int = 0, day: Int = 21): Long =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    /** A stamp with the same time of day but a date months earlier, as the wire carries them. */
    private fun scheduled(hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 3, 4, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun peer(location: UserLocation?) = CrewContact("u1", "Aisha Khan", location = location)

    private fun shared(
        mode: String? = null,
        start: Long = 0L,
        end: Long = 0L,
        days: List<UserLocationDay> = emptyList(),
    ) = UserLocation(
        latitude = 19.076,
        longitude = 72.8777,
        shared = true,
        mode = mode,
        everydayStart = start,
        everydayEnd = end,
        days = days,
    )

    @Test
    fun `a row with no sharing block is not shared`() {
        assertEquals(LocationAccess.NotShared, peer(null).locationAccess(at(12), zone))
    }

    @Test
    fun `show_location off refuses even with coordinates`() {
        val off = shared().copy(shared = false)
        assertEquals(LocationAccess.NotShared, peer(off).locationAccess(at(12), zone))
    }

    @Test
    fun `zeroed coordinates are no position at all`() {
        val nowhere = shared().copy(latitude = 0.0, longitude = 0.0)
        assertEquals(LocationAccess.NotShared, peer(nowhere).locationAccess(at(12), zone))
    }

    @Test
    fun `nothing scheduled opens the whole day, at their coordinates`() {
        val access = peer(shared()).locationAccess(at(3), zone)
        assertEquals(LocationAccess.Open(19.076, 72.8777), access)
        // The link is for the hosts with no map of their own, and for the
        // "Open in Maps" beside the embedded one.
        assertEquals("https://www.google.com/maps?q=19.076,72.8777", (access as LocationAccess.Open).mapsUrl)
    }

    @Test
    fun `an everyday window opens inside it and refuses outside`() {
        val nineToFive = shared(mode = "Everyday", start = scheduled(9), end = scheduled(17))
        assertTrue(peer(nineToFive).locationAccess(at(12), zone) is LocationAccess.Open)
        assertEquals(LocationAccess.OutsideWindow, peer(nineToFive).locationAccess(at(18), zone))
        assertEquals(LocationAccess.OutsideWindow, peer(nineToFive).locationAccess(at(8), zone))
    }

    @Test
    fun `the date baked into a stamp is noise — only its time of day counts`() {
        // The web saves `dayjs().valueOf()`, so every stored window is dated
        // the day it was set. Read whole, a schedule set in March would
        // refuse every request made in October.
        val window = shared(mode = "Everyday", start = scheduled(9), end = scheduled(17))
        assertTrue(peer(window).locationAccess(at(12, day = 21), zone) is LocationAccess.Open)
        assertTrue(peer(window).locationAccess(at(12, day = 22), zone) is LocationAccess.Open)
    }

    @Test
    fun `a half-written window lets nobody in`() {
        val half = shared(mode = "Everyday", start = scheduled(9))
        assertEquals(LocationAccess.OutsideWindow, peer(half).locationAccess(at(12), zone))
    }

    @Test
    fun `select days reads today's row`() {
        val wednesday = shared(
            mode = "SelectDays",
            days = listOf(UserLocationDay(3, scheduled(9), scheduled(17))),
        )
        assertTrue(peer(wednesday).locationAccess(at(12), zone) is LocationAccess.Open)
        assertEquals(LocationAccess.OutsideWindow, peer(wednesday).locationAccess(at(20), zone))
    }

    @Test
    fun `a day nobody scheduled is a day nobody may look`() {
        val mondayOnly = shared(
            mode = "SelectDays",
            days = listOf(UserLocationDay(1, scheduled(9), scheduled(17))),
        )
        assertEquals(LocationAccess.OutsideWindow, peer(mondayOnly).locationAccess(at(12), zone))
    }

    @Test
    fun `select days with no days chosen restricts nothing`() {
        val empty = shared(mode = "SelectDays")
        assertTrue(peer(empty).locationAccess(at(23), zone) is LocationAccess.Open)
    }

    @Test
    fun `days without a mode are still the select-day mode`() {
        // Older rows carry `select_days` and no `location_tracking_mode`;
        // Android reads them by day rather than by a window nobody set.
        val legacy = shared(days = listOf(UserLocationDay(1, scheduled(9), scheduled(17))))
        assertEquals(LocationAccess.OutsideWindow, peer(legacy).locationAccess(at(12), zone))
    }
}
