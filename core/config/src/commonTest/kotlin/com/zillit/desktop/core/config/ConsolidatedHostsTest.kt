package com.zillit.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Thirteen modules moved to four hosts.
 *
 * The mapping is the whole feature, and getting one entry wrong sends a
 * module's traffic to a service that does not serve it — a `route_not_found`
 * on every call, which reads as a broken tool rather than a misrouted one.
 */
class ConsolidatedHostsTest {

    private val hosts = mapOf(
        ZillitService.Cse to "https://cseapi-dev.zillit.com",
        ZillitService.Mrmi to "https://mrmiapi-dev.zillit.com",
        ZillitService.Lcw to "https://lcwapi-dev.zillit.com",
        ZillitService.ScriptOps to "https://scriptopsapi-dev.zillit.com",
    )

    private fun old(vararg pairs: Pair<ZillitService, String>) = pairs.toMap()

    @Test
    fun `exactly thirteen modules move`() {
        assertEquals(13, ConsolidatedHosts.mapping.size)
    }

    @Test
    fun `they move to exactly four hosts`() {
        assertEquals(
            setOf(ZillitService.Cse, ZillitService.Mrmi, ZillitService.Lcw, ZillitService.ScriptOps),
            ConsolidatedHosts.consolidated,
        )
    }

    @Test
    fun `cse takes continuity, sides and e-signature`() {
        val mine = ConsolidatedHosts.mapping.filterValues { it == ZillitService.Cse }.keys

        assertEquals(
            setOf(ZillitService.Continuity, ZillitService.Sides, ZillitService.ESignature),
            mine,
        )
    }

    @Test
    fun `mrmi takes map, recce, media and integrations`() {
        val mine = ConsolidatedHosts.mapping.filterValues { it == ZillitService.Mrmi }.keys

        assertEquals(
            setOf(
                ZillitService.Map,
                ZillitService.Recce,
                ZillitService.Media,
                ZillitService.Integrations,
            ),
            mine,
        )
    }

    @Test
    fun `lcw takes location, casting and wardrobe`() {
        val mine = ConsolidatedHosts.mapping.filterValues { it == ZillitService.Lcw }.keys

        assertEquals(
            setOf(ZillitService.Location, ZillitService.Casting, ZillitService.Wardrobe),
            mine,
        )
    }

    @Test
    fun `scriptops takes the notes and the two distributions`() {
        val mine = ConsolidatedHosts.mapping.filterValues { it == ZillitService.ScriptOps }.keys

        assertEquals(
            setOf(
                ZillitService.ScriptNotes,
                ZillitService.ScriptDistribution,
                ZillitService.ScheduleDistribution,
            ),
            mine,
        )
    }

    /** A consolidated host must not itself be listed as moving somewhere. */
    @Test
    fun `no host both absorbs and moves`() {
        assertTrue(ConsolidatedHosts.mapping.keys.none { it in ConsolidatedHosts.consolidated })
    }

    // -- applying it -----------------------------------------------------------

    @Test
    fun `a moved module resolves to its new host`() {
        val applied = ConsolidatedHosts.applied(
            hosts + old(ZillitService.Casting to "https://castingapi-dev.zillit.com"),
        )

        assertEquals("https://lcwapi-dev.zillit.com", applied[ZillitService.Casting])
    }

    /** Flipping back is a config change, so the old value must survive the rewrite. */
    @Test
    fun `the consolidated entries stay addressable`() {
        val applied = ConsolidatedHosts.applied(hosts)

        assertEquals("https://cseapi-dev.zillit.com", applied[ZillitService.Cse])
    }

    @Test
    fun `a module that did not move is untouched`() {
        val applied = ConsolidatedHosts.applied(
            hosts + old(ZillitService.Drive to "https://driveapi-dev.zillit.com"),
        )

        assertEquals("https://driveapi-dev.zillit.com", applied[ZillitService.Drive])
    }

    /**
     * A half-filled properties file degrades to the old routing for that one
     * module rather than throwing at its first call.
     */
    @Test
    fun `a module whose new host is unconfigured keeps its own`() {
        val partial = mapOf(
            ZillitService.Cse to "https://cseapi-dev.zillit.com",
            ZillitService.Casting to "https://castingapi-dev.zillit.com",
        )

        val applied = ConsolidatedHosts.applied(partial)

        assertEquals("https://castingapi-dev.zillit.com", applied[ZillitService.Casting])
        assertEquals("https://cseapi-dev.zillit.com", applied[ZillitService.Continuity])
    }

    @Test
    fun `applying to an empty map yields an empty map`() {
        assertTrue(ConsolidatedHosts.applied(emptyMap()).isEmpty())
    }

    @Test
    fun `applying twice changes nothing further`() {
        val once = ConsolidatedHosts.applied(hosts)

        assertEquals(once, ConsolidatedHosts.applied(once))
    }

    /** All three cse modules land on one host — that is the point of it. */
    @Test
    fun `modules sharing a host share its address`() {
        val applied = ConsolidatedHosts.applied(hosts)

        assertEquals(applied[ZillitService.Continuity], applied[ZillitService.Sides])
        assertEquals(applied[ZillitService.Sides], applied[ZillitService.ESignature])
    }

    @Test
    fun `every moved module has a config key of its own to roll back to`() {
        assertTrue(ConsolidatedHosts.mapping.keys.all { it.configKey.isNotBlank() })
        assertFalse(ConsolidatedHosts.mapping.keys.any { it.configKey == ZillitService.Cse.configKey })
    }
}
