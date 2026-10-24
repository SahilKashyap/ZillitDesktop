package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The lined script's values and geometry — the web's `TramLinesPage.jsx` and
 * the `shotColors` / `LINE_COLORS` half of its `shared.js` and
 * `constants/scriptSupervisionColors/script.js`.
 *
 * A lined script is the script supervisor's own page: the scene's text with a
 * vertical "tram line" beside it for every camera setup, running from the
 * first line that setup covers to the last. Solid means the actor is on
 * screen for it, dashed means off screen. It is how a cutting room sees at a
 * glance which words were shot from which angle.
 *
 * The arithmetic lives here rather than in the screen because the page is
 * geometry — which rows a line spans, how many rows there are at all — and
 * geometry is the part that is wrong silently.
 *
 * ⚠ **Line numbers here are 1-BASED.** `line_start`/`line_end` count the
 * script's lines from 1, so the text for line `n` is `script_text[n - 1]`.
 * The breakdown's `line_index` on the very same scene is 0-BASED. Two numbering
 * bases over one list of strings, and nothing on the wire says which is which.
 */
object LinedScript {

    /**
     * Each shot's tram-line colour, in the order shots first appear.
     *
     * Transcribed from the web, and the order matters as much as the values:
     * the desktop and the web show the same scene to the same crew, so shot
     * 1 has to be the same blue in both. Picking "better" colours here would
     * make the two clients disagree about which line is which shot.
     *
     * ⚠ It CYCLES at eight, as the web's `% LINE_COLORS.length` does, so a
     * ninth setup reuses the first's blue. That is a known weakness of the
     * original and kept deliberately for the agreement above — it is
     * survivable only because the shot number is drawn on every tram line
     * and in the legend, so colour is never the only thing telling two
     * setups apart. If the label is ever dropped, this cycling becomes a
     * real defect. The palette was validated against both themes: its worst
     * adjacent pair (amber against green) separates by only ΔE 7.9 for
     * protanopia, which is legal *only* with that secondary encoding.
     */
    val LINE_COLORS: List<String> = listOf(
        "#2563eb",
        "#dc2626",
        "#059669",
        "#d97706",
        "#7c3aed",
        "#db2777",
        "#0891b2",
        "#ea580c",
    )

    /** The legend's on-screen (solid) and off-screen (dashed) samples. */
    const val LEGEND_ON_SCREEN: String = "#2563eb"
    const val LEGEND_OFF_SCREEN: String = "#dc2626"

    /** The shot sizes the form offers, transcribed — the service holds a string. */
    val SHOT_SIZES: List<String> = listOf(
        "WS",
        "MS",
        "MCU",
        "CU",
        "ECU",
        "OTS",
        "2-Shot",
        "Group",
        "Insert",
    )

    /** On screen or off: the service's `line_type` enum, both values. */
    val LINE_TYPES: List<String> = listOf("straight", "squiggly")

    /**
     * Each shot's colour, keyed by shot number, in the order shots first
     * appear in [lines].
     *
     * Insertion-ordered on purpose: the legend is drawn from this and reads
     * in shot order as the page does. A shot that appears on several lines is
     * one entry — the colour belongs to the SETUP, not to the line.
     */
    fun shotColours(lines: List<Rec>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        lines.forEach { line ->
            val shot = line.str("shot_number")
            if (shot.isNotBlank() && shot !in map) {
                map[shot] = LINE_COLORS[map.size % LINE_COLORS.size]
            }
        }
        return map
    }

    /**
     * How many numbered rows the page draws.
     *
     * The widest of four claims, which is the web's own `Math.max`:
     *
     * - as many rows as the scene has script lines;
     * - as many as its measured length implies — [LINES_PER_EIGHTH] rows per
     *   eighth of a page, falling back to a whole page when nobody has
     *   measured it (the service stores 0 for unmeasured);
     * - far enough to reach the last line any coverage claims, so a line that
     *   runs past the text it was drawn against still has rows to run down;
     * - and [MINIMUM_ROWS] at the very least, so an empty scene is still a
     *   page somebody can line rather than a blank strip.
     *
     * ⚠ The third claim is the one that matters and the easy one to drop. A
     * scene whose script text was never imported has NO lines, and coverage
     * entered against it would otherwise have nothing to draw on.
     */
    fun totalRows(scriptLines: List<String>, pageCountEighths: Int, lines: List<Rec>): Int {
        val measured = (if (pageCountEighths > 0) pageCountEighths else EIGHTHS_PER_PAGE) * LINES_PER_EIGHTH
        val covered = lines.maxOfOrNull { it.int("line_end") } ?: 0
        return maxOf(scriptLines.size, measured, covered, MINIMUM_ROWS)
    }

    /** Whether row [row] falls inside this coverage line's span. */
    fun covers(line: Rec, row: Int): Boolean = row >= line.int("line_start") && row <= line.int("line_end")

    /** The gap covering row [row], if a gap check has found one. */
    fun gapAt(gaps: List<Rec>, row: Int): Rec? =
        gaps.firstOrNull { row >= it.int("from_line") && row <= it.int("to_line") }

    /**
     * How a script line reads on the page.
     *
     * Screenplay layout by eye: a character cue is set in capitals and
     * indented deep, a parenthetical is italic and indented less, everything
     * else is action or dialogue.
     *
     * ⚠ The cue test is the web's, transcribed, including what it gets
     * wrong: an all-capitals line under 40 characters starting with a letter
     * is a cue, so a scene heading ("INT. HOUSE - DAY") and a transition
     * ("CUT TO:") both read as cues too. Kept because the page has to look
     * the same in both clients, and because being indented is a cosmetic
     * wrong rather than a factual one. The length guard is what stops a
     * shouted line of dialogue from being mistaken for a cue.
     */
    fun lineKind(text: String): LineKind = when {
        text.startsWith("(") && text.endsWith(")") -> LineKind.Parenthetical
        isCue(text) -> LineKind.Cue
        else -> LineKind.Plain
    }

    /**
     * A scene's coverage, counted.
     *
     * Setups are DISTINCT shot numbers while lines are rows of coverage, and
     * the two differ whenever one setup covers two stretches of the scene —
     * which is the normal case, not an edge one.
     */
    fun coverage(lines: List<Rec>, gaps: List<Rec>): Coverage = Coverage(
        setups = lines.map { it.str("shot_number") }.filter { it.isNotBlank() }.distinct().size,
        lines = lines.size,
        onScreen = lines.count { it.str("line_type") == "straight" },
        offScreen = lines.count { it.str("line_type") == "squiggly" },
        gaps = gaps.size,
    )

    private fun isCue(text: String): Boolean =
        text.isNotEmpty() &&
            text.length < CUE_MAX_LENGTH &&
            text == text.uppercase() &&
            text.first() in 'A'..'Z'

    private const val CUE_MAX_LENGTH = 40
    private const val EIGHTHS_PER_PAGE = 8

    /**
     * Script rows per eighth of a page. Seven, which is the web's figure —
     * a page of screenplay is about 56 lines and an eighth of it about 7.
     */
    private const val LINES_PER_EIGHTH = 7
    private const val MINIMUM_ROWS = 20
}

/** How a script line is set: a character cue, a parenthetical, or neither. */
enum class LineKind { Cue, Parenthetical, Plain }

/** A scene's coverage, counted. */
data class Coverage(
    val setups: Int,
    val lines: Int,
    val onScreen: Int,
    val offScreen: Int,
    val gaps: Int,
)
