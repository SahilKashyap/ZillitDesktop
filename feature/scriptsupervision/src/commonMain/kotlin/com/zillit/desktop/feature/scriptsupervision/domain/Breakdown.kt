package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The script breakdown's rules — the web's `BreakdownTagModal.locate` and
 * `shared.byElement`.
 *
 * A breakdown is the list of everything a scene needs: props, vehicles,
 * costumes, effects. The service keeps it as TAGS — one per place an element
 * is mentioned — grouped under categories, and the screen has to turn that
 * back into "which elements does this scene need", which is what [byElement]
 * is for.
 */
object Breakdown {

    /**
     * The tags of one category, collapsed to the ELEMENTS they name.
     *
     * A scene that says "tiffin" three times carries three tags of one
     * element, and the sheet shows the element once. Removing it from the
     * scene therefore means removing every one of its tags, which is why the
     * tags travel with it rather than being counted and thrown away.
     *
     * Order is first appearance, as the web's `Map` iteration gives.
     */
    fun byElement(tags: List<Rec>): List<TaggedElement> {
        val order = LinkedHashMap<String, MutableList<Rec>>()
        val names = mutableMapOf<String, String>()
        tags.forEach { tag ->
            // The service sends the element either as an id or as the whole
            // record; `Rec.first` reads both spellings.
            val id = tag.elementId()
            order.getOrPut(id) { mutableListOf() }.add(tag)
            names.getOrPut(id) { tag.str("element_name") }
        }
        return order.map { (id, its) -> TaggedElement(id, names[id].orEmpty(), its) }
    }

    /**
     * Where [text] sits in the scene's script, as the service wants it: the
     * line, and the character range within that line.
     *
     * The web searches the TRIMMED line but reports the offset into it, which
     * is off by the leading whitespace on an indented line — screenplay text
     * is heavily indented, so this matters. Kept as the web does it, because
     * the offsets are stored and a tag made on either client has to point at
     * the same place; changing it here alone would make the two disagree.
     *
     * Nothing found answers the zero range, which is the web's own fallback:
     * the tag still exists, it just highlights nothing.
     */
    fun locate(lines: List<String>, text: String): TagPosition {
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return TagPosition(0, 0, 0)
        lines.forEachIndexed { index, line ->
            val at = line.trim().lowercase().indexOf(needle)
            if (at >= 0) return TagPosition(index, at, at + text.trim().length)
        }
        return TagPosition(0, 0, 0)
    }

    /** The element a tag names: an id, or the embedded record's own. */
    private fun Rec.elementId(): String =
        rec("element_id")?.id?.takeIf { it.isNotBlank() }
            ?: str("element_id").takeIf { it.isNotBlank() }
            ?: str("element_name")

    /** A tag the service has not been told about yet — the AI's guesses. */
    const val SUGGESTED: String = "suggested"

    /** What a reviewer does with a suggestion. */
    const val CONFIRM: String = "confirm"
    const val REJECT: String = "reject"
}

/** One element this scene needs, and every tag that put it there. */
data class TaggedElement(val id: String, val name: String, val tags: List<Rec>)

/** Where a tag points in the script: a line, and a range of characters in it. */
data class TagPosition(val lineIndex: Int, val charStart: Int, val charEnd: Int)
