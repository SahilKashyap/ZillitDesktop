package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The script group's two rules a reader cannot check by eye: how a page
 * length is written, and which paper a revision colour names.
 */
class ScriptRulesTest {

    @Test
    fun `a page length reads the way a production writes it`() {
        // Whole pages: no fraction at all.
        assertEquals("1", Script.formatEighths(8))
        assertEquals("3", Script.formatEighths(24))
        // Mixed.
        assertEquals("1 4/8", Script.formatEighths(12))
        assertEquals("2 1/8", Script.formatEighths(17))
        // Under a page: the fraction alone, not "0 3/8".
        assertEquals("3/8", Script.formatEighths(3))
        assertEquals("7/8", Script.formatEighths(7))
    }

    @Test
    fun `an unmeasured scene reads blank, not zero`() {
        // The service stores 0 for a scene nobody has measured, and "0" on a
        // schedule would read as a scene with no length.
        assertEquals("", Script.formatEighths(0))
        assertEquals("", Script.formatEighths(-1))
    }

    @Test
    fun `the second revision pass reuses the first pass's paper`() {
        assertEquals(Script.revisionHex("blue"), Script.revisionHex("2nd_blue"))
        assertEquals(Script.revisionHex("white"), Script.revisionHex("2nd_white"))
        assertEquals("#ffffff", Script.revisionHex("white"))
    }

    @Test
    fun `a colour the service does not know names no paper`() {
        assertNull(Script.revisionHex("chartreuse"))
        assertNull(Script.revisionHex(""))
    }

    @Test
    fun `every colour in the service's enum names a paper`() {
        // A colour in the list with no hex draws as the theme's border and
        // reads as "no revision", so the list and the palette stay in step.
        val unnamed = Script.REVISION_COLORS.filter { Script.revisionHex(it) == null }

        assertEquals(emptyList(), unnamed, "these revision colours name no paper")
    }
}
