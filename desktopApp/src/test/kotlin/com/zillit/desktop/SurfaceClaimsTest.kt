package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that keeps one browser component attached to exactly one host.
 *
 * This existed as an unconditional `invokeLater { park(component) }` and killed
 * the app mid-call: a leaving host parked a component the arriving host was
 * already holding, and Compose measured the emptied interop group. The rule is
 * three lines, so it is worth having three lines of proof.
 */
class SurfaceClaimsTest {

    @Test
    fun `the only host may park`() {
        val claims = SurfaceClaims()
        val only = claims.claim()
        assertTrue(claims.mayPark(only), "a host nobody superseded owns the surface")
    }

    /** The hand-off: arrive, then leave. The leaver must not touch it. */
    @Test
    fun `a superseded host may not park`() {
        val claims = SurfaceClaims()
        val leaving = claims.claim()
        val arriving = claims.claim()

        assertFalse(claims.mayPark(leaving), "the leaving host must not park what the new one holds")
        assertTrue(claims.mayPark(arriving), "the arriving host owns it")
    }

    /**
     * Compose does not promise that the leaving host disposes before the
     * arriving one mounts, so the rule has to hold whichever order they run in.
     * Only the newest ticket wins either way.
     */
    @Test
    fun `order within the frame does not matter`() {
        val claims = SurfaceClaims()
        val first = claims.claim()
        val second = claims.claim()
        val third = claims.claim()

        assertFalse(claims.mayPark(first))
        assertFalse(claims.mayPark(second))
        assertTrue(claims.mayPark(third))
    }

    /**
     * The other direction, which must keep working: when a host leaves and
     * nothing replaces it, the component MUST be parked — an unparented browser
     * is a dead media stack for the next call.
     */
    @Test
    fun `a host leaving with no successor still parks`() {
        val claims = SurfaceClaims()
        claims.claim()
        val last = claims.claim()
        assertTrue(claims.mayPark(last), "nothing took over, so it must go back to the holder")
    }

    @Test
    fun `claims never repeat`() {
        val claims = SurfaceClaims()
        val issued = (1..500).map { claims.claim() }
        assertEquals(issued.size, issued.toSet().size, "a reused ticket would let a stale host park")
    }
}
