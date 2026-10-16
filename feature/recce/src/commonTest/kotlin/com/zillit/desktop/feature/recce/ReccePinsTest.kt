package com.zillit.desktop.feature.recce

import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.ReccePins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a pin-less map picker opens — the web's `nearPinFor` rules. */
class ReccePinsTest {

    private val gate = LatLng(51.50, -0.12)
    private val bridge = LatLng(51.51, -0.10)
    private val tower = LatLng(51.52, -0.08)

    @Test
    fun `a stop opens on the nearest earlier stop with a pin`() {
        val stops = listOf(bridge, null, tower, null)
        assertEquals(tower, ReccePins.nearPin(stops, gate, index = 3))
        assertEquals(bridge, ReccePins.nearPin(stops, gate, index = 1))
    }

    @Test
    fun `with no earlier pin a stop falls back to the rendezvous`() {
        assertEquals(gate, ReccePins.nearPin(listOf(null, tower), gate, index = 0))
    }

    @Test
    fun `the rendezvous has only the stops to go on — never itself`() {
        assertEquals(tower, ReccePins.nearPin(listOf(null, tower), gate, ReccePins.RDV_INDEX))
        assertNull(ReccePins.nearPin(listOf(null, null), gate, ReccePins.RDV_INDEX))
    }

    @Test
    fun `an empty recce offers nothing, so the picker goes on to its own fallbacks`() {
        assertNull(ReccePins.nearPin(emptyList(), null, index = 0))
    }
}
