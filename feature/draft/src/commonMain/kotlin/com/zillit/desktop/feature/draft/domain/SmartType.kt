package com.zillit.desktop.feature.draft.domain

/**
 * Final Draft's SmartType: as a cue or heading is typed, the names and
 * places already in the script are offered, most recently used first —
 * so a character is spelt one way throughout, and INT. KITCHEN - DAY is
 * a keystroke on the second visit.
 */
object SmartType {

    /** Character names in the script, most recent last-used first, no duplicates. */
    fun characters(elements: List<ScriptElement>): List<String> =
        recentFirst(elements.filter { it.type == ElementType.Character }.map { cueName(it.text) })

    /** Scene headings in the script, most recent first. */
    fun headings(elements: List<ScriptElement>): List<String> =
        recentFirst(elements.filter { it.type == ElementType.SceneHeading }.map { it.text.trim().uppercase() })

    /** Locations — the part of a heading between INT./EXT. and the time of day. */
    fun locations(elements: List<ScriptElement>): List<String> =
        recentFirst(
            elements.filter { it.type == ElementType.SceneHeading }.mapNotNull { heading ->
                LOCATION.find(heading.text.trim().uppercase())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            },
        )

    /**
     * What to offer for [typed] in an element of [type]; empty when there is
     * nothing to add. The element itself ([selfId]) does not suggest itself.
     */
    fun suggestions(type: ElementType, typed: String, elements: List<ScriptElement>, selfId: String): List<String> {
        val prefix = typed.trim().uppercase()
        val others = elements.filter { it.id != selfId }
        val pool = when (type) {
            ElementType.Character -> characters(others)
            ElementType.SceneHeading -> headings(others) + intExtStarts(others, prefix)
            else -> return emptyList()
        }
        return pool.filter { it.startsWith(prefix) && it != prefix }.distinct().take(MAX_SUGGESTIONS)
    }

    /** "INT. KITCHEN" for a heading typed as "INT. K…": the known locations under the typed prefix. */
    private fun intExtStarts(elements: List<ScriptElement>, prefix: String): List<String> {
        val start = HEADING_START.find(prefix)?.value ?: return emptyList()
        return locations(elements).map { "$start$it" }
    }

    /** "SAM (V.O.)" cues as "SAM". */
    fun cueName(cue: String): String = cue.trim().uppercase().substringBefore('(').trim()

    private fun recentFirst(names: List<String>): List<String> =
        names.filter { it.isNotBlank() }.asReversed().distinct()

    private const val MAX_SUGGESTIONS = 6
    private val LOCATION = Regex("^(?:INT\\.?/EXT\\.?|I/E\\.?|INT\\.?|EXT\\.?|EST\\.?)\\s*(.*?)(?:\\s+-\\s+.*)?$")
    private val HEADING_START = Regex("^(?:INT\\.?/EXT\\.?|I/E\\.?|INT\\.?|EXT\\.?|EST\\.?)\\s*")
}
