package com.zillit.desktop.feature.recce

import com.zillit.desktop.feature.recce.domain.GeocodeHit
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.RecceRoute
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.ui.RecceCounts
import com.zillit.desktop.feature.recce.ui.RecceFilter
import com.zillit.desktop.feature.recce.ui.pages.pageNumbers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web's `RecceRouteMap` resolution order, and the small list rules beside it. */
class RecceRouteTest {

    @Test
    fun `an exact pin wins, then place and address, then address, then place`() {
        val candidates = RecceRoute.candidates(
            listOf(
                RecceStop(place = "Bridge", address = "1 River St", lat = 1.0, long = 2.0),
                RecceStop(place = "Cafe", address = "2 High St", kind = StopKind.Lunch),
                RecceStop(place = "", address = ""),
                RecceStop(place = "Station"),
            ),
        )
        assertEquals(listOf(1, 2, 4), candidates.map { it.number })
        assertEquals(LatLng(1.0, 2.0), candidates[0].exact)
        assertEquals(listOf("Cafe, 2 High St", "2 High St", "Cafe"), candidates[1].queries)
        assertEquals(true, candidates[1].isLunch)
        assertEquals(listOf("Station"), candidates[2].queries)
    }

    @Test
    fun `an outlier is re-geocoded biased to the dominant country`() = runTest {
        val asked = mutableListOf<Pair<String, String?>>()
        val pins = RecceRoute.resolve(
            listOf(
                RecceStop(place = "Connaught Place", lat = 28.63, long = 77.22),
                RecceStop(place = "India Gate"),
                RecceStop(place = "NDLS"),
            ),
        ) { query, country ->
            asked += query to country
            when {
                query == "NDLS" && country == null -> GeocodeHit(LatLng(53.3, -6.2), "IE")
                query == "NDLS" -> GeocodeHit(LatLng(28.64, 77.22), "IN")
                else -> GeocodeHit(LatLng(28.61, 77.23), "IN")
            }
        }
        assertEquals(3, pins.size)
        assertEquals(LatLng(28.64, 77.22), pins[2].pin)
        assertEquals("NDLS" to "IN", asked.last())
    }

    @Test
    fun `a stop nothing can place drops out and the rest keep their numbers`() = runTest {
        val pins = RecceRoute.resolve(
            listOf(RecceStop(place = "Nowhere"), RecceStop(place = "Somewhere", lat = 1.0, long = 1.0)),
        ) { _, _ -> null }
        assertEquals(listOf(2), pins.map { it.number })
    }

    @Test
    fun `the badges keep drafts as all minus published`() {
        val counts = RecceCounts(all = 10, published = 4, draft = 6).with(RecceFilter.Published, 7)
        assertEquals(RecceCounts(10, 7, 3), counts)
        assertEquals(RecceCounts(12, 7, 5), counts.with(RecceFilter.All, 12))
    }

    @Test
    fun `the pager shows every page up to seven, then the ends and the neighbours`() {
        assertEquals(listOf(1, 2, 3), pageNumbers(current = 2, count = 3))
        assertEquals(listOf(1, null, 5, 6, 7, null, 12), pageNumbers(current = 6, count = 12))
        assertEquals(listOf(1, 2, null, 12), pageNumbers(current = 1, count = 12))
    }
}
