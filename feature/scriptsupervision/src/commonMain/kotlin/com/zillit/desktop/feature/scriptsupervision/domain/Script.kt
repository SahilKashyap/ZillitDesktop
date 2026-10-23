package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The script group's values — the web's `screens/script/shared.js` and
 * `constants/scriptSupervisionColors/script.js`.
 *
 * The colours here are DATA, not decoration: a revision's colour is its
 * version, and a reader matches the swatch on screen against the paper in
 * their hand. They are the standalone product's exact values, transcribed,
 * for the same reason the enums are.
 */
object Script {

    /**
     * The industry's revision order, as the service's enum has it.
     *
     * A production reissues its script on coloured paper and the colour IS
     * the version. After the ten first-pass colours it starts again, which is
     * what the `2nd_` half is.
     */
    val REVISION_COLORS: List<String> = listOf(
        "white",
        "blue",
        "pink",
        "yellow",
        "green",
        "goldenrod",
        "buff",
        "salmon",
        "cherry",
        "tan",
        "2nd_white",
        "2nd_blue",
        "2nd_pink",
        "2nd_yellow",
        "2nd_green",
    )

    /** A white swatch needs an edge to be seen on a white card. */
    const val SWATCH_EDGE: String = "#d9d9d9"

    /**
     * The paper colour a revision colour names.
     *
     * The second pass reuses the first pass's paper, so `2nd_blue` is the
     * same blue — which is true of the real paper too.
     */
    fun revisionHex(colour: String): String? = PAPER[colour.removePrefix("2nd_")]

    /**
     * Script pages as a production measures them: eighths of a page.
     *
     * A scene is timed in eighths because that is how a day's work is
     * estimated, and the notation is mixed: 12 eighths reads "1 4/8", 8 reads
     * "1", 3 reads "3/8". Zero reads BLANK rather than "0", because the
     * service stores 0 for a scene nobody has measured and "0" on a schedule
     * would read as a scene with no length.
     */
    fun formatEighths(eighths: Int): String {
        if (eighths <= 0) return ""
        val whole = eighths / EIGHTHS_PER_PAGE
        val rest = eighths % EIGHTHS_PER_PAGE
        return when {
            rest == 0 -> whole.toString()
            whole == 0 -> "$rest/$EIGHTHS_PER_PAGE"
            else -> "$whole $rest/$EIGHTHS_PER_PAGE"
        }
    }

    private const val EIGHTHS_PER_PAGE = 8

    private val PAPER: Map<String, String> = mapOf(
        "white" to "#ffffff",
        "blue" to "#1890ff",
        "pink" to "#ff85c0",
        "yellow" to "#fadb14",
        "green" to "#52c41a",
        "goldenrod" to "#d4a017",
        "buff" to "#f0dc82",
        "salmon" to "#fa8072",
        "cherry" to "#de3163",
        "tan" to "#d2b48c",
    )
}
