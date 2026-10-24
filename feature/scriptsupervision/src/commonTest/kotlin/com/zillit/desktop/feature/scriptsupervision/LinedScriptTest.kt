package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.LineKind
import com.zillit.desktop.feature.scriptsupervision.domain.LinedScript
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lined script's geometry.
 *
 * The page is arithmetic — which rows a tram line spans and how many rows
 * there are to span — and arithmetic is what goes wrong without anybody
 * noticing. A line drawn one row short looks like a line.
 */
class LinedScriptTest {

    private fun line(shot: String, start: Int, end: Int, type: String = "straight") = Rec(
        buildJsonObject {
            put("_id", "l_${shot}_$start")
            put("shot_number", shot)
            put("line_start", start)
            put("line_end", end)
            put("line_type", type)
        },
    )

    private fun gap(from: Int, to: Int, type: String = "no_coverage") = Rec(
        buildJsonObject {
            put("_id", "g_$from")
            put("from_line", from)
            put("to_line", to)
            put("gap_type", type)
        },
    )

    @Test
    fun `a shot keeps one colour however many lines it has, in first-appearance order`() {
        val lines = listOf(
            line("2B", 1, 4),
            line("1A", 5, 9),
            // 2B again, further down the scene: the same setup, not a new one.
            line("2B", 10, 12),
        )

        val colours = LinedScript.shotColours(lines)

        // Insertion order, which is the order the LEGEND reads in.
        assertEquals(listOf("2B", "1A"), colours.keys.toList())
        assertEquals(LinedScript.LINE_COLORS[0], colours["2B"])
        assertEquals(LinedScript.LINE_COLORS[1], colours["1A"])
    }

    @Test
    fun `a ninth setup reuses the first's colour, as the web does`() {
        // Parity with the web's `% LINE_COLORS.length`, kept deliberately so
        // the two clients agree about which line is which shot. Survivable
        // only because every tram line carries its shot number.
        val lines = (1..9).map { line("S$it", it, it) }

        val colours = LinedScript.shotColours(lines)

        assertEquals(9, colours.size)
        assertEquals(colours["S1"], colours["S9"])
    }

    @Test
    fun `a blank shot number is not a setup`() {
        val lines = listOf(line("", 1, 2), line("1A", 3, 4))

        assertEquals(listOf("1A"), LinedScript.shotColours(lines).keys.toList())
        assertEquals(1, LinedScript.coverage(lines, emptyList()).setups)
    }

    @Test
    fun `the page is as long as the longest of its four claims`() {
        val lines = listOf(line("1A", 1, 30))

        // Script text wins when it is the longest.
        assertEquals(60, LinedScript.totalRows(List(60) { "line" }, 0, emptyList()))
        // Measured length: 7 rows an eighth.
        assertEquals(70, LinedScript.totalRows(emptyList(), 10, emptyList()))
        // Coverage reaching past everything else.
        assertEquals(30, LinedScript.totalRows(emptyList(), 1, lines))
        // Nothing at all still draws a page somebody can line.
        assertEquals(20, LinedScript.totalRows(emptyList(), 1, emptyList()))
    }

    @Test
    fun `an unmeasured scene is treated as a whole page, not as nothing`() {
        // The service stores 0 for a scene nobody has measured; 0 * 7 would
        // be no page at all.
        assertEquals(56, LinedScript.totalRows(emptyList(), 0, emptyList()))
    }

    @Test
    fun `coverage past the end of the script still has rows to run down`() {
        // The claim that is easy to drop: a scene whose text was never
        // imported has NO lines, so without this the tram lines would have
        // nothing to be drawn against.
        val lines = listOf(line("1A", 1, 48))

        assertEquals(48, LinedScript.totalRows(emptyList(), 1, lines))
    }

    @Test
    fun `a line covers its own rows inclusively, both ends`() {
        val l = line("1A", 4, 6)

        assertTrue(LinedScript.covers(l, 4), "the first row is covered")
        assertTrue(LinedScript.covers(l, 5))
        assertTrue(LinedScript.covers(l, 6), "the last row is covered")
        assertEquals(false, LinedScript.covers(l, 3))
        assertEquals(false, LinedScript.covers(l, 7))
    }

    @Test
    fun `a gap is found by the row it covers, and nothing is found outside one`() {
        val gaps = listOf(gap(3, 5), gap(9, 9, "off_screen_only"))

        assertEquals("no_coverage", LinedScript.gapAt(gaps, 4)?.str("gap_type"))
        assertEquals("off_screen_only", LinedScript.gapAt(gaps, 9)?.str("gap_type"))
        assertNull(LinedScript.gapAt(gaps, 6))
        assertNull(LinedScript.gapAt(emptyList(), 1))
    }

    @Test
    fun `a capital line reads as a cue, a bracketed one as a parenthetical`() {
        assertEquals(LineKind.Cue, LinedScript.lineKind("RAVI"))
        assertEquals(LineKind.Parenthetical, LinedScript.lineKind("(quietly)"))
        assertEquals(LineKind.Plain, LinedScript.lineKind("He crosses to the window."))
        // Shouted dialogue is NOT a cue: the length guard is what stops it.
        assertEquals(
            LineKind.Plain,
            LinedScript.lineKind("GET OUT OF THE HOUSE RIGHT NOW BEFORE IT GOES UP"),
        )
        // Not a letter at the front, so not a cue however shouty.
        assertEquals(LineKind.Plain, LinedScript.lineKind("1. THE DOOR"))
        assertEquals(LineKind.Plain, LinedScript.lineKind(""))
    }

    @Test
    fun `a scene heading and a transition read as cues too, which is the web's own flaw`() {
        // Transcribed warts and all, so the page looks the same in both
        // clients. Being indented is a cosmetic wrong, not a factual one.
        assertEquals(LineKind.Cue, LinedScript.lineKind("INT. HOUSE - DAY"))
        assertEquals(LineKind.Cue, LinedScript.lineKind("CUT TO:"))
    }

    @Test
    fun `setups count distinct shots while lines count rows of coverage`() {
        val lines = listOf(
            line("1A", 1, 4),
            line("1A", 10, 12),
            line("2B", 5, 9, type = "squiggly"),
        )

        val coverage = LinedScript.coverage(lines, listOf(gap(20, 22)))

        // One setup covering two stretches is the normal case, not an edge.
        assertEquals(2, coverage.setups)
        assertEquals(3, coverage.lines)
        assertEquals(2, coverage.onScreen)
        assertEquals(1, coverage.offScreen)
        assertEquals(1, coverage.gaps)
    }
}
